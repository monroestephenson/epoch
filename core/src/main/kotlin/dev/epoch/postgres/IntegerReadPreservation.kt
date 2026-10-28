package dev.epoch.postgres

import dev.epoch.core.*

/** Tracks only original physical integer bounds, never row samples or successful preparation. */
internal object IntegerReadPreservation {
    fun initial(baseline: SchemaSnapshot): Set<ColumnReference> = baseline.tables.flatMap { (name, table) ->
        if (table.kind != "table" || table.relationOid == null) emptyList()
        else table.columns.filterValues { bits(it.type) != null }.keys.map { ColumnReference(name, it) }
    }.toSet()

    fun advance(
        baseline: SchemaSnapshot,
        before: SchemaSnapshot,
        after: SchemaSnapshot,
        preserved: Set<ColumnReference>,
        statements: List<OperationalStatementReport>,
    ): Set<ColumnReference> {
        val valuesPreserved = statements.all { statement -> preserving(statement, before, after) }
        return initial(baseline).filterTo(linkedSetOf()) { ref ->
            val originalTable = baseline.tables.getValue(ref.table)
            val currentTable = after.tables[ref.table] ?: return@filterTo false
            if (currentTable.kind != "table" || currentTable.relationOid != originalTable.relationOid) return@filterTo false
            val old = originalTable.columns.getValue(ref.column)
            val current = currentTable.columns[ref.column] ?: return@filterTo false
            val currentBits = bits(current.type) ?: return@filterTo false
            val oldBits = bits(old.type) ?: return@filterTo false
            if (currentBits <= oldBits) true // The current physical type enforces the original bound again.
            else {
                val previousTable = before.tables[ref.table]
                val previous = previousTable?.columns?.get(ref.column)
                val beforeBounded = previous?.type?.let(::bits)?.let { it <= oldBits } == true
                valuesPreserved && (ref in preserved || beforeBounded) && previousTable?.relationOid == originalTable.relationOid &&
                    previous != null && previous.ordinal == current.ordinal &&
                    RuleProofs.widening(previous.type, current.type)
            }
        }
    }

    private fun preserving(statement: OperationalStatementReport, before: SchemaSnapshot, after: SchemaSnapshot): Boolean {
        // These observations include applicable event callbacks and unsupported casts/types/defaults.
        // An operational RISKY scan/rewrite is not by itself a data transformation.
        if (statement.verdict == OperationalVerdict.UNKNOWN || statement.findings.any { it.verdict == OperationalVerdict.UNKNOWN }) return false
        val parsed = OperationalSql.inspect(statement.sql)
        if (parsed.intents.isEmpty() || parsed.intents.any { !it.complete }) return false
        return parsed.intents.all { intent -> when (intent.kind) {
            OperationalActionKind.SETTINGS_CHANGE -> parsed.timeoutSetting != null
            OperationalActionKind.SET_NOT_NULL -> true
            OperationalActionKind.METADATA_CHANGE -> intent.modifier in setOf(
                "RENAME_TABLE", "RENAME_COLUMN", "RENAME_CONSTRAINT", "DROP_NOT_NULL", "DROP_DEFAULT", "SET_DEFAULT", "ADD_COLUMN")
            OperationalActionKind.CREATE_INDEX -> true // Supported measured ordinary index, no expression/custom opclass.
            OperationalActionKind.ALTER_COLUMN_TYPE -> {
                val name = intent.table?.let { resolve(it, before) } ?: return@all false
                val oldTable = before.tables[name] ?: return@all false
                val newTable = after.tables.values.singleOrNull { it.relationOid != null && it.relationOid == oldTable.relationOid }
                    ?: return@all false
                val column = intent.detail ?: return@all false
                val old = oldTable.columns[column] ?: return@all false
                val next = newTable.columns[column] ?: return@all false
                // The measured cast guard corroborates intrinsic conversion. Only integer/string
                // widening is retained here; USING/COLLATE were rejected by intent.complete.
                RuleProofs.widening(old.type, next.type) &&
                    (bits(old.type) != null && bits(next.type) != null || stringType(old.type) && stringType(next.type))
            }
            else -> false // DML, procedures, FK actions and other DDL require separate preservation proof.
        } }
    }

    private fun resolve(name: String, snapshot: SchemaSnapshot): String? {
        val components = name.split('.')
        if (components.size !in 1..2) return null
        val names = components.map { component ->
            if (component.startsWith('"') && component.endsWith('"')) postgresIdentifier(component.substring(1, component.length - 1).replace("\"\"", "\""))
            else postgresIdentifier(component.lowercase())
        }
        if (names.any { it.isBlank() || '"' in it || '.' in it }) return null
        return if (names.size == 2) names.joinToString(".").takeIf { it in snapshot.tables }
        else snapshot.expressionSearchPath.firstNotNullOfOrNull { schema -> "$schema.${names.single()}".takeIf { it in snapshot.tables } }
    }

    private fun bits(type: String) = when (type) { "smallint" -> 16; "integer" -> 32; "bigint" -> 64; else -> null }
    private fun stringType(type: String) = Regex("(?:text|varchar(?:\\(\\d+\\))?|character varying(?:\\(\\d+\\))?)").matches(type)
}
