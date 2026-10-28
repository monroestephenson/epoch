package dev.epoch.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Existing APIs deliberately establish the missing report metadata before its DTOs are added. */
class FindingGroupingTest {
    private val json = jacksonObjectMapper()
    private val engine = CompatibilityEngine()
    private val table = "public.accounts"
    private val f = "function:public.guard(integer)"
    private val g = "function:public.guard(text)"
    private val read = QueryContract("read", Operation.SELECT, "select id from public.accounts",
        tables = listOf(table), columns = listOf(ColumnReference(table, "id")), dependenciesComplete = true)
    private val contract = DatabaseContract(queries = listOf(read), coverage = Coverage(complete = true))
    private fun function(children: List<String> = emptyList(), definition: String = "opaque body") =
        BehaviorNode("function", definition, childNodeIds = children, dependenciesComplete = true,
            semanticsSupported = false, planningMayEvaluate = false, opaqueReasons = listOf("Unmodeled function semantics"))
    private fun attachment(children: List<String>) = BehaviorNode("check", "guard(id)", childNodeIds = children,
        dependenciesComplete = true, semanticsSupported = false, planningMayEvaluate = false,
        opaqueReasons = listOf("Local expression not modeled"))
    private fun schema(nodes: Map<String, BehaviorNode>, complete: Boolean = true) = SchemaSnapshot(
        tables = mapOf(table to TableSchema(columns = mapOf("id" to ColumnSchema("integer", false)))),
        expressionSearchPath = listOf("pg_catalog", "public"),
        behaviorGraph = BehaviorGraph(attachmentInventoryComplete = complete, nodes = nodes, attachments = emptyList()))
    private fun issue(name: String, callers: List<String> = listOf("read"), rule: String = "EXISTING_DATABASE_BEHAVIOR") =
        Finding(Verdict.UNKNOWN, rule, name, "Expression requires more evidence", "Local evidence for $name", callers)
    private fun groups(value: Any): List<JsonNode> {
        val tree = json.valueToTree<JsonNode>(value)
        assertTrue(tree.path("findingGroups").isArray, "Missing finding group metadata: $tree")
        return tree["findingGroups"].toList()
    }
    private fun members(group: JsonNode, findings: List<Finding>) = group["findingIndices"].map { findings[it.asInt()] }

    @Test fun `two hundred attachment findings share one exact source without losing local evidence`() {
        val roots = (1..200).map { "check:public.accounts.guard_$it" }
        val snapshot = schema(mapOf(f to function()) + roots.associateWith { attachment(listOf(f)) })
        val expected = roots.map { issue(it) }
        val health = engine.inspectBaseline(contract, snapshot, expected)
        val group = groups(health).single()
        assertEquals("SHARED_CATALOG_DEPENDENCY", group["kind"].asText())
        assertEquals(listOf(f), group["sourceIds"].map { it.asText() })
        assertEquals(roots.toSet(), members(group, health.findings).map { it.objectName }.toSet())
        assertEquals(200, group["findingIndices"].size())
        assertEquals(expected.map { it.evidence }.toSet(), health.findings.filter { it.objectName in roots }.map { it.evidence }.toSet())
        assertEquals(Verdict.UNKNOWN, health.verdict)
    }

    @Test fun `overloaded functions with identical bodies and messages stay separate`() {
        val roots = listOf("check:a", "check:b", "check:c", "check:d")
        val snapshot = schema(mapOf(f to function(), g to function()) + roots.mapIndexed { index, name ->
            name to attachment(listOf(if (index < 2) f else g)) }.toMap())
        val health = engine.inspectBaseline(contract, snapshot, roots.map { issue(it) })
        val groups = groups(health)
        assertEquals(2, groups.size)
        assertEquals(setOf(listOf(f), listOf(g)), groups.map { it["sourceIds"].map { id -> id.asText() } }.toSet())
        assertTrue(groups.all { it["findingIndices"].size() == 2 })
        assertEquals(2, groups.map { it["id"].asText() }.distinct().size)
    }

