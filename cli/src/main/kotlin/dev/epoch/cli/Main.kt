package dev.epoch.cli

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

// Laziness keeps linkage/initialization failures inside runCli's Throwable boundary.
internal val json by lazy { jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT) }

@Command(name = "db-compat", mixinStandardHelpOptions = true, version = ["epoch 0.3.0-SNAPSHOT"],
    description = ["Deterministic PostgreSQL compatibility checks for an explicit Hibernate SQL contract."],
    subcommands = [Extract::class, Verify::class, VerifyRelease::class, Report::class, Lint::class])
class Main : Runnable {
    @Option(names = ["-v", "--verbose"], scope = CommandLine.ScopeType.INHERIT,
        description = ["Enable library INFO logs and full error stack traces (default: quiet libraries, concise failures)"])
    var verbose = false
    override fun run() { CommandLine(this).usage(System.out) }
}

@Command(name = "extract", mixinStandardHelpOptions = true,
    description = ["Extract Hibernate mappings and generated SQL from compiled entities or scanned packages.",
        "Classpath entries are resolved relative to the JSON descriptor's directory."])
class Extract : Callable<Int> {
    @Option(names = ["--descriptor"], required = true, description = ["ExtractionRequest JSON: classes/packages, classpath, Hibernate settings and overrides"])
    lateinit var descriptor: Path
    @Option(names = ["--output"], required = true, description = ["Versioned contract JSON destination"])
    lateinit var output: Path
    @Option(names = ["--keep-going"], description = ["Record failed mapping groups as unresolved and continue independent groups"])
    var keepGoing = false
    @Option(names = ["--runtime-sql"], description = ["Captured SqlCollector JSONL input; repeat to merge test observations"])
    var runtimeSqlPaths: List<Path> = emptyList()

    override fun call(): Int {
        val request: ExtractionRequest = json.readValue(descriptor.toFile())
        val base = descriptor.toAbsolutePath().parent
        val resolved = request.copy(classpath = request.classpath.map { base.resolve(it).normalize().toString() },
            keepGoing = keepGoing || request.keepGoing,
            runtimeSqlFiles = request.runtimeSqlFiles.map { base.resolve(it).normalize().toString() } +
                runtimeSqlPaths.map { it.toAbsolutePath().normalize().toString() })
        val contract = HibernateExtractor().extract(resolved)
        writeJson(output, contract)
        contract.coverage.notes.filter { it.startsWith("Warning:") }.forEach(System.err::println)
        println("Extracted ${contract.entities.size} entity mappings and ${contract.queries.size} SQL templates to $output")
        println("Scope: ${contract.scope}; unresolved sources: ${contract.unresolvedQueries.size}")
        println(renderCoverage(contract))
        println("Entity discovery does not establish whole-application coverage.")
        return 0
    }
}

@Command(name = "verify", mixinStandardHelpOptions = true,
    description = ["Apply Flyway migrations to disposable PostgreSQL and check every intermediate state.",
        "Repeat --contract to verify multiple persistence units with one baseline application."])
class Verify : Callable<Int> {
    @Option(names = ["--contract"], required = true, description = ["Old application's contract JSON; repeat for multiple persistence units"])
    lateinit var contractPaths: List<Path>
    @Option(names = ["--baseline"], required = true,
        description = ["Directory of Flyway SQL migrations establishing the old schema"])
    lateinit var baseline: Path
    @Option(names = ["--migrations"], required = true,
        description = ["Directory of pending Flyway SQL migrations (versions after baseline)"])
    lateinit var migrations: Path
    @Option(names = ["--report"], required = true, description = ["JSON report destination, including separate baseline health and migration findings"])
    lateinit var reportPath: Path
    @Option(names = ["--accepted-baseline"], description = ["Justified baseline acceptance JSON; enables a separate migration gate without hiding new findings"])
    var acceptedBaseline: Path? = null
    @Option(names = ["--baseline-cache"], description = ["Optional directory for checksummed baseline dumps; each run restores into a fresh container"])
    var baselineCache: Path? = null
    @Option(names = ["--details"], description = ["Print every finding; default output groups rules and limits detail"])
    var details = false
    @Option(names = ["--new-contract"], description = ["New application contract; repeat to additionally check new application on old schema"])
    var newContractPaths: List<Path> = emptyList()
    @Option(names = ["--migration-timeout-seconds"], defaultValue = "120", description = ["Maximum seconds per migration (default: 120)"])
    var migrationTimeoutSeconds: Long = 120
    @Option(names = ["--global-timeout-seconds"], defaultValue = "1800", description = ["Maximum seconds for verification (default: 1800)"])
    var globalTimeoutSeconds: Long = 1800
    @Option(names = ["--planning-parallelism"], defaultValue = "4", description = ["Maximum independent planning connections (default: 4); transactional migration checks remain on their owning connection"])
    var planningParallelism: Int = 4
    @Option(names = ["--sarif"], description = ["Optional SARIF 2.1.0 output for CI code scanning"])
    var sarifPath: Path? = null
    @Option(names = ["--migration-source-prefix"], description = ["Repository-relative path prepended to migration filenames in SARIF"])
    var migrationSourcePrefix = ""
    @Option(names = ["--execute-boundaries"], description = ["Try supported rolled-back INSERT boundary witnesses; successful samples never prove SAFE"])
    var executeBoundaries = false

