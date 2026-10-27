package dev.epoch.cli

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

private val json = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

@Command(name = "db-compat", mixinStandardHelpOptions = true, version = ["epoch 0.1.0"],
    description = ["Deterministic PostgreSQL compatibility checks for an explicit Hibernate SQL contract."],
    subcommands = [Extract::class, Verify::class, Report::class])
class Main : Runnable {
    override fun run() { CommandLine(this).usage(System.out) }
}

@Command(name = "extract", mixinStandardHelpOptions = true,
    description = ["Extract actual Hibernate mappings and generated SQL from compiled entity classes.",
        "Supply a JSON descriptor; classpath entries are resolved relative to its directory."])
class Extract : Callable<Int> {
    @Option(names = ["--descriptor"], required = true, description = ["ExtractionRequest JSON file"])
    lateinit var descriptor: Path
    @Option(names = ["--output"], required = true, description = ["Versioned contract JSON destination"])
    lateinit var output: Path

    override fun call(): Int {
        val request: ExtractionRequest = json.readValue(descriptor.toFile())
        val base = descriptor.toAbsolutePath().parent
        val resolved = request.copy(classpath = request.classpath.map { base.resolve(it).normalize().toString() })
        val contract = HibernateExtractor().extract(resolved)
        writeJson(output, contract)
        println("Extracted ${contract.entities.size} entity mappings and ${contract.queries.size} SQL templates to $output")
        println("Scope: ${contract.scope}; unresolved sources: ${contract.unresolvedQueries.size}")
        println("Runtime observations and entity discovery do not establish whole-application coverage.")
        return 0
    }
}

@Command(name = "verify", mixinStandardHelpOptions = true,
    description = ["Apply Flyway migrations to a disposable PostgreSQL container and check every intermediate state.",
        "Requires Docker. No external database URL or production credentials are accepted."])
class Verify : Callable<Int> {
    @Option(names = ["--contract"], required = true) lateinit var contractPath: Path
    @Option(names = ["--baseline"], required = true,
        description = ["Directory of Flyway SQL migrations establishing the old schema"])
    lateinit var baseline: Path
    @Option(names = ["--migrations"], required = true,
        description = ["Directory of pending Flyway SQL migrations (versions after baseline)"])
    lateinit var migrations: Path
    @Option(names = ["--report"], required = true) lateinit var reportPath: Path

    override fun call(): Int {
        val contract: DatabaseContract = json.readValue(contractPath.toFile())
        require(contract.schemaVersion == 1) { "Unsupported contract schemaVersion: ${contract.schemaVersion}" }
        require(contract.dialect == "PostgreSQL") { "Only PostgreSQL contracts are supported" }
        require(Files.isDirectory(baseline)) { "Baseline directory does not exist: $baseline" }
        require(Files.isDirectory(migrations)) { "Migration directory does not exist: $migrations" }
        val report = PostgresVerifier().verify(contract, baseline, migrations)
        writeJson(reportPath, report)
        println(renderReport(report))
        return exitCode(report.verdict)
    }
}

@Command(name = "report", mixinStandardHelpOptions = true,
    description = ["Render an existing JSON report; returns its compatibility exit code."])
class Report : Callable<Int> {
    @Option(names = ["--input"], required = true) lateinit var input: Path
    override fun call(): Int {
        val report: CompatibilityReport = json.readValue(input.toFile())
        require(report.schemaVersion == 1) { "Unsupported report schemaVersion: ${report.schemaVersion}" }
        println(renderReport(report))
        return exitCode(report.verdict)
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

fun renderReport(report: CompatibilityReport): String = buildString {
    appendLine("Database Compatibility Report")
    appendLine("Application: ${report.applicationVersion}")
    appendLine("Scope: ${report.scope}")
    for (step in report.migrations) {
        appendLine()
        appendLine("${step.migration.version} ${step.migration.filename}: ${step.verdict}")
        for (finding in step.findings) {
            appendLine("  ${finding.verdict} [${finding.ruleId}] ${finding.objectName}: ${finding.message}")
            if (finding.affectedContractIds.isNotEmpty()) appendLine("    Affected: ${finding.affectedContractIds.joinToString()}")
            appendLine("    Evidence: ${finding.evidence}")
            finding.mitigation?.let { appendLine("    Mitigation: $it") }
        }
    }
    appendLine()
    appendLine("Result: ${report.verdict}")
    report.limitations.forEach { appendLine("Scope limitation: $it") }
}.trimEnd()

fun main(args: Array<String>) {
    val command = CommandLine(Main())
    command.setExecutionExceptionHandler { error, _, _ ->
        System.err.println("db-compat: ${error.message ?: error.javaClass.simpleName}")
        3
    }
    command.commandSpec.exitCodeOnInvalidInput(3)
    command.subcommands.values.forEach { it.commandSpec.exitCodeOnInvalidInput(3) }
    exitProcess(command.execute(*args))
}
