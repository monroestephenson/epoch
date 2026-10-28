package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GroupedDiagnosticsMultiUnitTest {
    @TempDir lateinit var directory: Path

    @Test fun `multiple units namespace grouped causes and retain every unresolved source`() {
        val sources = (1..40).map { "Repository#method$it:0" }
        val diagnostic = "extraction-diagnostic:shared-cause"
        val contract = DatabaseContract(
            queries = listOf(QueryContract("healthy", Operation.SELECT, "select id from sample",
                tables = listOf("public.sample"), columns = listOf(ColumnReference("public.sample", "id")),
                dependenciesComplete = true)),
            coverage = Coverage(complete = false),
            unresolvedQueries = listOf(UnresolvedQuery(diagnostic, "A shared compiled query failure", sources)),
            accessPaths = sources.map { AccessPathCoverage(it, "Repository", it.substringBefore(':'),
                AccessPathStatus.UNRESOLVED, reason = "Extraction diagnostic $diagnostic") },
        )
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE sample(id bigint);")
        val report = PostgresVerifier().verify(listOf(contract, contract), baseline,
            directory.resolve("pending").createDirectories())
        val findings = requireNotNull(report.baseline).findings.filter { it.ruleId == "UNRESOLVED_QUERY" }
        assertEquals(2, findings.size, "One grouped cause per persistence unit, with no per-path expansion: $findings")
        for (unit in 1..2) {
            val prefix = "contract[$unit]/"
            val finding = findings.single { it.objectName == prefix + diagnostic }
            assertEquals(sources.map { prefix + it }.toSet(), finding.affectedContractIds.toSet())
            assertTrue(report.accessPaths.filter { it.id.startsWith(prefix) }.all {
                it.reason == "Extraction diagnostic $prefix$diagnostic"
            })
        }
        val acceptance = BaselineAcceptance.fromReport(report, "Existing compiled query failure tracked separately")
        assertTrue(findings.all { finding -> acceptance.findings.any {
            it.fingerprint == BaselineFingerprints.finding(finding)
        } })
    }
}
