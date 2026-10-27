package dev.epoch.hibernate

import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import dev.epoch.core.QuerySource
import dev.epoch.core.VerificationStatus
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.util.concurrent.ConcurrentSkipListSet

/** Install through hibernate.session_factory.statement_inspector in integration tests only.
 * Captures templates, never bind values. Observation does not establish exhaustive coverage.
 */
class SqlCollector : StatementInspector {
    private val statements = ConcurrentSkipListSet<String>()
    override fun inspect(sql: String): String { statements += sql; return sql }
    fun statements(): List<String> = statements.toList()
    fun queries(): List<QueryContract> = statements().mapIndexed { index, sql ->
        QueryContract("runtime.$index", SqlOperationClassifier.operation(sql), sql, source = QuerySource.RUNTIME_OBSERVED,
            verificationStatus = VerificationStatus.OBSERVED, dependenciesComplete = false, explicitColumnList = false)
    }
    fun clear() = statements.clear()
}
