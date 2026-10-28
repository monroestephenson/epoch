package dev.epoch.cli

import dev.epoch.core.CompatibilityReport
import dev.epoch.core.OperationalVerdict
import dev.epoch.core.Verdict
import dev.epoch.postgres.VerificationException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Last verified states survive failures; only a successful verifier return can publish a final report. */
internal class VerificationCheckpoint(
    private val destination: Path,
    private val expectedDirections: List<String>,
    private val release: ReleaseProvenance? = null,
) : AutoCloseable {
    private val reports = sortedMapOf<String, CompatibilityReport>()
    private var terminal = false
    private var completed = false
    private val shutdown = Thread({ stop("INTERRUPTED", processShutdown = true) }, "epoch-report-checkpoint")

    init {
        publishPartial("IN_PROGRESS")
        Runtime.getRuntime().addShutdownHook(shutdown)
    }

    @Synchronized
    fun progress(direction: String, report: CompatibilityReport) {
        if (terminal) return
        require(direction in expectedDirections) { "Unexpected verification direction: $direction" }
        reports[direction] = report.copy(
            verdict = if (report.verdict == Verdict.BREAKING) Verdict.BREAKING else Verdict.UNKNOWN,
            gateVerdict = report.gateVerdict?.let { if (it == Verdict.BREAKING) Verdict.BREAKING else Verdict.UNKNOWN },
            operationalVerdict = report.operationalVerdict?.let {
                if (it == OperationalVerdict.RISKY) it else OperationalVerdict.UNKNOWN
            },
            complete = false,
            incompleteReason = "Verification has not completed all requested migration states.")
        publishPartial("IN_PROGRESS")
    }

    @Synchronized
    fun complete(report: Any) {
        check(!terminal) { "Verification report has already been finalized or interrupted." }
        atomicJson(destination, report)
        completed = true
        terminal = true
        removeHook()
    }

    fun fail(failure: Throwable) {
        val stage = generateSequence(failure) { it.cause }.take(32)
            .filterIsInstance<VerificationException>().firstOrNull()?.stage
            ?: if (failure is InterruptedException || Thread.currentThread().isInterrupted) "INTERRUPTED" else "RUNTIME_FAILURE"
        stop(stage)
    }

    @Synchronized
    private fun stop(stage: String, processShutdown: Boolean = false) {
        if (completed || terminal && !processShutdown) return
        terminal = true // Late worker callbacks cannot replace the retained checkpoint.
        try { publishPartial(stage) } finally { removeHook() }
    }

    private fun publishPartial(stage: String) {
        val envelope = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "complete" to false,
            "expectedDirections" to expectedDirections,
            "reports" to reports,
            "failureStage" to stage,
            "incompleteReason" to "Verification has not completed all requested migration states; retained findings are partial evidence, not a final verdict.",
        )
        release?.let { envelope["release"] = it }
        atomicJson(destination, envelope)
    }

    private fun removeHook() {
        if (Thread.currentThread() !== shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown) }
            catch (_: IllegalStateException) { /* JVM shutdown already started; the hook observes terminal. */ }
        }
    }

    override fun close() = stop("INCOMPLETE")
}

/** Same-directory replacement leaves readers either the previous complete JSON or the new complete JSON. */
internal fun atomicJson(path: Path, value: Any) {
    val target = path.toAbsolutePath()
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".epoch-report-", ".json.tmp")
    try {
        json.writeValue(temporary.toFile(), value)
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}