    override fun call(): Int {
        val contracts = contractPaths.map { json.readValue<DatabaseContract>(it.toFile()) }
        require(Files.isDirectory(baseline)) { "Baseline directory does not exist: $baseline" }
        require(Files.isDirectory(migrations)) { "Migration directory does not exist: $migrations" }
        val accepted = acceptedBaseline?.let { json.readValue<AcceptedBaseline>(it.toFile()) }
        val newContracts = newContractPaths.map { json.readValue<DatabaseContract>(it.toFile()) }
        val expectedDirections = if (newContracts.isEmpty()) listOf("old-on-new") else listOf("old-on-new", "new-on-old")
        return VerificationCheckpoint(reportPath, expectedDirections).use { checkpoint ->
            try {
                val options = VerificationOptions(baselineCache, accepted, migrationTimeoutSeconds, globalTimeoutSeconds,
                    executeBoundaries, planningParallelism = planningParallelism, progress = checkpoint::progress)
                val verifier = PostgresVerifier()
                if (newContracts.isNotEmpty()) {
                    val report = verifier.verifyBidirectional(contracts, newContracts, baseline, migrations, options)
                    checkpoint.complete(report)
                    val directions = listOf("old-on-new" to report.oldOnNew, "new-on-old" to report.newOnOld)
                    sarifPath?.let { writeJson(it, sarifReport(directions, migrationSourcePrefix)) }
                    directions.forEach { (name, result) -> println("Direction: $name\n${renderReport(result, allDetails = details)}") }
                    exitCode(CompatibilityEngine.aggregate(directions.map { it.second.gateVerdict ?: it.second.verdict }))
                } else {
                    val report = verifier.verify(contracts, baseline, migrations, options)
                    checkpoint.complete(report)
                    sarifPath?.let { writeJson(it, sarifReport(listOf("old-on-new" to report), migrationSourcePrefix)) }
                    println(renderReport(report, allDetails = details))
                    exitCode(report.gateVerdict ?: report.verdict)
                }
            } catch (failure: Throwable) {
                try { checkpoint.fail(failure) } catch (checkpointFailure: Throwable) { failure.addSuppressed(checkpointFailure) }
                throw failure
            }
        }
    }
}

@Command(name = "report", mixinStandardHelpOptions = true,
    description = ["Summarize a saved report by rule, keeping baseline debt separate from migration changes."])
class Report : Callable<Int> {
    @Option(names = ["--input"], required = true, description = ["Saved compatibility report JSON"])
    lateinit var input: Path
    @Option(names = ["--summary"], description = ["Show rule counts and verdicts only"])
    var summaryOnly = false
    @Option(names = ["--details"], description = ["Print all individual findings, including baseline health"])
    var details = false
    @Option(names = ["--write-accepted-baseline"], description = ["Write an acceptance file for this report's baseline findings only"])
    var acceptedOutput: Path? = null
    @Option(names = ["--justification"], description = ["Required nonempty explanation when writing a baseline acceptance file"])
    var justification: String? = null
    @Option(names = ["--sarif"], description = ["Write SARIF 2.1.0 findings from this saved report"])
    var sarifPath: Path? = null
    @Option(names = ["--migration-source-prefix"], description = ["Repository-relative migration path for SARIF locations"])
    var migrationSourcePrefix = ""

