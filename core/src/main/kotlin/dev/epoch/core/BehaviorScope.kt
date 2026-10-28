package dev.epoch.core

/** Operation-local traversal of catalog roots. Ownership does not imply invocation. */
class BehaviorScope(
    private val snapshot: SchemaSnapshot,
    private val transparentViews: Map<String, String> = emptyMap(),
) {
    private val graph = snapshot.behaviorGraph
    private val attachments = graph?.attachments.orEmpty().groupBy { it.table }
    private val incomingKeys = snapshot.tables.flatMap { (child, table) ->
        table.constraints.values.filter { it.type == "f" && it.referencedTable != null }.map { child to it }
    }.groupBy { it.second.referencedTable }
    private val closureCache = mutableMapOf<String, Set<String>>()
    private val operationCache = mutableMapOf<ScopeKey, OperationBehaviorScope>()
    private val userFunctionsByName = graph?.nodes.orEmpty().filterValues { it.kind.equals("function", true) }.keys
        .groupBy { it.removePrefix("function:").substringBefore('(').substringAfterLast('.').lowercase() }

    fun forQuery(query: QueryContract, facts: QueryBehaviorFacts): OperationBehaviorScope {
        val target = query.targetTable ?: query.writeColumns.map { it.table }.distinct().singleOrNull()
            ?: query.tables.singleOrNull().takeIf { query.operation != Operation.SELECT }
        val columns = (query.columns + query.writeColumns).toSet()
        val tables = query.tables + listOfNotNull(query.targetTable) + columns.map { it.table } +
            facts.references.filter { it.kind in setOf(BehaviorReferenceKind.RELATION, BehaviorReferenceKind.COLUMN) }.map { it.name }
        val seeds = tables.distinct().map { table -> Seed(table, if (table == target) query.operation else Operation.SELECT,
            columns.filter { it.table == table }.map { it.column }.toSet(),
            query.writeColumns.filter { it.table == table }.map { it.column }.toSet(),
            facts.defaultColumns.filter { it.table == table }.map { it.column }.toSet(),
            facts.defaultAssignmentsComplete && query.explicitColumnList, true) }
        return cachedCollect(seeds, facts, query.id, query.operation != Operation.SELECT,
            query.parameterTypes + query.resultColumns.mapNotNull { it.typeName }.map(::jdbcResultType))
    }

    fun forMapping(entity: EntityContract, operation: Operation): OperationBehaviorScope {
        val writes = entity.columns.filter {
            if (operation == Operation.INSERT) it.insertable && (!it.generated || entity.identifierSequence != null)
            else operation == Operation.UPDATE && it.updatable
        }.map { it.name }.toSet()
        return cachedCollect(listOf(Seed(entity.table, operation, entity.columns.map { it.name }.toSet(), writes,
            emptySet(), true, true)), QueryBehaviorFacts(complete = true, defaultAssignmentsComplete = true),
            entity.id, operation != Operation.SELECT, emptyList())
    }

    /** Exact attached objects permit rename checks without searching arbitrary SQL bodies. */
    fun expressionRoots(objectName: String): Set<String> = graph?.attachments.orEmpty()
        .filter { it.objectName == objectName }.mapTo(sortedSetOf()) { it.rootNodeId }

    private data class Seed(
        val table: String, val operation: Operation, val columns: Set<String>, val writes: Set<String>,
        val defaults: Set<String>, val assignmentsComplete: Boolean, val planning: Boolean,
    )
    private data class Visit(val id: String, val planning: Boolean)

    private data class ScopeKey(val seeds: List<Seed>, val facts: QueryBehaviorFacts, val writing: Boolean, val types: List<String>)
    private fun cachedCollect(seeds: List<Seed>, facts: QueryBehaviorFacts, caller: String, writing: Boolean, types: List<String>): OperationBehaviorScope {
        // Complete queries with equal operation facts share the immutable catalog closure even
        // when thousands of distinct SQL templates/call sites use the same mapped operation.
        if (!facts.complete) return collect(seeds, facts, caller, writing, types)
        val key = ScopeKey(seeds.sortedWith(compareBy({ it.table }, { it.operation.name })), facts, writing, types.distinct().sorted())
        return operationCache.getOrPut(key) { collect(key.seeds, facts, caller, writing, key.types) }
    }

    private fun collect(
        initial: List<Seed>, facts: QueryBehaviorFacts, caller: String, writing: Boolean, sqlTypes: List<String>,
    ): OperationBehaviorScope {
        val semantic = sortedSetOf<String>()
        val planning = sortedSetOf<String>()
        val references = linkedSetOf<BehaviorReference>()
        val planningReferences = linkedSetOf<BehaviorReference>()
        val unknown = linkedSetOf<BehaviorUncertainty>()
        val planningUnknown = linkedSetOf<BehaviorUncertainty>()
        val transformations = sortedSetOf<String>()
        var mayEvaluate = false
        fun uncertainty(id: String, reason: String, duringPlanning: Boolean) {
            val value = BehaviorUncertainty(id, reason)
            unknown += value
            if (duringPlanning) planningUnknown += value
        }
        if (!facts.complete) {
            uncertainty("query:$caller", facts.unresolvedReasons.sorted().joinToString("; ")
                .ifBlank { "SQL function, operator, type, or dependency coverage is incomplete." }, true)
        }
        if (graph == null || graph.version != 1 || !graph.attachmentInventoryComplete) {
            if (graph != null) uncertainty("catalog:behavior-inventory", "Complete operation-to-behavior attachments are unavailable.", true)
            if (graph == null) {
                snapshot.behaviors.toSortedMap().filterKeys { id ->
                    !id.startsWith("view:") || id.removePrefix("view:") !in transparentViews
                }.forEach { (id, _) ->
                    semantic += id
                    planning += id
                    uncertainty(id, "Legacy behavior has no operation-local dependency facts.", true)
                    if (writing) transformations += id
                }
            }
        }
        val seeds = ArrayDeque<Seed>()
        initial.forEach(seeds::addLast)
        val visitedSeeds = mutableSetOf<Seed>()
        val nodes = ArrayDeque<Visit>()
        facts.explicitRootNodeIds.sorted().forEach { nodes += Visit(it, true) }
        facts.builtinFunctionCalls.sorted().forEach { call ->
            val qualified = call.contains('.')
            val basename = call.substringAfterLast('.').lowercase()
            val searchPath = if (qualified) listOf(call.substringBeforeLast('.')) else snapshot.expressionSearchPath
            val candidates = userFunctionsByName[basename].orEmpty().filter { id ->
                val name = id.removePrefix("function:").substringBefore('(')
                name.substringBeforeLast('.', "") in searchPath
            }
            candidates.sorted().forEach { nodes += Visit(it, true) }
            if (graph?.attachmentInventoryComplete != true || graph.version != 1 ||
                (!qualified && snapshot.expressionSearchPath.firstOrNull() != "pg_catalog") ||
                (qualified && call.substringBeforeLast('.') != "pg_catalog")) {
                uncertainty("function:$call", "Builtin function resolution is not established from a complete catalog and pg_catalog-first search path.", true)
            }
        }

        fun typeReference(name: String, duringPlanning: Boolean) {
            val type = canonicalType(name)
            if (builtinType(type)) return
            val qualified = when {
                "type:$type" in graph?.nodes.orEmpty() || type in snapshot.enumTypes -> type
                "type:public.$type" in graph?.nodes.orEmpty() || "public.$type" in snapshot.enumTypes -> "public.$type"
                else -> type
            }
            val reference = BehaviorReference(BehaviorReferenceKind.TYPE, qualified)
            references += reference
            if (duringPlanning) planningReferences += reference
            if (qualified in snapshot.enumTypes && "type:$qualified" !in graph?.nodes.orEmpty()) return
            if ("type:$qualified" in graph?.nodes.orEmpty()) nodes += Visit("type:$qualified", duringPlanning)
            else uncertainty("type:$qualified", "The SQL type has no supported built-in or catalog dependency identity.", duringPlanning)
        }
        fun reference(value: BehaviorReference, duringPlanning: Boolean, record: Boolean = true, expand: Boolean = true) {
            if (record) {
                references += value
                if (duringPlanning) planningReferences += value
            }
            when (value.kind) {
                BehaviorReferenceKind.TYPE -> typeReference(value.name, duringPlanning)
                BehaviorReferenceKind.RELATION, BehaviorReferenceKind.COLUMN -> {
                    val operations = if (!expand) emptyList() else when (value.access) {
                        BehaviorAccess.METADATA -> emptyList()
                        BehaviorAccess.UNKNOWN -> Operation.entries
                        else -> listOf(Operation.valueOf(value.access.name))
                    }
                    operations.forEach { operation -> seeds += Seed(value.name, operation,
                        setOfNotNull(value.column), emptySet(), emptySet(), false,
                        duringPlanning && operation == Operation.SELECT) }
                }
                else -> Unit
            }
        }
        facts.references.forEach { value -> reference(value, true,
            value.kind !in setOf(BehaviorReferenceKind.RELATION, BehaviorReferenceKind.COLUMN)) }
        // JDBC inference markers are not user-defined PostgreSQL type identities. Actual
        // column/cast types still root their catalog dependencies; the planner separately
        // rejects parameters whose types cannot be resolved at the baseline.
        sqlTypes.filterNot { it.trim().uppercase() in setOf("OTHER", "UNKNOWN", "NULL") }
            .forEach { typeReference(jdbcType(it), true) }
        val visitedNodes = mutableSetOf<Visit>()
        while (seeds.isNotEmpty() || nodes.isNotEmpty()) {
            while (seeds.isNotEmpty()) {
                val seed = seeds.removeFirst()
                if (!visitedSeeds.add(seed)) continue
                val table = snapshot.tables[seed.table]
                val backing = transparentViews[seed.table]
                if (backing != null) seeds += seed.copy(table = backing)
                attachments[seed.table].orEmpty().forEach { attachment ->
                    if (!attachmentActive(attachment, seed)) return@forEach
                    if (graph?.nodes?.get(attachment.rootNodeId)?.kind == "foreign_key_action" &&
                        foreignKeyUnchanged(seed, attachment.updateColumns, facts)) return@forEach
                    // User UPDATE OF triggers remain conservative: generated values and other
                    // triggers may activate them even when SQL SET names are disjoint.
                    nodes += Visit(attachment.rootNodeId, seed.planning && attachment.planningRelevant)
                }
                val selectedColumns = if (seed.columns.isEmpty() || seed.operation == Operation.INSERT) table?.columns.orEmpty().keys else seed.columns
                selectedColumns.filterNot { implicitNull(seed, it) }.forEach { column ->
                    table?.columns?.get(column)?.let { typeReference(it.type, seed.planning) }
                }
                if (seed.operation == Operation.DELETE || seed.operation == Operation.UPDATE) {
                    incomingKeys[seed.table].orEmpty().forEach { (child, fk) ->
                        if (foreignKeyUnchanged(seed, fk.referencedColumns.toSet(), facts)) return@forEach
                        val action = if (seed.operation == Operation.DELETE) fk.onDelete else fk.onUpdate
                        val childOperation = when (action?.uppercase()) {
                            "C", "CASCADE" -> if (seed.operation == Operation.DELETE) Operation.DELETE else Operation.UPDATE
                            "N", "D", "SET NULL", "SET DEFAULT" -> Operation.UPDATE
                            "A", "R", "NO ACTION", "RESTRICT" -> Operation.SELECT
                            else -> null
                        }
                        if (childOperation == null) uncertainty("foreign-key:$child", "Referential action on ${seed.table} is not established.", false)
                        val operations = childOperation?.let(::listOf) ?: Operation.entries
                        // Referential actions are implicit SQL operations, not direct query
                        // dependencies. Keep their exact assigned columns for data-domain rules.
                        val access = childOperation?.let { BehaviorAccess.valueOf(it.name) } ?: BehaviorAccess.UNKNOWN
                        if (childOperation == Operation.DELETE || fk.columns.isEmpty()) {
                            references += BehaviorReference(BehaviorReferenceKind.RELATION, child, access = access)
                        } else fk.columns.forEach { column ->
                            references += BehaviorReference(BehaviorReferenceKind.COLUMN, child, column, access)
                        }
                        operations.forEach { operation -> seeds += Seed(child, operation, fk.columns.toSet(), fk.columns.toSet(),
                            if (action?.uppercase() in setOf("D", "SET DEFAULT")) fk.columns.toSet() else emptySet(),
                            childOperation != null, false) }
                    }
                }
            }
            while (nodes.isNotEmpty()) {
                val visit = nodes.removeFirst()
                if (!visitedNodes.add(visit)) continue
                for (id in closure(visit.id)) {
                    semantic += id
                    if (visit.planning) planning += id
                    val node = graph?.nodes?.get(id)
                    if (node == null) {
                        uncertainty(id, "A reached behavior dependency is absent from the catalog graph.", visit.planning)
                        continue
                    }
                    val transparent = id.startsWith("view:") && id.removePrefix("view:") in transparentViews
                    if (!node.dependenciesComplete || !node.semanticsSupported && !transparent) {
                        val reason = node.opaqueReasons.sorted().joinToString("; ").ifBlank {
                            if (!node.dependenciesComplete) "Catalog dependencies do not enumerate hidden object access."
                            else "Database-side semantics are outside the supported equivalence model."
                        }
                        // Semantic opacity alone does not prevent read-only planning. Dependency
                        // opacity or code that may execute during planning does.
                        uncertainty(id, reason, visit.planning && (!node.dependenciesComplete || node.planningMayEvaluate))
                        if (writing) transformations += id
                    }
                    if (visit.planning && node.planningMayEvaluate) mayEvaluate = true
                    node.references.forEach { reference(it, visit.planning, expand = node.kind != "foreign_key_action") }
                    // FK child activation is derived above from structured constraint columns and
                    // actions, so SET NULL does not accidentally activate a child's DEFAULT.

                }
            }
        }
        fun stableRefs(values: Set<BehaviorReference>) = values.sortedWith(compareBy({ it.kind.name }, { it.name }, { it.column ?: "" }, { it.access.name })).toCollection(linkedSetOf())
        val order = compareBy<BehaviorUncertainty>({ it.nodeId }, { it.reason })
        return OperationBehaviorScope(semantic, stableRefs(references), unknown.sortedWith(order), planning,
            stableRefs(planningReferences), planningUnknown.sortedWith(order), mayEvaluate, transformations)
    }

    private fun attachmentActive(attachment: BehaviorAttachment, seed: Seed): Boolean {
        val applies = seed.operation in attachment.operations ||
            seed.operation in setOf(Operation.UPDATE, Operation.DELETE) && Operation.SELECT in attachment.operations
        if (!applies) return false
        return when (attachment.activation) {
            BehaviorActivation.ALWAYS -> true
            BehaviorActivation.GENERATED -> seed.operation in setOf(Operation.INSERT, Operation.UPDATE)
            BehaviorActivation.COLUMN_TYPE -> !implicitNull(seed, attachment.column) && (attachment.column == null || seed.columns.isEmpty() ||
                attachment.column in seed.columns || seed.operation == Operation.INSERT)
            BehaviorActivation.DEFAULT -> when (seed.operation) {
                Operation.INSERT -> !seed.assignmentsComplete || attachment.column !in seed.writes || attachment.column in seed.defaults
                Operation.UPDATE -> !seed.assignmentsComplete || attachment.column in seed.defaults
                else -> false
            }
        }
    }

    /** An unrelated SET list proves no FK key update only when key transformations are excluded. */
    private fun foreignKeyUnchanged(seed: Seed, keys: Set<String>, facts: QueryBehaviorFacts): Boolean {
        if (seed.operation != Operation.UPDATE || !seed.assignmentsComplete || seed.writes.isEmpty() ||
            keys.isEmpty() || keys.any { it in seed.writes } || !facts.complete ||
            facts.explicitRootNodeIds.isNotEmpty() || facts.builtinFunctionCalls.isNotEmpty()) return false
        val table = snapshot.tables[seed.table] ?: return false
        val security = table.relationSecurity ?: return false
        if (table.kind != "table" || security.userTriggers || security.userRules || security.rowSecurity || security.inheritance ||
            keys.any { it !in table.columns || !table.columns.getValue(it).generated.isNullOrEmpty() }) return false
        if (graph == null && snapshot.behaviors.isNotEmpty() || graph != null && (graph.version != 1 || !graph.attachmentInventoryComplete)) return false
        return attachments[seed.table].orEmpty().filter { attachmentActive(it, seed) }.all { attachment ->
            val node = graph?.nodes?.get(attachment.rootNodeId) ?: return@all false
            node.kind == "foreign_key_action" || closure(attachment.rootNodeId).all { id ->
                graph?.nodes?.get(id)?.let { it.dependenciesComplete && it.semanticsSupported } == true
            }
        }
    }

    /** SQL NULL does not invoke a scalar type's text/binary input routine. Domain/default
     * restrictions are excluded by the positive catalog fact; row triggers/indexes remain roots. */
    private fun implicitNull(seed: Seed, column: String?): Boolean {
        if (column == null || seed.operation != Operation.INSERT || !seed.assignmentsComplete ||
            column in seed.writes && column !in seed.defaults) return false
        val physical = snapshot.tables[seed.table]?.columns?.get(column) ?: return false
        return physical.typeAcceptsImplicitNull == true && physical.defaultExpression == null &&
            physical.identity.isNullOrEmpty() && physical.generated.isNullOrEmpty()
    }

    /** Iterative closure handles strongly connected catalog graphs without recursion. */
    private fun closure(root: String): Set<String> = closureCache.getOrPut(root) {
        val found = sortedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += root
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!found.add(id)) continue
            graph?.nodes?.get(id)?.childNodeIds.orEmpty().forEach(pending::addLast)
        }
        found
    }

    private fun jdbcResultType(value: String): String = when (value.trim().lowercase()) {
        "smallserial", "serial2" -> "smallint"
        "serial", "serial4" -> "integer"
        "bigserial", "serial8" -> "bigint"
        else -> value
    }
    private fun jdbcType(value: String): String = when (value.trim().uppercase()) {
        "TIMESTAMP_WITH_TIMEZONE" -> "timestamp with time zone"
        "TIME_WITH_TIMEZONE" -> "time with time zone"
        "NCHAR" -> "char"
        "NVARCHAR", "LONGNVARCHAR", "LONGVARCHAR" -> "varchar"
        "DOUBLE" -> "double precision"
        "TINYINT" -> "smallint"
        "BINARY", "VARBINARY", "LONGVARBINARY" -> "bytea"
        "SQLXML" -> "xml"
        else -> value
    }
    private fun canonicalType(value: String): String {
        val raw = value.trim().removeSuffix("[]")
        val lower = raw.lowercase().removePrefix("pg_catalog.")
        return if (builtinType(lower)) lower else raw
    }
    private fun builtinType(type: String): Boolean = type in setOf(
        "boolean", "bool", "bit", "smallint", "int2", "integer", "int", "int4", "bigint", "int8",
        "real", "float4", "double precision", "float8", "float", "numeric", "decimal", "text", "varchar",
        "char", "character", "character varying", "bpchar", "date", "time", "timestamp", "timestamptz",
        "timestamp with time zone", "timestamp without time zone", "time with time zone", "time without time zone", "timetz",
        "uuid", "json", "jsonb", "xml", "bytea", "oid", "name", "regclass", "interval",
    ) || Regex("(?:numeric|decimal|character varying|varchar|character|char|bit varying|bit|timestamp|time)\\(\\d+(?:,\\d+)?\\)(?: with(?:out)? time zone)?").matches(type)
}
