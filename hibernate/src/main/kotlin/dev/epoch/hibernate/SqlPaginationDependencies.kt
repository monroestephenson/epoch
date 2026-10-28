package dev.epoch.hibernate

import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.JdbcParameter
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.statement.select.PlainSelect
import java.util.Locale

/** Only pagination operands with no possible hidden database dependency. */
internal object SqlPaginationDependencies {
    fun independent(select: PlainSelect): Boolean {
        val limit = select.limit
        if (limit != null && (limit.isLimitAll || limit.isLimitNull || !limit.byExpressions.isNullOrEmpty() ||
                !scalar(limit.rowCount) || limit.offset?.let { !scalar(it) } == true)) return false
        val offset = select.offset
        if (offset != null && (!scalar(offset.offset) ||
                offset.offsetParam?.trim()?.uppercase(Locale.ROOT).orEmpty() !in setOf("", "ROW", "ROWS"))) return false
        val fetch = select.fetch
        if (fetch != null && (!scalar(fetch.expression) ||
                fetch.fetchParam.trim().uppercase(Locale.ROOT) !in setOf("ROW ONLY", "ROWS ONLY"))) return false
        return true
    }

    private fun scalar(expression: Expression?): Boolean = when (expression) {
        is JdbcParameter -> true
        is LongValue -> expression.bigIntegerValue.signum() >= 0
        else -> false
    }
}
