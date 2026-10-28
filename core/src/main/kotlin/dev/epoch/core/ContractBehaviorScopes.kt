package dev.epoch.core

/** One indexed operation inventory per contract/snapshot, shared by the engine's rules. */
internal class ContractBehaviorScopes(
    contract: DatabaseContract,
    snapshot: SchemaSnapshot,
    syntax: BehaviorQuerySyntax,
    transparentViews: Map<String, String> = emptyMap(),
) {
    data class Entry(val id: String, val operation: Operation, val table: String?, val scope: OperationBehaviorScope)
    val resolver = BehaviorScope(snapshot, transparentViews)
    val queryScopes = contract.queries.associate { it.id to resolver.forQuery(it, syntax.inspect(it)) }
    val entries: List<Entry>
    val reached: Map<String, Set<String>>
    val uncertainties: Map<BehaviorUncertainty, Set<String>>
    private val writesByTable: Map<String?, List<Entry>>

    init {
        val queriesByMapping = contract.queries.flatMap { query -> query.mappedEntityIds.map { (it to query.operation) to query } }
            .groupBy({ it.first }, { it.second })
        entries = buildList {
            contract.queries.forEach { query -> add(Entry(query.id, query.operation,
                query.targetTable ?: query.writeColumns.map { it.table }.distinct().singleOrNull()
                    ?: query.tables.singleOrNull().takeIf { query.operation != Operation.SELECT }, queryScopes.getValue(query.id))) }
            contract.entities.forEach { entity -> entity.operations.sortedBy { it.name }.forEach { operation ->
                val captured = queriesByMapping[entity.id to operation].orEmpty()
                if (captured.isEmpty()) add(Entry(entity.id, operation, entity.table, resolver.forMapping(entity, operation)))
                else captured.forEach { query -> add(Entry(entity.id, operation, entity.table, queryScopes.getValue(query.id))) }
            } }
        }
        val reachedIndex = sortedMapOf<String, MutableSet<String>>()
        val uncertaintyIndex = linkedMapOf<BehaviorUncertainty, MutableSet<String>>()
        for (entry in entries) {
            entry.scope.semanticNodeIds.forEach { reachedIndex.getOrPut(it) { sortedSetOf() } += entry.id }
            entry.scope.uncertainties.forEach { uncertaintyIndex.getOrPut(it) { sortedSetOf() } += entry.id }
        }
        reached = reachedIndex
        uncertainties = uncertaintyIndex
        writesByTable = entries.filter { it.operation in setOf(Operation.INSERT, Operation.UPDATE) }.groupBy { it.table }
    }

    fun writeEffectsKnown(table: String, operations: Set<Operation> = setOf(Operation.INSERT, Operation.UPDATE)): Boolean =
        writesByTable[table].orEmpty().filter { it.operation in operations }.all {
            it.scope.uncertainties.isEmpty() && it.scope.writeTransformations.isEmpty()
        }

    fun queryWriteEffectsKnown(query: QueryContract): Boolean = queryScopes[query.id]?.let {
        it.uncertainties.isEmpty() && it.writeTransformations.isEmpty()
    } == true

    fun expressionCallers(objectName: String): Set<String> {
        val roots = resolver.expressionRoots(objectName)
        return roots.flatMapTo(sortedSetOf()) { reached[it].orEmpty() }
    }
}
