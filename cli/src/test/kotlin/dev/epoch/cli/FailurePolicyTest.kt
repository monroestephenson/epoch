package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class FailurePolicyTest {
    @TempDir lateinit var directory: Path

    private fun report(verdict: Verdict = Verdict.UNKNOWN) = CompatibilityReport(
        applicationVersion = "policy-fixture", scope = VerificationScope.STRUCTURAL_CONTRACT,
        verdict = verdict, migrations = emptyList(), operationalVerdict = OperationalVerdict.SAFE)

    private fun replay(value: Any, vararg options: String): Int {
        val path = directory.resolve("report.json")
        writeJson(path, value)
        val original = Files.readString(path)
        val result = runCli(arrayOf("report", "--input", path.toString(), "--summary", *options))
        assertEquals(original, Files.readString(path), "A gate policy must not rewrite the scientific evidence.")
        return result
    }

    @Test fun `breaking policy permits UNKNOWN while preserving verdict and default CI behavior`() {
        assertEquals(2, replay(report()))
        assertEquals(2, replay(report(), "--fail-on", "unknown"))
        assertEquals(0, replay(report(), "--fail-on", "breaking"))
        for (policy in listOf("breaking", "unknown")) {
            assertEquals(0, replay(report(Verdict.SAFE), "--fail-on", policy))
            assertEquals(1, replay(report(Verdict.BREAKING), "--fail-on", policy))
        }
        assertEquals(3, replay(report(), "--fail-on", "anything"))
    }

    @Test fun `accepted baseline and operational gate remain independent policies`() {
        assertEquals(0, replay(report().copy(gateVerdict = Verdict.SAFE), "--fail-on", "unknown"))
        assertEquals(0, replay(report().copy(gateVerdict = Verdict.UNKNOWN), "--fail-on", "breaking"))
        assertEquals(1, replay(report().copy(gateVerdict = Verdict.BREAKING), "--fail-on", "breaking"))
        assertEquals(4, replay(report().copy(operationalVerdict = OperationalVerdict.RISKY),
            "--fail-on", "breaking", "--fail-on-operational"))
        assertEquals(0, replay(report(), "--fail-on", "breaking", "--fail-on-operational"))
        assertEquals(2, replay(report().copy(operationalVerdict = OperationalVerdict.RISKY),
            "--fail-on", "unknown", "--fail-on-operational"))
        for (operational in listOf(OperationalVerdict.UNKNOWN, null)) {
            assertEquals(0, replay(report().copy(operationalVerdict = operational), "--fail-on", "breaking"))
            assertEquals(4, replay(report().copy(operationalVerdict = operational),
                "--fail-on", "breaking", "--fail-on-operational"))
        }
    }

    @Test fun `all directions and incomplete checkpoints obey policy without hiding data breakage`() {
        val combined = BidirectionalReport(oldOnNew = report(Verdict.SAFE), newOnOld = report(),
            dataCompatibility = report(Verdict.SAFE), verdict = Verdict.UNKNOWN)
        assertEquals(0, replay(combined, "--fail-on", "breaking"))
        assertEquals(1, replay(combined.copy(dataCompatibility = report(Verdict.BREAKING), verdict = Verdict.BREAKING),
            "--fail-on", "breaking"))
        assertEquals(3, replay(report().copy(complete = false), "--fail-on", "breaking"))
        assertEquals(3, replay(combined.copy(dataCompatibility = report().copy(complete = false)), "--fail-on", "breaking"))
        val wrapper = mapOf("schemaVersion" to 1, "release" to ReleaseProvenance("old", "new", "db/migration"),
            "verification" to combined)
        assertEquals(0, replay(wrapper, "--fail-on", "breaking"))
        val partial = mapOf("schemaVersion" to 1, "complete" to false, "reports" to mapOf("old-on-new" to report()))
        assertEquals(3, replay(partial, "--fail-on", "breaking", "--fail-on-operational"))
    }

    @Test fun `permitted UNKNOWN still prints UNKNOWN and leaves report evidence unchanged`() {
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        try {
            System.setOut(PrintStream(bytes))
            assertEquals(0, replay(report(), "--fail-on", "breaking"))
        } finally { System.setOut(previous) }
        assertTrue(bytes.toString(Charsets.UTF_8).contains("Result: UNKNOWN"), bytes.toString(Charsets.UTF_8))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("Result: SAFE"), bytes.toString(Charsets.UTF_8))
    }

    @Test fun `verify report and verify-release expose and validate the same policy option`() {
        val arguments = mapOf(
            "verify" to listOf("--contract", "old.json", "--baseline", "baseline", "--migrations", "pending", "--report", "out.json"),
            "verify-release" to listOf("--contract", "old.json", "--base-ref", "v1", "--migration-path", "db/migration", "--report", "out.json"),
            "report" to listOf("--input", "out.json"),
        )
        for ((name, required) in arguments) {
            val command = CommandLine(Main()).subcommands.getValue(name)
            assertTrue(Regex("--fail-on(?!-)").containsMatchIn(command.usageMessage), "$name: ${command.usageMessage}")
            for (policy in listOf("unknown", "breaking")) {
                val parsed = CommandLine(Main()).parseArgs(*(listOf(name) + required + listOf("--fail-on", policy)).toTypedArray())
                assertTrue(parsed.subcommand().hasMatchedOption("--fail-on"), name)
            }
            assertThrows(CommandLine.ParameterException::class.java) {
                CommandLine(Main()).parseArgs(*(listOf(name) + required + listOf("--fail-on", "safe")).toTypedArray())
            }
        }
    }

    @Test fun `incomplete outer and release envelopes take precedence over both policies`() {
        val single = report(Verdict.SAFE)
        val combined = BidirectionalReport(oldOnNew = single, newOnOld = single,
            dataCompatibility = single, verdict = Verdict.SAFE)
        val wrappers = listOf(
            mapOf("schemaVersion" to 1, "complete" to false, "verification" to single),
            mapOf("schemaVersion" to 1, "complete" to false, "oldOnNew" to single,
                "newOnOld" to single, "verdict" to Verdict.SAFE),
            mapOf("schemaVersion" to 1, "complete" to false,
                "release" to ReleaseProvenance("old", "new", "db/migration"), "verification" to combined),
        )
        for (wrapper in wrappers) assertEquals(3, replay(wrapper, "--fail-on", "breaking", "--fail-on-operational"))
    }

    @Test fun `baseline acceptance export retains its successful-action exit semantics`() {
        val finding = Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", "old.read", "Preexisting drift", "Baseline evidence")
        val value = report().copy(baseline = BaselineReport(Verdict.UNKNOWN, listOf(finding), "baseline-fixture"),
            operationalVerdict = OperationalVerdict.RISKY)
        for (policy in listOf("unknown", "breaking")) {
            val acceptance = directory.resolve("acceptance-$policy.json")
            val arguments = arrayOf("--fail-on", policy, "--write-accepted-baseline", acceptance.toString(),
                "--justification", "Reviewed preexisting drift")
            assertEquals(0, replay(value, *arguments))
            val saved = json.readTree(acceptance.toFile())
            assertEquals("baseline-fixture", saved["baselineFingerprint"].asText())
            assertEquals("Reviewed preexisting drift", saved["findings"][0]["justification"].asText())
            assertEquals(4, replay(value, *arguments, "--fail-on-operational"))
        }
    }

    @Test fun `default failures retain causes and only verbose prints stack frames`() {
        fun capture(verbose: Boolean): String {
            val bytes = ByteArrayOutputStream()
            val original = System.err
            try {
                System.setErr(PrintStream(bytes))
                val cause = IllegalStateException("underlying diagnostic").apply {
                    stackTrace = arrayOf(StackTraceElement("FixtureFailure", "invoke", "FixtureFailure.kt", 42))
                }
                val code = runCli(if (verbose) arrayOf("--verbose") else emptyArray()) {
                    throw NoClassDefFoundError("bootstrap diagnostic").apply { initCause(cause) }
                }
                assertEquals(3, code)
            } finally { System.setErr(original) }
            return bytes.toString(Charsets.UTF_8)
        }
        val quiet = capture(false)
        assertTrue(quiet.contains("bootstrap diagnostic"), quiet)
        assertTrue(quiet.contains("underlying diagnostic"), quiet)
        assertFalse(quiet.contains("FixtureFailure.invoke"), quiet)
        assertTrue(capture(true).contains("FixtureFailure.invoke(FixtureFailure.kt:42)"))
    }

    @Test fun `one migration shows breaking findings before unknown and safe details under the output cap`() {
        val migration = MigrationInfo("2", "V2__mixed.sql")
        val noisy = (1..25).map { Finding(Verdict.UNKNOWN, "NOISE_$it", "object$it", "Unknown $it", "Proof gap", migration = migration) }
        val breaking = Finding(Verdict.BREAKING, "BROKEN", "accounts.id", "Known break", "Concrete evidence", migration = migration)
        val safe = Finding(Verdict.SAFE, "SUPPORTED", "other", "Known safe", "Proof", migration = migration)
        val value = report(Verdict.BREAKING).copy(migrations = listOf(MigrationReport(migration, Verdict.BREAKING, listOf(safe) + noisy + breaking)))
        val details = renderReport(value, allDetails = true)
        assertTrue(details.indexOf("[BROKEN]") < details.indexOf("[NOISE_1]"), details)
        assertTrue(details.indexOf("[NOISE_25]") < details.indexOf("[SUPPORTED]"), details)
        val compact = renderReport(value)
        assertTrue(compact.contains("[BROKEN]"), compact)
        assertFalse(compact.contains("[SUPPORTED]"), compact)
    }
}
