package dev.epoch.postgres

import dev.epoch.core.ColumnSchema
import dev.epoch.core.TableSchema
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectViewProjectionTest {
    private val backing = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, ordinal = 1),
        "label" to ColumnSchema("text", true, ordinal = 2),
    ))
    private val view = backing.copy(kind = "view")

    private fun complete(sql: String, target: TableSchema = view, source: TableSchema = backing) =
        DirectViewProjection.complete(sql, "public.accounts_v2", target, source)

    @Test fun `complete ordered projections accept qualified identifiers and simple aliases`() {
        listOf(
            " SELECT accounts_v2.id,\n    accounts_v2.label\n   FROM accounts_v2;",
            "SELECT id, label FROM public.accounts_v2;",
            "SELECT public.accounts_v2.id, public.accounts_v2.label FROM public.accounts_v2",
            "SELECT a.id, a.label FROM public.accounts_v2 a;",
            "SELECT a.id, a.label FROM public.accounts_v2 AS a;",
        ).forEach { assertTrue(complete(it), it) }
    }

    @Test fun `all expressions filters joins ordering grouping and incomplete projections stay unproven`() {
        listOf(
            "SELECT id, label FROM accounts_v2 WHERE id > 0",
            "SELECT id, label FROM accounts_v2 WHERE true",
            "SELECT DISTINCT id, label FROM accounts_v2",
            "SELECT id, label FROM accounts_v2 ORDER BY id",
            "SELECT id, label FROM accounts_v2 GROUP BY id, label",
            "SELECT id, label FROM accounts_v2 LIMIT 1",
            "SELECT id, label FROM accounts_v2 OFFSET 0",
            "SELECT id, label FROM accounts_v2 UNION SELECT id, label FROM accounts_v2",
            "SELECT a.id, a.label FROM accounts_v2 a JOIN other b ON a.id = b.id",
            "SELECT id, upper(label) FROM accounts_v2",
            "SELECT id, label::text FROM accounts_v2",
            "SELECT id, 'label' FROM accounts_v2",
            "SELECT id, label AS renamed FROM accounts_v2",
            "SELECT label, id FROM accounts_v2",
            "SELECT id FROM accounts_v2",
            "SELECT id, id FROM accounts_v2",
            "SELECT * FROM accounts_v2",
            "SELECT other.id, other.label FROM accounts_v2",
            "SELECT id, label FROM other",
            "SELECT id, label FROM wrong.accounts_v2",
            "SELECT id, label FROM accounts_v2; DELETE FROM accounts_v2",
        ).forEach { assertFalse(complete(it), it) }
    }

    @Test fun `catalog output names types order and backing relation kind must match`() {
        val sql = "SELECT id, label FROM accounts_v2"
        assertFalse(complete(sql, view.copy(columns = view.columns - "label")))
        assertFalse(complete(sql, view.copy(columns = view.columns + ("extra" to ColumnSchema("text", true, ordinal = 3)))))
        assertFalse(complete(sql, view.copy(columns = view.columns + ("id" to ColumnSchema("integer", true, ordinal = 1)))))
        assertFalse(complete(sql, view.copy(columns = mapOf(
            "id" to view.columns.getValue("id").copy(ordinal = 2),
            "label" to view.columns.getValue("label").copy(ordinal = 1),
        ))))
        assertFalse(complete(sql, source = backing.copy(kind = "view")))
    }

    @Test fun `quoted identifiers retain punctuation case and doubled quotes`() {
        val table = TableSchema(columns = mapOf(
            "a\"b" to ColumnSchema("bigint", false, ordinal = 1),
            "," to ColumnSchema("text", true, ordinal = 2),
        ))
        assertTrue(DirectViewProjection.complete(
            "SELECT \"source rows\".\"a\"\"b\", \"source rows\".\",\" FROM \"Private\".\"Accounts V2\" AS \"source rows\";",
            "Private.Accounts V2", table.copy(kind = "view"), table,
        ))
        assertFalse(DirectViewProjection.complete(
            "SELECT \"a\"\"b\", \",\" FROM private.\"Accounts V2\";",
            "Private.Accounts V2", table.copy(kind = "view"), table,
        ))
    }

    @Test fun `matching SQL types do not hide changed column collations`() {
        val collatedBacking = backing.copy(columns = backing.columns +
            ("label" to backing.columns.getValue("label").copy(collation = "pg_catalog.default")))
        val matchingView = collatedBacking.copy(kind = "view")
        val changedView = matchingView.copy(columns = matchingView.columns +
            ("label" to matchingView.columns.getValue("label").copy(collation = "pg_catalog.C")))
        assertTrue(complete("SELECT id, label FROM accounts_v2", matchingView, collatedBacking))
        assertFalse(complete("SELECT id, label FROM accounts_v2", changedView, collatedBacking))
    }
}
