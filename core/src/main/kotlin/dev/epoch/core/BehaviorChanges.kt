package dev.epoch.core

/** Shared typed change boundary for semantic checks and incremental planning. */
class BehaviorChanges(before: SchemaSnapshot, after: SchemaSnapshot) {
    private val wholeRelations = sortedSetOf<String>()
    private val relationChanges = sortedSetOf<String>()
    private val columns = linkedSetOf<ColumnReference>()
    private val writeConstraintRelations = sortedSetOf<String>()
    private val sequences = changedKeys(before.sequences, after.sequences)
    private val types = changedKeys(before.enumTypes, after.enumTypes)
    private val extensions = changedKeys(before.extensions, after.extensions)
    val changedNodeIds: Set<String>
    val changedObjects: Set<String>
    val hasChanges: Boolean

    init {
        for (name in (before.tables.keys + after.tables.keys).sorted()) {
            val a = before.tables[name]
            val b = after.tables[name]
            if (a == b) continue
            relationChanges += name
            if (a == null || b == null || a.kind != b.kind || a.view != b.view || a.relationSecurity != b.relationSecurity) {
                wholeRelations += name
            }
            for (column in changedKeys(a?.columns.orEmpty(), b?.columns.orEmpty())) columns += ColumnReference(name, column)
            val changedConstraints = changedKeys(a?.constraints.orEmpty(), b?.constraints.orEmpty())
            if (changedConstraints.isNotEmpty()) writeConstraintRelations += name
            for (constraint in changedConstraints) {
                val old = a?.constraints?.get(constraint)
                val new = b?.constraints?.get(constraint)
                val affected = old?.columns.orEmpty() + new?.columns.orEmpty()
                if (affected.isEmpty()) wholeRelations += name
                else affected.forEach { columns += ColumnReference(name, it) }
            }
            val changedIndexes = changedKeys(a?.indexes.orEmpty(), b?.indexes.orEmpty()) +
                changedKeys(a?.indexValidity.orEmpty(), b?.indexValidity.orEmpty()) +
                changedKeys(a?.indexReadiness.orEmpty(), b?.indexReadiness.orEmpty())
            for (index in changedIndexes) {
                val affected = a?.indexColumns?.get(index).orEmpty() + b?.indexColumns?.get(index).orEmpty()
                if (affected.isEmpty()) wholeRelations += name
                else affected.forEach { columns += ColumnReference(name, it) }
            }
        }
        val a = before.behaviorGraph
        val b = after.behaviorGraph
        val attachmentChanges = (a?.attachments.orEmpty().toSet() - b?.attachments.orEmpty().toSet()) +
            (b?.attachments.orEmpty().toSet() - a?.attachments.orEmpty().toSet())
        changedNodeIds = (changedKeys(a?.nodes.orEmpty(), b?.nodes.orEmpty()) +
            changedKeys(before.behaviors, after.behaviors) + attachmentChanges.map { it.rootNodeId }).toSortedSet()
        changedObjects = (relationChanges + sequences + types + extensions + changedNodeIds).toSortedSet()
        hasChanges = changedObjects.isNotEmpty() || a?.version != b?.version ||
            a?.attachmentInventoryComplete != b?.attachmentInventoryComplete || before.expressionSearchPath != after.expressionSearchPath
    }

    fun intersects(references: Set<BehaviorReference>): Boolean = references.any { reference ->
        when (reference.kind) {
            BehaviorReferenceKind.RELATION -> reference.name in relationChanges
            BehaviorReferenceKind.COLUMN -> reference.name in wholeRelations || reference.column == null && reference.name in relationChanges ||
                ColumnReference(reference.name, reference.column.orEmpty()) in columns
            BehaviorReferenceKind.SEQUENCE -> reference.name in sequences
            BehaviorReferenceKind.TYPE -> reference.name in types || "type:${reference.name}" in changedNodeIds
            BehaviorReferenceKind.EXTENSION -> reference.name in extensions
        }
    }

    /** UPDATE enforces CHECKs even when their columns are absent from the assignment set. */
    fun intersectsRuntimeWrites(references: Set<BehaviorReference>): Boolean = references.any { reference ->
        reference.kind in setOf(BehaviorReferenceKind.RELATION, BehaviorReferenceKind.COLUMN) &&
            reference.access in setOf(BehaviorAccess.INSERT, BehaviorAccess.UPDATE, BehaviorAccess.DELETE, BehaviorAccess.UNKNOWN) &&
            (intersects(setOf(reference)) || reference.access != BehaviorAccess.DELETE && reference.name in writeConstraintRelations)
    }

    private fun <T> changedKeys(a: Map<String, T>, b: Map<String, T>): Set<String> =
        (a.keys + b.keys).filterTo(sortedSetOf()) { a[it] != b[it] }
}
