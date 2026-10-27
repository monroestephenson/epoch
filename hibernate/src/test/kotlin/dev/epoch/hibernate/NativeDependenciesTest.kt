package dev.epoch.hibernate

import dev.epoch.core.ColumnReference
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeDependenciesTest {
    @Test fun `native explicit SQL retains physical dependencies without entity-wide assumptions`() {
        val select = SqlDependencies.inspect("select a.id, b.legacy from accounts a join badges b on b.account_id = a.id where a.id = ?", nativeSql = true)
        assertTrue(select.complete)
        assertEquals(listOf("public.accounts", "public.badges"), select.tables)
        assertTrue(ColumnReference("public.badges", "legacy") in select.columns)
        val insert = SqlDependencies.inspect("insert into accounts(id, label) values (?, ?)", nativeSql = true)
        assertTrue(insert.complete)
        assertEquals("public.accounts", insert.target)
        assertEquals(listOf("id", "label"), insert.writes.map { it.column })
    }

    @Test fun `native opaque or ambiguous dependency paths remain incomplete`() {
        for (sql in listOf("select * from accounts", "select nextval('account_id_seq')",
            "select custom_lookup(id) from accounts", "insert into accounts values (?, ?)",
            "insert into accounts(id) values (?) on conflict(id) do update set label = 'changed'",
            "insert into accounts(id) select id from other_accounts", "select id from accounts a join badges b on b.account_id = a.id",
            "select x.id from (select id from accounts) x")) {
            assertFalse(SqlDependencies.inspect(sql, nativeSql = true).complete, sql)
        }
    }

    @Test fun `native schema resolution does not inherit an ORM default schema`() {
        val unqualified = SqlDependencies.inspect("select id from accounts", defaultSchema = "tenant", nativeSql = true)
        assertFalse(unqualified.complete)
        assertFalse(unqualified.tables.any { it.startsWith("tenant.") })
        val write = SqlDependencies.inspect("insert into accounts(id) values (?)", defaultSchema = "tenant", nativeSql = true)
        assertFalse(write.complete)
        assertEquals("public.accounts", write.target)
        val qualified = SqlDependencies.inspect("select id from tenant.accounts", defaultSchema = "tenant", nativeSql = true)
        assertTrue(qualified.complete)
        assertEquals(listOf("tenant.accounts"), qualified.tables)
        val generated = SqlDependencies.inspect("select id from accounts", defaultSchema = "tenant")
        assertTrue(generated.complete)
        assertEquals(listOf("tenant.accounts"), generated.tables)
    }

    @Test fun `order grouping and distinct expressions retain dependencies absent from the projection`() {
        for (native in listOf(true, false)) {
            for (sql in listOf("select id from public.accounts order by legacy",
                "select id from public.accounts group by id, legacy",
                "select distinct on (legacy) id from public.accounts order by legacy")) {
                val result = SqlDependencies.inspect(sql, nativeSql = native)
                assertTrue(result.complete, sql)
                assertTrue(ColumnReference("public.accounts", "legacy") in result.columns, "$sql: $result")
            }
        }
    }

    @Test fun `order by a simple output alias resolves to its actual expression`() {
        val result = SqlDependencies.inspect("select a.legacy as sort_key from public.accounts a order by sort_key", nativeSql = true)
        assertTrue(result.complete, result.toString())
        assertEquals(listOf(ColumnReference("public.accounts", "legacy")), result.columns)
        for (sql in listOf("select id as x, legacy as x from public.accounts order by x",
            "select legacy as id from public.accounts group by id",
            "select distinct on (id) legacy as id from public.accounts")) {
            assertFalse(SqlDependencies.inspect(sql, nativeSql = true).complete, sql)
        }
    }

    @Test fun `returning clauses retain dependencies and wildcards remain incomplete`() {
        for (prefix in listOf("insert into public.accounts(id) values (?)",
            "update public.accounts set id = ?", "delete from public.accounts where id = ?")) {
            val result = SqlDependencies.inspect("$prefix returning legacy", nativeSql = true)
            assertTrue(result.complete, "$prefix: $result")
            assertTrue(ColumnReference("public.accounts", "legacy") in result.columns, result.toString())
            assertFalse(SqlDependencies.inspect("$prefix returning *", nativeSql = true).complete)
        }
    }

    @Test fun `implicit joins and unmodeled pagination and window clauses cannot claim complete dependencies`() {
        for (native in listOf(true, false)) {
            for (sql in listOf("select a.id from public.accounts a natural join public.badges b",
                "select a.id from public.accounts a join public.badges b using (id)",
                "select id from public.accounts limit (select n from public.settings)",
                "select id from public.accounts limit 10 offset 2",
                "select id from public.accounts fetch first 10 rows only",
                "select sum(id) over w from public.accounts window w as (order by legacy)",
                "select sum(id) over (order by legacy) from public.accounts")) {
                assertFalse(SqlDependencies.inspect(sql, nativeSql = native).complete, sql)
            }
        }
    }
}
