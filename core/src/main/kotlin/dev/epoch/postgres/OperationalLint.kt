package dev.epoch.postgres

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.index.CreateIndex
import java.nio.file.Files
import java.nio.file.Path

data class OperationalFinding(val filename: String, val ruleId: String, val message: String, val line: Int = 1)
data class OperationalReport(val schemaVersion: Int = 1, val findings: List<OperationalFinding>,
    val scope: String = "Operational review hints only; no compatibility verdict or runtime guarantee.")

/** Deliberately independent of compatibility rules: these hints never enter a safety verdict. */
class OperationalLint {
    fun inspect(directory: Path): OperationalReport {
        require(Files.isDirectory(directory)) { "Migration directory does not exist: $directory" }
        val files = Files.walk(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sql") }.sorted().toList()
        }
        val findings = files.flatMap { file ->
            val name = directory.relativize(file).toString().replace('\\', '/')
            fun hint(rule: String, message: String) = OperationalFinding(name, rule, message)
            val sql = Files.readString(file)
            try {
                // JSQLParser 5.1 omits PostgreSQL's CONCURRENTLY token. Normalize only
                // a leading CREATE INDEX clause for this advisory parser, preserving the hint.
                val concurrentPrefix = Regex("(?i)^(\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+)CONCURRENTLY\\s+")
                val startsConcurrent = concurrentPrefix.containsMatchIn(sql)
                val normalized = concurrentPrefix.replaceFirst(sql, "$1")
                val statements = CCJSqlParserUtil.parseStatements(normalized).statements
                buildList {
                    for ((index, statement) in statements.withIndex()) when (statement) {
                        is Alter -> add(hint("ALTER_TABLE_LOCK_REVIEW",
                            "ALTER TABLE can require an ACCESS EXCLUSIVE lock or a table rewrite, depending on its subcommand and PostgreSQL version. Review the exact operation, table size and rollout window."))
                        is CreateIndex -> {
                            val concurrent = startsConcurrent && index == 0
                            if (concurrent) {
                                add(hint("CONCURRENT_INDEX_TRANSACTION",
                                    "CREATE INDEX CONCURRENTLY must run outside a transaction. Provide executeInTransaction=false in the migration .sql.conf file; long-running transactions can delay the build."))
                            } else add(hint("INDEX_WRITE_LOCK_REVIEW",
                                "CREATE INDEX without CONCURRENTLY can block writes while building. Review table size and the deployment window."))
                        }
                    }
                    if (statements.isNotEmpty()) add(hint("OPERATIONAL_TIMEOUT_REVIEW",
                        "Choose production lock_timeout and statement_timeout explicitly. Epoch verification deadlines apply only to its disposable database."))
                }
            } catch (_: Exception) {
                listOf(hint("OPERATIONAL_SQL_UNPARSED", "Operational lint could not parse this SQL; inspect its lock, rewrite and timeout requirements manually."))
            }
        }.distinctBy { it.filename to it.ruleId }
        return OperationalReport(findings = findings)
    }
}
