package dev.epoch.cli

import dev.epoch.core.*

internal data class FindingDisplayEntry(
    val members: List<Finding>,
    val group: FindingGroup?,
    val changes: List<FindingChange>,
)

/** Validated optional indexes affect presentation only; details always retain every raw finding. */
internal fun findingDisplayEntries(findings: List<Finding>, groups: List<FindingGroup>?,
                                  changes: FindingChangeIndex?, allDetails: Boolean): List<FindingDisplayEntry> {
    val index = FindingGroupIndex(findings, if (allDetails) null else groups)
    val emitted = mutableSetOf<String>()
    return findings.mapIndexedNotNull { position, finding ->
        val group = index.forFinding(position)
        if (group != null && !emitted.add(group.id)) return@mapIndexedNotNull null
        val members = group?.findingIndices?.map(findings::get) ?: listOf(finding)
        FindingDisplayEntry(members, group, members.flatMap { changes?.forFinding(it).orEmpty() }.distinct())
    }.sortedWith(compareBy<FindingDisplayEntry> { severity(it.members.first().verdict) }
        .thenBy { if (it.changes.any { change -> change.kind == FindingChangeKind.NEW }) 0 else 1 })
}

internal fun StringBuilder.appendFindingGroup(group: FindingGroup, members: List<Finding>, changes: List<FindingChange>) {
    val label = when (group.kind) {
        FindingGroupKind.SHARED_CATALOG_DEPENDENCY -> "Shared catalog dependency"
        FindingGroupKind.IDENTICAL_SQL_TEMPLATE -> "Identical typed SQL template"
        FindingGroupKind.INVENTORY -> "Shared inventory failure"
    }
    val sources = group.sourceIds.take(5).joinToString() +
        if (group.sourceIds.size > 5) " (+${group.sourceIds.size - 5} more sources retained in JSON)" else ""
    appendLine("  ${group.verdict} [${group.ruleId}] $label: $sources")
    val objects = members.map { it.objectName }.distinct().sorted()
    val callers = members.flatMap { it.affectedContractIds }.distinct().sorted()
    appendLine("    ${members.size} findings, ${objects.size} affected objects, ${callers.size} affected contract IDs; all member evidence retained in JSON.")
    appendLine("    Objects: " + objects.take(5).joinToString() +
        if (objects.size > 5) " (+${objects.size - 5} more; use report --details)" else "")
    appendGroupOrigins(changes)
    if (callers.isNotEmpty()) appendLine(renderAffectedIds(callers, false))
    members.take(3).forEach { member ->
        appendLine("    Example ${member.objectName}: ${member.message}")
        appendLine("      Evidence: ${member.evidence}")
        if (member.verdict == Verdict.UNKNOWN) appendLine("      Next evidence: ${member.mitigation ?: unknownEvidence(member)}")
    }
    if (members.size > 3) appendLine("    ${members.size - 3} additional member findings retained in JSON; use report --details.")
}

private fun StringBuilder.appendGroupOrigins(changes: List<FindingChange>) {
    val origins = changes.groupBy { it.kind to it.firstObserved }.entries.sortedWith(
        compareBy({ it.key.first.ordinal }, { it.key.second.version }, { it.key.second.filename }))
    origins.take(5).forEach { (key, values) ->
        val (kind, migration) = key
        val objects = values.map { it.objectName }.distinct().size
        val callers = values.flatMap { it.affectedContractIds }.distinct().size
        val anonymous = values.filter { it.affectedContractIds.isEmpty() }.map { it.objectName }.distinct().size
        appendLine("    $kind; first observed: ${migration.version} ${migration.filename} " +
            "($objects affected objects; $callers affected contract IDs" +
            (if (anonymous > 0) "; $anonymous unscoped objects" else "") + ")")
    }
    if (origins.size > 5) appendLine("    ${origins.size - 5} additional origins retained in JSON; use report --details.")
}
