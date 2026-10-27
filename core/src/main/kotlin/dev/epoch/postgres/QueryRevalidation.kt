package dev.epoch.postgres

import dev.epoch.core.*

/**
 * Reuses only structural planning results whose declared dependencies are unchanged.
 * This does not suppress catalog/input-domain rules or migration-effect analysis.
 * Incomplete dependencies and database-side behavior deliberately take the full path.
 */
internal object QueryRevalidation {
    fun needsPlanning(query: QueryContract, before: SchemaSnapshot, after: SchemaSnapshot): Boolean {
        if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED) return true
        // Function bodies, views, triggers, row policies, casts and custom type semantics
        // can reach dependencies not represented by table/column references in schema v1.
        if (before.behaviors != after.behaviors ||
            (before.behaviors.keys + after.behaviors.keys).any { !it.startsWith("enum:") } ||
            before.extensions != after.extensions || before.enumTypes != after.enumTypes) return true

        val tables = (query.tables + listOfNotNull(query.targetTable) +
            (query.columns + query.writeColumns).map { it.table }).map(::physicalTable).toSet()
        if (before.sequences != after.sequences) {
            // Schema v1 does not enumerate direct sequence-function dependencies. A
            // possible direct call therefore invalidates conservatively. Owned
            // sequences on unrelated relations cannot affect this query's columns.
            if (listOf("nextval", "currval", "setval").any { it in query.sql.lowercase() }) return true
            val changed = (before.sequences.keys + after.sequences.keys).filter { before.sequences[it] != after.sequences[it] }
            if (changed.any { name ->
                val owner = after.sequenceOwners[name] ?: before.sequenceOwners[name]
                owner == null || owner.table in tables
            }) return true
        }
        val references = (query.columns + query.writeColumns).groupBy({ physicalTable(it.table) }, { postgresIdentifier(it.column) })
        for (name in tables) {
            val old = before.tables[name]
            val new = after.tables[name]
            if (old == null || new == null) {
                if (old != new) return true
                // A previous rejected plan remains rejected while its dependency
                // remains absent; reuse does not turn that failure into acceptance.
                continue
            }
            if (old.kind != new.kind) return true
            if (old.constraints != new.constraints || old.indexes != new.indexes) return true
            if (!query.explicitColumnList) {
                if (old.columns != new.columns) return true
            } else if (references[name].orEmpty().any { old.columns[it] != new.columns[it] }) return true
        }
        if (query.operation != Operation.SELECT) {
            // Adding a referencing table can change an existing target's DELETE/UPDATE
            // behavior; foreign keys are not declared among the query's source tables.
            val changedConstraints = (before.tables.keys + after.tables.keys).any { name ->
                val old = before.tables[name]?.constraints.orEmpty()
                val new = after.tables[name]?.constraints.orEmpty()
                if (old == new) false else (old.values + new.values).any { constraint ->
                    constraint.type == "f" && (constraint.referencedTable == null || constraint.referencedTable in tables)
                }
            }
            if (changedConstraints) return true
        }
        return false
    }

    private fun physicalTable(name: String) = name.split('.').joinToString(".", transform = ::postgresIdentifier)
}
