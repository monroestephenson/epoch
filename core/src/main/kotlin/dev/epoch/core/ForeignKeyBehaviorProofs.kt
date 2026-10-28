package dev.epoch.core

/** Synthetic FK roots describe structured catalog facts, not arbitrary stored code. */
internal object ForeignKeyBehaviorProofs {
    private data class Key(val table: String, val constraint: String, val operation: Operation)
    private data class Candidate(val nodeId: String, val constraint: ConstraintSchema)

    fun preservedRoots(baseline: SchemaSnapshot, normalizedAfter: SchemaSnapshot, rawAfter: SchemaSnapshot,
                       provenViews: Map<String, String>): Set<String> {
        val oldNames = provenViews.entries.associate { it.value to it.key }
        fun candidates(snapshot: SchemaSnapshot, names: Map<String, String>): Map<Key, Candidate> {
            val graph = snapshot.behaviorGraph ?: return emptyMap()
            if (graph.version != 1 || !graph.attachmentInventoryComplete) return emptyMap()
            val objects = snapshot.tables.flatMap { (table, schema) -> schema.constraints.filterValues { it.type == "f" }
                .map { (name, constraint) -> "$table.$name" to Triple(table, name, constraint) } }
                .groupBy({ it.first }, { it.second })
            return graph.attachments.mapNotNull { attachment ->
                val node = graph.nodes[attachment.rootNodeId] ?: return@mapNotNull null
                if (node.kind != "foreign_key_action" || !node.dependenciesComplete || !node.semanticsSupported ||
                    node.childNodeIds.isNotEmpty() || node.opaqueReasons.isNotEmpty()) return@mapNotNull null
                val operation = attachment.operations.singleOrNull()?.takeIf { it in setOf(Operation.UPDATE, Operation.DELETE) }
                    ?: return@mapNotNull null
                val (table, name, constraint) = objects[attachment.objectName]?.singleOrNull() ?: return@mapNotNull null
                if (constraint.referencedTable != attachment.table || node.definition != constraint.definition) return@mapNotNull null
                val action = if (operation == Operation.DELETE) constraint.onDelete else constraint.onUpdate
                if (action?.uppercase() !in setOf("A", "R", "C", "N", "D", "NO ACTION", "RESTRICT", "CASCADE", "SET NULL", "SET DEFAULT")) return@mapNotNull null
                Key(names[table] ?: table, name, operation) to Candidate(attachment.rootNodeId, constraint)
            }.groupBy({ it.first }, { it.second }).mapNotNull { (key, values) ->
                values.singleOrNull()?.let { key to it }
            }.toMap()
        }
        val before = candidates(baseline, emptyMap())
        val after = candidates(rawAfter, oldNames)
        return buildSet {
            before.forEach { (key, original) ->
                val replacement = after[key] ?: return@forEach
                val normalized = normalizedAfter.tables[key.table]?.constraints?.get(key.constraint)
                if (original.constraint == normalized) {
                    add(original.nodeId)
                    add(replacement.nodeId)
                }
            }
        }
    }
}
