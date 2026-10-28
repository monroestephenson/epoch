package dev.epoch.postgres

import dev.epoch.core.QueryContract
import dev.epoch.core.SchemaSnapshot
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** Inspect only committed schemas from the same known verification policy as baseline.
 * The migration owner's temporary settings/objects are not application query evidence. */
internal class DeferredQueryPlanning(
    private val planner: PostgresQueryPlanner,
    private val connectionFactory: () -> Connection,
    private val parallelism: Int,
    private val baselineSession: Map<String, String?>,
) {
    fun inspectCommitted(connection: Connection, snapshot: SchemaSnapshot, selection: QueryStepSelection,
                         session: Map<String, String?>, lease: PendingMigrationLease): QueryPlanningBatch {
        checkSession(connection, session, lease)
        if (session != baselineSession) {
            val differing = (session.keys + baselineSession.keys).filter { session[it] != baselineSession[it] }.sorted()
            val evidence = "Committed connection settings differ from the baseline verification session; " +
                "setting names: ${differing.joinToString()}. SQL and boundary execution were not credited or reused under this unestablished policy."
            return QueryPlanningBatch(selection.changed.associate { it.id to QueryPlan(false, evidence, SESSION_UNVERIFIED) }, 0)
        }
        if (selection.changed.isEmpty()) return QueryPlanningBatch(emptyMap(), 0)
        val eligibleIds = planner.eligibleForFreshSession(selection.changed, snapshot).mapTo(hashSetOf()) { it.id }
        val plans = linkedMapOf<String, QueryPlan>()
        var maximumWorkers = 1
        var start = 0
        while (start < selection.changed.size) {
            val eligible = selection.changed[start].id in eligibleIds
            var end = start + 1
            while (end < selection.changed.size && (selection.changed[end].id in eligibleIds) == eligible) end++
            val batch = selection.changed.subList(start, end)
            val inspected = if (eligible && batch.size > 1 && parallelism > 1)
                parallel(batch, selection.baselineParameterTypes, session, lease)
            else QueryPlanningBatch(planner.inspectAll(connection, batch, selection.baselineParameterTypes) {
                // An unmodeled expression must not change the session for a later template.
                checkSession(connection, session, lease)
            }, 1)
            plans.putAll(inspected.plans)
            maximumWorkers = maxOf(maximumWorkers, inspected.planningParallelism)
            start = end
        }
        checkSession(connection, session, lease)
        return QueryPlanningBatch(selection.changed.associate { it.id to plans.getValue(it.id) }, maximumWorkers)
    }

    fun checkSession(connection: Connection, expected: Map<String, String?>, lease: PendingMigrationLease) {
        lease.checkExpired()
        val actual = PlanningSession.capture(connection)
        val changed = (actual.keys + expected.keys).filter { actual[it] != expected[it] }.sorted()
        if (changed.isNotEmpty()) throw VerificationException(
            "PENDING_PLANNING_SESSION_CHANGED", lease.migration,
            "The verification session changed while checking committed migration ${lease.migration.filename}; " +
                "setting names: ${changed.joinToString()}. Its evidence is incomplete.")
        lease.checkExpired()
    }

    private fun parallel(queries: List<QueryContract>, parameterTypes: Map<String, List<String>>,
                         session: Map<String, String?>, lease: PendingMigrationLease): QueryPlanningBatch {
        val workers = minOf(parallelism, queries.size)
        val partitions = List(workers) { mutableListOf<QueryContract>() }
        queries.forEachIndexed { index, query -> partitions[index % workers] += query }
        val executor = Executors.newFixedThreadPool(workers) { work ->
            Thread(work, "epoch-committed-query-planner").apply { isDaemon = true }
        }
        val completed = ExecutorCompletionService<Pair<Int, Map<String, QueryPlan>>>(executor)
        val futures = partitions.mapIndexed { ordinal, batch -> completed.submit(Callable {
            lease.withConnection(connectionFactory) { connection ->
                checkSession(connection, session, lease)
                val result = PostgresQueryPlanner().inspectAll(connection, batch, parameterTypes)
                checkSession(connection, session, lease)
                ordinal to result
            }
        }) }
        try {
            // Observe infrastructure failures as workers finish instead of hiding one behind
            // an earlier submitted worker. Output order still follows the original contract.
            val partitionsByOrdinal = mutableMapOf<Int, Map<String, QueryPlan>>()
            repeat(workers) {
                val (ordinal, plans) = lease.await(lease.completed(completed))
                partitionsByOrdinal[ordinal] = plans
            }
            val gathered = partitionsByOrdinal.toSortedMap().values.flatMap { it.entries }.associate { it.toPair() }
            return QueryPlanningBatch(queries.associate { it.id to gathered.getValue(it.id) }, workers)
        } catch (failure: Throwable) {
            lease.abortWork()
            throw failure
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    companion object { const val SESSION_UNVERIFIED = "EPOCH_SESSION_UNPROVEN" }
}
