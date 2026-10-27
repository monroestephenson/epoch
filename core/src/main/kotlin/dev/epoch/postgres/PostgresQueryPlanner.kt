package dev.epoch.postgres

import dev.epoch.core.*
import org.postgresql.core.Parser
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

internal data class QueryPlan(
    val accepted: Boolean,
    val evidence: String,
    val sqlState: String? = null,
    val results: List<ResultColumn> = emptyList(),
    /** PostgreSQL-resolved baseline types; reused verbatim for every migrated schema. */
    val parameterTypes: List<String> = emptyList(),
)

internal data class QueryPlanningStep(
    val findings: List<Finding>,
    val plans: Map<String, QueryPlan>,
    val replannedQueries: Int,
    val reusedQueries: Int,
    val planningParallelism: Int = 1,
)

internal data class QueryPlanningBatch(val plans: Map<String, QueryPlan>, val planningParallelism: Int)

/** PREPARE plus EXPLAIN, never EXECUTE/ANALYZE: DML is planned but not run. */
internal class PostgresQueryPlanner {
    private var batchTypeCache: MutableMap<String, String?>? = null

    /** Resolve recurring JDBC type aliases once per schema state, not once per parameter. */
    fun inspectAll(connection: Connection, queries: List<QueryContract>,
                   baselineParameterTypes: Map<String, List<String>> = emptyMap()): Map<String, QueryPlan> = withTypeCache {
        queries.associate { it.id to inspect(connection, it, baselineParameterTypes[it.id]) }
    }

