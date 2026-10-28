package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectCatalogObserverTest {
    @Test fun `accepted baseline debt never waives new catalog content observations`() {
        for ((table, column, sql) in listOf(
            Triple("pg_catalog.pg_class", "relname", "SELECT relname FROM pg_catalog.pg_class"),
            Triple("pg_catalog.pg_class", "relname", "WITH names AS (SELECT relname FROM pg_catalog.pg_class) SELECT relname FROM names"),
            Triple("information_schema.tables", "table_name", "SELECT table_name FROM information_schema.tables"),
        )) {
            val ordinary = TableSchema(mapOf("id" to ColumnSchema("bigint", false)),
                relationSecurity = RelationSecurity("epoch", false, false, false, false))
            val before = SchemaSnapshot(mapOf("public.records" to ordinary), expressionSearchPath = listOf("pg_catalog", "public"),
                behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true, nodes = emptyMap(), attachments = emptyList()))
            val after = before.copy(tables = before.tables + ("public.records" to ordinary.copy(columns = ordinary.columns +
                ("extra" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)))))
            val observer = QueryContract("catalog-reader", Operation.SELECT, sql, tables = listOf(table),
                columns = listOf(ColumnReference(table, column)), dependenciesComplete = true)
            val regular = QueryContract("ordinary-reader", Operation.SELECT, "SELECT id FROM public.records",
                tables = listOf("public.records"), columns = listOf(ColumnReference("public.records", "id")), dependenciesComplete = true)
            val contract = DatabaseContract(queries = listOf(observer, regular), coverage = Coverage(complete = true))
            val engine = CompatibilityEngine()
            val health = engine.inspectBaseline(contract, before)
            val policy = BaselineAcceptance.fromReport(engine.report(contract, emptyList(), health), "Existing catalog reader coverage debt")
            val migration = MigrationInfo("2", "V2__expand.sql")
            val step = engine.verifyStep(contract, before, after, migration, health.findings)
            val report = engine.report(contract, listOf(step), health, policy)
            assertEquals(Verdict.UNKNOWN, report.gateVerdict, sql)
            val causes = report.migrations.single().findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
            assertTrue(causes.isNotEmpty(), report.toString())
            assertEquals(setOf(observer.id), causes.flatMap { it.affectedContractIds }.toSet(), sql)
        }
    }
}
