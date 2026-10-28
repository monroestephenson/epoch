package dev.epoch.core

/** Match unchanged owned expression objects only across an independently proven physical rename.
 * This is identity normalization, not a proof that an opaque expression preserves behavior. */
internal object BehaviorAttachmentIdentity {
    fun renamedRoots(
        before: SchemaSnapshot, normalizedAfter: SchemaSnapshot, after: SchemaSnapshot,
        provenViews: Map<String, String>,
    ): Map<String, String> {
        val oldGraph = before.behaviorGraph ?: return emptyMap()
        val newGraph = after.behaviorGraph ?: return emptyMap()
        if (oldGraph.version != 1 || newGraph.version != 1 ||
            !oldGraph.attachmentInventoryComplete || !newGraph.attachmentInventoryComplete) return emptyMap()
        val oldNames = provenViews.entries.associate { it.value to it.key }
        fun originalName(name: String): String = oldNames.entries.firstOrNull { (physical, _) ->
            name == physical || name.startsWith("$physical.")
        }?.let { (physical, original) -> original + name.removePrefix(physical) } ?: name
        val byOwner = newGraph.attachments.groupBy { it.table }
        val pairs = sortedMapOf<String, String>()
        for (old in oldGraph.attachments) {
            val physical = provenViews[old.table] ?: continue
            val oldNode = oldGraph.nodes[old.rootNodeId] ?: continue
            if (oldNode.kind !in setOf("default", "generated", "check", "index")) continue
            val candidate = byOwner[physical].orEmpty().singleOrNull { new ->
                new.copy(table = old.table, rootNodeId = old.rootNodeId,
                    objectName = new.objectName?.let(::originalName)) == old &&
                    newGraph.nodes[new.rootNodeId]?.kind == oldNode.kind
            } ?: continue
            val newNode = newGraph.nodes.getValue(candidate.rootNodeId)
            val oldTable = before.tables[old.table] ?: continue
            val physicalTable = after.tables[physical] ?: continue
            val normalizedTable = normalizedAfter.tables[old.table] ?: continue
            val oldObject = old.objectName ?: continue
            val local = oldObject.removePrefix("${old.table}.")
            if (local == oldObject) continue
            val catalogUnchanged = when (oldNode.kind) {
                "default", "generated" -> old.column == local && oldTable.columns[local] != null &&
                    oldTable.columns[local] == normalizedTable.columns[local] &&
                    oldNode.definition == oldTable.columns[local]?.defaultExpression &&
                    newNode.definition == physicalTable.columns[local]?.defaultExpression
                "check" -> oldTable.constraints[local]?.type == "c" &&
                    oldTable.constraints[local] == normalizedTable.constraints[local] &&
                    oldNode.definition == oldTable.constraints[local]?.checkExpression &&
                    newNode.definition == physicalTable.constraints[local]?.checkExpression
                "index" -> oldTable.indexes[local] != null &&
                    oldTable.indexes[local] == normalizedTable.indexes[local] &&
                    oldTable.indexMethods[local] == normalizedTable.indexMethods[local] &&
                    oldTable.indexColumns[local] == normalizedTable.indexColumns[local] &&
                    oldTable.indexValidity[local] == normalizedTable.indexValidity[local] &&
                    oldTable.indexReadiness[local] == normalizedTable.indexReadiness[local] &&
                    oldNode.definition == oldTable.indexes[local] &&
                    newNode.definition == physicalTable.indexes[local]
                else -> false
            }
            if (!catalogUnchanged) continue
            val normalizedNode = newNode.copy(definition = oldNode.definition,
                references = newNode.references.map { it.copy(name = oldNames[it.name] ?: it.name) })
            if (normalizedNode != oldNode) continue
            pairs[candidate.rootNodeId] = old.rootNodeId
        }
        return pairs
    }
}
