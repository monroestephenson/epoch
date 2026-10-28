package dev.epoch.core

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest

enum class FindingGroupKind { SHARED_CATALOG_DEPENDENCY, IDENTICAL_SQL_TEMPLATE, INVENTORY }

/** Presentation-only references into this report section's original findings. No evidence is removed. */
data class FindingGroup(
    val id: String,
    val ruleId: String,
    val verdict: Verdict,
    val kind: FindingGroupKind,
    val sourceIds: List<String>,
    val findingIndices: List<Int>,
)

/** Optional saved-report metadata cannot hide raw evidence if indexes or membership are invalid. */
class FindingGroupIndex(findings: List<Finding>, groups: List<FindingGroup>?) {
    val groups: List<FindingGroup>
    private val byIndex: Map<Int, FindingGroup>
    init {
        val candidates = groups.orEmpty().filter { group ->
            group.id.isNotBlank() && group.ruleId in FindingGrouping.RULES && group.verdict != Verdict.SAFE &&
                group.sourceIds.isNotEmpty() && group.sourceIds.all { it.isNotBlank() } &&
                group.sourceIds.distinct().size == group.sourceIds.size && group.findingIndices.size >= 2 &&
                group.findingIndices.distinct().size == group.findingIndices.size &&
                group.findingIndices.all { index -> index in findings.indices &&
                    findings[index].ruleId == group.ruleId && findings[index].verdict == group.verdict } &&
                group.findingIndices.map { findings[it].migration }.distinct().size == 1
        }
        val idCounts = candidates.groupingBy { it.id }.eachCount()
        val memberCounts = candidates.flatMap { it.findingIndices }.groupingBy { it }.eachCount()
        this.groups = candidates.filter { idCounts.getValue(it.id) == 1 &&
            it.findingIndices.all { index -> memberCounts.getValue(index) == 1 } }
            .map { it.copy(findingIndices = it.findingIndices.sorted(), sourceIds = it.sourceIds.sorted()) }
            .sortedWith(compareBy({ severity(it.verdict) }, { it.ruleId }, { it.id }))
        byIndex = this.groups.flatMap { group -> group.findingIndices.map { it to group } }.toMap()
    }
    fun forFinding(index: Int): FindingGroup? = byIndex[index]
    private fun severity(verdict: Verdict) = when (verdict) { Verdict.BREAKING -> 0; Verdict.UNKNOWN -> 1; Verdict.SAFE -> 2 }
}

