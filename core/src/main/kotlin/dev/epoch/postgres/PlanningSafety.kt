package dev.epoch.postgres

import dev.epoch.core.*
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.AnalyticExpression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder

/** Eligibility is deliberately narrower than SQL acceptance: cached plans must not depend on data or opaque behavior. */
internal class PlanningSafety(private val snapshot: SchemaSnapshot) {
    private val syntax = mutableMapOf<String, Boolean>()
    private val opaque = snapshot.behaviors.keys.any { !it.startsWith("enum:") }

    fun eligible(query: QueryContract): Boolean {
        if (opaque || !query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED ||
            query.source == QuerySource.RUNTIME_OBSERVED) return false
        fun physicalTable(name: String) = name.split('.').joinToString(".", transform = ::postgresIdentifier)
        val tables = (query.tables + listOfNotNull(query.targetTable) + (query.columns + query.writeColumns).map { it.table })
            .map(::physicalTable).toSet()
        for (name in tables) {
            val table = snapshot.tables[name] ?: continue // Missing-object preparation failures are structural facts too.
            if (table.kind != "table" || table.constraints.values.any { it.type == "c" }) return false
            if (table.columns.values.any { !builtinType(it.type) && it.type !in snapshot.enumTypes && "public.${it.type}" !in snapshot.enumTypes }) return false
            if (query.operation == Operation.INSERT && (query.targetTable ?: query.tables.singleOrNull())?.let(::physicalTable) == name) {
                val supplied = query.writeColumns.map { postgresIdentifier(it.column) }.toSet()
                if (table.columns.any { (column, schema) -> column !in supplied && schema.defaultExpression != null &&
                        !RuleProofs.constantDefault(schema.defaultExpression, schema.type) &&
                        !schema.defaultExpression.startsWith("nextval('") }) return false
            }
        }
        return syntax.getOrPut(query.sql) { simpleSyntax(query.sql) }
    }

    private fun simpleSyntax(sql: String): Boolean = try {
        if (Regex("\\b(?:nextval|currval|setval|current_timestamp|current_date|current_time|localtimestamp|localtime|current_user|session_user)\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)) false
        else {
            val statement = CCJSqlParserUtil.parse(sql)
            var safe = statement is Select || statement is Insert || statement is Update || statement is Delete
            val visitor = object : TablesNamesFinder<Void>() {
                override fun <S : Any?> visit(function: Function, context: S): Void? {
                    // Aggregate/scalar names can be overloaded; function-bearing SQL is always rechecked.
                    safe = false
                    return super.visit(function, context)
                }
                override fun <S : Any?> visit(expression: AnalyticExpression, context: S): Void? { safe = false; return super.visit(expression, context) }
                override fun <S : Any?> visit(select: ParenthesedSelect, context: S): Void? { safe = false; return super.visit(select, context) }
                override fun <S : Any?> visit(select: SetOperationList, context: S): Void? { safe = false; return super.visit(select, context) }
                override fun <S : Any?> visit(withItem: WithItem<*>, context: S): Void? { safe = false; return super.visit(withItem, context) }
                override fun <S : Any?> visit(select: PlainSelect, context: S): Void? {
                    select.orderByElements.orEmpty().forEach { it.expression.accept(this, context) }
                    select.groupBy?.groupByExpressionList?.forEach { expression ->
                        if (expression is net.sf.jsqlparser.expression.Expression) expression.accept(this, context) else safe = false
                    }
                    select.distinct?.onSelectItems.orEmpty().forEach { it.expression.accept(this, context) }
                    if (select.limit != null || select.offset != null || select.fetch != null || select.qualify != null || !select.windowDefinitions.isNullOrEmpty()) safe = false
                    return super.visit(select, context)
                }
                override fun <S : Any?> visit(insert: Insert, context: S): Void? {
                    if (insert.select !is Values || insert.conflictAction != null || insert.conflictTarget != null) safe = false
                    insert.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return super.visit(insert, context)
                }
                override fun <S : Any?> visit(update: Update, context: S): Void? {
                    update.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return super.visit(update, context)
                }
                override fun <S : Any?> visit(delete: Delete, context: S): Void? {
                    delete.returningClause.orEmpty().forEach { it.expression.accept(this, context) }
                    return super.visit(delete, context)
                }
            }
            visitor.getTables(statement)
            safe
        }
    } catch (_: Exception) { false }

    private fun builtinType(type: String) = Regex(
        "(?:smallint|integer|bigint|real|double precision|numeric(?:\\(\\d+(?:,\\d+)?\\))?|boolean|text|character varying(?:\\(\\d+\\))?|character(?:\\(\\d+\\))?|uuid|bytea|json|jsonb|date|time(?:stamp)?(?:\\(\\d+\\))? (?:with|without) time zone|interval)(?:\\[\\])?"
    ).matches(type)
}
