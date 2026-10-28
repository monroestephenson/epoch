package dev.epoch.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Uses only preexisting public APIs so these tests can establish the reporting gap first. */
class FindingEpisodesTest {
    private val json = jacksonObjectMapper()
    private val engine = CompatibilityEngine()
    private val contract = DatabaseContract(applicationVersion = "episodes", coverage = Coverage(complete = true))
    private fun issue(ids: List<String> = listOf("Repo.read"), verdict: Verdict = Verdict.BREAKING,
                      rule: String = "REFERENCED_COLUMN_DROPPED", evidence: String = "column absent") =
        Finding(verdict, rule, "public.accounts.email", "Compatibility issue", evidence, ids)
    private fun step(version: String, vararg issues: Finding, unchanged: Boolean = false): MigrationReport {
        val migration = MigrationInfo(version, "V${version}__step.sql")
        return MigrationReport(migration, CompatibilityEngine.aggregate(issues.map { it.verdict } + Verdict.SAFE),
            issues.map { it.copy(migration = migration) }, catalogChanged = !unchanged)
    }
    private fun report(vararg steps: MigrationReport, baseline: BaselineReport? = null) = engine.report(contract, steps.toList(), baseline)
    private fun changes(report: CompatibilityReport, index: Int): List<JsonNode> {
        val step = json.valueToTree<JsonNode>(report)["migrations"][index]
        assertTrue(step.path("findingChanges").isArray, "Missing finding transition metadata: $step")
        return step["findingChanges"].toList()
    }
    private fun assertChange(change: JsonNode, kind: String, origin: String, ids: List<String>) {
        assertEquals(kind, change["kind"].asText(), change.toPrettyString())
        assertEquals(origin, change["firstObserved"]["version"].asText(), change.toPrettyString())
        assertEquals(ids, change["affectedContractIds"].map { it.asText() }, change.toPrettyString())
    }

    @Test fun `continuing break is attributed to its first checked state without relabeling current evidence`() {
        val original = listOf(step("2", issue()), step("3", issue(evidence = "new PREPARE SQLSTATE evidence"), unchanged = true))
        val result = report(*original.toTypedArray())
        assertChange(changes(result, 0).single(), "NEW", "2", listOf("Repo.read"))
        assertChange(changes(result, 1).single(), "PERSISTING", "2", listOf("Repo.read"))
        assertEquals(original.map { it.findings }, result.migrations.map { it.findings })
        assertEquals("3", result.migrations[1].findings.single().migration?.version)
        assertFalse(requireNotNull(result.migrations[1].catalogChanged))
        assertEquals(listOf(Verdict.BREAKING, Verdict.BREAKING), result.migrations.map { it.verdict })
    }

    @Test fun `restoration ends an episode but recurrence starts a new origin and never waives the window break`() {
        val result = report(step("2", issue()), step("3", issue()), step("4"), step("5", issue()))
        assertChange(changes(result, 2).single(), "RESOLVED", "2", listOf("Repo.read"))
        assertChange(changes(result, 3).single(), "NEW", "5", listOf("Repo.read"))
        assertEquals(Verdict.SAFE, result.migrations[2].verdict)
        assertEquals(Verdict.BREAKING, result.verdict)
    }

    @Test fun `group growth and shrink track each caller while retaining original grouped evidence`() {
        val result = report(step("2", issue(listOf("B", "A"))), step("3", issue(listOf("C", "A", "B", "A"))),
            step("4", issue(listOf("B", "C"))))
        assertChange(changes(result, 0).single(), "NEW", "2", listOf("A", "B"))
        val second = changes(result, 1)
        assertChange(second.single { it["kind"].asText() == "NEW" }, "NEW", "3", listOf("C"))
        assertChange(second.single { it["kind"].asText() == "PERSISTING" }, "PERSISTING", "2", listOf("A", "B"))
        val third = changes(result, 2)
        assertChange(third.single { it["kind"].asText() == "RESOLVED" }, "RESOLVED", "2", listOf("A"))
        assertEquals(setOf("2", "3"), third.filter { it["kind"].asText() == "PERSISTING" }
            .map { it["firstObserved"]["version"].asText() }.toSet())
        assertEquals(listOf("C", "A", "B", "A"), result.migrations[1].findings.single().affectedContractIds)
    }

    @Test fun `wording evidence and duplicate rows do not manufacture new origins`() {
        val first = issue(listOf("B", "A"))
        val changed = first.copy(message = "Different wording", evidence = "Different SQLSTATE", mitigation = "New mitigation",
            affectedContractIds = listOf("A", "B"))
        val result = report(step("2", first), step("3", changed, changed.copy(evidence = "Independent evidence")))
        assertChange(changes(result, 1).single(), "PERSISTING", "2", listOf("A", "B"))
        assertEquals(2, result.migrations[1].findings.size, "Independent current evidence must not be erased")
    }

    @Test fun `severity transitions start new episodes without implying that old failure is safe`() {
        val result = report(step("2", issue(verdict = Verdict.UNKNOWN)), step("3", issue()),
            step("4", issue(verdict = Verdict.UNKNOWN)))
        val second = changes(result, 1)
        assertEquals(setOf("NEW", "RESOLVED"), second.map { it["kind"].asText() }.toSet())
        assertEquals("BREAKING", second.single { it["kind"].asText() == "NEW" }["verdict"].asText())
        assertChange(changes(result, 2).single { it["kind"].asText() == "NEW" }, "NEW", "4", listOf("Repo.read"))
        assertEquals(Verdict.UNKNOWN, result.migrations[2].verdict)
        assertEquals(Verdict.BREAKING, result.verdict)
    }

