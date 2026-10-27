package dev.epoch.postgres

import dev.epoch.core.*
import org.postgresql.core.Parser
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong

internal data class QueryPlan(
    val accepted: Boolean,
    val evidence: String,
    val sqlState: String? = null,
    val results: List<ResultColumn> = emptyList(),
    /** PostgreSQL-resolved baseline types; reused verbatim for every migrated schema. */
    val parameterTypes: List<String> = emptyList(),
)

/** PREPARE plus EXPLAIN, never EXECUTE/ANALYZE: DML is planned but not run. */
internal class PostgresQueryPlanner {
    private val nextId = AtomicLong()

    fun inspect(connection: Connection, query: QueryContract, baselineParameterTypes: List<String>? = null): QueryPlan {
        val savepoint = if (!connection.autoCommit) connection.setSavepoint() else null
        val name = "epoch_contract_${nextId.incrementAndGet()}"
        var prepared = false
        return try {
            val parsed = Parser.parseJdbcSql(query.sql, true, true, true, false, false)
            if (parsed.size != 1) return QueryPlan(false, "Only a single SQL operation is supported.")
            val native = parsed.single()
            if (native.command.type.name !in setOf("SELECT", "INSERT", "UPDATE", "DELETE")) {
                return QueryPlan(false, "Only SELECT, INSERT, UPDATE, and DELETE SQL templates are supported.")
            }
            if (native.bindPositions.size != query.parameterTypes.size) {
                return QueryPlan(false, "SQL has ${native.bindPositions.size} parameters but ${query.parameterTypes.size} declared parameter types.")
            }
            val suppliedTypes = baselineParameterTypes ?: query.parameterTypes
            if (suppliedTypes.size != native.bindPositions.size) {
                return QueryPlan(false, "The resolved baseline parameter count differs from the SQL template.")
            }
            val inferredSlots = suppliedTypes.indices.filter { suppliedTypes[it].trim().uppercase() in setOf("OTHER", "JDBC_1111") }
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
            val resolvedTypes = connection.prepareStatement(
                "SELECT parameter_types::text[] FROM pg_prepared_statements WHERE name = ?"
            ).use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { result ->
                    check(result.next()) { "PostgreSQL did not retain the prepared statement" }
                    val array = result.getArray(1)
                    try { (array.array as Array<*>).map { it.toString() } } finally { array.free() }
                }
            }
            if (inferredSlots.any { resolvedTypes[it] in setOf("unknown", "text") }) {
                return QueryPlan(false,
                    "OTHER parameter type is ambiguous: PostgreSQL inferred unknown/text, which may be an unconstrained parameter default. Declare its PostgreSQL type explicitly.")
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
            if (savepoint != null) connection.rollback(savepoint)
            QueryPlan(false, "SQLSTATE ${exception.sqlState}: ${exception.message}", exception.sqlState)
        } catch (exception: IllegalArgumentException) {
            if (savepoint != null) connection.rollback(savepoint)
            QueryPlan(false, exception.message ?: "Invalid SQL template")
        } finally {
            if (prepared) {
                try {
                    connection.createStatement().use { it.execute("DEALLOCATE $name") }
                } catch (exception: SQLException) {
                    if (savepoint != null) connection.rollback(savepoint)
                    if (exception.sqlState != "26000") throw exception
                }
            }
            if (savepoint != null) connection.releaseSavepoint(savepoint)
        }
    }

    fun findings(
        connection: Connection,
        queries: List<QueryContract>,
        baseline: Map<String, QueryPlan>,
        migration: MigrationInfo,
    ): List<Finding> = queries.mapNotNull { query ->
        val previous = baseline.getValue(query.id)
        if (!previous.accepted) return@mapNotNull baselineFinding(query, previous)
        val current = inspect(connection, query, previous.parameterTypes)
        when {
            !current.accepted -> Finding(
                if (current.sqlState in setOf("42P01", "42703", "42883", "42804", "42704", "3F000", "EPOCH_RESULT_MISMATCH")) Verdict.BREAKING else Verdict.UNKNOWN,
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
    }

    fun baselineFindings(queries: List<QueryContract>, plans: Map<String, QueryPlan>): List<Finding> =
        queries.mapNotNull { query -> plans.getValue(query.id).takeUnless { it.accepted }?.let { baselineFinding(query, it) } }

    private fun baselineFinding(query: QueryContract, previous: QueryPlan) = Finding(
        Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", query.id,
        "The old SQL contract could not be validated against the baseline.", previous.evidence,
        listOf(query.id), migration = null,
        mitigation = "Supply a matching baseline and complete PostgreSQL parameter types using JDBC ? placeholders.",
    )

    private fun canonicalType(connection: Connection, type: String): String? =
        connection.prepareStatement("SELECT to_regtype(?)::text").use { statement ->
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
}
