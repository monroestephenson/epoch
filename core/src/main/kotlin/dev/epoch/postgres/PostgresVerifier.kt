package dev.epoch.postgres

import dev.epoch.core.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** The only public verification target is a newly created, disposable PostgreSQL container. */
class PostgresVerifier {
    fun verify(contract: DatabaseContract, baselineDirectory: Path, migrationsDirectory: Path): CompatibilityReport {
        require(contract.schemaVersion == 1) { "Unsupported contract schemaVersion: ${contract.schemaVersion}" }
        require(contract.dialect == "PostgreSQL") { "Only PostgreSQL contracts are supported" }
        require(Files.isDirectory(baselineDirectory)) { "Baseline migration directory does not exist: $baselineDirectory" }
        require(Files.isDirectory(migrationsDirectory)) { "Pending migration directory does not exist: $migrationsDirectory" }
        require(baselineDirectory.toRealPath() != migrationsDirectory.toRealPath()) { "Baseline and pending directories must differ" }
        require(contract.queries.map { it.id }.distinct().size == contract.queries.size) { "Query IDs must be unique" }
        val callbackNames = Event.entries.map { it.id }.toSet()
        val unsupportedCallbacks = listOf(baselineDirectory, migrationsDirectory).flatMap { directory ->
            Files.walk(directory).use { paths -> paths.filter { path ->
                Files.isRegularFile(path) && path.fileName.toString().endsWith(".sql") &&
                    path.fileName.toString().removeSuffix(".sql").substringBefore("__") in callbackNames
            }.map { it.fileName.toString() }.sorted().toList() }
        }
        fun callbackFindings(migration: MigrationInfo) = unsupportedCallbacks.map { callback -> Finding(
            Verdict.UNKNOWN, "FLYWAY_CALLBACK_UNSUPPORTED", callback,
            "SQL lifecycle callbacks are outside the supported migration contract and were not executed.",
            "Discovered callback resource: $callback", migration = migration,
            mitigation = "Express required changes as versioned migrations; verify callback behavior separately.",
        ) }

        val database = IsolatedPostgres(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("epoch")
            .withUsername("epoch")
            .withPassword("epoch-isolated-test-only")
        database.start()
        try {
            val baselineLocation = "filesystem:${baselineDirectory.toAbsolutePath()}"
            val pendingLocation = "filesystem:${migrationsDirectory.toAbsolutePath()}"
            fun configuration() = Flyway.configure()
                .dataSource(database.jdbcUrl, database.username, database.password)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .placeholderReplacement(false)
                .skipDefaultCallbacks(true)

            // Flyway owns migration ordering, checksums, version validation, and execution.
            configuration().locations(baselineLocation).load().migrate()
            val catalog = PostgresCatalog()
            val planner = PostgresQueryPlanner()
            val engine = CompatibilityEngine()
            val baseline: SchemaSnapshot
            val baselinePlans: Map<String, QueryPlan>
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                baseline = catalog.inspect(connection)
                baselinePlans = contract.queries.associate { it.id to planner.inspect(connection, it) }
            }
            val steps = mutableListOf<MigrationReport>()
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
                    steps += engine.verifyStep(contract, baseline, after, migration, effects + plans + callbackFindings(migration))
                }
            }
            try {
                configuration().locations(baselineLocation, pendingLocation).callbacks(callback).load().migrate()
            } catch (exception: FlywayException) {
                val failed = activeMigration ?: MigrationInfo("unresolved", "Flyway validation")
                steps += MigrationReport(failed, Verdict.UNKNOWN, listOf(Finding(
                    Verdict.UNKNOWN, "MIGRATION_EXECUTION_FAILED", failed.filename,
                    "Flyway could not complete migration execution or validation in the isolated database.",
                    exception.message ?: exception.javaClass.simpleName, migration = failed,
                    mitigation = "Correct the migration/Flyway validation error and rerun verification.",
                )))
            }
            if (steps.isEmpty()) {
                val noChanges = MigrationInfo("baseline", "No pending migrations")
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    steps += engine.verifyStep(contract, baseline, baseline, noChanges,
                        planner.findings(connection, contract.queries, baselinePlans, noChanges) + callbackFindings(noChanges))
                }
            }
            return engine.report(contract, steps)
        } finally {
            database.stop()
        }
    }

    companion object {
        const val POSTGRES_IMAGE = "postgres:16.8"
    }
}

private class IsolatedPostgres(image: DockerImageName) : PostgreSQLContainer<IsolatedPostgres>(image)
