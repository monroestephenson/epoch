package dev.epoch.core

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.security.MessageDigest

/** Stable evidence fingerprints. Neither pending-migration names nor runtime cache state enter them. */
object BaselineFingerprints {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build()

    fun finding(finding: Finding): String = hash(finding.copy(
        migration = null, affectedContractIds = finding.affectedContractIds.distinct().sorted(),
    ))

    fun baseline(contract: DatabaseContract, snapshot: SchemaSnapshot, findings: List<Finding>, context: String): String =
        hash(listOf(contract, snapshot, findings.map(::finding).sorted(), context))

    /** Same preexisting issue can have wording changes when rediscovered in a migration snapshot. */
    internal fun identity(finding: Finding): String = hash(listOf(
        finding.verdict, finding.ruleId, finding.objectName, finding.affectedContractIds.distinct().sorted(),
    ))

    private fun hash(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(value)).joinToString("") { "%02x".format(it) }
}

/** Acceptance changes the CI gate only. It never converts unproven compatibility into proof. */
object BaselineAcceptance {
    fun fromReport(report: CompatibilityReport, justification: String): AcceptedBaseline {
        require(justification.isNotBlank()) { "Accepted-baseline findings require a nonblank justification" }
        val baseline = requireNotNull(report.baseline) { "Report has no separate baseline health section" }
        return AcceptedBaseline(baselineFingerprint = baseline.fingerprint,
            findings = baseline.findings.filter { it.verdict != Verdict.SAFE }
                .map { AcceptedBaselineFinding(BaselineFingerprints.finding(it), justification.trim()) }
                .distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
    }

    fun apply(report: CompatibilityReport, acceptance: AcceptedBaseline): CompatibilityReport {
        val baseline = requireNotNull(report.baseline) { "Accepted baseline requires a report with baseline health" }
        require(acceptance.schemaVersion == 1) { "Unsupported accepted-baseline schemaVersion: ${acceptance.schemaVersion}" }
        require(acceptance.baselineFingerprint == baseline.fingerprint) {
            "Accepted baseline is stale: contract, baseline migrations, catalog, or baseline evidence changed"
        }
        require(acceptance.findings.all { it.justification.isNotBlank() }) {
            "Every accepted-baseline finding requires a nonblank justification"
        }
        require(acceptance.findings.map { it.fingerprint }.distinct().size == acceptance.findings.size) {
            "Accepted-baseline finding fingerprints must be unique"
        }
        val eligible = baseline.findings.filter { it.verdict != Verdict.SAFE }.map(BaselineFingerprints::finding).toSet()
        require(acceptance.findings.all { it.fingerprint in eligible }) {
            "Accepted-baseline file contains a finding that is not present in baseline health; migration findings cannot be accepted"
        }
        val accepted = acceptance.findings.map { it.fingerprint }.toSet()
        val remainingBaseline = baseline.findings.filter {
            it.verdict != Verdict.SAFE && BaselineFingerprints.finding(it) !in accepted
        }
        val gate = CompatibilityEngine.aggregate(
            remainingBaseline.map { it.verdict } + report.migrations.map { it.verdict } + Verdict.SAFE,
        )
        val limitation = "Accepted-baseline gate excludes only explicitly justified baseline findings; the compatibility verdict remains unchanged."
        return report.copy(
            gateVerdict = gate,
            acceptedBaseline = acceptance.findings.sortedBy { it.fingerprint },
            ruleSummary = summarizeFindings(baseline, report.migrations, accepted),
            limitations = (report.limitations + limitation).distinct(),
        )
    }
}

internal fun summarizeFindings(
    baseline: BaselineReport?, migrations: List<MigrationReport>, accepted: Set<String> = emptySet(),
): List<RuleSummary> {
    val original = baseline?.findings.orEmpty()
    val pending = migrations.flatMap { it.findings }
    return (original + pending).map { it.ruleId to it.verdict }.distinct()
        .sortedWith(compareBy({ it.first }, { it.second.name })).map { (rule, verdict) ->
            val existing = original.filter { it.ruleId == rule && it.verdict == verdict }
            RuleSummary(rule, verdict, existing.size,
                pending.count { it.ruleId == rule && it.verdict == verdict },
                existing.count { BaselineFingerprints.finding(it) in accepted })
        }
}
