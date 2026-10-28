package dev.epoch.postgres

import dev.epoch.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegerReadPreservationTest {
    private val ref = ColumnReference("public.values", "amount")
    private val baseline = SchemaSnapshot(tables = mapOf(ref.table to TableSchema(
        columns = mapOf("amount" to ColumnSchema("integer", false, ordinal = 1)), relationOid = 100L)),
        expressionSearchPath = listOf("pg_catalog", "public"))
    private val wide = baseline.copy(tables = baseline.tables.mapValues { (_, table) ->
        table.copy(columns = mapOf("amount" to table.columns.getValue("amount").copy(type = "bigint")))
    })
    private fun statement(sql: String, unknown: Boolean = false) = OperationalStatementReport(
        MigrationInfo("2", "V2__widen.sql"), 1, sql, false,
        OperationalTimeout(1000, "client"), OperationalTimeout(1000, "client"),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        if (unknown) OperationalVerdict.UNKNOWN else OperationalVerdict.SAFE)

    @Test fun `plain measured builtin widening carries the original physical range`() {
        assertEquals(setOf(ref), IntegerReadPreservation.advance(baseline, baseline, wide,
            IntegerReadPreservation.initial(baseline), listOf(statement("ALTER TABLE public.values ALTER COLUMN amount TYPE bigint;"))))
    }

    @Test fun `unsafe data effects erase a widened invariant and a no-op cannot revive it`() {
        for (sql in listOf("ALTER TABLE public.values ALTER COLUMN amount TYPE bigint USING amount+2147483648;",
            "UPDATE public.values SET amount=2147483648;", "DO $$ BEGIN PERFORM 1; END $$;")) {
            assertTrue(IntegerReadPreservation.advance(baseline, baseline, wide, setOf(ref), listOf(statement(sql))).isEmpty(), sql)
        }
        assertTrue(IntegerReadPreservation.advance(baseline, wide, wide, emptySet(), emptyList()).isEmpty())
    }

    @Test fun `callback or unproved measurement prevents preservation even for plain widening`() {
        assertTrue(IntegerReadPreservation.advance(baseline, baseline, wide, setOf(ref),
            listOf(statement("ALTER TABLE public.values ALTER COLUMN amount TYPE bigint;", unknown = true))).isEmpty())
    }

    @Test fun `physical range can re-establish bounds but replacement identity cannot borrow old rows`() {
        assertEquals(setOf(ref), IntegerReadPreservation.advance(baseline, wide, baseline, emptySet(),
            listOf(statement("UPDATE public.values SET amount=1;", unknown = true))))
        val replacement = wide.copy(tables = wide.tables.mapValues { (_, table) -> table.copy(relationOid = 101L) })
        assertTrue(IntegerReadPreservation.advance(baseline, baseline, replacement, setOf(ref),
            listOf(statement("ALTER TABLE public.values ALTER COLUMN amount TYPE bigint;"))).isEmpty())
    }
}