/** Groups established shared sources, never matching only generic exception/reason wording. */
internal class FindingGrouping(
    contract: DatabaseContract,
    before: SchemaSnapshot,
    after: SchemaSnapshot,
) {
    private val queries by lazy { contract.queries.groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() } }
    private val graphs by lazy { listOfNotNull(before.behaviorGraph, after.behaviorGraph)
        .takeIf { before.behaviorGraph != null && after.behaviorGraph != null &&
            it.all { graph -> graph.version == 1 && graph.attachmentInventoryComplete } }.orEmpty() }
    private val collectors by lazy { graphs.map(::CatalogSources) }
    private val templates = mutableMapOf<String, Source?>()
    private val objects = mutableMapOf<String, Source?>()
    private val catalogIdentities = mutableMapOf<List<String>, Source>()
    private data class Source(val kind: FindingGroupKind, val ids: List<String>, val identity: String)
    private data class Key(val rule: String, val verdict: Verdict, val source: Source)

    fun group(findings: List<Finding>): List<FindingGroup> = findings.mapIndexedNotNull { index, finding ->
        if (finding.ruleId !in RULES || finding.verdict == Verdict.SAFE) null
        else source(finding.objectName)?.let { Key(finding.ruleId, finding.verdict, it) to index }
    }.groupBy({ it.first }, { it.second }).filterValues { it.size >= 2 }.map { (key, indices) ->
        FindingGroup("cause:" + hash(listOf("finding-group-v1", key.rule, key.verdict, key.source)),
            key.rule, key.verdict, key.source.kind, key.source.ids, indices.sorted())
    }.sortedWith(compareBy({ it.ruleId }, { it.id }))

    private fun source(objectName: String): Source? {
        if (objectName == "catalog:behavior-inventory") return Source(FindingGroupKind.INVENTORY,
            listOf(objectName), hash(listOf("inventory", objectName)))
        if (objectName.startsWith("query:")) return templates.getOrPut(objectName) {
            val query = queries[objectName.removePrefix("query:")] ?: return@getOrPut null
            // Keep mapping/decoder facts, ordered parameters/results, variant and verification status.
            // Only source locations and the contract entry ID are excluded from template identity.
            val canonical = query.copy(id = "", accessPathIds = emptyList(), observationFrames = emptyList(),
                tables = query.tables.distinct().sorted(), columns = query.columns.distinct().sortedWith(columnOrder),
                writeColumns = query.writeColumns.distinct().sortedWith(columnOrder),
                mappedEntityIds = query.mappedEntityIds.distinct().sorted())
            val identity = hash(listOf("typed-query-v1", canonical))
            Source(FindingGroupKind.IDENTICAL_SQL_TEMPLATE, listOf("sql:$identity"), identity)
        }
        return objects.getOrPut(objectName) {
            if (collectors.isEmpty()) return@getOrPut null
            val roots = collectors.mapNotNull { collector -> collector.roots(objectName).takeIf { it.isNotEmpty() }
                ?.let { collector to it } }
            if (roots.isEmpty()) return@getOrPut null
            val sources = sortedSetOf<String>()
            for ((collector, ids) in roots) for (root in ids) {
                val found = collector.sources(root) ?: return@getOrPut null
                sources += found
            }
            if (sources.isEmpty()) return@getOrPut null
            val ids = sources.toList()
            catalogIdentities.getOrPut(ids) {
                // Preserve before/after position and explicit absence; dropping a source is distinct
                // from keeping its old definition in both states. Cache shared definitions once.
                val identities = ids.map { id -> id to graphs.map { it.nodes[id] } }
                Source(FindingGroupKind.SHARED_CATALOG_DEPENDENCY, ids, hash(identities))
            }
        }
    }

    private class CatalogSources(private val graph: BehaviorGraph) {
        private val attachments = graph.attachments.filter { it.objectName != null }.groupBy { it.objectName }
        private val cache = mutableMapOf<String, Set<String>?>()
        fun roots(objectName: String): Set<String> = if (objectName in graph.nodes) setOf(objectName)
            else attachments[objectName].orEmpty().mapTo(sortedSetOf()) { it.rootNodeId }
        fun sources(root: String): Set<String>? {
            val node = graph.nodes[root] ?: return null
            // Cache shared children rather than rewalking one function graph for every CHECK/default.
            val result = sortedSetOf<String>()
            if (namedOpaque(root, node)) result += root
            for (child in node.childNodeIds) result += closure(child) ?: return null
            return result
        }
        private fun closure(root: String): Set<String>? {
            if (cache.containsKey(root)) return cache[root]
            val pending = ArrayDeque<String>(); pending += root
            val visited = mutableSetOf<String>()
            val found = sortedSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!visited.add(id)) continue
                if (visited.size > 20_000) { cache[root] = null; return null }
                val node = graph.nodes[id] ?: run { cache[root] = null; return null }
                if (namedOpaque(id, node)) found += id
                node.childNodeIds.forEach(pending::addLast)
            }
            return found.also { cache[root] = it }
        }
        private fun namedOpaque(id: String, node: BehaviorNode): Boolean =
            (!node.dependenciesComplete || !node.semanticsSupported) && node.kind !in ATTACHMENT_KINDS &&
                (id.startsWith("function:") || id.startsWith("type:") || id.startsWith("catalog:") || id.startsWith("collation:"))
    }

    companion object {
        internal val RULES = setOf("DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN", "EXISTING_DATABASE_BEHAVIOR", "RELATION_RENAME_EXPRESSION_UNPROVEN")
        private val ATTACHMENT_KINDS = setOf("default", "generated", "check", "index", "trigger", "policy", "view", "rule", "foreign_key_action")
        private val columnOrder = compareBy<ColumnReference>({ it.table }, { it.column })
        private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build())
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
        private fun hash(value: Any): String = MessageDigest.getInstance("SHA-256")
            .digest(mapper.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }

        /** Caller supplies the exact index mapping created by its filter; changed caller sets are allowed. */
        internal fun remap(groups: List<FindingGroup>?, original: List<Finding>, oldToNew: Map<Int, Int>,
                           retained: List<Finding>): List<FindingGroup>? =
            groups?.let { FindingGroupIndex(original, it).groups }?.mapNotNull { group ->
                val indices = group.findingIndices.mapNotNull(oldToNew::get).distinct().sorted()
                if (indices.size < 2) null else group.copy(findingIndices = indices)
            }?.let { FindingGroupIndex(retained, it).groups }
    }
}
