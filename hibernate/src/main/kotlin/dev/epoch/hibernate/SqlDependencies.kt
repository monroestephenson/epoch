package dev.epoch.hibernate

import dev.epoch.core.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.ParenthesedSelect
import net.sf.jsqlparser.statement.select.WithItem
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder

/** Resolves the SQL emitted by Hibernate, rather than assigning every mapped column to every SQL table. */
internal object SqlDependencies {
    data class Dependencies(val tables: List<String>, val columns: List<ColumnReference>,
                            val writes: List<ColumnReference>, val target: String?, val complete: Boolean)

    fun inspect(sql: String, defaultSchema: String = "public"): Dependencies {
        return try {
            val statement = CCJSqlParserUtil.parse(sql)
            val aliases = linkedMapOf<String, String>()
            val references = mutableListOf<Pair<String?, String>>()
            var complete = true
            val visitor = object : TablesNamesFinder<Void>() {
                override fun <S : Any?> visit(table: Table, context: S): Void? {
                    val name = qualified(table.fullyQualifiedName, defaultSchema)
                    val alias = table.alias?.name?.let(::identifier) ?: identifier(table.name)
                    if (aliases.put(alias, name)?.let { it != name } == true) complete = false
                    aliases[name] = name
                    return super.visit(table, context)
                }
                override fun <S : Any?> visit(column: Column, context: S): Void? {
                    references += column.table?.fullyQualifiedName?.takeIf { it.isNotBlank() }?.let { value ->
                        if ('.' in value) qualified(value, defaultSchema) else identifier(value)
                    } to identifier(column.columnName)
                    return null
                }
                override fun <S : Any?> visit(column: AllColumns, context: S): Void? { complete = false; return null }
                override fun <S : Any?> visit(column: AllTableColumns, context: S): Void? { complete = false; return null }
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
                is Insert -> qualified(statement.table.fullyQualifiedName, defaultSchema)
                is Update -> qualified(statement.table.fullyQualifiedName, defaultSchema)
                is Delete -> qualified(statement.table.fullyQualifiedName, defaultSchema)
                else -> null
            }
            fun resolve(alias: String?, column: String): ColumnReference? {
                val table = if (alias == null) target ?: tables.singleOrNull() else aliases[alias]
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
            Dependencies(tables, columns, writes.distinct().sortedWith(compareBy({ it.table }, { it.column })), target, complete)
        } catch (_: Exception) { Dependencies(emptyList(), emptyList(), emptyList(), null, false) }
    }

    fun identifier(value: String): String {
        val text = if (value.startsWith("\"") && value.endsWith("\"")) value.removeSurrounding("\"").replace("\"\"", "\"")
            else value.lowercase(java.util.Locale.ROOT)
        return postgresIdentifier(text)
    }
    fun qualified(value: String, defaultSchema: String = "public"): String =
        if ('.' in value) value.split('.').joinToString(".", transform = ::identifier) else "${identifier(defaultSchema)}.${identifier(value)}"
}
