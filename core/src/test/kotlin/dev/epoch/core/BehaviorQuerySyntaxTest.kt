package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BehaviorQuerySyntaxTest {
    private val syntax = BehaviorQuerySyntax()
    private val table = "public.accounts"
    private val id = ColumnReference(table, "id")
    private val value = ColumnReference(table, "value")

    private fun query(sql: String, operation: Operation = Operation.SELECT, writes: List<ColumnReference> = emptyList()) =
        QueryContract("q", operation, sql, tables = listOf(table), writeColumns = writes,
            targetTable = table.takeIf { operation != Operation.SELECT }, dependenciesComplete = true)

    private fun complete(query: QueryContract): QueryBehaviorFacts = syntax.inspect(query).also {
        assertTrue(it.complete, it.unresolvedReasons.joinToString())
        assertTrue(it.defaultAssignmentsComplete)
    }

    @Test fun `flat generated CRUD has positive assignment and syntax coverage`() {
        complete(query("select a.id, a.value from accounts a where a.id = ? and a.value is not null"))
        complete(query("insert into accounts(id, value) values (?, ?)", Operation.INSERT, listOf(id, value)))
        complete(query("update accounts set value = ? where id = ?", Operation.UPDATE, listOf(value)))
        complete(query("delete from accounts where id = ?", Operation.DELETE))
    }

    @Test fun `explicit DEFAULT is distinguished from supplied and omitted insert columns`() {
        val default = complete(query("insert into accounts(id, value) values (?, DEFAULT)", Operation.INSERT, listOf(id, value)))
        assertEquals(setOf(value), default.defaultColumns)
        val supplied = complete(query("insert into accounts(id, value) values (?, 'DEFAULT')", Operation.INSERT, listOf(id, value)))
        assertTrue(supplied.defaultColumns.isEmpty())
        val omitted = complete(query("insert into accounts(id) values (?)", Operation.INSERT, listOf(id)))
        assertTrue(omitted.defaultColumns.isEmpty())
        complete(query("insert into accounts default values", Operation.INSERT))
    }

    @Test fun `multirow inserts collect DEFAULT positions across every variant`() {
        val facts = complete(query("insert into accounts(id, value) values (?, DEFAULT), (DEFAULT, ?)", Operation.INSERT, listOf(id, value)))
        assertEquals(setOf(id, value), facts.defaultColumns)
    }

    @Test fun `UPDATE defaults are determined by assignment syntax`() {
        assertEquals(setOf(value), complete(query("update accounts set value = DEFAULT where id = ?", Operation.UPDATE, listOf(value))).defaultColumns)
        assertTrue(complete(query("update accounts set value = ? where id = ?", Operation.UPDATE, listOf(value))).defaultColumns.isEmpty())
    }

    @Test fun `declared writes cannot conceal positional or conflicting SQL assignments`() {
        for (query in listOf(
            query("insert into accounts values (?, ?)", Operation.INSERT, listOf(id, value)),
            query("insert into accounts(id, value) values (?, ?)", Operation.INSERT, listOf(id)),
            query("update accounts set value = DEFAULT where id = ?", Operation.UPDATE, listOf(id)),
            query("insert into accounts(id) values (?) on conflict(id) do update set value = DEFAULT", Operation.INSERT, listOf(id)),
        )) {
            val facts = syntax.inspect(query)
            assertFalse(facts.complete, query.sql)
            assertFalse(facts.defaultAssignmentsComplete, query.sql)
        }
    }

    @Test fun `ordinary builtin signatures are retained for catalog overload resolution`() {
        val facts = complete(query("select count(*), lower(value), pg_catalog.length(value), coalesce(value, ?), nullif(value, ?) from accounts"))
        assertEquals(setOf("count", "lower", "pg_catalog.length", "coalesce", "nullif"), facts.builtinFunctionCalls)
        assertTrue(facts.references.isEmpty())
    }

    @Test fun `unknown and quoted function signatures remain caller local`() {
        for (sql in listOf("select custom_function(value) from accounts", "select private.lower(value) from accounts",
            "select \"lower\"(value) from accounts", "select lower(lower(value)) from accounts")) {
            val facts = syntax.inspect(query(sql))
            assertFalse(facts.complete, sql)
            assertTrue(facts.unresolvedReasons.any { "Function call" in it }, sql)
        }
        complete(query("select value from accounts"))
    }

    @Test fun `table free custom casts and typed parameters retain type roots`() {
        val facts = syntax.inspect(query("select cast(? as custom_domain)").copy(tables = emptyList(), parameterTypes = listOf("OTHER")))
        assertFalse(facts.complete)
        assertTrue(BehaviorReference(BehaviorReferenceKind.TYPE, "custom_domain") in facts.references)
        val explicitOther = syntax.inspect(query("select cast(? as other)").copy(tables = emptyList(), parameterTypes = listOf("UNKNOWN", "NULL")))
        assertTrue(BehaviorReference(BehaviorReferenceKind.TYPE, "other") in explicitOther.references)
        assertFalse(explicitOther.references.any { it.name == "UNKNOWN" || it.name == "NULL" })
        val typed = complete(query("select value from accounts").copy(parameterTypes = listOf("public.currency"),
            resultColumns = listOf(ResultColumn("value", "public.status"))))
        assertEquals(setOf("public.currency", "public.status"), typed.references.map { it.name }.toSet())
    }

    @Test fun `CTEs nested and specialized expressions never fabricate physical dependencies`() {
        for (sql in listOf("with recent as (select id from accounts) select id from recent",
            "select (select max(id) from accounts) from accounts", "select value collate \"C\" from accounts",
            "select current_timestamp from accounts", "select value from accounts union all select value from accounts")) {
            val facts = syntax.inspect(query(sql))
            assertFalse(facts.complete, sql)
            assertFalse(facts.references.any { it.kind == BehaviorReferenceKind.RELATION || it.kind == BehaviorReferenceKind.COLUMN }, sql)
        }
    }

    @Test fun `fixed and bound pagination is one supported syntax shape`() {
        complete(query("select id from accounts order by id limit ? offset ?"))
        complete(query("select id from accounts order by id offset 2 rows fetch next 10 rows only"))
        assertFalse(syntax.inspect(query("select id from accounts limit (1 + ?)" )).complete)
    }

    @Test fun `keywords in comments and literals do not introduce behavior`() {
        complete(query("select 'DEFAULT cast(? as hidden) random()' from accounts /* DEFAULT random() */ where id = ?"))
    }

    @Test fun `actual relation inventory is checked without guessing column aliases`() {
        val q = query("select a.id, b.value from accounts a join audit b on b.id = a.id where a.id in (?, ?)")
        assertFalse(syntax.inspect(q).complete)
        complete(q.copy(tables = listOf(table, "public.audit")))
    }

    @Test fun `cache reuses syntax across contract IDs but not differing writes or types`() {
        val q = query("update accounts set value = ? where id = ?", Operation.UPDATE, listOf(value))
        val first = complete(q)
        assertSame(first, syntax.inspect(q.copy(id = "another-repository-method")))
        assertNotSame(first, syntax.inspect(q.copy(writeColumns = listOf(id))))
        assertNotSame(first, syntax.inspect(q.copy(parameterTypes = listOf("public.domain"))))
    }

    @Test fun `malformed statements yield portable incomplete evidence`() {
        val facts = syntax.inspect(query("select from where ???"))
        assertFalse(facts.complete)
        assertTrue(facts.unresolvedReasons.any { "could not be classified" in it })
    }
    @Test fun `plain INSERT SELECT retains a separate read source and explicit target assignments`() {
        val copy = query("insert into accounts(id) select id from audit", Operation.INSERT, listOf(id))
            .copy(tables = listOf(table, "public.audit"))
        val facts = complete(copy)
        assertTrue(facts.defaultColumns.isEmpty())
        for (sql in listOf("insert into accounts(id) select * from audit",
            "insert into accounts(id) select opaque_writer(id) from audit",
            "with modified as (delete from audit returning id) insert into accounts(id) select id from modified")) {
            assertFalse(syntax.inspect(copy.copy(sql = sql)).complete, sql)
        }
    }

    @Test fun `JDBC serial result aliases never become fabricated physical type references`() {
        for (alias in listOf("smallserial", "serial2", "serial", "serial4", "bigserial", "serial8")) {
            val facts = complete(query("select id from accounts").copy(resultColumns = listOf(ResultColumn("id", alias))))
            assertFalse(facts.references.any { it.kind == BehaviorReferenceKind.TYPE && it.name == alias }, alias)
        }
        assertTrue(syntax.inspect(query("select id from accounts").copy(parameterTypes = listOf("serial"))).references
            .any { it.kind == BehaviorReferenceKind.TYPE && it.name == "serial" })
        assertTrue(syntax.inspect(query("select cast(? as serial)")).references
            .any { it.kind == BehaviorReferenceKind.TYPE && it.name == "serial" })
    }

}
