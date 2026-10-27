package dev.epoch.postgres

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.DatabaseContract
import dev.epoch.core.QueryContract
import dev.epoch.core.SchemaSnapshot
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class BaselinePlans(
    val plans: Map<String, QueryPlan>, val status: String, val key: String?, val reason: String?,
    val plannedQueries: Int, val reusedQueries: Int, val planningParallelism: Int,
)

internal data class PlanCacheEntry(val format: Int, val key: String, val checksum: String, val plans: Map<String, QueryPlan>)

/** Immutable single-document entries: warm readers need no writer lock and can never observe a half-written plan set. */
internal class BaselinePlanCache(private val directory: Path?, private val sessionSettings: Map<String, String?> = emptyMap()) {
    private val mapper = jacksonObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    fun prepare(contract: DatabaseContract, baselineKey: String, snapshot: SchemaSnapshot,
                compute: (List<QueryContract>) -> QueryPlanningBatch): BaselinePlans {
        val queries = contract.queries
        val safety = PlanningSafety(snapshot)
        val eligible = queries.filter(safety::eligible).map { it.id }.toSet()
        val key = digest(listOf(FORMAT, contract, baselineKey, snapshot, sessionSettings))
        if (directory == null || eligible.isEmpty()) {
            val result = compute(queries)
            return BaselinePlans(result.plans, if (directory == null) "DISABLED" else "BYPASSED", if (directory == null) null else key,
                if (directory == null) "Baseline plan caching is disabled." else "No queries have supported deterministic structural planning inputs; opaque, observed and data-dependent paths are rechecked.",
                queries.size, 0, result.planningParallelism)
        }
        val root = directory.resolve("checks")
        Files.createDirectories(root)
        val path = root.resolve("$key.json")
        val exists = Files.exists(path)
        var reason: String? = if (exists) null else "No baseline query-plan entry exists for this contract, baseline, catalog and planner format."
        val cached = if (!exists) emptyMap() else try {
            require(Files.isRegularFile(path) && Files.size(path) <= 64L * 1024 * 1024) { "Plan cache entry is not a supported regular document." }
            val entry: PlanCacheEntry = mapper.readValue(path.toFile())
            require(entry.format == FORMAT && entry.key == key) { "Plan cache format or fingerprint mismatch." }
            require(entry.plans.keys == eligible) { "Plan cache query inventory differs from the eligible contract." }
            require(entry.checksum == digest(entry.plans)) { "Plan cache checksum mismatch." }
            entry.plans
        } catch (failure: Exception) {
            reason = "Baseline query-plan cache was rejected: ${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(1000)}"
            emptyMap()
        }
        val missing = queries.filter { it.id !in cached }
        // Failures propagate before publication; infrastructure failures never become cache entries.
        val computed = compute(missing)
        val plans = queries.associate { it.id to (cached[it.id] ?: computed.plans.getValue(it.id)) }
        if (cached.isEmpty()) {
            val stored = plans.filterKeys { it in eligible }
            val temporary = Files.createTempFile(root, "$key-", ".tmp")
            try {
                mapper.writeValue(temporary.toFile(), PlanCacheEntry(FORMAT, key, digest(stored), stored))
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(temporary) }
        }
        return BaselinePlans(plans, if (cached.isNotEmpty()) "HIT" else if (exists) "REBUILT" else "MISS", key, reason,
            missing.size, cached.size, computed.planningParallelism)
    }

    private fun digest(value: Any) = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))
        .joinToString("") { "%02x".format(it) }

    companion object { private const val FORMAT = 1 }
}
