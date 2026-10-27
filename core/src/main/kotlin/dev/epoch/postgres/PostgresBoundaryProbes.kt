package dev.epoch.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.*
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.Values
import org.postgresql.core.Parser
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types

internal data class BoundaryProbe(
    val query: QueryContract,
    val table: String,
    val columns: List<String>,
    val typedSql: String,
    val rows: List<List<String?>>,
    val description: String,
)

internal data class BoundaryProbeBaseline(
    val probes: List<BoundaryProbe>,
    val attempted: Int,
    val skipped: Map<String, String>,
) {
    val summary: String get() = "Boundary execution retained ${probes.size} baseline-accepted INSERT witnesses from $attempted attempts; " +
        "${skipped.size} INSERT templates were skipped. Samples can demonstrate incompatibility but never establish SAFE. " +
        "Supported probes use rollback and exclude generated identifiers, nonconstant defaults, custom database behavior, and unsupported SQL/types."
}

/**
 * Optional counterexample search, never a safety proof. Inputs come only from the
 * declared contract and the disposable baseline; each candidate must actually work
 * there before a migrated database rejection can be called a regression.
 */
internal class PostgresBoundaryProbes {
    fun collectBaseline(
        connection: Connection,
        contract: DatabaseContract,
        schema: SchemaSnapshot,
        plans: Map<String, QueryPlan>,
    ): BoundaryProbeBaseline {
        val accepted = mutableListOf<BoundaryProbe>()
        val skipped = sortedMapOf<String, String>()
        var attempts = 0
        val duplicateIds = contract.queries.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        for (query in contract.queries.filter { it.operation == Operation.INSERT }) {
            if (query.id in duplicateIds) { skipped[query.id] = "Duplicate contract IDs prevent unambiguous probe attribution."; continue }
            val plan = plans[query.id]
            if (plan?.accepted != true) {
                skipped[query.id] = "Baseline SQL planning was not established."
                continue
            }
            val shape = insertShape(query)
            if (shape == null) {
                skipped[query.id] = "Only single-row INSERT with explicit columns and direct JDBC parameters is supported."
                continue
            }
            val (tableName, columns) = shape
            val table = schema.tables[tableName]
            val unsafe = unsafeReason(schema, tableName)
            if (table == null || unsafe != null) {
                skipped[query.id] = unsafe ?: "Target table is absent."
                continue
            }
            val mapping = contract.entities.filter { physicalTable(it.table) == tableName }.flatMap { it.columns }.associateBy { postgresIdentifier(it.name) }
            val candidates = columns.map { name ->
                table.columns[name]?.let { values(it, mapping[name], schema) }
            }
            if (candidates.any { it.isNullOrEmpty() } || columns.size != plan.parameterTypes.size) {
                skipped[query.id] = "A mapped column/input type has no bounded deterministic probe generator."
                continue
            }
            val values = candidates.map { requireNotNull(it) }
            val base = values.map { it.first() }.toMutableList()
            // Reuse an actual deterministic FK target from the synthetic baseline when
            // one exists. Empty target tables simply leave candidates unestablished.
            table.constraints.values.filter { it.type == "f" }.forEach { constraint ->
                if (constraint.referencedTable != null && constraint.columns.isNotEmpty() &&
                    constraint.columns.size == constraint.referencedColumns.size && constraint.columns.all { it in columns }) {
                    val target = foreignKeyValues(connection, constraint)
                    if (target != null && target.withIndex().all { (index, value) ->
                        val mapped = mapping[constraint.columns[index]]
                        value == null && mapped?.nullable != false || value != null &&
                            (mapped?.enumValues.isNullOrEmpty() || value in mapped!!.enumValues) &&
                            (mapped?.length == null || value.length.toLong() <= mapped.length)
                    }) target.forEachIndexed { index, value -> base[columns.indexOf(constraint.columns[index])] = value }
                }
            }
            val typedSql = typedSql(query, plan.parameterTypes)
            if (typedSql == null) {
                skipped[query.id] = "SQL parameters could not be bound to the baseline PostgreSQL types."
                continue
            }
            val specimens = mutableListOf("representative values" to listOf(base.toList()))
            for ((index, choices) in values.withIndex()) {
                for (value in choices) {
                    if (value == base[index]) continue
                    val row = base.toMutableList().also { it[index] = value }
                    specimens += "boundary ${columns[index]}" to listOf(row)
                }
            }
            val keys = table.constraints.values.filter { it.type == "p" }.flatMap { it.columns }
            val second = base.toMutableList()
            var distinctKey = keys.isEmpty()
            for (key in keys) {
                val index = columns.indexOf(key)
                if (index >= 0) {
                    val alternative = values[index].firstOrNull { it != null && it != base[index] }
                    if (alternative != null) { second[index] = alternative; distinctKey = true; break }
                }
            }
            if (distinctKey) specimens += "duplicate non-key values across two rows" to listOf(base.toList(), second)
            for ((description, rows) in specimens.distinctBy { it.second }.take(MAX_PROBES_PER_QUERY)) {
                val probe = BoundaryProbe(query, tableName, columns, typedSql, rows, description)
                attempts++
                if (execute(connection, probe) == null) accepted += probe
            }
            if (accepted.none { it.query.id == query.id }) skipped[query.id] = "No synthesized candidate was accepted by the baseline; no counterexample is established."
        }
        return BoundaryProbeBaseline(accepted, attempts, skipped)
    }

