package dev.epoch.postgres

import dev.epoch.core.*

/** Catalog facts are diffed once per migration, independently of the number of SQL templates. */
internal object QueryRevalidation {
    fun needsPlanning(query: QueryContract, before: SchemaSnapshot, after: SchemaSnapshot): Boolean =
        delta(before, after).needsPlanning(query)

    fun delta(before: SchemaSnapshot, after: SchemaSnapshot,
              syntax: BehaviorQuerySyntax = BehaviorQuerySyntax()): Delta {
        val changes = BehaviorChanges(before, after)
        val legacyDependencies = listOf(before, after).any {
            it.behaviorGraph?.let { graph -> graph.version != 1 || !graph.attachmentInventoryComplete } != false
        }
        val priorSafety = PlanningSafety(before, syntax)
        val targetSafety = PlanningSafety(after, syntax)
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
        return Delta(changes, priorSafety, targetSafety, relations, columns,
            legacyDependencies && (before.extensions != after.extensions || before.enumTypes != after.enumTypes),
            legacyDependencies && changedSequences.isNotEmpty(), owners.any { it == null },
            owners.filterNotNull().map { it.table }.toSet(), incomingTargets, unknownIncomingTarget)
    }

    data class Delta internal constructor(
        private val behaviorChanges: BehaviorChanges,
        private val priorSafety: PlanningSafety,
        private val targetSafety: PlanningSafety,
        private val changedRelations: Set<String>,
        private val changedColumns: Map<String, Set<String>>,
        private val unscopedTypeOrExtensionChanges: Boolean,
        private val sequencesChanged: Boolean,
        private val unknownSequenceOwner: Boolean,
        private val sequenceOwnerTables: Set<String>,
        private val incomingTargets: Set<String>,
        private val unknownIncomingTarget: Boolean,
    ) {
        fun needsPlanning(query: QueryContract): Boolean {
            if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED) return true
            if (unscopedTypeOrExtensionChanges) return true
            // Data/code-dependent plans are rechecked locally even when the catalog is unchanged.
            if (!priorSafety.eligible(query) || !targetSafety.eligible(query)) return true
            for (scope in listOf(priorSafety.behavior(query), targetSafety.behavior(query))) {
                if (scope.planningNodeIds.any { it in behaviorChanges.changedNodeIds } ||
                    behaviorChanges.intersects(scope.planningReferences)) return true
            }
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
