package dev.epoch.hibernate

import dev.epoch.core.Operation
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.update.Update

/** Classification only: this never establishes dependency coverage or compatibility. */
internal object SqlOperationClassifier {
    fun operation(sql: String): Operation = when (val statement = CCJSqlParserUtil.parse(sql)) {
        is Select -> Operation.SELECT
        is Insert -> Operation.INSERT
        is Update -> Operation.UPDATE
        is Delete -> Operation.DELETE
        else -> throw IllegalArgumentException("Unsupported SQL operation ${statement.javaClass.simpleName}; inspect statements() and declare an unresolved query source")
    }
}
