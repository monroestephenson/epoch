package dev.epoch.postgres

import dev.epoch.core.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.alter.AlterExpression
import net.sf.jsqlparser.statement.alter.AlterOperation
import net.sf.jsqlparser.statement.create.index.CreateIndex
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.create.view.TemporaryOption
import net.sf.jsqlparser.statement.drop.Drop
import org.postgresql.core.Parser

/** Parsing fences off effects that a catalog diff cannot represent; PostgreSQL owns schema truth. */
class MigrationClassifier {
    fun findings(
        sql: String, migration: MigrationInfo,
        before: SchemaSnapshot? = null, after: SchemaSnapshot? = null,
        provenCompatibilityViews: Set<String> = emptySet(),
    ): List<Finding> = try {
        // PostgreSQL's lexer handles dollar quotes, comments and semicolons inside literals.
        val statements = Parser.parseJdbcSql(sql, true, false, true, false, false)
        statements.mapNotNull { parsed ->
            val text = parsed.nativeSql.trim()
            // JSQLParser 5.1 lacks PostgreSQL's CONCURRENTLY modifier. It does not change
            // index semantics: remove only this leading grammar token before parsing the AST.
            if (supportedEnumChange(text, before, after)) return@mapNotNull null
            val normalized = text.replaceFirst(
                Regex("(?is)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\s+"),
                "CREATE $1INDEX ",
            ).replace(Regex("(?is)\\bDROP\\s+IDENTITY\\s+IF\\s+EXISTS\\s*;?$"), "DROP IDENTITY")
            try {
                val statement = CCJSqlParserUtil.parse(normalized)
                val supported = when (statement) {
                    is Alter -> statement.alterExpressions.all { expression ->
                        supportedAlter(statement.table, expression, before, after)
                    }
                    is CreateIndex -> true
                    is CreateTable -> statement.select == null
                    is CreateView -> !statement.isMaterialized && statement.temporary == TemporaryOption.NONE &&
                        tableName(statement.view) in provenCompatibilityViews
                    is Drop -> when (statement.type.uppercase()) {
                        "INDEX" -> true
                        // A dropped table that remains absent is represented by its catalog delta.
                        // DROP + CREATE of the same name destroys data despite an identical schema.
                        "TABLE" -> before != null && after != null && tableName(statement.name) !in after.tables
                        else -> false
                    }
                    else -> false
                }
                if (supported) null else unknown(migration,
                    "Statement ${statement.javaClass.simpleName} has effects outside the supported structural model.")
            } catch (failure: Exception) {
                unknown(migration, "The SQL parser cannot classify a statement: ${failure.javaClass.simpleName}. PostgreSQL still executes it in isolation.")
            }
        }.distinctBy { it.evidence }
    } catch (failure: Exception) {
        listOf(unknown(migration, "The PostgreSQL SQL lexer cannot classify this script: ${failure.javaClass.simpleName}."))
    }

    private fun supportedAlter(table: Table, expression: AlterExpression, before: SchemaSnapshot?, after: SchemaSnapshot?): Boolean {
        val name = tableName(table)
        val old = before?.tables?.get(name)
        val new = after?.tables?.get(name)
        return when (expression.operation) {
            AlterOperation.ADD, AlterOperation.RENAME, AlterOperation.RENAME_TABLE,
            AlterOperation.RENAME_INDEX, AlterOperation.RENAME_KEY, AlterOperation.RENAME_CONSTRAINT -> true
            AlterOperation.DROP -> {
                if (before == null || after == null) false
                else if (expression.columnName != null) identifier(expression.columnName) !in new?.columns.orEmpty()
                else expression.constraintName != null && identifier(expression.constraintName) !in new?.constraints.orEmpty()
            }
            AlterOperation.ALTER -> {
                // These ALTER COLUMN forms only change catalog properties already classified by
                // explicit rules. USING expressions may transform data and are never waived.
                val columns = expression.colDataTypeList.orEmpty()
                columns.isNotEmpty() && columns.all { column ->
                    val tokens = column.toString().removePrefix(column.columnName).trim().uppercase()
                    val columnName = identifier(column.columnName)
                    when {
                        tokens in setOf("SET NOT NULL", "DROP NOT NULL", "DROP DEFAULT", "DROP IDENTITY", "DROP IDENTITY IF EXISTS") -> true
                        tokens.startsWith("SET DEFAULT ") -> true
                        tokens.startsWith("TYPE ") && !Regex("\\b(?:USING|COLLATE)\\b").containsMatchIn(tokens) && old != null && new != null -> {
                            val a = old.columns[columnName]
                            val b = new.columns[columnName]
                            // Identity conversions and supported widenings preserve stored values.
                            a != null && b != null && (a.type == b.type || RuleProofs.widening(a.type, b.type))
                        }
                        else -> false
                    }
                }
            }
            else -> false
        }
    }

    /** A finite PostgreSQL enum operation omitted by the general-purpose AST parser. */
    private fun supportedEnumChange(sql: String, before: SchemaSnapshot?, after: SchemaSnapshot?): Boolean {
        if (before == null || after == null) return false
        val id = "(?:\"(?:[^\"]|\"\")*\"|[A-Za-z_][A-Za-z_0-9$]*)"
        val name = "(?:(?<schema>$id)\\.)?(?<name>$id)"
        val literal = "'(?:[^']|'')*'"
        val add = Regex("^ALTER\\s+TYPE\\s+$name\\s+ADD\\s+VALUE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+(?<value>$literal)(?:\\s+(?:BEFORE|AFTER)\\s+$literal)?\\s*;?$", RegexOption.IGNORE_CASE).matchEntire(sql)
        val rename = Regex("^ALTER\\s+TYPE\\s+$name\\s+RENAME\\s+VALUE\\s+(?<old>$literal)\\s+TO\\s+(?<value>$literal)\\s*;?$", RegexOption.IGNORE_CASE).matchEntire(sql)
        val match = add ?: rename ?: return false
        val type = identifier(match.groups["schema"]?.value ?: "public") + "." + identifier(match.groups["name"]!!.value)
        val old = before.enumTypes[type] ?: return false
        val new = after.enumTypes[type] ?: return false
        fun value(text: String) = text.removeSurrounding("'").replace("''", "'")
        val added = value(match.groups["value"]!!.value)
        return if (add != null) old.all { it in new } && new.toSet() == old.toSet() + added
        else new == old.map { if (it == value(match.groups["old"]!!.value)) added else it }
    }

    private fun tableName(table: Table): String =
        identifier(table.schemaName ?: "public") + "." + identifier(table.name)

    private fun identifier(value: String): String = postgresIdentifier(
        if (value.startsWith('"') && value.endsWith('"')) value.removeSurrounding("\"").replace("\"\"", "\"") else value.lowercase(),
    )

    private fun unknown(migration: MigrationInfo, evidence: String) = Finding(
        verdict = Verdict.UNKNOWN, ruleId = "UNSUPPORTED_MIGRATION_EFFECT", objectName = migration.filename,
        message = "Data changes, procedural statements, and unsupported SQL require separate behavioral verification.",
        evidence = evidence, migration = migration,
        mitigation = "Review the migration's behavioral effects separately; a structural schema comparison cannot prove them safe.",
    )
}
