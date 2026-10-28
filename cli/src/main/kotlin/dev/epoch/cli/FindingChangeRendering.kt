package dev.epoch.cli

import dev.epoch.core.*

internal fun StringBuilder.appendFindingTransitions(step: MigrationReport) {
    val changes = step.findingChanges ?: return // Legacy reports do not have origin evidence.
    fun count(kind: FindingChangeKind) = changes.filter { it.kind == kind }
        .sumOf { maxOf(1, it.affectedContractIds.size) }
    appendLine("  Finding transitions: NEW ${count(FindingChangeKind.NEW)}, PERSISTING ${count(FindingChangeKind.PERSISTING)}, " +
        "no longer reported ${count(FindingChangeKind.RESOLVED)} (per affected contract ID or unscoped finding).")
}

internal fun StringBuilder.appendFindingOrigins(changes: List<FindingChange>, allDetails: Boolean) {
    val limit = if (allDetails) Int.MAX_VALUE else 5
    changes.take(limit).forEach { change ->
        appendLine("    ${change.kind}; first observed: ${change.firstObserved.version} ${change.firstObserved.filename}" +
            if (change.affectedContractIds.isEmpty()) " (unscoped finding)" else " (${change.affectedContractIds.size} affected contract IDs)")
    }
    if (changes.size > limit) appendLine("    ${changes.size - limit} additional origins retained in JSON; use report --details.")
}