    @Test fun `baseline caller debt stays separate when a grouped cause grows`() {
        val debt = issue(listOf("A", "B"), Verdict.UNKNOWN, "UNRESOLVED_QUERY")
        val baseline = BaselineReport(Verdict.UNKNOWN, listOf(debt), "baseline-identity")
        val result = report(step("2", debt.copy(affectedContractIds = listOf("B"))),
            step("3", debt.copy(affectedContractIds = listOf("A", "B", "C"))), baseline = baseline)
        assertTrue(result.migrations[0].findings.isEmpty(), result.toString())
        assertTrue(changes(result, 0).isEmpty())
        assertEquals(listOf("C"), result.migrations[1].findings.single().affectedContractIds)
        assertChange(changes(result, 1).single(), "NEW", "3", listOf("C"))
        assertEquals(listOf(debt), result.baseline?.findings)
        val accepted = BaselineAcceptance.fromReport(result, "Reviewed baseline A and B only")
        assertEquals(Verdict.UNKNOWN, BaselineAcceptance.apply(result, accepted).gateVerdict)
    }

    @Test fun `invalid baseline object filtering and finding fingerprints remain unchanged`() {
        val debt = issue(listOf("A"), Verdict.UNKNOWN, "INVALID_BASELINE_COLUMN")
        val fingerprint = BaselineFingerprints.finding(debt)
        val baseline = BaselineReport(Verdict.UNKNOWN, listOf(debt), "baseline-identity")
        val result = report(step("2", debt.copy(affectedContractIds = listOf("A", "B"))), baseline = baseline)
        assertTrue(result.migrations.single().findings.isEmpty())
        assertTrue(changes(result, 0).isEmpty())
        assertEquals(fingerprint, BaselineFingerprints.finding(requireNotNull(result.baseline).findings.single()))
    }

    @Test fun `anonymous and named findings remain separate and SAFE explanations are not episodes`() {
        val result = report(step("2", issue(emptyList()), issue(listOf("A")), issue(verdict = Verdict.SAFE)),
            step("3", issue(listOf("A"))))
        assertEquals(2, changes(result, 0).size)
        assertChange(changes(result, 1).single { it["kind"].asText() == "RESOLVED" }, "RESOLVED", "2", emptyList())
        assertEquals(3, result.migrations[0].findings.size)
    }

    @Test fun `checkpoints are stable prefixes and supplied execution order is authoritative`() {
        val steps = listOf(step("10", issue()), step("2", issue()), step("30"), step("4", issue()))
        val full = report(*steps.toTypedArray())
        for (length in 1..steps.size) {
            val prefix = report(*steps.take(length).toTypedArray())
            assertEquals(full.migrations.take(length), prefix.migrations)
        }
        assertChange(changes(full, 1).single(), "PERSISTING", "10", listOf("Repo.read"))
        val partial = report(*steps.take(2).toTypedArray()).copy(complete = false, incompleteReason = "Infrastructure failure")
        assertTrue(changes(partial, 1).none { it["kind"].asText() == "RESOLVED" })
        assertFalse(partial.complete)
    }

    @Test fun `JSON round trips retain transitions and legacy reports keep absence explicit`() {
        val result = report(step("2", issue()), step("3"))
        val encoded = json.writeValueAsString(result)
        assertEquals(result, json.readValue<CompatibilityReport>(encoded))
        val legacy = json.readTree(encoded)
        legacy["migrations"].forEach { (it as com.fasterxml.jackson.databind.node.ObjectNode).remove("findingChanges") }
        val decoded = json.treeToValue(legacy, CompatibilityReport::class.java)
        assertTrue(json.valueToTree<JsonNode>(decoded)["migrations"].all { it.path("findingChanges").isNull })
        assertEquals(result.verdict, decoded.verdict)
    }

    @Test fun `accepted baseline cannot waive a break that is no longer reported in the final schema`() {
        val debt = issue(verdict = Verdict.UNKNOWN, rule = "UNRESOLVED_QUERY")
        val baseline = BaselineReport(Verdict.UNKNOWN, listOf(debt), "baseline-identity")
        val result = report(step("2", issue(rule = "SQL_REJECTED")), step("3"), baseline = baseline)
        val accepted = BaselineAcceptance.fromReport(result, "Existing query discovery debt")
        assertEquals(Verdict.BREAKING, BaselineAcceptance.apply(result, accepted).gateVerdict)
        assertChange(changes(result, 1).single(), "RESOLVED", "2", listOf("Repo.read"))
    }

    @Test fun `independent compatibility directions cannot inherit another direction's origin`() {
        val forward = report(step("2", issue()), step("3", issue()))
        val reverse = report(step("baseline", issue()))
        assertChange(changes(forward, 1).single(), "PERSISTING", "2", listOf("Repo.read"))
        assertChange(changes(reverse, 0).single(), "NEW", "baseline", listOf("Repo.read"))
    }
}
