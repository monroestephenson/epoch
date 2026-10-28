package dev.epoch.cli

import dev.epoch.core.*
import dev.epoch.postgres.VerificationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class VerificationCheckpointTest {
    @TempDir lateinit var directory: Path

    private fun report(verdict: Verdict = Verdict.SAFE) = CompatibilityReport(
        applicationVersion = "checkpoint-test", scope = VerificationScope.STRUCTURAL_CONTRACT,
        verdict = verdict, migrations = listOf(MigrationReport(MigrationInfo("2", "V2__step.sql"), verdict,
            listOf(Finding(verdict, "CHECKPOINT_TEST", "public.accounts", "Verified checkpoint", "Evidence",
                migration = MigrationInfo("2", "V2__step.sql"))))),
    )

    @Test fun `initial checkpoint and retained steps stay incomplete and late callbacks cannot replace failure`() {
        val output = directory.resolve("report.json")
        VerificationCheckpoint(output, listOf("old-on-new", "new-on-old")).use { checkpoint ->
            val initial = json.readTree(output.toFile())
            assertFalse(initial["complete"].asBoolean())
            assertEquals(0, initial["reports"].size())
            assertEquals("IN_PROGRESS", initial["failureStage"].asText())
            checkpoint.progress("old-on-new", report())
            checkpoint.fail(VerificationException("GLOBAL_TIMEOUT", message = "Timeout"))
            val retained = Files.readString(output)
            checkpoint.progress("new-on-old", report(Verdict.BREAKING))
            assertEquals(retained, Files.readString(output))
            assertEquals("GLOBAL_TIMEOUT", json.readTree(retained)["failureStage"].asText())
            assertFalse(json.readTree(retained)["reports"]["old-on-new"]["complete"].asBoolean())
            assertThrows(IllegalStateException::class.java) { checkpoint.complete(report()) }
        }
        assertEquals(3, runCli(arrayOf("report", "--input", output.toString(), "--summary")))
    }

    @Test fun `successful final report atomically replaces partial envelope and ignores late progress`() {
        val output = directory.resolve("report.json")
        VerificationCheckpoint(output, listOf("old-on-new")).use { checkpoint ->
            checkpoint.progress("old-on-new", report())
            checkpoint.complete(report())
            checkpoint.progress("old-on-new", report(Verdict.BREAKING))
        }
        val final = json.readTree(output.toFile())
        assertTrue(final["complete"].asBoolean())
        assertFalse(final.has("reports"))
        assertEquals("SAFE", final["verdict"].asText())
        assertEquals(0, runCli(arrayOf("report", "--input", output.toString(), "--summary")))
        assertEquals(listOf(output), Files.list(directory).use { it.toList() })
    }

    @Test fun `partial release supports SARIF but cannot export accepted baseline`() {
        val output = directory.resolve("partial-release.json")
        val sarif = directory.resolve("partial.sarif")
        val acceptance = directory.resolve("acceptance.json")
        VerificationCheckpoint(output, listOf("old-on-new"), ReleaseProvenance("old", "new", "db/migration")).use { checkpoint ->
            checkpoint.progress("old-on-new", report(Verdict.BREAKING))
        }
        assertEquals(3, runCli(arrayOf("report", "--input", output.toString(), "--sarif", sarif.toString())))
        assertEquals("db/migration/V2__step.sql", json.readTree(sarif.toFile())["runs"][0]["results"][0]
            ["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText())
        assertEquals(3, runCli(arrayOf("report", "--input", output.toString(), "--write-accepted-baseline",
            acceptance.toString(), "--justification", "Reviewed")))
        assertFalse(Files.exists(acceptance))
    }

    @Test fun `incomplete single and bidirectional reports never return their provisional SAFE code`() {
        val single = report().copy(complete = false, incompleteReason = "Stopped")
        val output = directory.resolve("incomplete.json")
        writeJson(output, single)
        assertEquals(3, runCli(arrayOf("report", "--input", output.toString())))
        writeJson(output, BidirectionalReport(oldOnNew = report(), newOnOld = single, verdict = Verdict.SAFE))
        assertEquals(3, runCli(arrayOf("report", "--input", output.toString())))
    }

    @Test fun `checkpoints are deterministic for the same evidence and failure stage`() {
        val a = directory.resolve("a.json")
        val b = directory.resolve("b.json")
        for (output in listOf(a, b)) VerificationCheckpoint(output, listOf("old-on-new")).use { checkpoint ->
            checkpoint.progress("old-on-new", report())
            checkpoint.fail(VerificationException("MIGRATION_TIMEOUT", message = "Ignored runtime wording"))
        }
        assertEquals(Files.readString(a), Files.readString(b))
    }

    @Test fun `incomplete checkpoints retain measured risk but never certify remaining operational work`() {
        for (verdict in OperationalVerdict.entries) {
            val output = directory.resolve("operational-$verdict.json")
            VerificationCheckpoint(output, listOf("old-on-new")).use { checkpoint ->
                checkpoint.progress("old-on-new", report().copy(operationalVerdict = verdict))
                checkpoint.fail(VerificationException("MIGRATION_TIMEOUT", message = "Timeout"))
            }
            val retained = json.readTree(output.toFile())["reports"]["old-on-new"]
            assertEquals(if (verdict == OperationalVerdict.RISKY) "RISKY" else "UNKNOWN",
                retained["operationalVerdict"].asText())
            assertEquals(3, runCli(arrayOf("report", "--input", output.toString(), "--fail-on-operational", "--summary")))
        }
    }
}