    /** Each worker owns its connection and type cache; aggregation follows contract order, not completion order. */
    fun inspectParallel(queries: List<QueryContract>, connectionFactory: () -> Connection, parallelism: Int,
                        baselineParameterTypes: Map<String, List<String>> = emptyMap()): QueryPlanningBatch {
        if (queries.isEmpty()) return QueryPlanningBatch(emptyMap(), 0)
        val opened = ConcurrentLinkedQueue<Connection>()
        try {
            val partitions = PlanningWorkers.mapPartitions(queries, parallelism) { batch ->
                connectionFactory().also(opened::add).use { connection ->
                    PostgresQueryPlanner().inspectAll(connection, batch, baselineParameterTypes)
                }
            }
            val gathered = partitions.flatMap { it.entries }.associate { it.toPair() }
            return QueryPlanningBatch(queries.associate { it.id to gathered.getValue(it.id) }, minOf(parallelism, queries.size))
        } catch (failure: Throwable) {
            opened.forEach { connection -> runCatching { connection.abort { it.run() } }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    fun inspectSupportedBatch(connection: Connection, queries: List<QueryContract>, snapshot: SchemaSnapshot,
                              connectionFactory: (() -> Connection)?, parallelism: Int,
                              baselineParameterTypes: Map<String, List<String>> = emptyMap()): QueryPlanningBatch {
        if (queries.isEmpty()) return QueryPlanningBatch(emptyMap(), 0)
        if (connectionFactory == null || parallelism == 1 || !connection.autoCommit)
            return QueryPlanningBatch(inspectAll(connection, queries, baselineParameterTypes), 1)
        val safety = PlanningSafety(snapshot)
        val eligible = queries.map(safety::eligible)
        val plans = linkedMapOf<String, QueryPlan>()
        var maximumWorkers = 1
        var start = 0
        while (start < queries.size) {
            var end = start + 1
            while (end < queries.size && eligible[end] == eligible[start]) end++
            val batch = queries.subList(start, end)
            var pooled: QueryPlanningBatch? = null
            if (eligible[start] && batch.size > 1) {
                val expected = PlanningSession.capture(connection)
                try {
                    pooled = inspectParallel(batch, {
                        val opened = connectionFactory()
                        try {
                            if (PlanningSession.capture(opened) != expected) throw PlanningSessionMismatch()
                            opened
                        } catch (failure: Throwable) {
                            runCatching { opened.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                            throw failure
                        }
                    }, parallelism, baselineParameterTypes)
                } catch (_: PlanningSessionMismatch) {
                    // Session-local settings, role or temporary names require the migration's own connection.
                }
            }
            val result = pooled ?: QueryPlanningBatch(inspectAll(connection, batch, baselineParameterTypes), 1)
            plans.putAll(result.plans)
            maximumWorkers = maxOf(maximumWorkers, result.planningParallelism)
            start = end
        }
        return QueryPlanningBatch(plans, maximumWorkers)
    }

    private inline fun <T> withTypeCache(block: () -> T): T {
        check(batchTypeCache == null) { "Nested planner batches are unsupported" }
        batchTypeCache = hashMapOf()
        return try { block() } finally { batchTypeCache = null }
    }

    fun inspect(connection: Connection, query: QueryContract, baselineParameterTypes: List<String>? = null): QueryPlan {
        val savepoint = if (!connection.autoCommit) connection.setSavepoint() else null
        // Fixed-length names keep PostgreSQL error positions stable across worker schedules and cache hits.
        val name = "epoch_" + MessageDigest.getInstance("SHA-256").digest((query.id + "\u0000" + query.sql).toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        var prepared = false
        var primaryFailure: Throwable? = null
        return try {
            val parsed = Parser.parseJdbcSql(query.sql, true, true, true, false, false)
            if (parsed.size != 1) return QueryPlan(false, "Only a single SQL operation is supported.")
            val native = parsed.single()
            if (native.command.type.name !in setOf("SELECT", "INSERT", "UPDATE", "DELETE")) {
                return QueryPlan(false, "Only SELECT, INSERT, UPDATE, and DELETE SQL templates are supported.")
            }
            val declaredTypes = if (query.parameterTypes.isEmpty() && native.bindPositions.isNotEmpty() &&
                query.source in setOf(QuerySource.NAMED_NATIVE, QuerySource.STATIC, QuerySource.RUNTIME_OBSERVED)) {
                List(native.bindPositions.size) { "OTHER" }
            } else query.parameterTypes
            if (native.bindPositions.size != declaredTypes.size) {
                return QueryPlan(false, "SQL has ${native.bindPositions.size} parameters but ${query.parameterTypes.size} declared parameter types.")
            }
            val suppliedTypes = baselineParameterTypes ?: declaredTypes
            if (suppliedTypes.size != native.bindPositions.size) {
                return QueryPlan(false, "The resolved baseline parameter count differs from the SQL template.")
            }
            val inferredSlots = suppliedTypes.indices.filter { suppliedTypes[it].trim().uppercase() in setOf("OTHER", "JDBC_1111", "UNKNOWN") }
            val types = suppliedTypes.mapIndexed { index, type ->
                if (index in inferredSlots) "unknown"
                else canonicalType(connection, type) ?: return QueryPlan(false, "Unknown PostgreSQL parameter type: $type",
                    if (baselineParameterTypes == null) null else "42704")
            }
            val typeList = if (types.isEmpty()) "" else types.joinToString(",", "(", ")")
            val values = if (types.isEmpty()) "" else types.joinToString(",", "(", ")") { "NULL" }
            connection.createStatement().use { statement ->
                statement.execute("PREPARE $name$typeList AS ${native.nativeSql}")
                prepared = true
            }
            // PREPARE derives OTHER from PostgreSQL's actual catalog/operator/cast context.
            // Retain these baseline types: re-inferring after ALTER TYPE could conceal broken bindings.
            val resolvedTypes = if (inferredSlots.isEmpty()) types else connection.prepareStatement(
                "SELECT parameter_types::text[] FROM pg_prepared_statements WHERE name = ?"
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { result ->
                    check(result.next()) { "PostgreSQL did not retain the prepared statement" }
                    val array = result.getArray(1)
                    try { (array.array as Array<*>).map { it.toString() } } finally { array.free() }
                }
            }
            val constrainedTextSlots = if (inferredSlots.any { resolvedTypes[it] == "text" })
                SqlParameterContexts.constrainedSlots(query.sql) else emptySet()
            if (inferredSlots.any { resolvedTypes[it] == "unknown" || resolvedTypes[it] == "text" && it !in constrainedTextSlots }) {
                return QueryPlan(false,
                    "Parameter type is ambiguous: PostgreSQL inferred unknown/text without an established column or explicit cast context. Declare its PostgreSQL type explicitly.")
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("EXPLAIN (FORMAT JSON) EXECUTE $name$values").use { result ->
                    check(result.next()) { "PostgreSQL returned no query plan" }
                }
            }
            // getMetaData sends protocol Parse/Describe; it does not execute the prepared operation.
            // Describing EXECUTE uses the server's already typed prepared statement, avoiding guessed JDBC types.
            val results = connection.prepareStatement("EXECUTE $name$values").use { statement ->
                val metadata = statement.metaData
                if (metadata == null) emptyList() else (1..metadata.columnCount).map { column ->
                    ResultColumn(metadata.getColumnLabel(column),
                        canonicalType(connection, metadata.getColumnTypeName(column)) ?: metadata.getColumnTypeName(column))
                }
            }
            if (query.resultColumns.isNotEmpty()) {
                val expected = query.resultColumns.map { column ->
                    column.copy(typeName = column.typeName?.let { canonicalType(connection, it) ?: it })
                }
                val matches = expected.size == results.size && expected.zip(results).all { (want, actual) ->
                    want.name == actual.name && (want.typeName == null || want.typeName == actual.typeName)
                }
                if (!matches) return QueryPlan(false,
                    "Declared result columns $expected differ from PostgreSQL's result columns $results.",
                    "EPOCH_RESULT_MISMATCH", results, resolvedTypes)
            }
            QueryPlan(true, "PostgreSQL PREPARE, EXPLAIN, and Describe accepted the declared SQL template and parameter types.",
                results = results, parameterTypes = resolvedTypes)
        } catch (exception: SQLException) {
            // A lost server, cancelled statement, or exhausted resource is a failed
            // verification run, not evidence that the old SQL became incompatible.
            if (isInfrastructureFailure(exception)) {
                primaryFailure = exception
                if (savepoint != null) runCatching { connection.rollback(savepoint) }.exceptionOrNull()?.let(exception::addSuppressed)
                throw exception
            }
            if (savepoint != null) connection.rollback(savepoint)
            QueryPlan(false, "SQLSTATE ${exception.sqlState}: ${exception.message}", exception.sqlState)
        } catch (exception: IllegalArgumentException) {
            if (savepoint != null) connection.rollback(savepoint)
            QueryPlan(false, exception.message ?: "Invalid SQL template")
        } finally {
            val cleanupFailure = runCatching {
                if (prepared) {
                    try {
                        connection.createStatement().use { it.execute("DEALLOCATE $name") }
                    } catch (exception: SQLException) {
                        if (savepoint != null) connection.rollback(savepoint)
                        if (exception.sqlState != "26000") throw exception
                    }
                }
                if (savepoint != null) connection.releaseSavepoint(savepoint)
            }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    /** Compatibility comparison always uses the original baseline, even when a plan is reused. */
    fun planStep(
        connection: Connection,
        queries: List<QueryContract>,
        baseline: Map<String, QueryPlan>,
        migration: MigrationInfo,
        before: SchemaSnapshot,
        after: SchemaSnapshot,
        previousPlans: Map<String, QueryPlan> = baseline,
        connectionFactory: (() -> Connection)? = null,
        parallelism: Int = 1,
    ): QueryPlanningStep {
        val delta = QueryRevalidation.delta(before, after)
        val changed = queries.filter { query -> baseline.getValue(query.id).accepted &&
            (previousPlans[query.id] == null || delta.needsPlanning(query)) }
        val types = changed.associate { it.id to baseline.getValue(it.id).parameterTypes }
        // A second session cannot observe DDL inside Flyway's current transaction.
        val inspected = inspectSupportedBatch(connection, changed, after, connectionFactory, parallelism, types)
        val plans = linkedMapOf<String, QueryPlan>()
        val findings = queries.mapNotNull { query ->
            val original = baseline.getValue(query.id)
            if (!original.accepted) {
                plans[query.id] = original
                return@mapNotNull baselineFinding(query, original)
            }
            val current = inspected.plans[query.id] ?: previousPlans.getValue(query.id)
            plans[query.id] = current
            comparisonFinding(query, original, current, migration)
        }
        return QueryPlanningStep(findings, plans, changed.size, queries.size - changed.size, inspected.planningParallelism)
    }

    /** Full revalidation entry point retained for callers without factual dependency snapshots. */
    fun findings(
        connection: Connection,
        queries: List<QueryContract>,
        baseline: Map<String, QueryPlan>,
        migration: MigrationInfo,
    ): List<Finding> = withTypeCache {
        queries.mapNotNull { query ->
            val previous = baseline.getValue(query.id)
            if (!previous.accepted) baselineFinding(query, previous)
            else comparisonFinding(query, previous, inspect(connection, query, previous.parameterTypes), migration)
        }
    }

    private fun comparisonFinding(
        query: QueryContract, previous: QueryPlan, current: QueryPlan, migration: MigrationInfo,
    ): Finding? = when {
        !current.accepted -> Finding(
            if (current.sqlState?.startsWith("42") == true || current.sqlState in setOf("3F000", "EPOCH_RESULT_MISMATCH")) Verdict.BREAKING else Verdict.UNKNOWN,
            "POSTGRES_SQL_REJECTED", query.id,
            "PostgreSQL accepted this SQL before migration and rejects it afterward.", current.evidence,
            listOf(query.id), migration, "Retain the old table/column/type contract until the old application is retired.",
        )
        current.results != previous.results -> Finding(
            if (query.exactResultShape || query.resultColumns.isNotEmpty()) Verdict.BREAKING else Verdict.UNKNOWN,
            "RESULT_SIGNATURE_CHANGED", query.id,
            "The query's PostgreSQL result labels or types changed.",
            "Before: ${previous.results}; after: ${current.results}", listOf(query.id), migration,
            "Retain the old result shape and types, or explicitly adapt and verify the old application's hydration contract.",
        )
        else -> null
    }

    fun baselineFindings(queries: List<QueryContract>, plans: Map<String, QueryPlan>): List<Finding> =
        queries.mapNotNull { query -> plans.getValue(query.id).takeUnless { it.accepted }?.let { baselineFinding(query, it) } }

    private fun baselineFinding(query: QueryContract, previous: QueryPlan) = Finding(
        Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", query.id,
        "The old SQL contract could not be validated against the baseline.", previous.evidence,
        listOf(query.id), migration = null,
        mitigation = "Supply a matching baseline and complete PostgreSQL parameter types using JDBC ? placeholders.",
    )

    private fun canonicalType(connection: Connection, type: String): String? {
        val cache = batchTypeCache
        if (cache != null && cache.containsKey(type)) return cache[type]
        val resolved = connection.prepareStatement("SELECT to_regtype(?)::text").use { statement ->
            val postgresType = when (type.trim().uppercase()) {
                "DOUBLE" -> "double precision"
                "TINYINT" -> "smallint"
                "TIMESTAMP_WITH_TIMEZONE" -> "timestamp with time zone"
                "TIME_WITH_TIMEZONE" -> "time with time zone"
                "BINARY", "VARBINARY", "LONGVARBINARY" -> "bytea"
                "LONGVARCHAR", "LONGNVARCHAR", "NVARCHAR" -> "text"
                else -> type
            }
            statement.setString(1, postgresType)
            statement.executeQuery().use { result -> result.next(); result.getString(1) }
        }
        cache?.put(type, resolved)
        return resolved
    }
}

/** SQLSTATEs describing unavailable infrastructure or an interrupted run must reach the CLI's exit 3. */
internal fun isInfrastructureFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(32).any { cause ->
        cause is SQLNonTransientConnectionException || cause is SQLTransientConnectionException ||
            cause is SQLRecoverableException || cause is SQLException && generateSequence(cause) { it.nextException }
                .take(32).any { exception ->
                    val state = exception.sqlState
                    state == null || state.startsWith("08") || state.startsWith("25") ||
                        state.startsWith("40") || state.startsWith("53") ||
                        state.startsWith("57") || state.startsWith("58") || state.startsWith("XX") || state == "55P03"
                }
    }
