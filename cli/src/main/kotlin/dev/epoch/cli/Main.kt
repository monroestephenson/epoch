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
private val json by lazy { jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT) }

@Command(name = "db-compat", mixinStandardHelpOptions = true, version = ["epoch 0.2.0-SNAPSHOT"],
    description = ["Deterministic PostgreSQL compatibility checks for an explicit Hibernate SQL contract."],
    subcommands = [Extract::class, Verify::class, Report::class])
class Main : Runnable {
    @Option(names = ["-v", "--verbose"], scope = CommandLine.ScopeType.INHERIT,
        description = ["Enable library INFO logs and full error stack traces (default: warnings/errors only)"])
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

    override fun call(): Int {
        val request: ExtractionRequest = json.readValue(descriptor.toFile())
        val base = descriptor.toAbsolutePath().parent
        val resolved = request.copy(classpath = request.classpath.map { base.resolve(it).normalize().toString() },
            keepGoing = keepGoing || request.keepGoing)
        val contract = HibernateExtractor().extract(resolved)
        writeJson(output, contract)
        println("Extracted ${contract.entities.size} entity mappings and ${contract.queries.size} SQL templates to $output")
        println("Scope: ${contract.scope}; unresolved sources: ${contract.unresolvedQueries.size}")
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

    override fun call(): Int {
        val contracts = contractPaths.map { json.readValue<DatabaseContract>(it.toFile()) }
        require(Files.isDirectory(baseline)) { "Baseline directory does not exist: $baseline" }
        require(Files.isDirectory(migrations)) { "Migration directory does not exist: $migrations" }
        val accepted = acceptedBaseline?.let { json.readValue<AcceptedBaseline>(it.toFile()) }
        val report = PostgresVerifier().verify(contracts, baseline, migrations, VerificationOptions(baselineCache, accepted))
        writeJson(reportPath, report)
        println(renderReport(report, allDetails = details))
        return exitCode(report.gateVerdict ?: report.verdict)
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

    override fun call(): Int {
        require(!(summaryOnly && details)) { "Choose either --summary or --details" }
        val report: CompatibilityReport = json.readValue(input.toFile())
        require(report.schemaVersion == 1) { "Unsupported report schemaVersion: ${report.schemaVersion}" }
        println(renderReport(report, summaryOnly, details))
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

private fun writeJson(path: Path, value: Any) {
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
    report.baseline?.let { baseline ->
        appendLine("Baseline health: ${baseline.verdict} (${baseline.findings.size} findings)")
        baseline.cacheStatus?.let { appendLine("Baseline cache: $it") }
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
        ruleRows.forEach { row ->
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
        f.mitigation?.let { appendLine("    Mitigation: $it") }
    }
    // Prioritize migration detail so a large baseline cannot bury new findings.
    for (step in report.migrations) {
        appendLine()
        appendLine("${step.migration.version} ${step.migration.filename}: ${step.verdict} (${step.findings.size} findings)")
        step.findings.forEach(::finding)
    }
    report.baseline?.takeIf { it.findings.isNotEmpty() }?.let {
        appendLine()
        appendLine("Baseline findings (predate pending migrations):")
        it.findings.forEach(::finding)
    }
    if (omitted > 0 && !summaryOnly) appendLine("$omitted findings omitted from terminal output; use report --details or inspect JSON.")
    appendLine()
    appendLine("Result: ${report.verdict}")
    report.gateVerdict?.let { appendLine("Migration gate with accepted baseline: $it (not a whole-contract safety proof)") }
    report.limitations.forEach { appendLine("Scope limitation: $it") }
}.trimEnd()

/** Covers LinkageError, NoClassDefFoundError and other non-Exception failures as well as CLI errors. */
fun runCli(args: Array<String>, commandFactory: () -> CommandLine = { CommandLine(Main()) }): Int {
    val verbose = args.any { it == "--verbose" || it == "-v" }
    return try {
        System.setProperty("org.jboss.logging.provider", "slf4j")
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", if (verbose) "info" else "warn")
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
