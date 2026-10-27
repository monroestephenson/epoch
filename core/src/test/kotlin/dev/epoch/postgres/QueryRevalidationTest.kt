package dev.epoch.postgres

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException

class QueryRevalidationTest {
    private val query = QueryContract("find", Operation.SELECT, "select id from customers", tables = listOf("public.customers"),
        columns = listOf(ColumnReference("public.customers", "id")), dependenciesComplete = true)
    private val table = TableSchema(columns = mapOf("id" to ColumnSchema("bigint", false), "unused" to ColumnSchema("text", true)))
    private val baseline = SchemaSnapshot(tables = mapOf("public.customers" to table))

    @Test fun `unreferenced table and column changes reuse explicit query plans`() {
        val after = baseline.copy(tables = baseline.tables + ("public.audit" to table) +
            ("public.customers" to table.copy(columns = table.columns + ("metadata" to ColumnSchema("text", true)))))
        assertFalse(QueryRevalidation.needsPlanning(query, baseline, after))
        val removedUnreferenced = baseline.copy(tables = mapOf("public.customers" to table.copy(columns = table.columns - "unused")))
        assertFalse(QueryRevalidation.needsPlanning(query, baseline, removedUnreferenced))
    }

    @Test fun `dropping referenced columns and replacing tables with views rechecks SQL`() {
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, SchemaSnapshot()))
        val dropped = baseline.copy(tables = mapOf("public.customers" to table.copy(columns = table.columns - "id")))
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, dropped))
        val view = baseline.copy(tables = mapOf("public.customers" to table.copy(kind = "view")))
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, view))
    }

    @Test fun `wildcard queries recheck on unrelated columns on same table`() {
        val wildcard = query.copy(sql = "select * from customers", explicitColumnList = false)
        val after = baseline.copy(tables = mapOf("public.customers" to table.copy(columns = table.columns - "unused")))
        assertTrue(QueryRevalidation.needsPlanning(wildcard, baseline, after))
    }

    @Test fun `incomplete queries and hidden database behavior always recheck`() {
        assertTrue(QueryRevalidation.needsPlanning(query.copy(dependenciesComplete = false), baseline, baseline))
        val custom = baseline.copy(behaviors = mapOf("function:public.lookup()" to "select id from customers"))
        assertTrue(QueryRevalidation.needsPlanning(query, custom, custom))
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, baseline.copy(extensions = mapOf("example" to "2"))))
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, baseline.copy(enumTypes = mapOf("public.state" to listOf("ok")))))
    }

    @Test fun `incoming foreign key changes recheck target writes but not unrelated selects`() {
        val child = TableSchema(constraints = mapOf("fk" to ConstraintSchema("f", "FOREIGN KEY(customer_id) REFERENCES customers(id)",
            referencedTable = "public.customers")))
        val after = baseline.copy(tables = baseline.tables + ("public.child" to child))
        assertTrue(QueryRevalidation.needsPlanning(query.copy(operation = Operation.DELETE, targetTable = "public.customers"), baseline, after))
        assertFalse(QueryRevalidation.needsPlanning(query, baseline, after))
        val unrelated = child.copy(constraints = mapOf("fk" to child.constraints.getValue("fk").copy(referencedTable = "public.accounts")))
        assertFalse(QueryRevalidation.needsPlanning(query.copy(operation = Operation.DELETE), baseline,
            baseline.copy(tables = baseline.tables + ("public.child" to unrelated))))
    }

    @Test fun `connection loss cancellation and resource exhaustion are runtime failures`() {
        for (state in listOf("08006", "57P01", "57P05", "57014", "53100", "58030", "XX000", "55P03", "40001")) {
            assertTrue(isInfrastructureFailure(SQLException("failure", state)), state)
        }
        assertTrue(isInfrastructureFailure(SQLException("unknown failure")))
        assertTrue(isInfrastructureFailure(SQLNonTransientConnectionException("connection failed", "n/a")))
        assertTrue(isInfrastructureFailure(IllegalStateException("wrapped", SQLException("dead", "08003"))))
        assertFalse(isInfrastructureFailure(SQLException("missing relation", "42P01")))
        assertFalse(isInfrastructureFailure(SQLException("constraint violation", "23514")))
        assertFalse(isInfrastructureFailure(SQLException("index row exceeds maximum size", "54000")))
    }
    @Test fun `lost database reaches caller instead of becoming unverified SQL`() {
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            when (method.name) {
                "getAutoCommit" -> true
                "createStatement" -> throw SQLException("backend terminated", "08006")
                else -> error("Unexpected JDBC call: ${method.name}")
            }
        } as Connection
        val failure = assertThrows(SQLException::class.java) {
            PostgresQueryPlanner().inspect(connection, QueryContract("constant", Operation.SELECT, "SELECT 1"))
        }
        assertEquals("08006", failure.sqlState)
    }

    @Test fun `reused rejection remains a breaking finding against original baseline`() {
        val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            error("An unchanged plan must not access JDBC: ${method.name}")
        } as Connection
        val original = mapOf(query.id to QueryPlan(true, "baseline accepted"))
        val prior = mapOf(query.id to QueryPlan(false, "relation customers does not exist", "42P01"))
        val missing = SchemaSnapshot()
        val result = PostgresQueryPlanner().planStep(connection, listOf(query), original,
            MigrationInfo("3", "V3__unrelated.sql"), missing, missing, prior)
        assertEquals(0, result.replannedQueries)
        assertEquals(1, result.reusedQueries)
        assertEquals(Verdict.BREAKING, result.findings.single().verdict)
        assertEquals("3", result.findings.single().migration?.version)
    }

    @Test fun `owned sequence on unrelated new table does not invalidate old query plans`() {
        val after = baseline.copy(sequences = mapOf("public.audit_id_seq" to "bigint:1:1:99:1:false:1"),
            sequenceOwners = mapOf("public.audit_id_seq" to ColumnReference("public.audit", "id")))
        assertFalse(QueryRevalidation.needsPlanning(query, baseline, after))
        assertTrue(QueryRevalidation.needsPlanning(query.copy(sql = "select nextval('public.audit_id_seq')"), baseline, after))
        assertTrue(QueryRevalidation.needsPlanning(query, baseline, after.copy(sequenceOwners = emptyMap())))
    }

    @Test fun `PostgreSQL truncated identifiers retain their actual catalog dependency`() {
        val longName = "c".repeat(70)
        val actual = postgresIdentifier(longName)
        val longQuery = query.copy(columns = listOf(ColumnReference("public.customers", longName)))
        val before = baseline.copy(tables = mapOf("public.customers" to TableSchema(columns = mapOf(actual to ColumnSchema("text", true)))))
        val after = baseline.copy(tables = mapOf("public.customers" to TableSchema()))
        assertTrue(QueryRevalidation.needsPlanning(longQuery, before, after))
    }

}
