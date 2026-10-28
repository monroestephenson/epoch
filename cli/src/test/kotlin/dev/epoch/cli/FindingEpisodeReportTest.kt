package dev.epoch.cli

import com.fasterxml.jackson.databind.JsonNode
import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FindingEpisodeReportTest {
    @TempDir lateinit var directory: Path
    private val contract = DatabaseContract(applicationVersion = "episode-report", coverage = Coverage(complete = true))
    private fun issue(rule: String, ids: List<String> = listOf("Repo.read")) =
        Finding(Verdict.BREAKING, rule, "public.accounts.email", "Issue $rule", "Exact SQLSTATE evidence", ids)
    private fun step(version: String, vararg findings: Finding): MigrationReport {
        val migration = MigrationInfo(version, "V${version}__step.sql")
        return MigrationReport(migration, CompatibilityEngine.aggregate(findings.map { it.verdict } + Verdict.SAFE),
            findings.map { it.copy(migration = migration) })
    }
    private fun report(vararg steps: MigrationReport) = CompatibilityEngine().report(contract, steps.toList())

    @Test fun `human details distinguish the new cause from persisting evidence inside the same step`() {
        val value = report(step("2", issue("OLD_BREAK")), step("3", issue("OLD_BREAK"), issue("NEW_BREAK")), step("4"))
        val text = renderReport(value, allDetails = true)
        val third = text.substringAfter("3 V3__step.sql:").substringBefore("4 V4__step.sql:")
        assertTrue(third.indexOf("[NEW_BREAK]") < third.indexOf("[OLD_BREAK]"), text)
        assertTrue(third.contains("NEW; first observed: 3 V3__step.sql"), text)
        assertTrue(third.contains("PERSISTING; first observed: 2 V2__step.sql"), text)
        assertTrue(text.contains("no longer reported 2"), text)
        assertTrue(text.contains("Result: BREAKING"), text)
        assertTrue(text.contains("migration observations"), text)
    }

    @Test fun `summary displays transitions with explicit denominator without duplicating evidence`() {
        val value = report(step("2", issue("GROUP", listOf("A", "B"))),
            step("3", issue("GROUP", listOf("A", "B", "C"))))
        val summary = renderReport(value, summaryOnly = true)
        assertTrue(summary.contains("NEW 1, PERSISTING 2, no longer reported 0"), summary)
        assertTrue(summary.contains("per affected contract ID or unscoped finding"), summary)
        assertFalse(summary.contains("Exact SQLSTATE evidence"), summary)
        assertFalse(summary.contains("findings omitted"), summary)
    }

    @Test fun `SARIF retains current migration locations and adds caller-scoped original observations`() {
        val value = report(step("2", issue("GROUP", listOf("A", "B"))),
            step("3", issue("GROUP", listOf("A", "B", "C"))), step("4"))
        val tree = json.valueToTree<JsonNode>(sarifReport(listOf("old-on-new" to value), "db/migration"))
        val results = tree["runs"][0]["results"].toList()
        assertEquals(2, results.size, "Ended episodes are metadata, not extra current failures")
        val third = results.single { it["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText().endsWith("V3__step.sql") }
        val changes = third["properties"].path("findingChanges")
        assertTrue(changes.isArray, third.toPrettyString())
        assertEquals(setOf("NEW", "PERSISTING"), changes.map { it["kind"].asText() }.toSet())
        val continuing = changes.single { it["kind"].asText() == "PERSISTING" }
        assertEquals("2", continuing["firstObserved"]["version"].asText())
        assertEquals(listOf("A", "B"), continuing["affectedContractIds"].map { it.asText() })
        assertFalse(third.has("baselineState"), "Deployment-state episodes are not SARIF run baselines")
    }

    @Test fun `annotation index does not give one finding the callers of an independent current finding`() {
        val value = report(step("2", issue("GROUP", listOf("A"))),
            step("3", issue("GROUP", listOf("A")), issue("GROUP", listOf("B"))))
        val tree = json.valueToTree<JsonNode>(sarifReport(listOf("old-on-new" to value)))
        val third = tree["runs"][0]["results"].filter {
            it["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText() == "V3__step.sql"
        }
        assertEquals(2, third.size)
        for (finding in third) {
            val ids = finding["properties"]["contractIds"]
            assertEquals(ids, finding["properties"].path("findingChanges").single()["affectedContractIds"])
        }
    }

    @Test fun `checkpoint carries completed episode prefix and replay still exits 3 after interruption`() {
        val value = report(step("2", issue("OLD_BREAK")), step("3", issue("OLD_BREAK")))
        val path = directory.resolve("checkpoint.json")
        VerificationCheckpoint(path, listOf("old-on-new")).use { checkpoint ->
            checkpoint.progress("old-on-new", value)
            checkpoint.fail(InterruptedException("Stopped after checked V3"))
        }
        val partial = json.readTree(path.toFile())
        assertFalse(partial["complete"].asBoolean())
        val retained = partial["reports"]["old-on-new"]
        assertEquals("BREAKING", retained["verdict"].asText())
        val changes = retained["migrations"][1].path("findingChanges")
        assertTrue(changes.isArray, retained.toPrettyString())
        assertEquals("PERSISTING", changes.single()["kind"].asText())
        assertEquals("2", changes.single()["firstObserved"]["version"].asText())
        assertEquals(3, runCli(arrayOf("report", "--input", path.toString(), "--summary", "--fail-on", "breaking")))
    }

    @Test fun `legacy replay does not invent missing origin metadata or rewrite evidence`() {
        val legacy = CompatibilityReport(applicationVersion = "legacy", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.BREAKING, migrations = listOf(step("2", issue("OLD_BREAK"))))
        val path = directory.resolve("legacy.json")
        writeJson(path, legacy)
        val before = Files.readString(path)
        assertEquals(1, runCli(arrayOf("report", "--input", path.toString(), "--summary")))
        assertEquals(before, Files.readString(path))
        assertFalse(renderReport(legacy).contains("first observed:"))
        assertFalse(renderReport(legacy).contains("Finding transitions:"))
    }
}
