package dev.epoch.postgres

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class CommittedSessionPlanningTest {
    private val migration = MigrationInfo("2", "V2__policy.sql")
    private val queries = (1..4).map { QueryContract("q$it", Operation.SELECT, "SELECT id FROM accounts WHERE id=?",
        tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
        parameterTypes = listOf("BIGINT"), dependenciesComplete = true) }
    private val baseline = queries.associate { it.id to QueryPlan(true, "accepted", parameterTypes = listOf("bigint")) }

    @Test fun `different committed policy produces one unknown without claimed plans reuse or setting values`() {
        val original = verificationFixtureSession()
        val changed = original + ("primary_conninfo" to "secret-new-value")
        val connection = verificationSessionConnection(changed)
        PendingMigrationLease(migration, 60, connection).use { lease ->
            val queryPlanner = PostgresQueryPlanner()
            val selection = queryPlanner.selectStep(queries, baseline, SchemaSnapshot(), SchemaSnapshot(), baseline, forceReplan = true)
            assertEquals(baseline.mapValues { it.value.parameterTypes }, selection.baselineParameterTypes)
            val planning = DeferredQueryPlanning(queryPlanner, { error("No SQL worker may run under an unestablished policy") }, 4, original)
            val inspected = planning.inspectCommitted(connection, SchemaSnapshot(), selection, changed, lease)
            val result = queryPlanner.completeStep(queries, baseline, selection, inspected, migration)
            assertEquals(1, result.findings.size)
            val finding = result.findings.single()
            assertEquals("QUERY_SESSION_UNPROVEN", finding.ruleId)
            assertEquals(Verdict.UNKNOWN, finding.verdict)
            assertEquals(queries.map { it.id }.toSet(), finding.affectedContractIds.toSet())
            assertTrue(finding.evidence.contains("primary_conninfo"))
            assertFalse(finding.evidence.contains("secret-new-value"))
            assertFalse(finding.evidence.contains("secret-old-value"))
            assertEquals(0, result.replannedQueries)
            assertEquals(0, result.reusedQueries)
            assertEquals(0, result.planningParallelism)
            assertTrue(result.plans.values.none { it.accepted })
            lease.finish()
        }
    }

    @Test fun `restored policy invalidates unverified plans without a schema change and keeps baseline types`() {
        val previous = queries.associate { it.id to QueryPlan(false, "Unestablished policy", DeferredQueryPlanning.SESSION_UNVERIFIED) }
        val selection = PostgresQueryPlanner().selectStep(queries, baseline, SchemaSnapshot(), SchemaSnapshot(), previous, forceReplan = true)
        assertEquals(queries, selection.changed)
        assertEquals(baseline.mapValues { it.value.parameterTypes }, selection.baselineParameterTypes)
    }

    @Test @Timeout(10)
    fun `a completed worker failure is observed while an earlier submitted worker is blocked`() {
        val connection = verificationSessionConnection(verificationFixtureSession())
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val completions = ExecutorCompletionService<Unit>(executor)
        val first = completions.submit(java.util.concurrent.Callable {
            firstEntered.countDown(); releaseFirst.await(); Unit
        })
        val second = completions.submit(java.util.concurrent.Callable<Unit> {
            firstEntered.await()
            throw SQLTransientConnectionException("Observed worker infrastructure failure", "08006")
        })
        try {
            PendingMigrationLease(migration, 60, connection).use { lease ->
                val failure = assertThrows(SQLTransientConnectionException::class.java) {
                    lease.await(lease.completed(completions))
                }
                assertEquals("08006", failure.sqlState)
                assertFalse(first.isDone, "The infrastructure failure must not wait for the earlier partition.")
                lease.finish()
            }
        } finally {
            releaseFirst.countDown(); first.cancel(true); second.cancel(true); executor.shutdownNow()
        }
    }

    @Test fun `external interruption prevents a serial result or checkpoint from being credited`() {
        PendingMigrationLease(migration, 60, verificationSessionConnection(verificationFixtureSession())).use { lease ->
            try {
                Thread.currentThread().interrupt()
                val failure = assertThrows(VerificationException::class.java) { lease.checkExpired() }
                assertEquals("INTERRUPTED", failure.stage)
                assertEquals(migration, failure.migration)
            } finally { Thread.interrupted() }
            lease.finish()
        }
    }
}

internal fun verificationFixtureSession(): Map<String, String?> = sortedMapOf(
    "enable_seqscan" to "on", "primary_conninfo" to "secret-old-value",
    "epoch.effective_search_path" to "{pg_catalog,public}", "epoch.current_user" to "epoch", "epoch.session_user" to "epoch",
)

internal fun verificationSessionConnection(session: Map<String, String?>, aborted: CountDownLatch = CountDownLatch(1)): Connection {
    fun rows(values: List<List<String?>>): ResultSet {
        var index = -1
        return jdbcProxy(ResultSet::class.java) { name, args -> when (name) {
            "next" -> { index++; index < values.size }
            "getString" -> values[index][(args.single() as Int) - 1]
            "close" -> null
            else -> error(name)
        } }
    }
    return jdbcProxy(Connection::class.java) { name, _ -> when (name) {
        "createStatement" -> jdbcProxy(Statement::class.java) { method, args -> when (method) {
            "executeQuery" -> when {
                (args.single() as String).startsWith("SELECT name, setting") -> rows(session.filterKeys { !it.startsWith("epoch.") }
                    .toSortedMap().map { (key, value) -> listOf(key, value) })
                (args.single() as String).startsWith("SELECT current_schemas") -> rows(listOf(listOf(
                    session["epoch.effective_search_path"], session["epoch.current_user"], session["epoch.session_user"])))
                else -> error("Unexpected test query")
            }
            "close" -> null
            else -> error(method)
        } }
        "getAutoCommit" -> true
        "abort" -> { aborted.countDown(); null }
        "close" -> null
        else -> error(name)
    } }
}

private fun <T> jdbcProxy(type: Class<T>, answer: (String, Array<out Any?>) -> Any?): T = type.cast(Proxy.newProxyInstance(
    type.classLoader, arrayOf(type),
) { value, method, arguments -> when (method.name) {
    "hashCode" -> System.identityHashCode(value)
    "equals" -> value === arguments?.singleOrNull()
    "toString" -> "Fixture${type.simpleName}"
    else -> answer(method.name, arguments ?: emptyArray())
} })
