package dev.epoch.postgres

import dev.epoch.core.*

/** Catalog facts are diffed once per migration, independently of the number of SQL templates. */
internal object QueryRevalidation {
    fun needsPlanning(query: QueryContract, before: SchemaSnapshot, after: SchemaSnapshot): Boolean =
        delta(before, after).needsPlanning(query)

    fun delta(before: SchemaSnapshot, after: SchemaSnapshot): Delta {
        val opaque = before.behaviors != after.behaviors ||
            (before.behaviors.keys + after.behaviors.keys).any { !it.startsWith("enum:") } ||
            before.extensions != after.extensions || before.enumTypes != after.enumTypes
        val relations = mutableSetOf<String>()
        val columns = mutableMapOf<String, Set<String>>()
        val incomingTargets = mutableSetOf<String>()
        var unknownIncomingTarget = false
        for (name in before.tables.keys + after.tables.keys) {
            val old = before.tables[name]
            val new = after.tables[name]
            if (old == new) continue
            if (old == null || new == null || old.kind != new.kind || old.constraints != new.constraints || old.indexes != new.indexes)
                relations += name
            val changed = (old?.columns.orEmpty().keys + new?.columns.orEmpty().keys)
                .filter { old?.columns?.get(it) != new?.columns?.get(it) }.toSet()
            if (changed.isNotEmpty()) columns[name] = changed
            if (old?.constraints.orEmpty() != new?.constraints.orEmpty()) {
                (old?.constraints.orEmpty().values + new?.constraints.orEmpty().values).filter { it.type == "f" }.forEach {
                    if (it.referencedTable == null) unknownIncomingTarget = true else incomingTargets += it.referencedTable
                }
            }
        }
        val changedSequences = (before.sequences.keys + after.sequences.keys).filter { before.sequences[it] != after.sequences[it] }
        val owners = changedSequences.map { after.sequenceOwners[it] ?: before.sequenceOwners[it] }
        return Delta(opaque, relations, columns, changedSequences.isNotEmpty(), owners.any { it == null },
            owners.filterNotNull().map { it.table }.toSet(), incomingTargets, unknownIncomingTarget)
    }

    data class Delta internal constructor(
        private val forceAll: Boolean,
        private val changedRelations: Set<String>,
        private val changedColumns: Map<String, Set<String>>,
        private val sequencesChanged: Boolean,
        private val unknownSequenceOwner: Boolean,
        private val sequenceOwnerTables: Set<String>,
        private val incomingTargets: Set<String>,
        private val unknownIncomingTarget: Boolean,
    ) {
        fun needsPlanning(query: QueryContract): Boolean {
            if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED || forceAll) return true
            val tables = (query.tables + listOfNotNull(query.targetTable) + (query.columns + query.writeColumns).map { it.table })
                .map(::physicalTable).toSet()
            if (tables.any { it in changedRelations }) return true
            if (!query.explicitColumnList && tables.any { it in changedColumns }) return true
            if ((query.columns + query.writeColumns).any { postgresIdentifier(it.column) in changedColumns[physicalTable(it.table)].orEmpty() }) return true
            if (sequencesChanged && (unknownSequenceOwner || tables.any { it in sequenceOwnerTables } ||
                    listOf("nextval", "currval", "setval").any { it in query.sql.lowercase() })) return true
            if (query.operation != Operation.SELECT && (unknownIncomingTarget || tables.any { it in incomingTargets })) return true
            return false
        }
    }

    private fun physicalTable(name: String) = name.split('.').joinToString(".", transform = ::postgresIdentifier)
}
