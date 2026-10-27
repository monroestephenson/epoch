package dev.epoch.cli

import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.*
import dev.epoch.postgres.*
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "verify-release", mixinStandardHelpOptions = true,
    description = ["Check retained release contracts against migrations added between two Git refs.",
        "Existing migrations must be unchanged. Reads Git blobs without changing your checkout."])
class VerifyRelease : Callable<Int> {
    @Option(names = ["--repository"], defaultValue = ".", description = ["Local Git repository (default: current directory)"])
    var repository: Path = Path.of(".")
    @Option(names = ["--base-ref"], required = true, description = ["Old release Git ref, such as v1.4.0"])
    lateinit var baseRef: String
    @Option(names = ["--target-ref"], defaultValue = "HEAD", description = ["New release Git ref (default: HEAD)"])
    var targetRef = "HEAD"
    @Option(names = ["--migration-path"], required = true, description = ["Migration directory relative to the Git repository root"])
    lateinit var migrationPath: String
    @Option(names = ["--contract"], required = true, description = ["Retained old release contract JSON; repeat for multiple persistence units"])
    lateinit var contractPaths: List<Path>
    @Option(names = ["--new-contract"], description = ["New release contract JSON; adds new-application/old-schema verification"])
    var newContractPaths: List<Path> = emptyList()
    @Option(names = ["--report"], required = true, description = ["JSON destination with exact Git commits and compatibility results"])
    lateinit var reportPath: Path
    @Option(names = ["--sarif"], description = ["Optional SARIF 2.1.0 output; locations use --migration-path"])
    var sarifPath: Path? = null
    @Option(names = ["--baseline-cache"], description = ["Optional baseline dump cache directory"])
    var baselineCache: Path? = null
    @Option(names = ["--migration-timeout-seconds"], defaultValue = "120", description = ["Maximum seconds per migration"])
    var migrationTimeoutSeconds: Long = 120
    @Option(names = ["--global-timeout-seconds"], defaultValue = "1800", description = ["Maximum verification duration in seconds"])
    var globalTimeoutSeconds: Long = 1800
    @Option(names = ["--details"], description = ["Show every finding"])
    var details = false
    @Option(names = ["--execute-boundaries"], description = ["Run supported counterexample probes; samples never establish SAFE"])
    var executeBoundaries = false

    override fun call(): Int {
        val contracts = contractPaths.map { json.readValue<DatabaseContract>(it.toFile()) }
        val newContracts = newContractPaths.map { json.readValue<DatabaseContract>(it.toFile()) }
        val options = VerificationOptions(baselineCache = baselineCache, migrationTimeoutSeconds = migrationTimeoutSeconds,
            globalTimeoutSeconds = globalTimeoutSeconds, executeBoundaries = executeBoundaries)
        return GitReleaseMigrations.materialize(repository, baseRef, targetRef, migrationPath).use { files ->
            val verifier = PostgresVerifier()
            val verification: Any
            val directions: List<Pair<String, CompatibilityReport>>
            if (newContracts.isEmpty()) {
                val report = verifier.verify(contracts, files.baseline, files.pending, options)
                verification = report
                directions = listOf("old-on-new" to report)
            } else {
                val report = verifier.verifyBidirectional(contracts, newContracts, files.baseline, files.pending, options)
                verification = report
                directions = listOf("old-on-new" to report.oldOnNew, "new-on-old" to report.newOnOld)
            }
            writeJson(reportPath, mapOf("schemaVersion" to 1, "release" to files.provenance, "verification" to verification))
            sarifPath?.let { writeJson(it, sarifReport(directions, migrationPath)) }
            println("Git migrations: ${files.provenance.baseCommit} -> ${files.provenance.targetCommit}")
            directions.forEach { (name, report) -> println("Direction: $name\n${renderReport(report, allDetails = details)}") }
            exitCode(CompatibilityEngine.aggregate(directions.map { it.second.gateVerdict ?: it.second.verdict }))
        }
    }
}

@Command(name = "lint", mixinStandardHelpOptions = true,
    description = ["Optional operational SQL review hints, separate from compatibility verdicts.",
        "Does not run migrations or certify lock duration, runtime, or online deployment safety."])
class Lint : Callable<Int> {
    @Option(names = ["--migrations"], required = true, description = ["Directory containing migration SQL to inspect"])
    lateinit var migrations: Path
    @Option(names = ["--report"], required = true, description = ["Operational lint JSON destination (does not contain a compatibility verdict)"])
    lateinit var reportPath: Path

    override fun call(): Int {
        val report = OperationalLint().inspect(migrations)
        writeJson(reportPath, report)
        report.findings.forEach { println("${it.filename}:${it.line} [${it.ruleId}] ${it.message}") }
        println("${report.findings.size} operational review hints. Compatibility verdict is unaffected.")
        return 0
    }
}