    @Test fun `entire source set determines membership and cycles terminate without conflating roots`() {
        val h = "function:public.audit(integer)"
        val roots = mapOf("check:f1" to listOf(f), "check:f2" to listOf(f),
            "check:g1" to listOf(g), "check:g2" to listOf(g),
            "check:fg1" to listOf(f, g), "check:fg2" to listOf(f, g))
        val snapshot = schema(mapOf(f to function(listOf(h)), h to function(listOf(f)), g to function()) +
            roots.mapValues { attachment(it.value) })
        val health = engine.inspectBaseline(contract, snapshot, roots.keys.map { issue(it) })
        val groups = groups(health)
        assertEquals(3, groups.size)
        assertEquals(setOf(setOf(f, h), setOf(g), setOf(f, g, h)),
            groups.map { it["sourceIds"].map { id -> id.asText() }.toSet() }.toSet())
        assertTrue(groups.all { it["findingIndices"].size() == 2 })
    }

    @Test fun `missing dependency nodes incomplete inventory and local-only warnings establish no shared source`() {
        val roots = listOf("check:a", "check:b")
        for (snapshot in listOf(
            schema(roots.associateWith { attachment(listOf("function:public.absent()")) }),
            schema(mapOf(f to function()) + roots.associateWith { attachment(listOf(f)) }, complete = false),
            schema(roots.associateWith { attachment(emptyList()) }),
        )) {
            val health = engine.inspectBaseline(contract, snapshot, roots.map { issue(it) })
            assertTrue(groups(health).isEmpty(), json.writeValueAsString(health))
            assertTrue(health.findings.any { it.objectName == roots.first() })
            assertEquals(Verdict.UNKNOWN, health.verdict)
        }
    }

    @Test fun `group IDs are stable across caller evidence and ordering changes but track exact function definition`() {
        val roots = listOf("check:a", "check:b")
        fun health(body: String, findings: List<Finding>) = engine.inspectBaseline(contract,
            schema(mapOf(f to function(definition = body)) + roots.associateWith { attachment(listOf(f)) }), findings)
        val first = health("body one", roots.map { issue(it) })
        val reordered = health("body one", roots.reversed().map { issue(it, listOf("B", "A")).copy(evidence = "New wording") })
        assertEquals(groups(first).single()["id"], groups(reordered).single()["id"])
        assertNotEquals(groups(first).single()["id"], groups(health("body two", roots.map { issue(it) })).single()["id"])
        assertEquals(first.findings.map { it.objectName }.toSet(), reordered.findings.map { it.objectName }.toSet())
    }

    @Test fun `identical fixed SQL templates share metadata while parameter result variant and mapping differences do not`() {
        val sql = "select id from public.accounts where id = ?"
        val base = read.copy(sql = sql, parameterTypes = listOf("INTEGER"), resultColumns = listOf(ResultColumn("id", "integer")))
        val queries = listOf(base.copy(id = "A", accessPathIds = listOf("RepoA#read:1")),
            base.copy(id = "B", accessPathIds = listOf("RepoB#read:2")),
            base.copy(id = "parameter", parameterTypes = listOf("BIGINT")),
            base.copy(id = "result", resultColumns = listOf(ResultColumn("id", "bigint"))),
            base.copy(id = "variant", variant = "pagination"),
            base.copy(id = "dependency", dependenciesComplete = false))
        val findings = queries.map { issue("query:${it.id}", listOf(it.id)) }
        val health = engine.inspectBaseline(contract.copy(queries = queries), schema(emptyMap()), findings)
        val templateGroups = groups(health).filter { it["kind"].asText() == "IDENTICAL_SQL_TEMPLATE" }
        assertEquals(1, templateGroups.size)
        assertEquals(setOf("query:A", "query:B"), members(templateGroups.single(), health.findings).map { it.objectName }.toSet())
        assertTrue(health.findings.any { it.objectName == "query:dependency" })
    }

