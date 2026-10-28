package dev.epoch.hibernate

import dev.epoch.core.*
import net.sf.jsqlparser.expression.AnalyticExpression
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.ParenthesedFromItem
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.SetOperationList
import net.sf.jsqlparser.statement.select.Join
import net.sf.jsqlparser.statement.select.TableFunction
import net.sf.jsqlparser.statement.select.Values
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder

/** Resolves the SQL emitted by Hibernate, rather than assigning every mapped column to every SQL table. */
internal object SqlDependencies {
    data class Dependencies(val tables: List<String>, val columns: List<ColumnReference>,
                            val writes: List<ColumnReference>, val target: String?, val complete: Boolean,
                            val explicitColumnList: Boolean = true, val failureReason: String? = null)

    fun inspect(sql: String, defaultSchema: String = "public", nativeSql: Boolean = false, sourceId: String? = null): Dependencies {
        return try {
            val statement = CCJSqlParserUtil.parse(sql)
            val aliases = linkedMapOf<String, String>()
            val references = mutableListOf<Pair<String?, String>>()
            var complete = statement is Select || statement is Insert || statement is Update || statement is Delete
            var explicitColumns = statement !is Insert || !statement.columns.isNullOrEmpty()
            // Hibernate's default_schema qualifies generated ORM SQL; it does not set native search_path.
            // The current native model uses the verifier's public schema and cannot infer a custom path.
            val sqlDefaultSchema = if (nativeSql) "public" else defaultSchema
            fun tableName(table: Table) = qualified(table.fullyQualifiedName, sqlDefaultSchema)
            if (nativeSql && statement is Insert && (statement.columns.isNullOrEmpty() || statement.select !is Values ||
                    statement.conflictAction != null || statement.conflictTarget != null)) complete = false
            val visitor = object : TablesNamesFinder<Void>() {
                private fun checkJoins(joins: List<Join>?) {
                    if (joins.orEmpty().any { it.isNatural || !it.usingColumns.isNullOrEmpty() }) complete = false
                }

                override fun <S : Any?> visit(select: PlainSelect, context: S): Void? {
                    checkJoins(select.joins)
                    // TablesNamesFinder intentionally omits these expression-bearing clauses.
                    // Read supported clauses explicitly; never infer completeness from table discovery alone.
                    val result = super.visit(select, context)
                    val outputAliases = select.selectItems.orEmpty().filter { it.alias != null }
                        .groupBy { identifier(it.alias.name) }
                    fun visitClause(expression: Expression, allowOutputAlias: Boolean) {
                        val name = (expression as? Column)?.takeIf { it.table?.fullyQualifiedName.isNullOrBlank() }
                            ?.columnName?.let(::identifier)
                        val matching = name?.let(outputAliases::get)
                        if (matching != null) {
                            if (allowOutputAlias && matching.size == 1) matching.single().expression.accept(this, context)
                            else complete = false // GROUP BY input/output precedence needs catalog evidence.
                        } else expression.accept(this, context)
                    }
                    select.orderByElements.orEmpty().forEach { visitClause(it.expression, true) }
                    select.groupBy?.groupByExpressionList?.forEach { expression ->
                        if (expression is Expression) visitClause(expression, false) else complete = false
                    }
                    if (!select.groupBy?.groupingSets.isNullOrEmpty()) complete = false
                    select.distinct?.onSelectItems.orEmpty().forEach { visitClause(it.expression, false) }
                    if (select.limit != null || select.limitBy != null || select.offset != null || select.fetch != null ||
                        !select.windowDefinitions.isNullOrEmpty() || select.qualify != null) complete = false
                    return result
                }

                override fun <S : Any?> visit(insert: Insert, context: S): Void? {
                    val result = super.visit(insert, context)
                    insert.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return result
                }

                override fun <S : Any?> visit(update: Update, context: S): Void? {
                    checkJoins(update.joins)
                    checkJoins(update.startJoins)
                    val result = super.visit(update, context)
                    update.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return result
                }

                override fun <S : Any?> visit(delete: Delete, context: S): Void? {
                    checkJoins(delete.joins)
                    val result = super.visit(delete, context)
                    delete.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return result
                }

                override fun <S : Any?> visit(expression: AnalyticExpression, context: S): Void? {
                    complete = false // Named windows, frames and custom window functions are not modeled.
                    return super.visit(expression, context)
                }

                override fun <S : Any?> visit(select: SetOperationList, context: S): Void? {
                    complete = false // Branch aliases and outer ORDER BY require separate lexical scopes.
                    return super.visit(select, context)
                }

                override fun <S : Any?> visit(item: ParenthesedFromItem, context: S): Void? {
                    complete = false // Parenthesized join groups can contain implicit/renamed dependencies.
                    return super.visit(item, context)
                }

                override fun <S : Any?> visit(function: Function, context: S): Void? {
                    // Native functions may conceal table/sequence access. Only these built-in scalar/aggregate
                    // functions have a supported direct dependency model; database behavior is checked separately.
                    if (nativeSql && function.name.lowercase(java.util.Locale.ROOT) !in setOf(
                            "count", "min", "max", "sum", "avg", "lower", "upper", "length", "char_length", "coalesce", "nullif", "abs")) complete = false
                    return super.visit(function, context)
                }
                override fun <S : Any?> visit(function: TableFunction, context: S): Void? {
                    complete = false
                    return super.visit(function, context)
                }
                override fun <S : Any?> visit(table: Table, context: S): Void? {
                    if (nativeSql && table.schemaName.isNullOrBlank() && identifier(defaultSchema) != "public") complete = false
                    val name = tableName(table)
                    val alias = table.alias?.name?.let(::identifier) ?: identifier(table.name)
                    if (aliases.put(alias, name)?.let { it != name } == true) complete = false
                    aliases[name] = name
                    return super.visit(table, context)
                }
                override fun <S : Any?> visit(column: Column, context: S): Void? {
                    references += column.table?.fullyQualifiedName?.takeIf { it.isNotBlank() }?.let { value ->
                        if ('.' in value) qualified(value, sqlDefaultSchema) else identifier(value)
                    } to identifier(column.columnName)
                    return null
                }
                override fun <S : Any?> visit(column: AllColumns, context: S): Void? { complete = false; explicitColumns = false; return null }
                override fun <S : Any?> visit(column: AllTableColumns, context: S): Void? { complete = false; explicitColumns = false; return null }
                override fun <S : Any?> visit(select: ParenthesedSelect, context: S): Void? {
                    // Nested aliases require lexical scopes; retain factual dependencies but do not claim completeness.
                    complete = false
                    return super.visit(select, context)
                }
                override fun <S : Any?> visit(withItem: WithItem<*>, context: S): Void? {
                    complete = false
                    return super.visit(withItem, context)
                }
            }
            visitor.getTables(statement)
            val tables = aliases.values.distinct().sorted()
            val target = when (statement) {
                is Insert -> tableName(statement.table)
                is Update -> tableName(statement.table)
                is Delete -> tableName(statement.table)
                else -> null
            }
            fun resolve(alias: String?, column: String): ColumnReference? {
                val table = if (alias == null) {
                    if (nativeSql && tables.size > 1) null else target ?: tables.singleOrNull()
                } else aliases[alias]
                if (table == null) complete = false
                return table?.let { ColumnReference(it, column) }
            }
            val writes = when (statement) {
                is Insert -> statement.columns.orEmpty().mapNotNull { resolve(null, identifier(it.columnName)) }
                is Update -> statement.updateSets.orEmpty().flatMap { it.columns }.mapNotNull { resolve(null, identifier(it.columnName)) }
                else -> emptyList()
            }
            val columns = (references.mapNotNull { (alias, column) -> resolve(alias, column) } + writes)
                .distinct().sortedWith(compareBy({ it.table }, { it.column }))
            Dependencies(tables, columns, writes.distinct().sortedWith(compareBy({ it.table }, { it.column })), target, complete, explicitColumns)
        } catch (error: Throwable) {
            Dependencies(emptyList(), emptyList(), emptyList(), null, false,
                failureReason = QueryExtraction.failure(sourceId ?: "unattributed SQL", "SQL dependency parsing failed", error))
        }
    }

    fun identifier(value: String): String {
        val text = if (value.startsWith("\"") && value.endsWith("\"")) value.removeSurrounding("\"").replace("\"\"", "\"")
            else value.lowercase(java.util.Locale.ROOT)
        return postgresIdentifier(text)
    }
    fun qualified(value: String, defaultSchema: String = "public"): String =
        if ('.' in value) value.split('.').joinToString(".", transform = ::identifier) else "${identifier(defaultSchema)}.${identifier(value)}"
}
