package dev.epoch.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.epoch.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** JSON lookup keeps the tests compilable before the optional grouping DTOs are introduced. */
class FindingGroupingReportTest {
    @TempDir lateinit var directory: Path
    private val f = "function:public.guard(integer)"
    private val contract = DatabaseContract(queries = listOf(QueryContract("read", Operation.SELECT,
        "select id from public.accounts", tables = listOf("public.accounts"),
        columns = listOf(ColumnReference("public.accounts", "id")), dependenciesComplete = true)),
        coverage = Coverage(complete = true))
    private fun report(count: Int = 42, versions: List<String> = listOf("2")): CompatibilityReport {
        val roots = (1..count).map { "check:public.accounts.guard_${it.toString().padStart(3, '0')}" }
        val graph = BehaviorGraph(attachmentInventoryComplete = true, attachments = emptyList(),
            nodes = mapOf(f to BehaviorNode("function", "opaque body", dependenciesComplete = true,
                semanticsSupported = false, planningMayEvaluate = false)) + roots.associateWith {
                BehaviorNode("check", "guard(id)", childNodeIds = listOf(f), dependenciesComplete = true,
                    semanticsSupported = false, planningMayEvaluate = false)
            })
        val snapshot = SchemaSnapshot(tables = mapOf("public.accounts" to TableSchema(
            mapOf("id" to ColumnSchema("integer", false)))), expressionSearchPath = listOf("pg_catalog", "public"),
            behaviorGraph = graph)
        val engine = CompatibilityEngine()
        val steps = versions.map { version -> engine.verifyStep(contract, snapshot, snapshot,
            MigrationInfo(version, "V${version}__change.sql"), roots.map { root ->
                Finding(Verdict.UNKNOWN, "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN", root,
                    "Member-local limitation", "Exact local evidence for $root", listOf("Repo.$root"))
            }) }
        return engine.report(contract, steps)
    }
    private fun assertGroups(value: CompatibilityReport) {
        assertTrue(json.valueToTree<JsonNode>(value)["migrations"][0].path("findingGroups").isArray,
            "Report lacks shared-source grouping metadata")
    }

    @Test fun `default display spends one budget item per shared source and keeps full evidence in JSON`() {
        val value = report()
        assertGroups(value)
        val text = renderReport(value)
        assertEquals(1, Regex("Shared catalog dependency:").findAll(text).count(), text)
        assertTrue(text.contains(f), text)
        assertTrue(text.contains("42 findings, 42 affected objects, 42 affected contract IDs"), text)
        assertTrue(Regex("Exact local evidence for").findAll(text).count() in 1..3, text)
        assertFalse(text.contains("findings omitted from terminal output"), text)
        assertTrue(text.contains("Result: UNKNOWN"), text)
        val encoded = json.valueToTree<JsonNode>(value)
        assertEquals(42, encoded["migrations"][0]["findings"].size())
        assertTrue(encoded["migrations"][0]["findings"].all { it["evidence"].asText().startsWith("Exact local evidence for") })
    }

    @Test fun `details displays every member and preserves exact local object caller and evidence`() {
        val value = report()
        assertGroups(value)
        val text = renderReport(value, allDetails = true)
        assertEquals(42, Regex("Exact local evidence for").findAll(text).count(), text)
        for (finding in value.migrations.single().findings) {
            assertTrue(text.contains(finding.objectName), finding.objectName)
            assertTrue(text.contains(finding.evidence), finding.evidence)
            assertTrue(text.contains(finding.affectedContractIds.single()), finding.affectedContractIds.single())
        }
        assertFalse(text.contains("findings omitted from terminal output"), text)
    }