    @Test fun `baseline grouping does not alter finding fingerprints or acceptance keys`() {
        val roots = listOf("check:a", "check:b")
        val snapshot = schema(mapOf(f to function()) + roots.associateWith { attachment(listOf(f)) })
        val health = engine.inspectBaseline(contract, snapshot, roots.map { issue(it) })
        assertEquals(1, groups(health).size)
        assertEquals(BaselineFingerprints.baseline(contract, snapshot, health.findings, ""), health.fingerprint)
        val legacy = json.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(health).also { it.remove("findingGroups") }
        val oldHealth = json.treeToValue(legacy, BaselineReport::class.java)
        val report = engine.report(contract, emptyList(), health)
        val oldReport = engine.report(contract, emptyList(), oldHealth)
        assertEquals(BaselineAcceptance.fromReport(oldReport, "Reviewed these exact baseline findings"),
            BaselineAcceptance.fromReport(report, "Reviewed these exact baseline findings"))
        assertEquals(oldHealth.findings.map(BaselineFingerprints::finding), health.findings.map(BaselineFingerprints::finding))
    }

    @Test fun `baseline caller subtraction remaps groups to retained rows without waiving newly affected callers`() {
        val roots = listOf("check:a", "check:b")
        val snapshot = schema(mapOf(f to function()) + roots.associateWith { attachment(listOf(f)) })
        val health = engine.inspectBaseline(contract, snapshot, roots.map { issue(it, listOf("A")) })
        val migration = MigrationInfo("2", "V2__change.sql")
        val raw = engine.verifyStep(contract, snapshot, snapshot, migration,
            roots.map { issue(it, listOf("A", "B")) })
        val result = engine.report(contract, listOf(raw), health)
        val step = result.migrations.single()
        assertEquals(setOf(roots[0], roots[1]), step.findings.map { it.objectName }.toSet())
        assertTrue(step.findings.all { it.affectedContractIds == listOf("B") }, step.toString())
        val group = groups(step).single()
        assertEquals(step.findings.toSet(), members(group, step.findings).toSet())
        assertTrue(group["findingIndices"].all { it.asInt() in step.findings.indices })
        assertEquals(Verdict.UNKNOWN, BaselineAcceptance.apply(result,
            BaselineAcceptance.fromReport(result, "Accept preexisting caller A")).gateVerdict)
        assertTrue(requireNotNull(result.baseline).findings.all { it.affectedContractIds == listOf("A") })

        // An out-of-range member must invalidate its original group; filtering cannot quietly
        // repair that metadata into a plausible display group over the two retained rows.
        val corrupt = json.valueToTree<JsonNode>(raw)
        (corrupt["findingGroups"][0]["findingIndices"] as com.fasterxml.jackson.databind.node.ArrayNode).add(9999)
        val imported = json.treeToValue(corrupt, MigrationReport::class.java)
        val filtered = engine.report(contract, listOf(imported), health).migrations.single()
        assertTrue(groups(filtered).isEmpty())
        assertEquals(step.findings, filtered.findings)
    }

    @Test fun `JSON round trip keeps raw findings and report groups while legacy absence remains explicit`() {
        val roots = listOf("check:a", "check:b")
        val snapshot = schema(mapOf(f to function()) + roots.associateWith { attachment(listOf(f)) })
        val raw = engine.verifyStep(contract, snapshot, snapshot, MigrationInfo("2", "V2__change.sql"), roots.map { issue(it) })
        val report = engine.report(contract, listOf(raw))
        assertEquals(1, groups(report.migrations.single()).size)
        assertEquals(report, json.readValue<CompatibilityReport>(json.writeValueAsString(report)))
        val legacy = json.valueToTree<JsonNode>(report)
        legacy["migrations"].forEach { (it as com.fasterxml.jackson.databind.node.ObjectNode).remove("findingGroups") }
        val old = json.treeToValue(legacy, CompatibilityReport::class.java)
        assertEquals(report.migrations.single().findings, old.migrations.single().findings)
        assertTrue(json.valueToTree<JsonNode>(old)["migrations"][0].path("findingGroups").isNull)
        assertEquals(report.verdict, old.verdict)
    }
}
