package dev.epoch.hibernate

import dev.epoch.core.QueryContract
import dev.epoch.core.UnresolvedQuery
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/** A query is published only after its translation and metadata collection have both completed. */
internal data class ExtractedSql(
    val query: QueryContract? = null,
    val limitations: List<UnresolvedQuery> = emptyList(),
)

/** Query-local recovery does not recover process/resource failures or cancellation. */
internal object QueryExtraction {
    private val logger = LoggerFactory.getLogger(QueryExtraction::class.java)

    fun <T> attempt(id: String, phase: String, unresolved: MutableList<UnresolvedQuery>, action: () -> T): T? =
        try { action() }
        catch (error: Throwable) {
            unresolved += UnresolvedQuery(id, failure(id, phase, error))
            null
        }

    fun record(id: String, phase: String, queries: MutableList<QueryContract>, unresolved: MutableList<UnresolvedQuery>,
               action: () -> ExtractedSql?) {
        val result = attempt(id, phase, unresolved, action) ?: return
        result.query?.let(queries::add)
        unresolved += result.limitations
    }

    /** Original throwable stays in verbose diagnostics, never in the portable contract. */
    fun failure(id: String, phase: String, error: Throwable): String {
        rethrowNonRecoverable(error)
        logger.info("Cannot extract query $id during $phase", error)
        return "$phase: ${describe(error)}"
    }

    fun rethrowNonRecoverable(error: Throwable) =
        ExtractionFailurePolicy.rethrowNonRecoverable(error, recoverQueryStackOverflow = true)

    fun describe(error: Throwable): String = causes(error).take(8).joinToString("; caused by ") { cause ->
        val message = cause.message?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            // Class-loader/object identities and local artifact roots are not reproducible facts.
            .replace(Regex("@[0-9a-fA-F]{4,}"), "@<identity>")
            .replace(Regex("(?:file:)?(?:/[A-Za-z0-9._+%~-]+)+\\.(?:jar|class)(?![A-Za-z0-9])"), "<artifact>")
            .take(512)
        "${cause.javaClass.simpleName}: ${message.ifEmpty { "no additional detail" }}"
    }.take(4096)

    private fun causes(error: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            result += current
            current = current.cause
        }
        return result
    }
}
