package dev.epoch.cli

import dev.epoch.core.*
import java.nio.file.Path
import java.util.Locale

internal fun renderExtractionSummary(contract: DatabaseContract, output: Path, allDetails: Boolean = false): String = buildString {
    val diagnostics = contract.unresolvedQueries
    val sources = diagnostics.flatMap { it.affectedIds.ifEmpty { listOf(it.id) } }.toSet()
    appendLine("Extracted ${contract.entities.size} entity mappings and ${contract.queries.size} SQL templates to $output")
    appendLine("Scope: ${contract.scope}; unresolved sources: ${sources.size} (${diagnostics.size} diagnostic groups)")
    appendLine(renderCoverage(contract, details = allDetails))
    if (diagnostics.isNotEmpty()) {
        appendLine("Unresolved extraction diagnostics:")
        val shown = if (allDetails) diagnostics else diagnostics.take(20)
        shown.forEach { diagnostic ->
            appendLine("  ${diagnostic.id}: ${diagnostic.reason}")
            appendLine(renderAffectedIds(diagnostic.affectedIds.ifEmpty { listOf(diagnostic.id) }, allDetails))
        }
        if (shown.size < diagnostics.size) appendLine("  ${diagnostics.size - shown.size} diagnostic groups omitted; use --details or inspect full JSON.")
    }
    append("Entity discovery does not establish whole-application coverage.")
}

/** A grouped cause retains its complete source inventory in JSON without flooding normal terminal output. */
internal fun renderAffectedIds(affectedIds: List<String>, allDetails: Boolean): String = buildString {
    val ids = affectedIds.distinct()
    val shown = if (allDetails) ids else ids.take(20)
    append("    Affected (${ids.size} total): ${shown.joinToString()}")
    if (shown.size < ids.size) {
        appendLine()
        append("    ${ids.size - shown.size} affected IDs omitted; use --details or inspect full JSON.")
    }
}

/** Percentages describe only the inventory's discovered methods, never all application access. */
fun renderCoverage(contract: DatabaseContract, details: Boolean = false): String =
    renderCoverage(contract.accessPaths, details = details, maxClasses = if (details) Int.MAX_VALUE else 20, inventory = contract.apiInventory)

fun renderCoverage(accessPaths: List<AccessPathCoverage>, details: Boolean = true, maxClasses: Int = 20,
                   inventory: DatabaseApiInventory? = null): String = buildString {
    if (inventory != null) {
        appendLine("Compiled database API inventory (declared scan scope; known API families):")
        appendLine("  ${inventory.inspectedMethodInvocations} method-call instructions inspected; ${inventory.invocations.size} database API invocations")
        appendLine("  " + DatabaseApiRole.entries.joinToString(", ") { role -> "${role.name.lowercase()}=${inventory.invocations.count { it.role == role }}" })
        val byId = accessPaths.associateBy { it.id }
        val queryCalls = inventory.invocations.filter { it.queryCapable }
        val covered = queryCalls.count { invocation -> invocation.accessPathIds.isNotEmpty() &&
            invocation.accessPathIds.all { byId[it]?.status in setOf(AccessPathStatus.STATIC, AccessPathStatus.DECLARED) } }
        appendLine("  Query-capable invocation paths: $covered/${queryCalls.size} statically extracted or linked; ${queryCalls.size - covered} unresolved")
        appendLine("  Unexpanded database API method references: ${inventory.methodReferenceAccessPathIds.size} (separate potential access paths)")
        appendLine("  Repository/annotation declarations: ${inventory.declarationAccessPathIds.size}; runtime observations are separate")
        appendLine("  Inventory extent: ${if (inventory.completeWithinDeclaredScope) "complete within declared known-API scope" else "incomplete; unavailable bytecode, ancestry or unexpanded references mean the full denominator is unknown"}")
        appendLine("  Unreadable sources=${inventory.failedSources.size}; unresolved owner groups=${inventory.ownerGaps.size}")
        if (details) inventory.ownerGaps.forEach { gap -> appendLine("    ${gap.owner}: ${gap.reason}; affected=${gap.callSiteIds.joinToString()}") }
    }
    appendLine("Repository coverage (discovered methods in the declared scan scope):")
    if (accessPaths.isEmpty() && inventory?.invocations.isNullOrEmpty()) {
        append("  No repository method inventory is available; entity SQL is not repository coverage.")
        return@buildString
    }
    val discovered = accessPaths.groupBy { it.className }
    val callsByClass = inventory?.invocations.orEmpty().groupBy { it.className }
    val classes = (discovered.keys + callsByClass.keys).sorted()
    for (className in classes.take(maxClasses)) {
        val paths = discovered[className].orEmpty()
        if (inventory != null) {
            val calls = callsByClass[className].orEmpty()
            val queryCalls = calls.filter { it.queryCapable }
            val byId = paths.associateBy { it.id }
            val covered = queryCalls.count { call -> call.accessPathIds.isNotEmpty() && call.accessPathIds.all {
                byId[it]?.status in setOf(AccessPathStatus.STATIC, AccessPathStatus.DECLARED) } }
            appendLine("  $className: ${calls.size} raw API invocations; $covered/${queryCalls.size} query-capable sites extracted or linked")
        }
        val methods = paths.groupBy { it.method }
        if (methods.isEmpty()) { appendLine("    No query-access paths in the inspected calls."); continue }
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
    if (classes.size > maxClasses) appendLine("  ${classes.size - maxClasses} classes omitted; full method inventory is retained in JSON and --details.")
    if (!details && classes.size <= maxClasses) appendLine("  Per-method access paths are retained in JSON; use --details to print them.")
    append("Runtime observations and static discovery do not establish exhaustive variants or unscanned application coverage.")
}.trimEnd()