    fun findings(
        connection: Connection,
        baseline: BoundaryProbeBaseline,
        schema: SchemaSnapshot,
        migration: MigrationInfo,
    ): List<Finding> {
        val found = mutableSetOf<String>()
        return baseline.probes.mapNotNull { probe ->
            if (probe.query.id in found || unsafeReason(schema, probe.table) != null) return@mapNotNull null
            val failure = execute(connection, probe) ?: return@mapNotNull null
            // Planning handles absent objects. Runtime witnesses focus on rejected
            // input/constraint domains, not unavailable infrastructure or unsupported execution.
            if (failure.sqlState?.take(2) !in setOf("22", "23") && failure.sqlState != "54000") return@mapNotNull null
            found += probe.query.id
            Finding(Verdict.BREAKING, "BOUNDARY_EXECUTION_REJECTED", probe.table,
                "An old INSERT input accepted at baseline is rejected after migration.",
                "${probe.description}; SQL=${probe.query.sql}; typed SQL=${probe.typedSql}; " +
                    "rows=${jacksonObjectMapper().writeValueAsString(probe.rows)}; SQLSTATE ${failure.sqlState}: ${failure.message}",
                listOf(probe.query.id), migration,
                "Preserve this previously accepted input or deploy an explicitly compatible application contract before constraining it.")
        }
    }

    private fun execute(connection: Connection, probe: BoundaryProbe): SQLException? {
        val wasAutoCommit = connection.autoCommit
        if (wasAutoCommit) connection.autoCommit = false
        val savepoint = connection.setSavepoint()
        var primaryFailure: Throwable? = null
        try {
            connection.prepareStatement(probe.typedSql).use { statement ->
                probe.rows.forEach { row ->
                    row.forEachIndexed { index, value ->
                        if (value == null) statement.setNull(index + 1, Types.VARCHAR) else statement.setString(index + 1, value)
                    }
                    statement.executeUpdate()
                }
            }
            return null
        } catch (failure: SQLException) {
            if (isInfrastructureFailure(failure)) { primaryFailure = failure; throw failure }
            return failure
        } finally {
            val cleanupFailure = runCatching {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
                if (wasAutoCommit) { connection.rollback(); connection.autoCommit = true }
            }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun foreignKeyValues(connection: Connection, constraint: ConstraintSchema): List<String?>? {
        val relation = constraint.referencedTable ?: return null
        val columns = constraint.referencedColumns.map(::quote)
        if (relation.split('.').size != 2 || columns.isEmpty()) return null
        val sql = "SELECT ${columns.joinToString(",")} FROM ${relation.split('.').joinToString(".", transform = ::quote)} " +
            "WHERE ${columns.joinToString(" AND ") { "$it IS NOT NULL" }} ORDER BY ${columns.joinToString(",")} LIMIT 1"
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                if (!result.next()) null else columns.indices.map { result.getString(it + 1) }
            }
        }
    }

