package dev.epoch.core

/**
 * Describes successive checked states without changing evidence, verdicts or migration attribution.
 * Identity is per affected caller, so growth of a grouped cause does not reattribute its old callers.
 */
object FindingEpisodes {
    fun annotate(steps: List<MigrationReport>): List<MigrationReport> {
        var previous = emptyMap<FindingMember, MigrationInfo>()
        return steps.map { step ->
            val current = step.findings.asSequence().filter { it.verdict != Verdict.SAFE }
                .flatMap { members(it).asSequence() }.toSet()
            val active = current.associateWith { previous[it] ?: step.migration }
            val changes = current.map { member -> member to ChangeState(
                if (member in previous) FindingChangeKind.PERSISTING else FindingChangeKind.NEW, active.getValue(member)) } +
                previous.filterKeys { it !in current }.map { (member, origin) -> member to ChangeState(FindingChangeKind.RESOLVED, origin) }
            previous = active
            step.copy(findingChanges = grouped(changes))
        }
    }

    /** Baseline facts stay separate even when a rediscovered grouped cause gains or loses callers. */
    internal fun subtractBaseline(steps: List<MigrationReport>, baseline: BaselineReport): List<MigrationReport> {
        val known = baseline.findings.flatMap(::members).toSet()
        val invalidObjects = baseline.findings.filter { it.ruleId.startsWith("INVALID_BASELINE_") }
            .map { it.ruleId to it.objectName }.toSet()
        return steps.map { step ->
            val retained = step.findings.mapIndexedNotNull { index, finding ->
                val updated = when {
                    (finding.ruleId to finding.objectName) in invalidObjects -> null
                    step.catalogChanged == false && finding.verdict == Verdict.SAFE -> null
                    else -> {
                        val original = members(finding)
                        val remaining = original.filter { it !in known }
                        if (remaining.isEmpty()) null
                        else if (remaining.size == original.size) finding
                        else finding.copy(affectedContractIds = remaining.mapNotNull { it.caller }.sorted())
                    }
                }
                updated?.let { index to it }
            }
            val findings = retained.map { it.second }
            val indexMap = retained.mapIndexed { index, old -> old.first to index }.toMap()
            step.copy(findings = findings, verdict = CompatibilityEngine.aggregate(findings.map { it.verdict } + Verdict.SAFE),
                findingGroups = FindingGrouping.remap(step.findingGroups, step.findings, indexMap, findings))
        }
    }
}

/** O(total caller memberships) index for text/SARIF consumers; historical resolutions are separate. */
class FindingChangeIndex(step: MigrationReport) {
    private val current = buildMap {
        step.findingChanges.orEmpty().filter { it.kind != FindingChangeKind.RESOLVED }.forEach { change ->
            val callers = change.affectedContractIds.ifEmpty { listOf<String?>(null) }
            callers.forEach { caller -> put(FindingMember(change.verdict, change.ruleId, change.objectName, caller),
                ChangeState(change.kind, change.firstObserved)) }
        }
    }

    fun forFinding(finding: Finding): List<FindingChange> = grouped(members(finding).mapNotNull { member ->
        current[member]?.let { member to it }
    })
}

private data class FindingMember(val verdict: Verdict, val rule: String, val objectName: String, val caller: String?)
private data class ChangeState(val kind: FindingChangeKind, val firstObserved: MigrationInfo)
private data class ChangeGroup(val verdict: Verdict, val rule: String, val objectName: String,
                               val state: ChangeState, val anonymous: Boolean)

private fun members(finding: Finding): List<FindingMember> = finding.affectedContractIds.distinct()
    .ifEmpty { listOf<String?>(null) }.map { FindingMember(finding.verdict, finding.ruleId, finding.objectName, it) }

private fun grouped(changes: Iterable<Pair<FindingMember, ChangeState>>): List<FindingChange> = changes.groupBy { (member, state) ->
    ChangeGroup(member.verdict, member.rule, member.objectName, state, member.caller == null)
}.map { (key, values) ->
    FindingChange(key.rule, key.objectName, key.verdict, values.mapNotNull { it.first.caller }.distinct().sorted(),
        key.state.kind, key.state.firstObserved)
}.sortedWith(compareBy<FindingChange> { when (it.verdict) { Verdict.BREAKING -> 0; Verdict.UNKNOWN -> 1; Verdict.SAFE -> 2 } }
    .thenBy { it.kind.ordinal }.thenBy { it.ruleId }.thenBy { it.objectName }
    .thenBy { it.firstObserved.version }.thenBy { it.firstObserved.filename }.thenBy { it.affectedContractIds.isNotEmpty() }
    .thenBy { it.affectedContractIds.joinToString("\u0000") })
