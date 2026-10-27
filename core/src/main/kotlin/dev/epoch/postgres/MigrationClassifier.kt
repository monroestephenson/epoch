package dev.epoch.postgres

import dev.epoch.core.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.alter.AlterOperation
import net.sf.jsqlparser.statement.create.index.CreateIndex
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.drop.Drop

/** Parsing is used only to fence off unsupported effects, never to infer the resulting schema. */
class MigrationClassifier {
    fun findings(sql: String, migration: MigrationInfo): List<Finding> = try {
        val statements = CCJSqlParserUtil.parseStatements(sql).statements
        statements.mapNotNull { statement ->
            val supported = when (statement) {
                // ALTER/MODIFY/USING may rewrite values without changing the final catalog.
                // DROP can destroy data even when a later statement recreates the same shape.
                is Alter -> statement.alterExpressions.all { expression ->
                    expression.operation in setOf(AlterOperation.ADD, AlterOperation.RENAME,
                        AlterOperation.RENAME_TABLE, AlterOperation.RENAME_INDEX, AlterOperation.RENAME_KEY,
                        AlterOperation.RENAME_CONSTRAINT)
                }
                is CreateIndex -> true
                is CreateTable -> statement.select == null
                is Drop -> statement.type.uppercase() == "INDEX"
                else -> false
            }
            if (supported) null else unknown(migration, "Statement ${statement.javaClass.simpleName} has effects outside the supported structural model.")
        }.distinctBy { it.evidence }
    } catch (exception: Exception) {
        listOf(unknown(migration, "The SQL parser cannot classify this script: ${exception.javaClass.simpleName}. PostgreSQL still executes it in isolation."))
    }

    private fun unknown(migration: MigrationInfo, evidence: String) = Finding(
        verdict = Verdict.UNKNOWN, ruleId = "UNSUPPORTED_MIGRATION_EFFECT", objectName = migration.filename,
        message = "Data changes, procedural statements, and unsupported SQL require separate behavioral verification.",
        evidence = evidence, migration = migration,
        mitigation = "Review the migration's behavioral effects separately; a structural schema comparison cannot prove them safe.",
    )
}