    private fun unsafeReason(schema: SchemaSnapshot, tableName: String): String? {
        if (schema.behaviors.keys.any { !it.startsWith("enum:") }) return "Database-side behavior cannot be safely executed as a bounded rollback probe."
        if (schema.extensions.keys.any { it !in setOf("plpgsql", "pgcrypto", "pg_trgm") }) return "An installed extension may have unmodeled execution effects."
        if (schema.tables.values.any { relation -> relation.constraints.values.any { "; deferrable=true" in it.definition } })
            return "Deferred constraints cannot be isolated from the migration's own transaction state."
        val table = schema.tables[tableName] ?: return "Target table is absent."
        if (table.kind != "table") return "View/partitioned-table writes are outside execution probe support."
        if (table.columns.values.any { !it.identity.isNullOrEmpty() || !it.generated.isNullOrEmpty() }) return "Generated/identity columns may have effects that transaction rollback cannot undo."
        if (table.columns.values.any { it.defaultExpression != null && !constantDefault(it.defaultExpression) }) return "Nonconstant defaults may have effects that transaction rollback cannot undo."
        if (table.constraints.values.any { it.type == "c" && !safeCheck(it.definition) }) return "A CHECK expression is outside the execution probe expression whitelist."
        if (table.columns.values.any { !supportedType(it.type, schema) }) return "An unsupported/custom column type may invoke unverified input or cast functions."
        return null
    }

    private fun values(column: ColumnSchema, mapping: ColumnContract?, schema: SchemaSnapshot): List<String?>? {
        val enum = mapping?.enumValues?.takeIf { it.isNotEmpty() } ?: enumValues(column.type, schema)
        val type = column.type.lowercase()
        val values: List<String> = when {
            enum != null -> enum.take(MAX_PROBES_PER_QUERY)
            type in setOf("smallint", "int2") -> if (mapping?.typeName?.uppercase() == "TINYINT")
                listOf("1", "2", "0", "-1", Byte.MIN_VALUE.toString(), Byte.MAX_VALUE.toString())
                else listOf("1", "2", "0", "-1", Short.MIN_VALUE.toString(), Short.MAX_VALUE.toString())
            type in setOf("integer", "int", "int4") -> listOf("1", "2", "0", "-1", Int.MIN_VALUE.toString(), Int.MAX_VALUE.toString())
            type in setOf("bigint", "int8") -> listOf("1", "2", "0", "-1", Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString())
            type.startsWith("numeric") || type.startsWith("decimal") -> {
                val modifiers = Regex("(?:numeric|decimal)\\((\\d+),(\\d+)\\)").matchEntire(type)
                val precision = mapping?.precision ?: modifiers?.groupValues?.get(1)?.toIntOrNull()
                val scale = mapping?.scale ?: modifiers?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val boundary = if (precision != null && precision in 1..1000 && scale in 0..precision)
                    BigDecimal.TEN.pow(precision - scale).subtract(BigDecimal.ONE.scaleByPowerOfTen(-scale)).toPlainString() else null
                listOfNotNull("1", "2", "0", "-1", boundary, boundary?.let { "-$it" })
            }
            type == "text" || type.startsWith("character varying") || type.startsWith("varchar") || type.startsWith("character(") || type == "character" || type.startsWith("char(") || type == "char" -> {
                val size = mapping?.length ?: Regex(".*\\((\\d+)\\)").matchEntire(type)?.groupValues?.get(1)?.toLongOrNull()
                val limit = (size ?: 4096L).coerceIn(1, 8192).toInt()
                listOf("e", "f", "", "x".repeat(limit), variedString(limit))
            }
            type == "boolean" || type == "bool" -> listOf("true", "false")
            type == "uuid" -> listOf("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002")
            type == "date" -> listOf("2000-01-01", "2000-01-02")
            type == "json" || type == "jsonb" -> listOf("{}", "[]")
            else -> return null
        }
        return (values + if (column.nullable && mapping?.nullable != false) listOf(null) else emptyList()).distinct()
    }

