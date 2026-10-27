package dev.epoch.postgres

import dev.epoch.core.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Reusable policy and cache configuration, never a target database connection. */
data class VerificationOptions(
    val baselineCache: Path? = null,
    val acceptedBaseline: AcceptedBaseline? = null,
)

/** The only public verification target is a newly created, disposable PostgreSQL container. */
class PostgresVerifier {
    fun verify(
        contract: DatabaseContract,
        baselineDirectory: Path,
        migrationsDirectory: Path,
        options: VerificationOptions = VerificationOptions(),
    ): CompatibilityReport = verify(listOf(contract), baselineDirectory, migrationsDirectory, options)

    /** Multiple persistence-unit contracts share one baseline and one sequence of migrations. */
    fun verify(
        contracts: List<DatabaseContract>,
        baselineDirectory: Path,
        migrationsDirectory: Path,
        options: VerificationOptions = VerificationOptions(),
    ): CompatibilityReport {
        require(contracts.isNotEmpty()) { "At least one database contract is required" }
        contracts.forEach { contract ->
            require(contract.schemaVersion == 1) { "Unsupported contract schemaVersion: ${contract.schemaVersion}" }
            require(contract.dialect == "PostgreSQL") { "Only PostgreSQL contracts are supported" }
            require(contract.queries.map { it.id }.distinct().size == contract.queries.size) { "Query IDs must be unique within each contract" }
        }
        require(Files.isDirectory(baselineDirectory)) { "Baseline migration directory does not exist: $baselineDirectory" }
        require(Files.isDirectory(migrationsDirectory)) { "Pending migration directory does not exist: $migrationsDirectory" }
        validateDirectoryIsolation(baselineDirectory, migrationsDirectory, options.baselineCache)
        val contract = mergeContracts(contracts)
        val baselineCallbacks = callbackFiles(baselineDirectory)
        val pendingCallbacks = callbackFiles(migrationsDirectory)

        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("epoch")
            .withUsername("epoch")
            .withPassword("epoch-isolated-test-only")
            .use { database ->
                try { database.start() }
                catch (failure: Throwable) {
                    throw IllegalStateException("Cannot start isolated PostgreSQL. Docker must be available with images $POSTGRES_IMAGE and $RYUK_IMAGE. See the underlying cause; TESTCONTAINERS_RYUK_DISABLED=true is supported when your CI manages container cleanup.", failure)
                }
                val baselineLocation = "filesystem:${baselineDirectory.toAbsolutePath()}"
                val pendingLocation = "filesystem:${migrationsDirectory.toAbsolutePath()}"
                fun configuration() = Flyway.configure()
                    .dataSource(database.jdbcUrl, database.username, database.password)
                    .cleanDisabled(true)
                    .validateMigrationNaming(true)
                    .validateOnMigrate(true)
                    .placeholderReplacement(false)
                    .skipDefaultCallbacks(true)

                val cache = BaselineCache(options.baselineCache)
                val cacheKey = cache.key(baselineDirectory, database.containerInfo.imageId)
                val cacheStatus = cache.prepare(database, cacheKey) {
                    // Flyway owns migration ordering, checksums, version validation, and execution.
                    configuration().locations(baselineLocation).load().migrate()
                }
                // Restore retains Flyway's history; still validate the exact current baseline files.
                configuration().locations(baselineLocation).load().validate()
                val catalog = PostgresCatalog()
                val planner = PostgresQueryPlanner()
                val engine = CompatibilityEngine()
                val baseline: SchemaSnapshot
                val baselinePlans: Map<String, QueryPlan>
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    baseline = catalog.inspect(connection)
                    baselinePlans = contract.queries.associate { it.id to planner.inspect(connection, it) }
                }
                val baselineFindings = planner.baselineFindings(contract.queries, baselinePlans) +
                    baselineCallbacks.map { callbackFinding(it, null) }
                val baselineHealth = engine.inspectBaseline(contract, baseline, baselineFindings, cacheKey)
                    .copy(cacheStatus = cacheStatus, cacheKey = cacheKey)
                val steps = pendingCallbacks.map { filename ->
                    val migration = MigrationInfo("callback", filename)
                    MigrationReport(migration, Verdict.UNKNOWN, listOf(callbackFinding(filename, migration)))
                }.toMutableList()
                var activeMigration: MigrationInfo? = null
                val callback = object : Callback {
                    override fun getCallbackName() = "epoch-intermediate-schema-verification"
                    override fun supports(event: Event, context: Context?) = event in setOf(Event.BEFORE_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE)
                    override fun canHandleInTransaction(event: Event, context: Context?) = true
                    override fun handle(event: Event, context: Context) {
                        val info = context.migrationInfo
                        val migration = MigrationInfo(info.version?.toString() ?: "repeatable", info.script)
                        activeMigration = migration
                        if (event != Event.AFTER_EACH_MIGRATE) return
                        val effects = try {
                            MigrationClassifier().findings(Files.readString(Path.of(info.physicalLocation)), migration)
                        } catch (exception: Exception) {
                            listOf(Finding(Verdict.UNKNOWN, "MIGRATION_EFFECTS_UNAVAILABLE", migration.filename,
                                "Could not read the exact migration resource for effect classification.",
                                exception.javaClass.simpleName, migration = migration))
                        }
                        val after = catalog.inspect(context.connection)
                        val plans = planner.findings(context.connection, contract.queries, baselinePlans, migration)
                        // Retain invalid-baseline IDs for rule protections; report removes their
                        // duplicated findings from the pending-migration section afterward.
                        steps += engine.verifyStep(contract, baseline, after, migration,
                            baselineHealth.findings + effects + plans)
                    }
                }
                try {
                    configuration().locations(baselineLocation, pendingLocation).callbacks(callback).load().migrate()
                } catch (exception: FlywayException) {
                    val failed = activeMigration ?: MigrationInfo("unresolved", "Flyway validation")
                    steps += MigrationReport(failed, Verdict.UNKNOWN, listOf(Finding(
                        Verdict.UNKNOWN, "MIGRATION_EXECUTION_FAILED", failed.filename,
                        "Flyway could not complete migration execution or validation in the isolated database.",
                        causeMessages(exception), migration = failed,
                        mitigation = "Correct the migration/Flyway validation error and rerun verification.",
                    )))
                }
                return engine.report(contract, steps, baselineHealth, options.acceptedBaseline)
            }
    }

    private fun validateDirectoryIsolation(baselineDirectory: Path, migrationsDirectory: Path, cacheDirectory: Path?) {
        // canonicalFile resolves existing symlinks even when the final cache directory
        // does not exist yet, and normalizes relative paths before containment checks.
        val baseline = baselineDirectory.toFile().canonicalFile.toPath()
        val pending = migrationsDirectory.toFile().canonicalFile.toPath()
        require(!baseline.startsWith(pending) && !pending.startsWith(baseline)) {
            "Baseline and pending migration directories must be separate and must not overlap"
        }
        cacheDirectory?.toFile()?.canonicalFile?.toPath()?.let { cache ->
            require(!cache.startsWith(baseline) && !cache.startsWith(pending)) {
                "Baseline cache directory must be outside both baseline and pending migration directories"
            }
        }
    }

    private fun callbackFiles(directory: Path): List<String> {
        val callbackNames = Event.entries.map { it.id }.toSet()
        return Files.walk(directory).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) && path.fileName.toString().endsWith(".sql") &&
                    path.fileName.toString().removeSuffix(".sql").substringBefore("__") in callbackNames
            }.map { directory.relativize(it).toString().replace('\\', '/') }.sorted().toList()
        }
    }

    private fun callbackFinding(filename: String, migration: MigrationInfo?) = Finding(
        Verdict.UNKNOWN, "FLYWAY_CALLBACK_UNSUPPORTED", "${if (migration == null) "baseline" else "pending"}/$filename",
        "SQL lifecycle callbacks are outside the supported migration contract and were not executed.",
        "Discovered ${if (migration == null) "baseline" else "pending"} callback resource: $filename", migration = migration,
        mitigation = "Express required changes as versioned migrations; verify callback behavior separately.",
    )

    private fun mergeContracts(contracts: List<DatabaseContract>): DatabaseContract {
        if (contracts.size == 1) return contracts.single()
        val prefixed = contracts.mapIndexed { index, item ->
            val prefix = "contract[${index + 1}]/"
            item.copy(
                entities = item.entities.map { it.copy(id = prefix + it.id) },
                queries = item.queries.map { it.copy(id = prefix + it.id) },
                unresolvedQueries = item.unresolvedQueries.map { it.copy(id = prefix + it.id) },
            )
        }
        return DatabaseContract(
            applicationVersion = contracts.mapIndexed { index, item -> "contract[${index + 1}]=${item.applicationVersion}" }.joinToString("; "),
            scope = if (contracts.any { it.scope == VerificationScope.WHOLE_APPLICATION }) VerificationScope.WHOLE_APPLICATION else VerificationScope.STRUCTURAL_CONTRACT,
            entities = prefixed.flatMap { it.entities },
            queries = prefixed.flatMap { it.queries },
            coverage = Coverage(
                complete = contracts.all { it.coverage.complete },
                mechanisms = contracts.flatMap { it.coverage.mechanisms }.distinct().sorted(),
                notes = contracts.flatMapIndexed { index, item ->
                    listOf("contract[${index + 1}]: applicationVersion=${item.applicationVersion}; scope=${item.scope}") +
                        item.coverage.notes.map { "contract[${index + 1}]: $it" }
                },
            ),
            unresolvedQueries = prefixed.flatMap { it.unresolvedQueries },
        )
    }

    private fun causeMessages(failure: Throwable): String = generateSequence(failure) { it.cause }
        .take(20).map { "${it.javaClass.simpleName}: ${it.message}" }.distinct().joinToString("; caused by ")

    companion object {
        const val POSTGRES_IMAGE = "postgres:16.8"
        const val RYUK_IMAGE = "testcontainers/ryuk:0.14.0"
    }
}
