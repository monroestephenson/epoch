package dev.epoch.cli

import dev.epoch.core.*
import java.net.URI

/** Migration-wide evidence has a file location, never an invented causal SQL line. */
fun sarifReport(reports: List<Pair<String, CompatibilityReport>>, migrationPrefix: String = ""): Map<String, Any> {
    data class Entry(val direction: String, val finding: Finding, val changes: List<FindingChange> = emptyList(),
                     val groupId: String? = null)
    val entries = reports.flatMap { (direction, report) ->
        val baselineGroups = FindingGroupIndex(report.baseline?.findings.orEmpty(), report.baseline?.findingGroups)
        report.baseline?.findings.orEmpty().mapIndexed { index, finding ->
            Entry(direction, finding, groupId = baselineGroups.forFinding(index)?.id)
        } + report.migrations.flatMap { step ->
            val changes = FindingChangeIndex(step)
            val groups = FindingGroupIndex(step.findings, step.findingGroups)
            step.findings.mapIndexed { index, finding -> Entry(direction, finding, changes.forFinding(finding), groups.forFinding(index)?.id) }
        }
    }.filter { it.finding.verdict != Verdict.SAFE }
        .sortedWith(compareBy({ severity(it.finding.verdict) }, { it.direction }, { it.finding.ruleId }, { it.finding.objectName }))
    val rules = entries.map { it.finding.ruleId }.distinct().sorted()
    val results = entries.map { (direction, finding, changes, groupId) ->
        buildMap<String, Any> {
            put("ruleId", finding.ruleId)
            put("level", if (finding.verdict == Verdict.BREAKING) "error" else "warning")
            put("message", mapOf("text" to buildString {
                append("[$direction] ${finding.message}\n${finding.evidence}")
                append("\nNext evidence: ${finding.mitigation ?: unknownEvidence(finding)}")
            }))
            put("properties", buildMap<String, Any> {
                put("verdict", finding.verdict.name)
                put("object", finding.objectName)
                put("contractIds", finding.affectedContractIds)
                put("baseline", finding.migration == null)
                put("locationPrecision", when {
                    finding.migration?.filename?.endsWith(".sql") == true -> "migration-file"
                    direction == "new-writes-old-reads" -> "contract"
                    finding.migration == null -> "baseline"
                    else -> "schema"
                })
                if (changes.isNotEmpty()) put("findingChanges", changes)
                if (groupId != null) put("findingGroupId", groupId)
            })
            finding.migration?.takeIf { it.filename.endsWith(".sql") }?.let { migration ->
                val path = listOf(migrationPrefix.trimEnd('/'), migration.filename).filter(String::isNotBlank).joinToString("/")
                put("locations", listOf(mapOf("physicalLocation" to mapOf(
                    "artifactLocation" to mapOf("uri" to URI(null, null, path.replace('\\', '/'), null).toASCIIString()),
                    "region" to mapOf("startLine" to 1),
                ))))
            }
        }
    }
    return mapOf("\$schema" to "https://json.schemastore.org/sarif-2.1.0.json", "version" to "2.1.0", "runs" to listOf(mapOf(
        "tool" to mapOf("driver" to mapOf("name" to "Epoch", "informationUri" to "https://github.com/monroestephenson/epoch",
            "rules" to rules.map { mapOf("id" to it, "shortDescription" to mapOf("text" to it.replace('_', ' '))) })),
        "results" to results,
    )))
}

internal fun severity(verdict: Verdict) = when (verdict) { Verdict.BREAKING -> 0; Verdict.UNKNOWN -> 1; Verdict.SAFE -> 2 }

internal fun unknownEvidence(finding: Finding): String = when {
    finding.ruleId.contains("BASELINE") -> "Provide a matching baseline schema and a complete, correctly typed SQL contract; rerun baseline validation."
    finding.ruleId.contains("QUERY") || finding.ruleId.contains("COVERAGE") -> "Supply all supported SQL variants, their parameter/result types, and complete physical dependencies."
    finding.ruleId.contains("TYPE") || finding.ruleId.contains("RESULT") -> "Declare accepted input domains and demonstrate that the old application's result mappings can read the new PostgreSQL types."
    finding.ruleId.contains("CONSTRAINT") || finding.ruleId.contains("NULL") -> "Establish that every old accepted input still satisfies the new constraint, including null and boundary values."
    finding.ruleId.contains("MIGRATION") || finding.ruleId.contains("BEHAVIOR") -> "Provide a supported structural transformation or a separate exhaustive proof of the affected database behavior."
    else -> "Supply an explicit dependency/input contract and a supported proof for this rule; successful sample executions alone do not establish SAFE."
}