    /** Fixed SHA-256 blocks exercise size limits without relying on compressible repeated characters. */
    private fun variedString(length: Int): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return buildString {
            var block = 0
            while (this.length < length) append(digest.digest("epoch-boundary:${block++}".toByteArray()).joinToString("") { "%02x".format(it) })
        }.take(length)
    }

    private fun enumValues(type: String, schema: SchemaSnapshot): List<String>? {
        val name = type.split('.').joinToString(".", transform = ::unquote)
        return schema.enumTypes[name] ?: schema.enumTypes["public.$name"]
    }
    private fun supportedType(type: String, schema: SchemaSnapshot) = enumValues(type, schema) != null ||
        type.lowercase() in setOf("smallint", "int2", "integer", "int", "int4", "bigint", "int8", "numeric", "decimal", "text", "varchar", "character varying", "character", "char", "boolean", "bool", "uuid", "date", "json", "jsonb") ||
        Regex("(numeric|decimal)\\(\\d+,\\d+\\)|(character varying|varchar|character|char)\\(\\d+\\)").matches(type.lowercase())

    private fun typedSql(query: QueryContract, types: List<String>): String? {
        val parsed = Parser.parseJdbcSql(query.sql, true, true, true, false, false).singleOrNull() ?: return null
        if (parsed.bindPositions.size != types.size) return null
        return buildString {
            var offset = 0
            parsed.bindPositions.forEachIndexed { index, position ->
                append(parsed.nativeSql.substring(offset, position))
                append("CAST(? AS ${types[index]})")
                offset = position + (index + 1).toString().length + 1
            }
            append(parsed.nativeSql.substring(offset))
        }
    }

    private fun insertShape(query: QueryContract): Pair<String, List<String>>? = try {
        if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED) null
        else {
            val insert = CCJSqlParserUtil.parse(query.sql) as? Insert
            if (insert == null || !insert.withItemsList.isNullOrEmpty() || insert.returningClause != null ||
                insert.conflictAction != null || !insert.duplicateUpdateSets.isNullOrEmpty() || insert.columns.isNullOrEmpty()) null
            else {
                val values = (insert.select as? Values)?.expressions
                val row = if (values?.singleOrNull() is ExpressionList<*>) values.single() as ExpressionList<*> else values
                if (row == null || row.size != insert.columns.size || row.withIndex().any { (index, value) ->
                    value !is JdbcParameter || value.index != index + 1
                }) null else {
                    val name = listOf(insert.table.schemaName ?: "public", insert.table.name).joinToString(".") { postgresIdentifier(unquote(it)) }
                    if (name.split('.').size != 2 || query.targetTable != null && name != physicalTable(query.targetTable)) null
                    else name to insert.columns.map { postgresIdentifier(unquote(it.columnName)) }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun constantDefault(expression: String): Boolean = try {
        fun constant(value: Expression): Boolean = when (value) {
            is NullValue, is StringValue, is LongValue, is DoubleValue, is BooleanValue -> true
            is SignedExpression -> constant(value.expression)
            is CastExpression -> constant(value.leftExpression) && value.colDataType.toString().lowercase() in setOf("text", "integer", "bigint", "smallint", "numeric", "boolean", "uuid", "date", "json", "jsonb")
            else -> false
        }
        constant(CCJSqlParserUtil.parseExpression(expression))
    } catch (_: Exception) { false }

    private fun safeCheck(definition: String): Boolean = try {
        val expression = definition.substringBefore("; validated=").removePrefix("CHECK ")
        var safe = true
        val visitor = object : ExpressionVisitorAdapter<Unit>() {
            override fun <S> visit(function: Function, context: S) {
                if (function.name.lowercase() !in setOf("length", "char_length", "octet_length", "lower", "upper", "abs", "btrim", "ltrim", "rtrim")) safe = false
                super.visit(function, context)
            }
            override fun <S> visit(expression: net.sf.jsqlparser.statement.select.ParenthesedSelect, context: S) { safe = false }
            override fun <S> visit(expression: NextValExpression, context: S) { safe = false }
            override fun <S> visit(expression: TimeKeyExpression, context: S) { safe = false }
            override fun <S> visit(expression: CastExpression, context: S) {
                if (!supportedType(expression.colDataType.toString(), SchemaSnapshot())) safe = false
                super.visit(expression, context)
            }
        }
        CCJSqlParserUtil.parseCondExpression(expression).accept(visitor, Unit)
        safe
    } catch (_: Exception) { false }

    private fun physicalTable(name: String) = name.split('.').joinToString(".", transform = ::postgresIdentifier)
    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun unquote(value: String) = if (value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1).replace("\"\"", "\"") else value.lowercase()

    companion object { private const val MAX_PROBES_PER_QUERY = 128 }
}