    @Test fun `invalid or conflicting group metadata cannot suppress raw BREAKING or UNKNOWN rows`() {
        val original = report(3)
        assertGroups(original)
        val tree = json.valueToTree<ObjectNode>(original)
        val step = tree["migrations"][0] as ObjectNode
        val valid = step["findingGroups"][0] as ObjectNode
        val corruptions: List<(ObjectNode) -> Unit> = listOf(
            { it.set<ArrayNode>("findingIndices", json.createArrayNode().add(0).add(9999)) },
            { it.put("verdict", "BREAKING") },
            { it.put("ruleId", "SQL_REJECTED") },
            { it.set<ArrayNode>("findingIndices", json.createArrayNode().add(0).add(0)) },
        )
        for (corrupt in corruptions) {
            val copy = tree.deepCopy()
            val altered = valid.deepCopy().also(corrupt)
            (copy["migrations"][0] as ObjectNode).set<ArrayNode>("findingGroups", json.createArrayNode().add(altered))
            val parsed = json.treeToValue(copy, CompatibilityReport::class.java)
            val text = renderReport(parsed)
            assertFalse(text.contains("Shared catalog dependency:"), text)
            assertEquals(3, Regex("Exact local evidence for").findAll(text).count(), text)
            assertEquals(original.verdict, parsed.verdict)
        }
        val conflict = tree.deepCopy()
        (conflict["migrations"][0] as ObjectNode).set<ArrayNode>("findingGroups", json.createArrayNode()
            .add(valid.deepCopy()).add(valid.deepCopy().put("id", "different-id-same-members")))
        assertEquals(3, Regex("Exact local evidence for").findAll(renderReport(json.treeToValue(conflict,
            CompatibilityReport::class.java))).count())
        val breaking = tree.deepCopy()
        (((breaking["migrations"][0] as ObjectNode)["findings"][0]) as ObjectNode).put("verdict", "BREAKING")
        val breakingText = renderReport(json.treeToValue(breaking, CompatibilityReport::class.java))
        assertTrue(breakingText.contains("BREAKING [DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN]"), breakingText)
        assertEquals(3, Regex("Exact local evidence for").findAll(breakingText).count(), breakingText)
    }

    @Test fun `SARIF keeps individual current locations and adds exact optional group links`() {
        val value = report(4, listOf("2", "3"))
        assertGroups(value)
        val tree = json.valueToTree<JsonNode>(sarifReport(listOf("old-on-new" to value), "db/migration"))
        val results = tree["runs"][0]["results"].toList()
        assertEquals(8, results.size, "Grouping must not drop distinct SARIF locations or member evidence")
        for (version in listOf("2", "3")) {
            val step = json.valueToTree<JsonNode>(value)["migrations"].single { it["migration"]["version"].asText() == version }
            val groupId = step["findingGroups"][0]["id"].asText()
            val current = results.filter { it["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText() ==
                "db/migration/V${version}__change.sql" }
            assertEquals(4, current.size)
            assertTrue(current.all { it["properties"].path("findingGroupId").asText() == groupId })
            assertEquals(4, current.map { it["properties"]["object"].asText() }.distinct().size)
            assertTrue(current.all { it["message"]["text"].asText().contains("Exact local evidence for") })
        }
    }

    @Test fun `grouped display retains new versus persisting origins and interrupted replay exits 3`() {
        val value = report(3, listOf("2", "3"))
        assertGroups(value)
        val text = renderReport(value)
        assertTrue(text.contains("NEW 3, PERSISTING 0"), text)
        assertTrue(text.contains("NEW 0, PERSISTING 3"), text)
        assertTrue(text.contains("PERSISTING; first observed: 2 V2__change.sql"), text)
        val file = directory.resolve("checkpoint.json")
        writeJson(file, value.copy(complete = false, incompleteReason = "Interrupted after checked step"))
        assertEquals(3, runCli(arrayOf("report", "--input", file.toString(), "--fail-on", "breaking")))
    }

    @Test fun `legacy report without group metadata renders exact raw evidence`() {
        val value = report(3)
        assertGroups(value)
        val tree = json.valueToTree<JsonNode>(value)
        tree["migrations"].forEach { (it as ObjectNode).remove("findingGroups") }
        val legacy = json.treeToValue(tree, CompatibilityReport::class.java)
        val text = renderReport(legacy)
        assertFalse(text.contains("Shared catalog dependency:"), text)
        assertEquals(3, Regex("Exact local evidence for").findAll(text).count(), text)
        assertEquals(value.migrations.single().findings, legacy.migrations.single().findings)
        assertEquals(value.verdict, legacy.verdict)
    }
}
