package dev.epoch.core

/** An unsupported readonly syntax shape may retain unchanged dependency identity through
 * an independently proven physical projection. This never supplies SQL/result equivalence;
 * baseline coverage debt and PostgreSQL parameter/result comparisons remain authoritative. */
internal class TransparentQueryBoundaries(
    contract: DatabaseContract,
    private val baseline: SchemaSnapshot,
    private val normalizedAfter: SchemaSnapshot,
    private val target: SchemaSnapshot,
    private val provenViews: Map<String, String>,
    private val preservedRoots: Set<String>,
    private val syntax: BehaviorQuerySyntax,
    private val oldScopes: ContractBehaviorScopes,
    private val newScopes: ContractBehaviorScopes,
    private val changes: BehaviorChanges,
) {
    private val queries = contract.queries.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
    private val oldNames = provenViews.entries.associate { it.value to it.key }
    private val normalizedChanges = BehaviorChanges(baseline, normalizedAfter)
    private val memo = mutableMapOf<String, Boolean>()

    fun preserved(queryId: String): Boolean = memo.getOrPut(queryId) {
        val query = queries[queryId] ?: return@getOrPut false
        if (provenViews.isEmpty() || query.operation != Operation.SELECT || !query.dependenciesComplete ||
            baseline.expressionSearchPath != target.expressionSearchPath) return@getOrPut false
        val facts = syntax.inspect(query)
        if (!facts.dependencyCoverageComplete || !facts.rowIdentityIndependent) return@getOrPut false
        val original = oldScopes.queryScopes[queryId] ?: return@getOrPut false
        val current = newScopes.queryScopes[queryId] ?: return@getOrPut false
        val causeId = "query:$queryId"
        val knownColumns = query.columns.all { column ->
            val before = baseline.tables[column.table]?.columns?.get(column.column)
            val after = normalizedAfter.tables[column.table]?.columns?.get(column.column)
            before != null && before == after
        }
        if (!knownColumns) return@getOrPut false
        val physicalTables = query.tables + query.columns.map { it.table }
        if (physicalTables.any { it !in baseline.tables || it !in normalizedAfter.tables ||
                it.startsWith("pg_catalog.") || it.startsWith("information_schema.") }) return@getOrPut false
        for ((scope, snapshot) in listOf(original to baseline, current to target)) {
            if (causeId !in scope.boundedQueryUncertaintyIds || scope.uncertainties.any { it.nodeId != causeId }) return@getOrPut false
            val graph = snapshot.behaviorGraph ?: return@getOrPut false
            if (!graph.attachmentInventoryComplete || graph.version != 1) return@getOrPut false
            if (scope.semanticNodeIds.any { id ->
                    val node = graph.nodes[id]
                    node?.dependenciesComplete != true || !node.semanticsSupported ||
                        id in changes.changedNodeIds && id !in preservedRoots &&
                            !(id.startsWith("view:") && id.removePrefix("view:") in provenViews)
                }) return@getOrPut false
            val references = (scope.queryDependencyReferences + scope.references).mapTo(linkedSetOf()) {
                it.copy(name = oldNames[it.name] ?: it.name)
            }
            if (normalizedChanges.intersects(references)) return@getOrPut false
        }
        true
    }
}
