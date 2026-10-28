package dev.epoch.hibernate

import dev.epoch.core.ColumnReference
import org.junit.jupiter.api.Test
import kotlin.test.*

class PaginationDependencyTest {
    @Test fun `literal and JDBC pagination operands retain complete flat physical dependencies`() {
        val suffixes = listOf("limit 10 offset 2", "limit ? offset ?", "offset ? rows fetch first ? rows only",
            "offset 2 rows fetch next 10 rows only", "fetch first ? rows only", "fetch first 1 row only", "offset ? rows")
        for (native in listOf(false, true)) for (suffix in suffixes) {
            val sql = "select a.id from public.accounts a order by a.legacy $suffix"
            val dependencies = SqlDependencies.inspect(sql, nativeSql = native)
            assertTrue(dependencies.complete, "$sql: $dependencies")
            assertEquals(listOf("public.accounts"), dependencies.tables)
            assertEquals(listOf(ColumnReference("public.accounts", "id"), ColumnReference("public.accounts", "legacy")), dependencies.columns)
        }
    }

    @Test fun `expression-bearing limits offsets and fetches cannot hide dependencies behind a scalar bound`() {
        val suffixes = listOf("limit custom_limit()", "offset custom_offset()", "limit (select n from public.settings)",
            "offset (select n from public.settings)", "fetch first (custom_limit()) rows only",
            "limit ? + custom_limit()", "limit 10 by legacy", "fetch first 10 rows with ties")
        for (native in listOf(false, true)) for (suffix in suffixes) {
            val sql = "select a.id from public.accounts a order by a.id $suffix"
            assertFalse(SqlDependencies.inspect(sql, nativeSql = native).complete, sql)
        }
    }

    @Test fun `simple pagination does not waive lexical CTE and derived lineage boundaries`() {
        for (sql in listOf(
            "with c as (select id from public.accounts limit 5) select c.id from c",
            "with c as (select id from public.accounts) select c.id from c offset ? rows fetch first ? rows only",
            "select d.id from (select id from public.accounts) d limit ?")) {
            val dependencies = SqlDependencies.inspect(sql, nativeSql = true)
            assertFalse(dependencies.complete, "$sql: $dependencies")
            assertFalse(dependencies.tables.any { it in setOf("public.c", "public.d") }, dependencies.toString())
        }
    }
}
