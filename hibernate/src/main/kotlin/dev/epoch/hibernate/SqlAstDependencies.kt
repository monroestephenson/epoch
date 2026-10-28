package dev.epoch.hibernate

import org.hibernate.sql.ast.spi.AbstractSqlAstWalker
import org.hibernate.sql.ast.tree.expression.ColumnReference
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression
import org.hibernate.sql.ast.tree.from.NamedTableReference
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate
import java.util.Collections
import java.util.IdentityHashMap

/** Hibernate's base walker redispatches selection references to themselves; visit their value instead. */
internal class SqlAstDependencies : AbstractSqlAstWalker() {
    val tables = linkedMapOf<String, String>()
    val columns = linkedSetOf<Pair<String, String>>()
    val limitations = linkedSetOf<String>()
    var complete = true
        private set
    private val activeSelections = Collections.newSetFromMap(IdentityHashMap<SqlSelectionExpression, Boolean>())

    override fun visitSqlSelectionExpression(expression: SqlSelectionExpression) {
        if (!activeSelections.add(expression)) {
            complete = false
            limitations += "Hibernate SQL AST contains a cyclic selection reference"
            return
        }
        try { expression.selection.expression.accept(this) }
        finally { activeSelections.remove(expression) }
    }

    override fun visitNamedTableReference(tableReference: NamedTableReference) {
        val key = tableReference.identificationVariable ?: tableReference.tableExpression
        val value = SqlDependencies.qualified(tableReference.tableExpression)
        if (tables.put(key, value)?.let { it != value } == true) complete = false
    }

    override fun visitColumnReference(columnReference: ColumnReference) {
        if (columnReference.isColumnExpressionFormula) complete = false
        else columns += (columnReference.qualifier ?: "") to SqlDependencies.identifier(columnReference.columnExpression)
    }

    override fun visitSelfRenderingExpression(expression: SelfRenderingExpression) { complete = false }
    override fun visitSelfRenderingPredicate(predicate: SelfRenderingPredicate) { complete = false }
}
