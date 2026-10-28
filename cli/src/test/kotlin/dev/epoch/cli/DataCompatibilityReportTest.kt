package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DataCompatibilityReportTest {
    @TempDir lateinit var directory: Path

    private fun schemaReport() = CompatibilityReport(applicationVersion = "fixture",
        scope = VerificationScope.STRUCTURAL_CONTRACT, verdict = Verdict.SAFE, migrations = emptyList())

    private fun dataReport(complete: Boolean = true): CompatibilityReport {
        val assessment = MigrationInfo("contract", "<old-vs-new-contract>")
        val finding = Finding(Verdict.BREAKING, "DATA_ENUM_VALUE_UNREADABLE", "public.accounts.status",
            "The new writer can store an enum value outside the old decoder.",
            "New stored value: ARCHIVED; old decoder: ACTIVE, INACTIVE.",
            affectedContractIds = listOf("old-reader", "new-writer"), migration = assessment)
        return schemaReport().copy(verdict = Verdict.BREAKING,
            migrations = listOf(MigrationReport(assessment, Verdict.BREAKING, listOf(finding))),
            complete = complete, incompleteReason = if (complete) null else "Data assessment interrupted")
    }

    private fun combined(complete: Boolean = true) = mapOf(
        "schemaVersion" to 1,
        "oldOnNew" to schemaReport(), "newOnOld" to schemaReport(),
        "dataCompatibility" to dataReport(complete), "verdict" to Verdict.BREAKING,
    )

    @Test fun `saved reports and release reports gate on new writes unreadable by old readers`() {
        for (release in listOf(false, true)) {
            val input = directory.resolve("report-$release.json")
            val output = directory.resolve("report-$release.sarif")
            writeJson(input, if (release) mapOf("schemaVersion" to 1,
                "release" to ReleaseProvenance("old", "new", "db/migration"), "verification" to combined()) else combined())
            assertEquals(1, runCli(arrayOf("report", "--input", input.toString(), "--summary", "--sarif", output.toString())))
            val results = json.readTree(output.toFile())["runs"][0]["results"]
            assertEquals(1, results.size())
            assertTrue(results[0]["message"]["text"].asText().contains("new-writes-old-reads"))
            assertFalse(results[0].has("locations"), "A contract assessment must not invent a migration location")
            assertFalse(results[0]["properties"]["baseline"].asBoolean())
            assertEquals("contract", results[0]["properties"]["locationPrecision"].asText())
        }
    }

    @Test fun `an incomplete data direction keeps a combined report incomplete`() {
        val input = directory.resolve("incomplete.json")
        writeJson(input, combined(complete = false))
        assertEquals(3, runCli(arrayOf("report", "--input", input.toString(), "--summary")))
    }

    @Test fun `an accepted schema baseline gate cannot hide data regressions`() {
        val input = directory.resolve("accepted-baseline.json")
        writeJson(input, combined() + mapOf("oldOnNew" to schemaReport().copy(
            verdict = Verdict.UNKNOWN, gateVerdict = Verdict.SAFE), "gateVerdict" to Verdict.SAFE))
        assertEquals(1, runCli(arrayOf("report", "--input", input.toString(), "--summary")))
    }

    @Test fun `legacy schema-only reports retain their original narrower scope`() {
        val input = directory.resolve("legacy.json")
        writeJson(input, combined().filterKeys { it != "dataCompatibility" } + ("verdict" to Verdict.SAFE))
        assertEquals(0, runCli(arrayOf("report", "--input", input.toString(), "--summary")))
    }

    @Test fun `a checkpoint with both schema results still retains the pending data direction`() {
        val input = directory.resolve("partial.json")
        VerificationCheckpoint(input, verificationDirections(withNewContract = true)).use { checkpoint ->
            checkpoint.progress("old-on-new", schemaReport())
            checkpoint.progress("new-on-old", schemaReport())
        }
        val partial = json.readTree(input.toFile())
        assertTrue(partial["expectedDirections"].any { it.asText() == "new-writes-old-reads" })
        assertFalse(partial["reports"].has("new-writes-old-reads"))
        assertEquals(3, runCli(arrayOf("report", "--input", input.toString(), "--summary")))
    }
}
