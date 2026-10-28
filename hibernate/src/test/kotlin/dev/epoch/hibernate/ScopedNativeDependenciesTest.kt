package dev.epoch.hibernate

import dev.epoch.core.ColumnReference
import dev.epoch.core.postgresIdentifier
import org.junit.jupiter.api.Test
import kotlin.test.*

class ScopedNativeDependenciesTest {
    private val legacy = ColumnReference("public.accounts", "legacy")
    private fun inspect(sql: String) = SqlDependencies.inspect(sql, nativeSql = true)
    private fun noVirtual(dependencies: SqlDependencies.Dependencies, vararg names: String) {
        val fake = names.map { "public.$it" }.toSet()
        assertTrue(dependencies.tables.none { it in fake }, dependencies.toString())
        assertTrue(dependencies.columns.none { it.table in fake }, dependencies.toString())
    }

    @Test fun `acyclic direct CTE projections retain only real physical lineage`() {
        val result = inspect("with c as (select a.legacy from accounts a) select c.legacy from c")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf("public.accounts"), result.tables)
        assertEquals(listOf(legacy), result.columns)
        noVirtual(result, "c")
    }

    @Test fun `explicit CTE output aliases do not become physical column names`() {
        val result = inspect("with c(alias_name) as (select a.legacy from accounts a) select c.alias_name from c")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf(legacy), result.columns)
        assertFalse(result.columns.any { it.column == "alias_name" })
        noVirtual(result, "c")
    }

    @Test fun `chained CTE scopes propagate direct physical lineage`() {
        val result = inspect("with first_scope as (select legacy from accounts), second_scope as (select legacy from first_scope) select legacy from second_scope")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf(legacy), result.columns)
        noVirtual(result, "first_scope", "second_scope")
    }

    @Test fun `nonrecursive CTE does not hide a physical table from its own definition`() {
        val result = inspect("with accounts as (select id from accounts) select id from accounts")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf("public.accounts"), result.tables)
        assertEquals(listOf(ColumnReference("public.accounts", "id")), result.columns)
    }

    @Test fun `recursive visibility applies to the entire WITH list without fabricated tables`() {
        val result = inspect("with recursive first_scope as (select id from accounts union all select id from first_scope), " +
            "second_scope as (select id from first_scope union all select id from second_scope) select id from second_scope")
        assertFalse(result.complete)
        assertTrue("public.accounts" in result.tables)
        noVirtual(result, "first_scope", "second_scope")
        assertNotNull(result.failureReason)
    }

    @Test fun `nested CTE shadowing remains local to its enclosing query`() {
        val result = inspect("with c as (select id from accounts) select c.id from c where exists " +
            "(with c as (select b.account_id as id from badges b) select c.id from c)")
        assertFalse(result.complete) // Correlated/nested expression coverage is still conservative.
        assertTrue(ColumnReference("public.accounts", "id") in result.columns)
        assertTrue(ColumnReference("public.badges", "account_id") in result.columns)
        assertFalse(ColumnReference("public.badges", "id") in result.columns)
        noVirtual(result, "c")
    }

    @Test fun `schema qualification bypasses a same-named CTE`() {
        val result = inspect("with accounts as (select b.account_id as id from badges b) " +
            "select p.legacy from public.accounts p join accounts c on c.id = p.id")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf("public.accounts", "public.badges"), result.tables)
        assertTrue(legacy in result.columns)
        assertTrue(ColumnReference("public.badges", "account_id") in result.columns)
    }

    @Test fun `same aliases in separate SELECT bodies never overwrite each other`() {
        val result = inspect("select a.id from accounts a where exists (select 1 from badges a where a.account_id = ? and a.legacy is not null)")
        assertFalse(result.complete)
        assertTrue(ColumnReference("public.accounts", "id") in result.columns)
        assertTrue(ColumnReference("public.badges", "account_id") in result.columns)
        assertTrue(ColumnReference("public.badges", "legacy") in result.columns)
        assertFalse(ColumnReference("public.accounts", "account_id") in result.columns)
        assertFalse(ColumnReference("public.badges", "id") in result.columns)
    }

    @Test fun `derived outputs have factual lineage without a fabricated alias relation`() {
        val result = inspect("select x.renamed from (select a.legacy as renamed from accounts a) x")
        assertFalse(result.complete) // Derived completeness remains explicitly unsupported in this patch.
        assertEquals(listOf(legacy), result.columns)
        noVirtual(result, "x")
    }

    @Test fun `ambiguous local columns are omitted instead of assigned to a convenient relation`() {
        val result = inspect("with c as (select id from accounts a join badges b on b.account_id = a.id) select id from c")
        assertFalse(result.complete)
        assertFalse(ColumnReference("public.badges", "id") in result.columns)
        assertTrue(ColumnReference("public.accounts", "id") in result.columns) // Explicit ON operand only.
        noVirtual(result, "c")
    }

    @Test fun `writable CTE bodies retain actual targets without being classified as ordinary reads`() {
        for (body in listOf("delete from accounts where id = ? returning id", "update accounts set legacy = ? returning id",
            "insert into accounts(id) values (?) returning id")) {
            val result = inspect("with changed as ($body) select id from changed")
            assertFalse(result.complete, body)
            assertTrue("public.accounts" in result.tables, result.toString())
            assertTrue(result.writes.isEmpty(), "Outer SELECT must not pretend to be a direct INSERT/UPDATE: $result")
            assertNull(result.target)
            noVirtual(result, "changed")
        }
    }

    @Test fun `quoted and truncated CTE aliases use PostgreSQL identifier identity`() {
        val long = "界".repeat(24)
        val truncated = postgresIdentifier(long)
        val result = inspect("with \"$long\" as (select a.legacy from accounts a) select \"$truncated\".legacy from \"$truncated\"")
        assertTrue(result.complete, result.toString())
        assertEquals(listOf(legacy), result.columns)
        noVirtual(result, long, truncated)
    }

    @Test fun `an alias hides the original physical relation name in qualified references`() {
        val result = inspect("with c as (select accounts.legacy from accounts a) select legacy from c")
        assertFalse(result.complete)
        assertTrue("public.accounts" in result.tables)
        assertFalse(legacy in result.columns)
        noVirtual(result, "c")
    }

    @Test fun `unqualified correlated names are not guessed from an inner physical relation`() {
        val result = inspect("select a.id from accounts a where exists (select 1 from badges b where legacy = ?)")
        assertFalse(result.complete)
        assertFalse(result.columns.any { it.column == "legacy" }, result.toString())
    }

    @Test fun `unsupported CTE projections never invent physical output columns`() {
        for (projection in listOf("legacy as x, id as x", "count(*) as n", "custom_lookup(legacy) as x", "*")) {
            val result = inspect("with c as (select $projection from accounts) select c.x from c")
            assertFalse(result.complete, projection)
            noVirtual(result, "c")
            assertFalse(ColumnReference("public.accounts", "x") in result.columns)
        }
    }
    @Test fun `whole-row references are not fabricated as columns on their sole physical relation`() {
        for ((from, row) in listOf("accounts a" to "a", "accounts" to "accounts")) {
            val result = inspect("with c as (select $row as composite_value from $from) select composite_value from c")
            assertFalse(result.complete, result.toString())
            assertEquals(listOf("public.accounts"), result.tables)
            assertTrue(result.columns.isEmpty(), "A relation-name expression can be a whole-row value, not a physical column: $result")
            noVirtual(result, "c")
        }
        // Explicit qualification still establishes a scalar column, even when its name also
        // matches the relation alias. The ambiguity concerns the unqualified expression only.
        val scalar = inspect("with c as (select a.a from accounts a) select a from c")
        assertTrue(scalar.complete, scalar.toString())
        assertEquals(listOf(ColumnReference("public.accounts", "a")), scalar.columns)
    }

    @Test fun `nested ORDER BY scopes cannot overwrite outer relation aliases`() {
        val result = inspect("select a.id from accounts a order by (select max(a.account_id) from badges a)")
        assertFalse(result.complete)
        assertTrue(ColumnReference("public.accounts", "id") in result.columns)
        assertTrue(ColumnReference("public.badges", "account_id") in result.columns)
        assertFalse(ColumnReference("public.badges", "id") in result.columns)
    }

}
