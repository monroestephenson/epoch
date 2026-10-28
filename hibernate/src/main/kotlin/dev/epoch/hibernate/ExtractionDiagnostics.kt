package dev.epoch.hibernate

import dev.epoch.core.UnresolvedQuery
import java.security.MessageDigest

/** Per-extraction grouping retains every source without duplicating its cause or original trace. */
internal class ExtractionDiagnostics {
    private data class Group(val id: String, val reason: String, val sources: MutableSet<String> = sortedSetOf())
    private val groups = linkedMapOf<String, Group>()

    fun capture(sourceId: String, phase: String, error: Throwable): String {
        QueryExtraction.rethrowNonRecoverable(error)
        val key = QueryExtraction.failureKey(phase, error)
        val id = "extraction-diagnostic:" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val group = groups.getOrPut(id) {
            // Log the original throwable exactly once per normalized cause, with a located source.
            Group(id, QueryExtraction.failure(sourceId, phase, error))
        }
        group.sources += sourceId
        return reference(id)
    }

    fun attach(sourceId: String, marker: String?): Boolean {
        val group = groups[marker?.removePrefix(PREFIX)] ?: return false
        if (marker != reference(group.id)) return false
        group.sources += sourceId
        return true
    }

    fun resolve(marker: String?): String? {
        val group = groups[marker?.removePrefix(PREFIX)] ?: return marker
        return if (group.sources.size == 1) group.reason else reference(group.id)
    }

    fun unresolved(): List<UnresolvedQuery> = groups.values.map { group ->
        val sources = group.sources.toList()
        // Preserve longstanding exact source IDs when a diagnostic has no repeated occurrence.
        UnresolvedQuery(if (sources.size == 1) sources.single() else group.id, group.reason, sources)
    }.sortedBy { it.id }

    companion object {
        private const val PREFIX = "Extraction diagnostic "
        fun reference(id: String): String = PREFIX + id
    }
}
