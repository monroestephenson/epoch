package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Existing-API tests can run before applying the production draft. */
class BehaviorBoundaryRegressionTest {
    private val accounts = "public.accounts"
    private val badges = "public.badges"
    private val noise = "public.noise"
    private val table = TableSchema(mapOf("id" to ColumnSchema("bigint", false, ordinal = 1),
        "label" to ColumnSchema("text", true, ordinal = 2)),
        relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val baseline = SchemaSnapshot(mapOf(accounts to table, badges to table, noise to table),
        expressionSearchPath = listOf("pg_catalog", "public"),
        behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true, nodes = emptyMap(), attachments = emptyList()))
    private val migration = MigrationInfo("2", "V2__noise.sql")
    private fun query(sql: String, complete: Boolean = true, tables: List<String> = listOf(accounts, badges)) = QueryContract(
        "repository.read", Operation.SELECT, sql, tables, tables.map { ColumnReference(it, "id") }, dependenciesComplete = complete)
    private fun checkPending(query: QueryContract, changed: String = noise): MigrationReport {
        val after = baseline.copy(tables = baseline.tables + (changed to table.copy(columns = table.columns +
            ("optional" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)))))
        return CompatibilityEngine().verifyStep(DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true)),
            baseline, after, migration)
    }

    @Test fun `fully walked read only CTE and EXISTS retain baseline debt without reemitting on unrelated DDL`() {
        for (sql in listOf(
            "WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen",
            "SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT b.id FROM public.badges b WHERE b.id=a.id)",
            "SELECT a.id FROM public.accounts a WHERE a.id IN (SELECT b.id FROM public.badges b)",
            "SELECT x.id FROM (SELECT id FROM public.accounts) x",
        )) {
            val query = query(sql)
            val contract = DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true))
            val health = CompatibilityEngine().inspectBaseline(contract, baseline)
            assertEquals(Verdict.UNKNOWN, health.verdict, sql)
            assertTrue(health.findings.any { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }, health.toString())
            assertFalse(checkPending(query).findings.any { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }, sql)
            assertTrue(checkPending(query, accounts).findings.any {
                it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" && query.id in it.affectedContractIds
            }, sql)
        }
    }

    @Test fun `unknown physical inventory writable recursive and hidden function calls retain broad uncertainty`() {
        for (query in listOf(
            query("WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen", complete = false),
            query("WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen", tables = listOf(badges)),
            query("WITH RECURSIVE walk(id) AS (SELECT id FROM public.accounts UNION ALL SELECT id FROM walk) SELECT id FROM walk"),
            query("WITH changed AS (DELETE FROM public.accounts RETURNING id) SELECT id FROM changed"),
            query("WITH chosen AS (SELECT hidden_lookup(id) AS id FROM public.accounts) SELECT id FROM chosen"),
            query("SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT hidden_lookup(b.id) FROM public.badges b)"),
            query("SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT b.id::regclass FROM public.badges b)"),
        )) assertTrue(checkPending(query).findings.any {
            it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" && query.id in it.affectedContractIds
        }, query.sql)
    }

    @Test fun `a joined caller does not transfer one complete cause dependencies to all other roots`() {
        val names = (0 until 24).map { "public.source_$it" }
        val views = names.map { it.replace("source_", "view_") }
        val nodes = names.indices.associate { index ->
            "view:${views[index]}" to BehaviorNode("view", "SELECT id FROM ${names[index]}",
                references = listOf(BehaviorReference(BehaviorReferenceKind.COLUMN, names[index], "id", BehaviorAccess.SELECT)),
                dependenciesComplete = true, semanticsSupported = false, planningMayEvaluate = false,
                opaqueReasons = listOf("Security-barrier view semantics are not modeled."))
        }
        val graph = BehaviorGraph(attachmentInventoryComplete = true, nodes = nodes,
            attachments = views.map { BehaviorAttachment(it, "view:$it", setOf(Operation.SELECT)) })
        val schema = baseline.copy(tables = (names + views).associateWith { table }, behaviorGraph = graph)
        val query = QueryContract("join-all", Operation.SELECT,
            "SELECT v0.id FROM " + views.mapIndexed { index, name -> "$name v$index" }.joinToString(" CROSS JOIN "),
            tables = views, columns = views.map { ColumnReference(it, "id") }, dependenciesComplete = true)
        val changed = names[7]
        val after = schema.copy(tables = schema.tables + (changed to table.copy(columns = table.columns +
            ("id" to table.columns.getValue("id").copy(nullable = true)))))
        val report = CompatibilityEngine().verifyStep(DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true)),
            schema, after, migration)
        val causes = report.findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
        assertEquals(listOf("view:${views[7]}"), causes.map { it.objectName }, causes.toString())
        assertEquals(listOf("join-all"), causes.single().affectedContractIds)
    }

    @Test fun `unchanged clock defaults have bounded dependencies without claiming constant values`() {
        val empty = SchemaSnapshot(emptyMap())
        for ((expression, type) in listOf(
            "CURRENT_TIMESTAMP" to "timestamp with time zone", "CURRENT_TIMESTAMP(3)" to "timestamp(3) with time zone",
            "now()" to "timestamp without time zone", "pg_catalog.transaction_timestamp()" to "timestamp with time zone",
            "CURRENT_DATE" to "date", "LOCALTIME(6)" to "time(6) without time zone",
        )) {
            assertTrue(CompatibilityViews.safeDefault(expression, type, empty), expression)
            assertFalse(RuleProofs.constantDefault(expression, type), "Clock values are not constants: $expression")
        }
        for ((expression, type) in listOf(
            "public.now()" to "timestamp with time zone", "now(1)" to "timestamp with time zone",
            "CURRENT_TIMESTAMP" to "public.custom_clock", "CURRENT_TIMESTAMP(7)" to "timestamp with time zone",
            "now() + (SELECT hidden_interval())" to "timestamp with time zone", "nextval('absent'::regclass)" to "bigint",
        )) assertFalse(CompatibilityViews.safeDefault(expression, type, empty), expression)
    }
}
