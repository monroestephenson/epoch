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
)

/** PREPARE plus EXPLAIN, never EXECUTE/ANALYZE: DML is planned but not run. */
internal class PostgresQueryPlanner {
    private val nextId = AtomicLong()

    fun inspect(connection: Connection, query: QueryContract): QueryPlan {
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
            val types = query.parameterTypes.map { type -> canonicalType(connection, type)
                ?: return QueryPlan(false, "Unknown PostgreSQL parameter type: $type") }
            val typeList = if (types.isEmpty()) "" else types.joinToString(",", "(", ")")
            val values = if (types.isEmpty()) "" else types.joinToString(",", "(", ")") { "NULL" }
            connection.createStatement().use { statement ->
                statement.execute("PREPARE $name$typeList AS ${native.nativeSql}")
                prepared = true
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
                    "EPOCH_RESULT_MISMATCH", results)
            }
            QueryPlan(true, "PostgreSQL PREPARE, EXPLAIN, and Describe accepted the declared SQL template and parameter types.", results = results)
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
        val current = inspect(connection, query)
        when {
            !previous.accepted -> Finding(
                Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", query.id,
                "The old SQL contract could not be validated against the baseline.", previous.evidence,
                listOf(query.id), migration,
                "Supply a matching baseline and complete PostgreSQL parameter types using JDBC ? placeholders.",
            )
            !current.accepted -> Finding(
                if (current.sqlState in setOf("42P01", "42703", "42883", "42804", "3F000", "EPOCH_RESULT_MISMATCH")) Verdict.BREAKING else Verdict.UNKNOWN,
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

    private fun canonicalType(connection: Connection, type: String): String? =
        connection.prepareStatement("SELECT to_regtype(?)::text").use { statement ->
            statement.setString(1, type)
            statement.executeQuery().use { result -> result.next(); result.getString(1) }
        }
}