    override fun call(): Int {
        require(!(summaryOnly && details)) { "Choose either --summary or --details" }
        val tree = json.readTree(input.toFile())
        require(tree.path("schemaVersion").asInt(-1) == 1) { "Unsupported report schemaVersion: ${tree.path("schemaVersion")}" }
        val sourcePrefix = migrationSourcePrefix.ifEmpty { tree.path("release").path("migrationPath").asText("") }
        if (tree.path("complete").isBoolean && !tree.path("complete").asBoolean() && tree.has("reports")) {
            require(acceptedOutput == null && justification == null) { "Cannot accept baseline findings from incomplete verification." }
            val directions = tree.path("reports").fields().asSequence().sortedBy { it.key }.map { (name, value) ->
                val report = json.treeToValue(value, CompatibilityReport::class.java)
                require(report.schemaVersion == 1) { "Unsupported report schemaVersion: ${report.schemaVersion}" }
                name to report
            }.toList()
            println("INCOMPLETE verification: ${tree.path("failureStage").asText("INCOMPLETE")}")
            println(tree.path("incompleteReason").asText("Only completed checkpoints are available."))
            directions.forEach { (name, result) -> println("Direction: $name\n${renderReport(result, summaryOnly, details)}") }
            sarifPath?.let { writeJson(it, sarifReport(directions, sourcePrefix)) }
            return 3
        }
        val payload = tree.get("verification") ?: tree
        require(payload.path("schemaVersion").asInt(-1) == 1) { "Unsupported verification schemaVersion: ${payload.path("schemaVersion")}" }
        if (payload.has("oldOnNew")) {
            require(acceptedOutput == null && justification == null) { "Export baseline acceptance from a single-direction report" }
            val directions = listOf("old-on-new" to json.treeToValue(payload.get("oldOnNew"), CompatibilityReport::class.java),
                "new-on-old" to json.treeToValue(payload.get("newOnOld"), CompatibilityReport::class.java))
            directions.forEach { (name, result) ->
                require(result.schemaVersion == 1) { "Unsupported report schemaVersion: ${result.schemaVersion}" }
                println("Direction: $name\n${renderReport(result, summaryOnly, details)}")
            }
            sarifPath?.let { writeJson(it, sarifReport(directions, sourcePrefix)) }
            if (directions.any { !it.second.complete } || payload.path("complete").let { it.isBoolean && !it.asBoolean() } ||
                tree.path("complete").let { it.isBoolean && !it.asBoolean() }) return 3
            return exitCode(CompatibilityEngine.aggregate(directions.map { it.second.gateVerdict ?: it.second.verdict }))
        }
        val report = json.treeToValue(payload, CompatibilityReport::class.java)
        require(report.schemaVersion == 1) { "Unsupported report schemaVersion: ${report.schemaVersion}" }
        println(renderReport(report, summaryOnly, details))
        sarifPath?.let { writeJson(it, sarifReport(listOf("old-on-new" to report), sourcePrefix)) }
        if (!report.complete || tree.path("complete").let { it.isBoolean && !it.asBoolean() }) {
            require(acceptedOutput == null && justification == null) { "Cannot accept baseline findings from incomplete verification." }
            return 3
        }
        acceptedOutput?.let {
            writeJson(it, BaselineAcceptance.fromReport(report, requireNotNull(justification) {
                "--justification is required with --write-accepted-baseline"
            }))
            println("Wrote baseline acceptance to $it; migration findings are never suppressed by this file.")
            return 0
        }
        require(justification == null) { "--justification requires --write-accepted-baseline" }
        return exitCode(report.gateVerdict ?: report.verdict)
    }
}

internal fun writeJson(path: Path, value: Any) {
    path.toAbsolutePath().parent?.let(Files::createDirectories)
    json.writeValue(path.toFile(), value)
}

fun exitCode(verdict: Verdict) = when (verdict) {
    Verdict.SAFE -> 0
    Verdict.BREAKING -> 1
    Verdict.UNKNOWN -> 2
}

