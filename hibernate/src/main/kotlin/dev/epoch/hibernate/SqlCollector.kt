package dev.epoch.hibernate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.*
import org.hibernate.resource.jdbc.spi.StatementInspector
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentSkipListSet

/** Install through hibernate.session_factory.statement_inspector in integration tests only.
 * Captures templates and call-site frames, never bind values. Observation is not exhaustive coverage.
 * -Depoch.sql.capture=/path/to/observations.jsonl persists observations for extract --runtime-sql.
 */
class SqlCollector : StatementInspector {
    private val statements = ConcurrentSkipListSet<String>()
    override fun inspect(sql: String): String {
        statements += sql
        System.getProperty("epoch.sql.capture")?.takeIf { it.isNotBlank() }?.let { output ->
            val frames = Thread.currentThread().stackTrace.filter { frame ->
                !frame.className.startsWith("java.") && !frame.className.startsWith("jdk.") &&
                    !frame.className.startsWith("org.hibernate.") && !frame.className.startsWith("dev.epoch.hibernate.SqlCollector")
            }.take(32).map { "${it.className}#${it.methodName}(${it.fileName ?: "unknown"}:${it.lineNumber})" }
            val record = RuntimeSqlObservation(sql = sql, callSites = frames)
            val path = Path.of(output).toAbsolutePath().normalize()
            synchronized(writeLock) {
                path.parent?.let(Files::createDirectories)
                Files.writeString(path, mapper.writeValueAsString(record) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
        return sql
    }
    fun statements(): List<String> = statements.toList()
    fun queries(): List<QueryContract> = statements().map { query(it) }
    fun clear() = statements.clear()

    companion object {
        private val mapper = jacksonObjectMapper()
        private val writeLock = Any()
        internal fun query(sql: String): QueryContract {
            val hash = MessageDigest.getInstance("SHA-256").digest(sql.toByteArray()).joinToString("") { "%02x".format(it) }
            val deps = SqlDependencies.inspect(sql)
            return QueryContract("runtime.$hash", SqlOperationClassifier.operation(sql), sql,
                tables = deps.tables, columns = deps.columns, writeColumns = deps.writes,
                source = QuerySource.RUNTIME_OBSERVED, verificationStatus = VerificationStatus.OBSERVED,
                dependenciesComplete = false, explicitColumnList = false, targetTable = deps.target)
        }
        fun read(path: Path): List<RuntimeSqlObservation> = Files.readAllLines(path).filter { it.isNotBlank() }.mapIndexed { index, line ->
            try { mapper.readValue<RuntimeSqlObservation>(line).also { require(it.schemaVersion == 1) { "Unsupported runtime observation schema" } } }
            catch (failure: Exception) { throw IllegalArgumentException("Invalid runtime SQL record at $path:${index + 1}", failure) }
        }.distinct().sortedWith(compareBy({ it.sql }, { it.callSites.joinToString() }))
    }
}

data class RuntimeSqlObservation(val schemaVersion: Int = 1, val sql: String, val callSites: List<String> = emptyList())
