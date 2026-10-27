package dev.epoch.cli

import dev.epoch.core.*
import java.util.Locale

/** Percentages describe only the inventory's discovered methods, never all application access. */
fun renderCoverage(contract: DatabaseContract): String = renderCoverage(contract.accessPaths)

fun renderCoverage(accessPaths: List<AccessPathCoverage>, details: Boolean = true, maxClasses: Int = 20): String = buildString {
    appendLine("Repository coverage (discovered methods in the declared scan scope):")
    if (accessPaths.isEmpty()) {
        append("  No repository method inventory is available; entity SQL is not repository coverage.")
        return@buildString
    }
    val classes = accessPaths.groupBy { it.className }.toSortedMap()
    for ((className, paths) in classes.entries.take(maxClasses)) {
        val methods = paths.groupBy { it.method }
        val totals = methods.values.groupingBy { methodPaths ->
            when {
                methodPaths.any { it.status == AccessPathStatus.UNRESOLVED } -> AccessPathStatus.UNRESOLVED
                methodPaths.any { it.status == AccessPathStatus.OBSERVED } -> AccessPathStatus.OBSERVED
                methodPaths.all { it.status == AccessPathStatus.DECLARED } -> AccessPathStatus.DECLARED
                else -> AccessPathStatus.STATIC
            }
        }.eachCount()
        val summary = AccessPathStatus.entries.joinToString(", ") { status ->
            val count = totals[status] ?: 0
            val percentage = String.format(Locale.ROOT, "%.1f", count.toDouble() * 100 / methods.size)
            "${status.name.lowercase()}=$count ($percentage%)"
        }
        appendLine("  $className: ${methods.size} methods; $summary")
        if (details) paths.sortedBy { it.id }.forEach {
            appendLine("    ${it.status} ${it.id}: ${it.reason ?: it.queryIds.joinToString()}")
        }
    }
    if (classes.size > maxClasses) appendLine("  ${classes.size - maxClasses} classes omitted; full method inventory is retained in JSON and report --details.")
    append("Runtime observations and static discovery do not establish exhaustive variants or unscanned application coverage.")
}.trimEnd()
