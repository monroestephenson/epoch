package dev.epoch.hibernate

import dev.epoch.core.DatabaseApiRole
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.util.IdentityHashMap

internal data class InvocationSite(val id: String, val ordinal: Int, val instructionIndex: Int, val line: Int?)
internal object InvocationSites {
    fun index(owner: String, method: MethodNode): Map<MethodInsnNode, InvocationSite> {
        val result = IdentityHashMap<MethodInsnNode, InvocationSite>()
        var ordinal = 0
        var line: Int? = null
        method.instructions.toArray().forEachIndexed { instructionIndex, instruction ->
            if (instruction is LineNumberNode) line = instruction.line
            if (instruction is MethodInsnNode) {
                result[instruction] = InvocationSite("${owner.replace('/', '.')}#${method.name}${method.desc}:${ordinal}",
                    ordinal++, instructionIndex, line)
            }
        }
        return result
    }
}

internal data class QueryOrigin(val siteId: String, val family: String)
internal data class QueryEffect(val siteId: String, val reason: String)
internal data class TerminalLink(val sourceQueryIds: List<String>, val unresolved: List<String>)

/** Immutable origins flow through ASM values. Effects are collected after convergence, per origin. */
internal class QueryOrigins(
    private val sites: Map<MethodInsnNode, InvocationSite>,
    private val calls: Map<MethodInsnNode, DatabaseCall>,
    private val linearControlFlow: Boolean = false,
) {
    private val effects = sortedMapOf<String, MutableSet<QueryEffect>>()
    private data class PaginationChange(val siteId: String, val instructionIndex: Int, val name: String, val value: Int)
    private val pagination = sortedMapOf<String, MutableSet<PaginationChange>>()
    private val executions = sortedMapOf<String, MutableSet<Int>>()
    private val supportedPaginationCalls = sortedSetOf<String>()

    /** No control-flow products are invented: this initial model supports literal settings on local straight-line HQL. */
    fun paginationVariants(originId: String): List<QueryPagination> {
        val changes = pagination[originId].orEmpty().sortedBy { it.instructionIndex }
        if (changes.isEmpty()) return listOf(QueryPagination())
        return executions[originId].orEmpty().sorted().map { execution ->
            var state = QueryPagination()
            changes.filter { it.instructionIndex < execution }.forEach { change ->
                state = when (change.name) {
                    "setFirstResult" -> state.copy(firstResult = change.value)
                    "setMaxResults" -> state.copy(maxResults = change.value)
                    else -> error("Unexpected pagination operation ${change.name}")
                }
            }
            state
        }.distinct()
    }

    fun supportsPagination(siteId: String) = siteId in supportedPaginationCalls

    fun factory(call: MethodInsnNode, actual: DatabaseCall? = calls[call]): QueryOrigin? {
        val classification = actual ?: return null
        if (classification.reason != null || classification.role != DatabaseApiRole.FACTORY || classification.translation !in setOf("PANACHE", "JPA_HQL", "JPA_NATIVE", "JPA_NAMED")) return null
        return QueryOrigin(sites.getValue(call).id, if (classification.translation == "PANACHE") "PANACHE_QUERY" else "ORM_QUERY")
    }

    /** Interpreter hook: return a new immutable value containing the receiver's origins. */
    fun returnsReceiver(call: MethodInsnNode): Boolean {
        val classification = calls[call] ?: return false
        return classification.role in setOf(DatabaseApiRole.MODIFIER, DatabaseApiRole.BINDING) &&
            Type.getReturnType(call.desc).sort == Type.OBJECT
    }

    /** Called after Analyzer completes, using each instruction's incoming frame, never from naryOperation. */
    fun observeCall(call: MethodInsnNode, receiver: Set<QueryOrigin>?, argumentOrigins: List<Set<QueryOrigin>?>,
                    bindingShapeProven: Boolean = false, paginationValue: Int? = null,
                    receiverExtentUnknown: Boolean = false) {
        val site = sites.getValue(call).id
        val known = calls[call]
        val literalPagination = known?.translation == "QUERY_PAGINATION" && linearControlFlow && !receiverExtentUnknown &&
            paginationValue != null && paginationValue >= 0 &&
            (call.name != "setMaxResults" || paginationValue > 0) && !receiver.isNullOrEmpty() && receiver.all { origin ->
                origin.family == "ORM_QUERY" && calls.entries.any { (instruction, classification) ->
                    sites[instruction]?.id == origin.siteId && classification.translation == "JPA_HQL" && classification.reason == null
                }
            }
        if (literalPagination) {
            supportedPaginationCalls += site
            receiver.orEmpty().forEach { origin -> pagination.getOrPut(origin.siteId) { linkedSetOf() } +=
                PaginationChange(site, sites.getValue(call).instructionIndex, call.name, requireNotNull(paginationValue)) }
        }
        if (known?.translation == "QUERY_TERMINAL" && known.reason == null)
            receiver.orEmpty().forEach { origin -> executions.getOrPut(origin.siteId) { sortedSetOf() } += sites.getValue(call).instructionIndex }
        val reason = when {
            known == null -> "Query escapes through an unclassified application call"
            known.reason != null -> known.reason
            known.translation == "QUERY_PAGINATION" && call.name == "setMaxResults" && paginationValue == 0 ->
                "maxResults=0 can skip SQL execution in Hibernate; no executable SQL template is asserted for this pagination state"
            known.translation == "QUERY_PAGINATION" && !literalPagination ->
                "Pagination requires a nonnegative constant on a local HQL query with straight-line control flow; dynamic, escaped, named/native or branch-dependent pagination is unresolved"
            known.role == DatabaseApiRole.MODIFIER && !literalPagination -> "Query modifier requires additional SQL variants"
            known.role == DatabaseApiRole.BINDING && !bindingShapeProven -> "Binding/cardinality semantics are not proven shape-neutral"
            known.role == DatabaseApiRole.UNSUPPORTED -> "Query receiver reaches an unsupported API method"
            else -> null
        }
        if (reason != null) receiver.orEmpty().forEach { add(it, site, reason) }
        // Passing a query as an argument can allow alias mutation even when a different receiver is used.
        argumentOrigins.filterNotNull().flatten().forEach { add(it, site, "Query escapes as an argument to ${call.owner}.${call.name}") }
    }

    fun escape(origins: Set<QueryOrigin>?, sourceId: String, reason: String) {
        origins.orEmpty().forEach { add(it, sourceId, reason) }
    }

    /** Invoked only after Analyzer converges; discarded candidates cannot evade later effects. */
    fun unresolvedMethodOrigins(sourceId: String, reason: String) {
        calls.filterValues { it.role == DatabaseApiRole.FACTORY }.keys.forEach { call ->
            effects.getOrPut(sites.getValue(call).id) { linkedSetOf() } += QueryEffect(sourceId, reason)
        }
    }

    private fun add(origin: QueryOrigin, sourceId: String, reason: String) {
        effects.getOrPut(origin.siteId) { linkedSetOf() } += QueryEffect(sourceId, reason)
    }

    fun limitations(originId: String): List<QueryEffect> = (effects[originId].orEmpty() +
        if (!pagination[originId].isNullOrEmpty() && executions[originId].isNullOrEmpty())
            listOf(QueryEffect(originId, "Paginated query has no locally established execution; declare its complete SQL variants")) else emptyList())
        .sortedWith(compareBy({ it.siteId }, { it.reason }))

    fun terminal(origins: Set<QueryOrigin>?, extractedSources: Map<String, List<String>>, family: String,
                 extentUnknown: Boolean = false): TerminalLink {
        if (origins.isNullOrEmpty()) return TerminalLink(emptyList(), listOf(
            "Query execution has no locally established factory origin; returned, field-held, or erased query producers remain unresolved"))
        val ids = sortedSetOf<String>()
        val failures = sortedSetOf<String>()
        if (extentUnknown) failures += "Query receiver also has an unestablished producer; its complete SQL origin set is unresolved"
        for (origin in origins.sortedBy { it.siteId }) {
            if (origin.family != family) { failures += "Query origin ${origin.siteId} belongs to ${origin.family}, not $family"; continue }
            val effects = limitations(origin.siteId)
            if (effects.isNotEmpty()) failures += effects.map { "${it.siteId}: ${it.reason}" }
            val queryIds = extractedSources[origin.siteId]
            if (queryIds.isNullOrEmpty()) failures += "Factory ${origin.siteId} has unresolved SQL/HQL variants"
            else ids += queryIds
        }
        return TerminalLink(ids.toList(), failures.toList())
    }
}