fun renderReport(report: CompatibilityReport, summaryOnly: Boolean = false, allDetails: Boolean = false): String = buildString {
    appendLine("Database Compatibility Report")
    appendLine("Application: ${report.applicationVersion}")
    appendLine("Scope: ${report.scope}")
    if (!report.complete) appendLine("INCOMPLETE: ${report.incompleteReason ?: "Verification did not finish."}")
    if (report.accessPaths.isNotEmpty()) appendLine(renderCoverage(report.accessPaths, details = allDetails,
        maxClasses = if (allDetails) Int.MAX_VALUE else 20))
    report.baseline?.let { baseline ->
        appendLine("Baseline health: ${baseline.verdict} (${baseline.findings.size} findings)")
        baseline.cacheStatus?.let { appendLine("Baseline cache: $it") }
        baseline.cacheReason?.let { appendLine("Baseline cache reason: $it") }
        baseline.planCacheStatus?.let { appendLine("Baseline plan cache: $it") }
        baseline.planCacheReason?.let { appendLine("Baseline plan cache reason: $it") }
        if (report.acceptedBaseline.isNotEmpty()) appendLine("Accepted baseline findings: ${report.acceptedBaseline.size} (justifications retained in JSON)")
    }
    val ruleRows = report.ruleSummary.ifEmpty {
        val original = report.baseline?.findings.orEmpty()
        val pending = report.migrations.flatMap { it.findings }
        (original + pending).groupBy { it.ruleId to it.verdict }.keys
            .sortedWith(compareBy({ it.first }, { it.second.name })).map { (rule, verdict) ->
                RuleSummary(rule, verdict, original.count { it.ruleId == rule && it.verdict == verdict },
                    pending.count { it.ruleId == rule && it.verdict == verdict }, 0)
            }
    }
    if (ruleRows.isNotEmpty()) {
        appendLine("Rule summary (baseline / migrations / accepted baseline):")
        ruleRows.sortedWith(compareBy({ severity(it.verdict) }, { it.ruleId })).forEach { row ->
            appendLine("  ${row.verdict} ${row.ruleId}: ${row.baselineCount} / ${row.migrationCount} / ${row.acceptedBaselineCount}")
        }
    }
    var remaining = if (summaryOnly) 0 else if (allDetails) Int.MAX_VALUE else 20
    var omitted = 0
    fun finding(f: Finding) {
        if (remaining == 0) { omitted++; return }
        remaining--
        appendLine("  ${f.verdict} [${f.ruleId}] ${f.objectName}: ${f.message}")
        if (f.affectedContractIds.isNotEmpty()) appendLine("    Affected: ${f.affectedContractIds.joinToString()}")
        appendLine("    Evidence: ${f.evidence}")
        if (f.verdict == Verdict.UNKNOWN) appendLine("    Next evidence: ${f.mitigation ?: unknownEvidence(f)}")
        else f.mitigation?.let { appendLine("    Mitigation: $it") }
    }
    // Prioritize migration detail so a large baseline cannot bury new findings.
    for (step in report.migrations.sortedBy { severity(it.verdict) }) {
        appendLine()
        appendLine("${step.migration.version} ${step.migration.filename}: ${step.verdict} (${step.findings.size} findings)")
        step.findings.sortedBy { severity(it.verdict) }.forEach(::finding)
    }
    report.baseline?.takeIf { it.findings.isNotEmpty() }?.let {
        appendLine()
        appendLine("Baseline findings (predate pending migrations):")
        it.findings.sortedBy { f -> severity(f.verdict) }.forEach(::finding)
    }
    if (omitted > 0 && !summaryOnly) appendLine("$omitted findings omitted from terminal output; use report --details or inspect JSON.")
    appendLine()
    appendLine(if (report.complete) "Result: ${report.verdict}" else "Partial evidence: ${report.verdict} (verification incomplete; exit 3)")
    report.gateVerdict?.let { appendLine("Migration gate with accepted baseline: $it (not a whole-contract safety proof)") }
    report.limitations.forEach { appendLine("Scope limitation: $it") }
}.trimEnd()

/** Covers LinkageError, NoClassDefFoundError and other non-Exception failures as well as CLI errors. */
fun runCli(args: Array<String>, commandFactory: () -> CommandLine = { CommandLine(Main()) }): Int {
    val verbose = args.any { it == "--verbose" || it == "-v" }
    return try {
        System.setProperty("org.jboss.logging.provider", "slf4j")
        // The command owns concise diagnostics. Library errors often include full traces and
        // Flyway logs PostgreSQL NOTICE as WARN, so opt into library output only with --verbose.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", if (verbose) "info" else "off")
        val command = commandFactory()
        command.setExecutionExceptionHandler { error, _, _ -> printFailure(error, verbose); 3 }
        fun configure(cli: CommandLine) {
            cli.commandSpec.exitCodeOnInvalidInput(3)
            cli.subcommands.values.forEach(::configure)
        }
        configure(command)
        command.execute(*args)
    } catch (error: Throwable) {
        printFailure(error, verbose)
        3
    }
}

internal fun printFailure(error: Throwable, verbose: Boolean) {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        System.err.println("${if (current === error) "db-compat" else "  caused by"}: ${current.javaClass.simpleName}: ${current.message ?: "no message"}")
        current = current.cause
    }
    if (verbose) error.printStackTrace(System.err)
}

fun main(args: Array<String>) { exitProcess(runCli(args)) }
