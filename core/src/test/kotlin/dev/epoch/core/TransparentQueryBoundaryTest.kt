package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransparentQueryBoundaryTest {
    private val old = "public.accounts"
    private val physical = "public.accounts_v2"
    private val security = RelationSecurity("epoch", false, false, false, false)
    private val table = TableSchema(mapOf("id" to ColumnSchema("bigint", false, ordinal = 1),
        "label" to ColumnSchema("text", true, ordinal = 2)), relationSecurity = security, relationOid = 41)
    private val graph = BehaviorGraph(attachmentInventoryComplete = true, nodes = emptyMap(), attachments = emptyList())
    private val baseline = SchemaSnapshot(mapOf(old to table), expressionSearchPath = listOf("pg_catalog", "public"), behaviorGraph = graph)
    private val viewFacts = ViewSchema("SELECT id, label FROM public.accounts_v2", 28, 28,
        setOf("id", "label"), setOf("id", "label"), physical, true)
    private val view = table.copy(kind = "view", view = viewFacts, relationOid = 42,
        columns = table.columns.mapValues { it.value.copy(nullable = true) })
    private val target = baseline.copy(tables = mapOf(old to view, physical to table),
        behaviorGraph = graph.copy(nodes = mapOf("view:$old" to BehaviorNode("view", viewFacts.definition,
            references = listOf(BehaviorReference(BehaviorReferenceKind.RELATION, physical, access = BehaviorAccess.SELECT)),
            dependenciesComplete = true, semanticsSupported = true, planningMayEvaluate = false)),
            attachments = listOf(BehaviorAttachment(old, "view:$old", Operation.entries.toSet()))))
    private fun query(sql: String, columns: List<String> = listOf("id")) = QueryContract("read", Operation.SELECT, sql,
        listOf(old), columns.map { ColumnReference(old, it) }, dependenciesComplete = true)
    private fun result(query: QueryContract, after: SchemaSnapshot = target): CompatibilityReport {
        val contract = DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true))
        val engine = CompatibilityEngine()
        val health = engine.inspectBaseline(contract, baseline)
        val step = engine.verifyStep(contract, baseline, after, MigrationInfo("2", "V2__view.sql"), health.findings)
        return engine.report(contract, listOf(step), health)
    }

    @Test fun `closed readonly syntax debt is unchanged through independently proven physical projection`() {
        for (sql in listOf("WITH selected AS (SELECT id FROM public.accounts) SELECT id FROM selected",
            "SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT b.id FROM public.accounts b WHERE b.id=a.id)",
            "SELECT selected.id FROM (SELECT id FROM public.accounts) selected")) {
            val report = result(query(sql))
            assertEquals(Verdict.UNKNOWN, report.baseline!!.verdict, "The original semantic coverage debt remains")
            assertEquals(Verdict.SAFE, report.migrations.single().verdict, report.toString())
            assertTrue(report.migrations.single().findings.none { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }, sql)
        }
    }

    @Test fun `row identity system columns and hidden name observers cannot use transparent dependency quotient`() {
        for (query in listOf(
            query("WITH selected AS (SELECT a FROM public.accounts a) SELECT a FROM selected", emptyList()),
            query("WITH selected AS (SELECT a, a.id FROM public.accounts a) SELECT id FROM selected"),
            query("WITH selected AS (SELECT tableoid FROM public.accounts) SELECT tableoid FROM selected", listOf("tableoid")),
            query("WITH selected AS (SELECT pg_typeof(a) FROM public.accounts a) SELECT 1 FROM selected"),
            query("WITH selected AS (SELECT pg_get_serial_sequence('accounts','id') FROM public.accounts) SELECT 1 FROM selected"),
        )) assertTrue(result(query).migrations.single().findings.any {
            it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN"
        }, query.sql)
    }

    @Test fun `changed physical columns or unproven replacement cannot waive the cause`() {
        val query = query("WITH selected AS (SELECT id FROM public.accounts) SELECT id FROM selected")
        val changedTable = table.copy(columns = table.columns + ("id" to table.columns.getValue("id").copy(type = "numeric")))
        val changedView = view.copy(columns = view.columns + ("id" to view.columns.getValue("id").copy(type = "numeric")))
        val changed = target.copy(tables = mapOf(old to changedView, physical to changedTable))
        for (after in listOf(changed, target.copy(tables = target.tables + (physical to table.copy(relationOid = 99))))) {
            assertTrue(result(query, after).migrations.single().findings.any { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" })
        }
        assertFalse(CompatibilityViews.provenViewNames(baseline, target.copy(expressionSearchPath = listOf("public", "pg_catalog"))).isNotEmpty())
    }
}
