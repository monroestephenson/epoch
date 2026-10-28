package dev.epoch.postgres

import dev.epoch.core.*
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Reusable policy and cache configuration, never a target database connection. */
data class VerificationOptions(
    val baselineCache: Path? = null,
    val acceptedBaseline: AcceptedBaseline? = null,
    val migrationTimeoutSeconds: Long = 120,
    val globalTimeoutSeconds: Long = 1800,
    val executeBoundaries: Boolean = false,
    val planningParallelism: Int = 4,
    /** Immutable incomplete snapshots after baseline and each migration; final results are returned normally. */
    val progress: ((String, CompatibilityReport) -> Unit)? = null,
    /** Optional metadata-only target estimates; verification never connects to the target environment. */
    val tableSizes: TableSizes? = null,
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
    ): CompatibilityReport = runVerification(contracts, null, baselineDirectory, migrationsDirectory, options).forward

    /** Checks both schema directions and new mapped writes against old readers on the final schema. */
    fun verifyBidirectional(
        oldContracts: List<DatabaseContract>,
        newContracts: List<DatabaseContract>,
        baselineDirectory: Path,
        migrationsDirectory: Path,
        options: VerificationOptions = VerificationOptions(),
    ): BidirectionalReport {
        val (forward, reverse, data) = runVerification(oldContracts, newContracts, baselineDirectory, migrationsDirectory, options)
        val backward = requireNotNull(reverse)
        val dataCompatibility = requireNotNull(data)
        return BidirectionalReport(oldOnNew = forward, newOnOld = backward, dataCompatibility = dataCompatibility,
            operationalVerdict = forward.operationalVerdict,
            verdict = CompatibilityEngine.aggregate(listOf(forward.verdict, backward.verdict, dataCompatibility.verdict)),
            gateVerdict = forward.gateVerdict?.let {
                CompatibilityEngine.aggregate(listOf(it, backward.verdict, dataCompatibility.verdict))
            })
    }

    private data class VerificationRun(val forward: CompatibilityReport, val reverse: CompatibilityReport? = null,
                                       val dataCompatibility: CompatibilityReport? = null)

    private fun runVerification(
        contracts: List<DatabaseContract>, newContracts: List<DatabaseContract>?,
        baselineDirectory: Path, migrationsDirectory: Path, options: VerificationOptions,
    ): VerificationRun {
        validateContracts(contracts)
        newContracts?.let(::validateContracts)
        require(options.migrationTimeoutSeconds in 1..86400) { "migrationTimeoutSeconds must be between 1 and 86400" }
        require(options.globalTimeoutSeconds in 1..604800) { "globalTimeoutSeconds must be between 1 and 604800" }
        require(options.planningParallelism in 1..32) { "planningParallelism must be between 1 and 32" }
        val tableSizes = OperationalSizes.validateAndNormalize(options.tableSizes)
        require(Files.isDirectory(baselineDirectory)) { "Baseline migration directory does not exist: $baselineDirectory" }
        require(Files.isDirectory(migrationsDirectory)) { "Pending migration directory does not exist: $migrationsDirectory" }
        validateDirectoryIsolation(baselineDirectory, migrationsDirectory, options.baselineCache)
        val owned = AtomicReference<PostgreSQLContainer?>()
        val cancelled = AtomicBoolean(false)
        return VerificationDeadline(options.globalTimeoutSeconds).run(cleanup = { cancelled.set(true); owned.get()?.stop() }) {
            val contract = mergeContracts(contracts)
            val newContract = newContracts?.let(::mergeContracts)
            val baselineCallbacks = callbackFiles(baselineDirectory)
            val pendingCallbacks = callbackFiles(migrationsDirectory)
            PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("epoch")
                .withUsername("epoch")
                .withPassword("epoch-isolated-test-only")
                .withUrlParam("options", "-c statement_timeout=${options.migrationTimeoutSeconds * 1000} -c lock_timeout=${minOf(30, options.migrationTimeoutSeconds) * 1000}")
                .withUrlParam("connectTimeout", "10")
                .withUrlParam("socketTimeout", (options.migrationTimeoutSeconds + 10).toString())
                .use { database ->
                    owned.set(database)
                    try { database.start() }
                    catch (failure: Throwable) {
                        throw VerificationException("DATABASE_START", message = "Cannot start isolated PostgreSQL. Docker must be available with images $POSTGRES_IMAGE and $RYUK_IMAGE. See the underlying cause; TESTCONTAINERS_RYUK_DISABLED=true is supported when your CI manages container cleanup.", cause = failure)
                    }
                    if (cancelled.get()) throw VerificationException("GLOBAL_TIMEOUT", message = "Verification timed out while PostgreSQL was starting.")
                    val baselineLocation = "filesystem:${baselineDirectory.toAbsolutePath()}"
                    val pendingLocation = "filesystem:${migrationsDirectory.toAbsolutePath()}"
                    fun configuration() = Flyway.configure()
                        .dataSource(database.jdbcUrl, database.username, database.password)
                        .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                        .cleanDisabled(true)
                        .validateMigrationNaming(true)
                        .validateOnMigrate(true)
                        .placeholderReplacement(false)
                        .skipDefaultCallbacks(true)
                        .group(false)
                        .batch(false)
                        .lockRetryCount(10)
                        .loggers("slf4j")

                    val cache = BaselineCache(options.baselineCache)
                    val cacheKey = cache.key(baselineDirectory, database.containerInfo.imageId)
                    val cacheResult = cache.prepare(database, cacheKey) {
                        MigrationDeadline(options.migrationTimeoutSeconds).use { deadline ->
                            try { configuration().locations(baselineLocation).callbacks(deadline).load().migrate() }
                            catch (failure: Throwable) {
                                deadline.checkExpired()
                                throw executionFailure("BASELINE_EXECUTION_FAILED", deadline.activeMigration, failure)
                            }
                        }
                    }
                    org.slf4j.LoggerFactory.getLogger(PostgresVerifier::class.java).info("Baseline cache {}: {}",
                        cacheResult.status, cacheResult.reason ?: if (cacheResult.status == "HIT")
                            "Validated immutable archive and restored baseline catalog." else "Baseline archive caching is disabled.")
                    try { configuration().locations(baselineLocation).load().validate() }
                    catch (failure: Throwable) { throw executionFailure("BASELINE_VALIDATION_FAILED", null, failure) }
                    val catalog = PostgresCatalog()
                    val planner = PostgresQueryPlanner()
                    fun openPlanningConnection() = DriverManager.getConnection(database.jdbcUrl, database.username, database.password)
                    fun planBatch(connection: java.sql.Connection, queries: List<QueryContract>, snapshot: SchemaSnapshot): QueryPlanningBatch {
                        return planner.inspectSupportedBatch(connection, queries, snapshot, ::openPlanningConnection, options.planningParallelism)
                    }
                    val boundaryCollector = if (options.executeBoundaries) PostgresBoundaryProbes() else null
                    val engine = CompatibilityEngine()
                    val baseline: SchemaSnapshot
                    val baselinePlans: Map<String, QueryPlan>
                    val baselinePlanResult: BaselinePlans
                    val boundaryBaseline: BoundaryProbeBaseline?
                    DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                        baseline = catalog.inspect(connection)
                        baselinePlanResult = BaselinePlanCache(options.baselineCache, PlanningSession.capture(connection)).prepare(contract, cacheKey, baseline) { queries ->
                            planBatch(connection, queries, baseline)
                        }
                        baselinePlans = baselinePlanResult.plans
                        boundaryBaseline = boundaryCollector?.collectBaseline(connection, contract, baseline, baselinePlans)
                        // Preserve the exact old schema and seed data once, without replaying migrations.
                        // PostgreSQL clones only this verifier-owned database into another owned database.
                        if (newContract != null) connection.createStatement().use {
                            it.execute("CREATE DATABASE epoch_rollback TEMPLATE epoch")
                        }
                    }
                    val baselineFindings = planner.baselineFindings(contract.queries, baselinePlans) +
                        baselineCallbacks.map { callbackFinding(it, null) }
                    val baselineHealth = engine.inspectBaseline(contract, baseline, baselineFindings, cacheKey)
                        .copy(cacheStatus = cacheResult.status, cacheKey = cacheKey, cacheReason = cacheResult.reason,
                            planCacheStatus = baselinePlanResult.status, planCacheKey = baselinePlanResult.key,
                            planCacheReason = baselinePlanResult.reason, plannedQueries = baselinePlanResult.plannedQueries,
                            reusedQueries = baselinePlanResult.reusedQueries, planningParallelism = baselinePlanResult.planningParallelism)
                    val steps = pendingCallbacks.map { filename ->
                        val migration = MigrationInfo("callback", filename)
                        MigrationReport(migration, Verdict.UNKNOWN, listOf(callbackFinding(filename, migration)))
                    }.toMutableList()
                    val operational = PostgresOperationalMonitor(baseline, tableSizes)
                    if (options.progress != null) publishProgress(options, "old-on-new",
                        withOperational(engine.report(contract, steps.toList(), baselineHealth, options.acceptedBaseline)))
                    var priorSchema = baseline
                    var currentPlans = baselinePlans
                    MigrationDeadline(options.migrationTimeoutSeconds).use { deadline ->
                        val callback = object : Callback {
                            override fun getCallbackName() = "epoch-intermediate-schema-verification"
                            override fun supports(event: Event, context: Context?) = event == Event.AFTER_EACH_MIGRATE
                            override fun canHandleInTransaction(event: Event, context: Context?) = true
                            override fun handle(event: Event, context: Context) {
                                val info = context.migrationInfo
                                val migration = MigrationInfo(info.version?.toString() ?: "repeatable", info.script)
                                val after = catalog.inspect(context.connection)
                                val effects = try {
                                    MigrationClassifier().findings(Files.readString(Path.of(info.physicalLocation)), migration, priorSchema, after,
                                        CompatibilityViews.provenViewNames(baseline, after))
                                } catch (failure: java.io.IOException) {
                                    listOf(Finding(Verdict.UNKNOWN, "MIGRATION_EFFECTS_UNAVAILABLE", migration.filename,
                                        "Could not read the exact migration resource for effect classification.",
                                        failure.javaClass.simpleName, migration = migration))
                                }
                                val planning = planner.planStep(context.connection, contract.queries, baselinePlans,
                                    migration, priorSchema, after, currentPlans, ::openPlanningConnection, options.planningParallelism)
                                val witnesses = boundaryBaseline?.let { boundaryCollector?.findings(context.connection, it, after, migration) }.orEmpty()
                                steps += engine.verifyStep(contract, baseline, after, migration,
                                    baselineHealth.findings + effects + planning.findings + witnesses)
                                    .copy(replannedQueries = planning.replannedQueries, reusedQueries = planning.reusedQueries,
                                        planningParallelism = planning.planningParallelism, operational = operational.snapshot(migration))
                                priorSchema = after
                                currentPlans = planning.plans
                                if (options.progress != null) publishProgress(options, "old-on-new",
                                    withOperational(engine.report(contract, steps.toList(), baselineHealth, options.acceptedBaseline)))
                            }
                        }
                        try {
                            configuration().locations(baselineLocation, pendingLocation).callbacks(deadline, callback, operational).load().migrate()
                            deadline.checkExpired()
                        } catch (failure: Throwable) {
                            deadline.checkExpired()
                            throw executionFailure("MIGRATION_EXECUTION_FAILED", deadline.activeMigration, failure)
                        }
                    }
                    val forward = withOperational(engine.report(contract, steps, baselineHealth, options.acceptedBaseline)).let { report ->
                        if (boundaryBaseline == null) report else report.copy(limitations = report.limitations + boundaryBaseline.summary +
                            boundaryBaseline.skipped.entries.groupBy { it.value }.map { (reason, entries) ->
                                "Boundary execution skipped ${entries.size} INSERT templates: $reason"
                            }.sorted())
                    }
                    var dataCompatibility: CompatibilityReport? = null
                    val reverse = newContract?.let { next ->
                        val finalSchema: SchemaSnapshot
                        val finalPlans: Map<String, QueryPlan>
                        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                            finalSchema = catalog.inspect(connection)
                            finalPlans = planBatch(connection, next.queries, finalSchema).plans
                        }
                        val finalHealth = engine.inspectBaseline(next, finalSchema,
                            planner.baselineFindings(next.queries, finalPlans), "new-application-final-schema:$cacheKey")
                        if (options.progress != null) publishProgress(options, "new-on-old", engine.report(next, emptyList(), finalHealth))
                        val queryPart = database.jdbcUrl.substringAfter('?', "")
                        val oldUrl = database.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/epoch_rollback" +
                            if (queryPart.isNotEmpty()) "?$queryPart" else ""
                        val reverseStep = DriverManager.getConnection(oldUrl, database.username, database.password).use { connection ->
                            val migration = MigrationInfo("rollback", "<old-schema>")
                            val planning = planner.planStep(connection, next.queries, finalPlans, migration, finalSchema, baseline,
                                connectionFactory = { DriverManager.getConnection(oldUrl, database.username, database.password) },
                                parallelism = options.planningParallelism)
                            engine.verifyStep(next, finalSchema, baseline, migration, finalHealth.findings + planning.findings)
                                .copy(replannedQueries = planning.replannedQueries, reusedQueries = planning.reusedQueries,
                                    planningParallelism = planning.planningParallelism)
                        }
                        if (options.progress != null) publishProgress(options, "new-on-old", engine.report(next, listOf(reverseStep), finalHealth))
                        dataCompatibility = DataContractCompatibility().assess(contract, next, finalSchema,
                            currentPlans.filterValues { it.accepted }.keys, finalPlans.filterValues { it.accepted }.keys)
                        if (options.progress != null) publishProgress(options, "new-writes-old-reads", requireNotNull(dataCompatibility))
                        engine.report(next, listOf(reverseStep), finalHealth).let {
                            it.copy(limitations = it.limitations + "New-on-old verifies the new contract against the retained original schema; it does not execute or prove down-migration behavior." +
                                if (options.executeBoundaries) listOf("Optional boundary execution applies only to old-on-new verification; reverse compatibility uses catalog and SQL planning evidence.") else emptyList())
                        }
                    }
                    VerificationRun(forward, reverse, dataCompatibility)
                }
        }
    }

    /** Only the forward deployment executes pending SQL; reverse/data assessments have no operational evidence. */
    private fun withOperational(report: CompatibilityReport): CompatibilityReport {
        val verdicts = report.migrations.map { it.operational?.verdict ?: OperationalVerdict.UNKNOWN }
        return report.copy(
            operationalVerdict = OperationalAssessmentRules.aggregateVerdicts(verdicts),
            operationalLimitations = (report.migrations.flatMap { it.operational?.limitations.orEmpty() } +
                when {
                    report.migrations.isEmpty() -> listOf("No pending migrations were executed in the requested deployment window.")
                    report.migrations.any { it.operational == null } -> listOf("Some declared steps have no measured operational assessment.")
                    else -> emptyList()
                }).distinct().sorted(),
        )
    }

    private fun publishProgress(options: VerificationOptions, direction: String, report: CompatibilityReport) {
        val snapshot = report.copy(
            migrations = report.migrations.map { it.copy(findings = it.findings.toList()) },
            complete = false,
            incompleteReason = "Verification has not completed all requested migration states.",
            verdict = if (report.verdict == Verdict.BREAKING) Verdict.BREAKING else Verdict.UNKNOWN,
            gateVerdict = report.gateVerdict?.let { if (it == Verdict.BREAKING) it else Verdict.UNKNOWN },
            operationalVerdict = report.operationalVerdict?.let {
                if (it == OperationalVerdict.RISKY) it else OperationalVerdict.UNKNOWN
            },
        )
        try { options.progress?.invoke(direction, snapshot) }
        catch (failure: Exception) {
            throw VerificationException("PROGRESS_REPORT_FAILED", message = "Could not persist verification progress: ${failure.message}", cause = failure)
        }
    }

    private fun validateContracts(contracts: List<DatabaseContract>) {
        require(contracts.isNotEmpty()) { "At least one database contract is required" }
        contracts.forEach { contract ->
            require(contract.schemaVersion == 1) { "Unsupported contract schemaVersion: ${contract.schemaVersion}" }
            require(contract.dialect == "PostgreSQL") { "Only PostgreSQL contracts are supported" }
            require(contract.queries.map { it.id }.distinct().size == contract.queries.size) { "Query IDs must be unique within each contract" }
        }
    }

    private fun executionFailure(stage: String, migration: MigrationInfo?, failure: Throwable): VerificationException {
        if (failure is VerificationException) return failure
        val location = migration?.let { " ${it.version} ${it.filename}" }.orEmpty()
        val timedOut = generateSequence(failure) { it.cause }.take(20).filterIsInstance<SQLException>()
            .any { it.sqlState == "57014" && it.message.orEmpty().contains("timeout", ignoreCase = true) }
        val effectiveStage = if (timedOut) "MIGRATION_TIMEOUT" else stage
        return VerificationException(effectiveStage, migration,
            "$effectiveStage$location: verification did not complete. ${causeMessages(failure)}", failure)
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
                entities = item.entities.map { entity -> entity.copy(id = prefix + entity.id,
                    hierarchy = entity.hierarchy?.let { hierarchy -> hierarchy.copy(
                        rootEntityId = hierarchy.rootEntityId?.let { prefix + it }) }) },
                queries = item.queries.map { it.copy(
                    id = prefix + it.id,
                    accessPathIds = it.accessPathIds.map { path -> prefix + path },
                    mappedEntityIds = it.mappedEntityIds.map { entity -> prefix + entity },
                    mappedReads = it.mappedReads.map { read -> read.copy(entityId = prefix + read.entityId) },
                    polymorphicReads = it.polymorphicReads.map { read -> read.copy(rootEntityId = prefix + read.rootEntityId) },
                    discriminatorWrites = it.discriminatorWrites.map { write -> write.copy(entityId = prefix + write.entityId) },
                ) },
                unresolvedQueries = item.unresolvedQueries.map { it.copy(id = prefix + it.id) },
                accessPaths = item.accessPaths.map { it.copy(id = prefix + it.id, queryIds = it.queryIds.map { query -> prefix + query }) },
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
            accessPaths = prefixed.flatMap { it.accessPaths },
        )
    }

    private fun causeMessages(failure: Throwable): String = generateSequence(failure) { it.cause }
        .take(20).map { "${it.javaClass.simpleName}: ${it.message}" }.distinct().joinToString("; caused by ")

    companion object {
        const val POSTGRES_IMAGE = "postgres:16.8"
        const val RYUK_IMAGE = "testcontainers/ryuk:0.14.0"
    }
}
