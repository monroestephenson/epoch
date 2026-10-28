package dev.epoch.cli

import com.fasterxml.jackson.core.JsonParser
import dev.epoch.core.*
import java.nio.file.Files
import java.nio.file.Path

/** Strict metadata decoding prevents duplicate keys or fractional estimates from being silently changed. */
internal fun readTableSizes(path: Path?): TableSizes? {
    if (path == null) return null
    require(Files.isRegularFile(path)) { "Table-size file does not exist: $path" }
    val mapper = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    val tree = mapper.readTree(path.toFile())
    require(tree.isObject && tree.path("schemaVersion").isIntegralNumber &&
        tree.path("schemaVersion").canConvertToInt() && tree.path("schemaVersion").asInt() == 1) {
        "Table-size input requires integer schemaVersion 1"
    }
    val tables = tree.path("tables")
    require(tables.isObject) { "Table-size input requires a tables object keyed by schema-qualified name" }
    tables.fields().forEach { (name, values) ->
        require(values.isObject) { "Table-size entry $name must be an object" }
        for (field in listOf("estimatedRows", "totalBytes")) {
            val value = values.get(field) ?: continue
            require(value.isNull || value.isIntegralNumber && value.canConvertToLong() && value.asLong() >= 0) {
                "Table-size $name.$field must be a nonnegative 64-bit integer or null"
            }
        }
    }
    return mapper.treeToValue(tree, TableSizes::class.java)
}

/** Compatibility failures keep their existing codes; the extra operational gate is opt-in. */
internal fun operationalExitCode(compatibilityExit: Int, verdict: OperationalVerdict?, failOnOperational: Boolean): Int =
    if (compatibilityExit != 0) compatibilityExit
    else if (failOnOperational && verdict != OperationalVerdict.SAFE) 4 else 0

internal fun operationalSeverity(verdict: OperationalVerdict): Int = when (verdict) {
    OperationalVerdict.RISKY -> 0
    OperationalVerdict.UNKNOWN -> 1
    OperationalVerdict.SAFE -> 2
}

internal fun renderOperational(report: CompatibilityReport, summaryOnly: Boolean, allDetails: Boolean): String = buildString {
    if (report.operationalVerdict == null && report.migrations.none { it.operational != null }) return@buildString
    appendLine("Operational result: ${report.operationalVerdict ?: OperationalVerdict.UNKNOWN} (separate from compatibility)")
    val findings = report.migrations.flatMap { it.operational?.findings.orEmpty() }
        .sortedWith(compareBy<OperationalRiskFinding> { operationalSeverity(it.verdict) }
            .thenBy { it.ruleId }.thenBy { it.migration.version }.thenBy { it.statementOrdinal }.thenBy { it.objectName })
    findings.groupBy { it.verdict to it.ruleId }.forEach { (key, values) ->
        appendLine("  ${key.first} ${key.second}: ${values.size}")
    }
    if (!summaryOnly) {
        val displayed = if (allDetails) findings else findings.filter { it.verdict != OperationalVerdict.SAFE }.take(20)
        displayed.forEach { finding ->
            appendLine("  ${finding.migration.filename} statement ${finding.statementOrdinal}: ${finding.verdict}" +
                (finding.severity?.let { " [$it]" } ?: "") + " [${finding.ruleId}] ${finding.objectName}")
            appendLine("    ${finding.message}")
            appendLine("    Evidence: ${finding.evidence}")
            finding.mitigation?.let { appendLine("    Mitigation: $it") }
        }
        val omitted = findings.count { it.verdict != OperationalVerdict.SAFE } - displayed.count { it.verdict != OperationalVerdict.SAFE }
        if (omitted > 0) appendLine("$omitted operational findings omitted; use report --details or inspect JSON.")
        if (allDetails) report.migrations.forEach { step -> step.operational?.statements?.forEach { statement ->
            appendLine("  ${step.migration.filename} statement ${statement.ordinal}: ${statement.verdict}; autoCommit=${statement.autoCommit}")
            appendLine("    SQL: ${statement.sql}")
            appendLine("    lock_timeout: ${statement.lockTimeoutBefore.milliseconds}ms (${statement.lockTimeoutBefore.origin}) -> " +
                "${statement.lockTimeoutAfter.milliseconds}ms (${statement.lockTimeoutAfter.origin})")
            statement.heldLocksAfter.forEach { appendLine("    Held: ${it.relation} (${it.relationKind}) ${it.mode}") }
            statement.rewrittenTables.forEach { appendLine("    Rewrite: ${it.beforeName} -> ${it.afterName}; preExisting=${it.table.preExisting}") }
        } }
    }
    report.operationalLimitations.forEach { appendLine("Operational scope limitation: $it") }
}.trimEnd()
