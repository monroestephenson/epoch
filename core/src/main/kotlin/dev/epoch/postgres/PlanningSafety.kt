package dev.epoch.postgres

import dev.epoch.core.*

/** Planning eligibility concerns only behavior reachable while preparing this statement. */
internal class PlanningSafety(
    private val snapshot: SchemaSnapshot,
    private val syntax: BehaviorQuerySyntax = BehaviorQuerySyntax(),
) {
    private val scope = BehaviorScope(snapshot)
    private val scopes = mutableMapOf<QueryContract, OperationBehaviorScope>()

    fun behavior(query: QueryContract): OperationBehaviorScope = scopes.getOrPut(query) {
        scope.forQuery(query, syntax.inspect(query))
    }

    fun eligible(query: QueryContract): Boolean {
        if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED ||
            query.source == QuerySource.RUNTIME_OBSERVED) return false
        val facts = syntax.inspect(query)
        val reached = behavior(query)
        if (!facts.complete || reached.planningMayEvaluate || reached.planningUncertainties.isNotEmpty()) return false
        // Older manually supplied snapshots cannot establish view expansion or CHECK evaluation
        // from absent attachment facts. Actual PostgreSQL snapshots carry a versioned graph.
        if (snapshot.behaviorGraph == null) {
            val tables = (query.tables + listOfNotNull(query.targetTable) +
                (query.columns + query.writeColumns).map { it.table }).map(::physicalTable).toSet()
            for (name in tables) {
                val table = snapshot.tables[name] ?: continue
                if (table.kind != "table" || table.constraints.values.any { it.type == "c" }) return false
                if (query.operation == Operation.INSERT && (query.targetTable ?: query.tables.singleOrNull())?.let(::physicalTable) == name) {
                    val supplied = query.writeColumns.map { postgresIdentifier(it.column) }.toSet()
                    if (table.columns.any { (column, schema) -> column !in supplied && schema.defaultExpression != null &&
                            !RuleProofs.constantDefault(schema.defaultExpression, schema.type) &&
                            !schema.defaultExpression.startsWith("nextval('") }) return false
                }
            }
        }
        return true
    }

    private fun physicalTable(name: String) = name.split('.').joinToString(".", transform = ::postgresIdentifier)
}
