package dev.epoch.postgres

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.statement.update.Update

/**
 * PostgreSQL resolves unconstrained unknown parameters to text. Only these explicit
 * syntax contexts establish that a text result came from a column or declared cast.
 * This is an ambiguity guard; PostgreSQL remains responsible for type resolution.
 */
internal object SqlParameterContexts {
    fun constrainedSlots(sql: String): Set<Int> = try {
        val slots = mutableSetOf<Int>()
        fun mark(expression: Expression?) {
            if (expression is JdbcParameter && expression.index != null) slots += expression.index - 1
        }
        val visitor = object : ExpressionVisitorAdapter<Unit>() {
            override fun <S> visitBinaryExpression(expression: BinaryExpression, context: S) {
                if (expression.leftExpression is Column) mark(expression.rightExpression)
                if (expression.rightExpression is Column) mark(expression.leftExpression)
                super.visitBinaryExpression(expression, context)
            }
            override fun <S> visit(expression: InExpression, context: S) {
                if (expression.leftExpression is Column) {
                    (expression.rightExpression as? ExpressionList<*>)?.forEach { mark(it) }
                }
                super.visit(expression, context)
            }
            override fun <S> visit(expression: CastExpression, context: S) {
                mark(expression.leftExpression)
                super.visit(expression, context)
            }
        }
        fun expression(item: Expression?) { item?.accept(visitor, Unit) }
        fun select(item: Select?) {
            when (item) {
                is PlainSelect -> {
                    item.selectItems.orEmpty().forEach { expression(it.expression) }
                    expression(item.where)
                    expression(item.having)
                    item.joins.orEmpty().forEach { join -> join.onExpressions.orEmpty().forEach { expression(it) } }
                }
                is ParenthesedSelect -> select(item.select)
                is SetOperationList -> item.selects.orEmpty().forEach { select(it) }
                else -> Unit
            }
        }
        when (val statement = CCJSqlParserUtil.parse(sql)) {
            is Select -> select(statement)
            is Insert -> {
                if (!statement.columns.isNullOrEmpty()) {
                    val values = (statement.select as? Values)?.expressions
                    val row = if (values?.singleOrNull() is ExpressionList<*>) values.single() as ExpressionList<*> else values
                    if (row?.size == statement.columns.size) row.forEach { mark(it) }
                }
                select(statement.select)
            }
            is Update -> {
                statement.updateSets.orEmpty().forEach { set ->
                    if (set.columns.size == set.values.size) set.values.forEach { mark(it) }
                }
                expression(statement.where)
            }
            is Delete -> expression(statement.where)
        }
        slots
    } catch (_: Exception) {
        emptySet()
    }
}
