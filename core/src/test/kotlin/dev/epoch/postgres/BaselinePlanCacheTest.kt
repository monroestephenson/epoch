package dev.epoch.postgres

import dev.epoch.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.*

class BaselinePlanCacheTest {
    @TempDir lateinit var directory: Path
    private val query = QueryContract("read", Operation.SELECT, "select id from accounts", tables = listOf("public.accounts"),
        columns = listOf(ColumnReference("public.accounts", "id")), dependenciesComplete = true)
    private val contract = DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true))
    private val snapshot = SchemaSnapshot(tables = mapOf("public.accounts" to TableSchema(mapOf("id" to ColumnSchema("bigint", false)))))
    private fun compute(queries: List<QueryContract>) = QueryPlanningBatch(queries.associate {
        it.id to QueryPlan(true, "fixed structural evidence", results = listOf(ResultColumn("id", "bigint")))
    }, if (queries.isEmpty()) 0 else 1)

    @Test fun `plan cache binds contract baseline catalog and integrity independently of cache diagnostics`() {
        val cache = BaselinePlanCache(directory)
        val first = cache.prepare(contract, "baseline-a", snapshot, ::compute)
        assertEquals("MISS", first.status)
        assertEquals(1, first.plannedQueries)
        val warm = cache.prepare(contract, "baseline-a", snapshot) { missing ->
            assertTrue(missing.isEmpty()); compute(missing)
        }
        assertEquals("HIT", warm.status)
        assertEquals(first.plans, warm.plans)
        assertEquals(1, warm.reusedQueries)
        assertEquals(0, warm.plannedQueries)
        assertNotEquals(first.key, cache.prepare(contract.copy(applicationVersion = "new"), "baseline-a", snapshot, ::compute).key)
        assertNotEquals(first.key, cache.prepare(contract, "baseline-b", snapshot, ::compute).key)
        val changed = snapshot.copy(tables = snapshot.tables + ("public.other" to TableSchema()))
        assertNotEquals(first.key, cache.prepare(contract, "baseline-a", changed, ::compute).key)
        val entry = directory.resolve("checks/${first.key}.json")
        Files.writeString(entry, Files.readString(entry).replace("fixed structural evidence", "corrupt evidence"))
        val rebuilt = cache.prepare(contract, "baseline-a", snapshot, ::compute)
        assertEquals("REBUILT", rebuilt.status)
        assertTrue(rebuilt.reason.orEmpty().contains("checksum mismatch"))
        assertEquals(first.plans, rebuilt.plans)
    }

    @Test fun `opaque incomplete observed and function dependent plans are rechecked instead of cached`() {
        for (unsafe in listOf(query.copy(dependenciesComplete = false), query.copy(source = QuerySource.RUNTIME_OBSERVED),
            query.copy(sql = "select current_timestamp from accounts"), query.copy(sql = "select id from accounts order by random()"),
            query.copy(sql = "select nextval('accounts_seq') from accounts"))) {
            val result = BaselinePlanCache(directory).prepare(contract.copy(queries = listOf(unsafe)), "baseline", snapshot, ::compute)
            assertEquals("BYPASSED", result.status, unsafe.toString())
            assertEquals(1, result.plannedQueries)
        }
        val behavior = snapshot.copy(behaviors = mapOf("function:public.value()" to "opaque body"))
        assertEquals("BYPASSED", BaselinePlanCache(directory).prepare(contract, "baseline", behavior, ::compute).status)
        val constrained = snapshot.copy(tables = mapOf("public.accounts" to snapshot.tables.getValue("public.accounts").copy(
            constraints = mapOf("guard" to ConstraintSchema("c", "CHECK(opaque(id))")))))
        assertEquals("BYPASSED", BaselinePlanCache(directory).prepare(contract, "baseline", constrained, ::compute).status)
    }

    @Test fun `infrastructure failure never publishes baseline plans`() {
        assertFailsWith<SQLException> {
            BaselinePlanCache(directory).prepare(contract, "baseline", snapshot) { throw SQLException("server stopped", "08006") }
        }
        Files.list(directory.resolve("checks")).use { assertEquals(0L, it.count()) }
    }
}
