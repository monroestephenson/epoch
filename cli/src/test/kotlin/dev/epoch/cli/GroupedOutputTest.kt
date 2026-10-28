package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.nio.file.Path

class GroupedOutputTest {
    private val ids = (1..1000).map { "Repo#query${it.toString().padStart(4, '0')}:0" }

    @Test fun `one grouped finding caps affected IDs by default and details retains every source`() {
        val migration = MigrationInfo("2", "V2__change.sql")
        val finding = Finding(Verdict.UNKNOWN, "GROUPED_FAILURE", "query-discovery", "Shared cause", "One cause", ids)
        val report = CompatibilityReport(applicationVersion = "old", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.UNKNOWN, migrations = listOf(MigrationReport(migration, Verdict.UNKNOWN, listOf(finding))))
        val compact = renderReport(report)
        assertTrue(compact.contains("Affected (1000 total)"), compact)
        assertTrue(compact.contains("980 affected IDs omitted"), compact)
        assertTrue(compact.contains("--details") && compact.contains("JSON"), compact)
        assertTrue(compact.contains(ids[19]), compact)
        assertFalse(compact.contains(ids[20]), compact)
        assertFalse(compact.contains(ids.last()), compact)
        assertTrue(compact.length < 2000, "Default grouped output grew to ${compact.length} characters")
        val detailed = renderReport(report, allDetails = true)
        ids.forEach { assertTrue(detailed.contains(it), "Missing detailed source $it") }
        assertFalse(detailed.contains("affected IDs omitted"), detailed)
        assertEquals(ids, report.migrations.single().findings.single().affectedContractIds)
    }

    @Test fun `extraction defaults to class summaries instead of printing a thousand sites`() {
        val paths = ids.map { AccessPathCoverage(it, "Repo", it.substringBefore(':'), AccessPathStatus.UNRESOLVED,
            reason = "Shared extraction diagnostic") }
        val contract = DatabaseContract(accessPaths = paths)
        val compact = renderExtractionSummary(contract, Path.of("contract.json"))
        assertTrue(compact.contains("1000 methods"), compact)
        assertTrue(compact.contains("unresolved=1000 (100.0%)"), compact)
        assertFalse(compact.contains("Repo#query"), compact)
        assertTrue(compact.length < 1500, "Default extraction output grew to ${compact.length} characters")
        val detailed = renderExtractionSummary(contract, Path.of("contract.json"), allDetails = true)
        ids.forEach { assertTrue(detailed.contains(it), "Missing detailed access path $it") }
    }

    @Test fun `extraction details expands all classes while compact coverage names its omitted inventory`() {
        val paths = ids.mapIndexed { index, id -> AccessPathCoverage(id, "Repo${index.toString().padStart(4, '0')}",
            "query", AccessPathStatus.STATIC, listOf("query$index")) }
        val contract = DatabaseContract(accessPaths = paths)
        val compact = renderExtractionSummary(contract, Path.of("contract.json"))
        assertTrue(compact.contains("980 classes omitted"), compact)
        assertFalse(compact.contains("Repo0999"), compact)
        val detailed = renderExtractionSummary(contract, Path.of("contract.json"), allDetails = true)
        assertTrue(detailed.contains("Repo0999"), detailed)
        assertTrue(detailed.contains(ids.last()), detailed)
        assertFalse(detailed.contains("classes omitted"), detailed)
    }

    @Test fun `unresolved source count unions grouped affected IDs and legacy individual IDs`() {
        val unresolved = listOf(
            UnresolvedQuery("diagnostic-one", "Shared first cause", ids),
            UnresolvedQuery("diagnostic-two", "Shared second cause", listOf(ids.first(), "legacy-source")),
            UnresolvedQuery("legacy-source", "Individual source"),
        )
        val contract = DatabaseContract(unresolvedQueries = unresolved)
        val compact = renderExtractionSummary(contract, Path.of("contract.json"))
        assertTrue(compact.contains("unresolved sources: 1001 (3 diagnostic groups)"), compact)
        assertTrue(compact.contains("Shared first cause"), compact)
        assertFalse(compact.contains(ids.last()), compact)
        assertTrue(compact.contains("980 affected IDs omitted"), compact)
        val detailed = renderExtractionSummary(contract, Path.of("contract.json"), allDetails = true)
        assertTrue(detailed.contains(ids.last()), detailed)
        assertTrue(detailed.contains("legacy-source"), detailed)
    }

    @Test fun `extract accepts details without changing extraction inputs`() {
        val command = CommandLine(Extract())
        val parsed = command.parseArgs("--descriptor", "request.json", "--output", "contract.json", "--details")
        assertTrue(parsed.hasMatchedOption("--details"))
        assertTrue(command.usageMessage.contains("--details"), command.usageMessage)
    }

    @Test fun `compact grouped report preserves explicit catalog no-op message`() {
        val report = CompatibilityReport(applicationVersion = "old", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.SAFE, migrations = listOf(MigrationReport(MigrationInfo("2", "V2__noop.sql"),
                Verdict.SAFE, emptyList(), catalogChanged = false)))
        assertTrue(renderReport(report).contains("No changes in the inspected catalog"))
    }
}
