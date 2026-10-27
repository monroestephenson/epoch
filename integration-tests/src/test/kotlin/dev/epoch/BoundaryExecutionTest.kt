package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A sample can prove a rejected old input; successful samples never prove universal safety. */
class BoundaryExecutionTest {
    @TempDir lateinit var directory: Path

    private fun verify(change: String, probes: Boolean = true, assertEmptyAfter: Boolean = false,
                       labelType: String = "varchar(20)", mappedLength: Long? = 20): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id bigint PRIMARY KEY, label $labelType);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__change.sql").writeText(change)
        if (assertEmptyAfter) pending.resolve("V3__assert_probes_rolled_back.sql").writeText("""
            DO ${'$'}${'$'} BEGIN
                IF EXISTS (SELECT 1 FROM accounts) THEN
                    RAISE EXCEPTION 'Boundary probes leaked rows into the migration database';
                END IF;
            END ${'$'}${'$'};
        """.trimIndent())
        val id = ColumnReference("public.accounts", "id")
        val label = ColumnReference("public.accounts", "label")
        val contract = DatabaseContract(
            applicationVersion = "boundary-fixture",
            entities = listOf(EntityContract("Account", "public.accounts", listOf(
                ColumnContract("id", "bigint", nullable = false),
                ColumnContract("label", if (labelType == "text") "text" else "varchar", nullable = true, length = mappedLength),
            ), operations = setOf(Operation.INSERT))),
            queries = listOf(QueryContract("Account.insert", Operation.INSERT,
                "INSERT INTO accounts (id, label) VALUES (?, ?)",
                tables = listOf("public.accounts"), columns = listOf(id, label), writeColumns = listOf(id, label),
                parameterTypes = listOf("bigint", "varchar"), dependenciesComplete = true, targetTable = "public.accounts")),
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit INSERT boundary fixture")),
        )
        return PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(executeBoundaries = probes))
    }

    @Test fun `rolled-back old INSERT witnesses a restrictive length CHECK rejection`() {
        val report = verify("ALTER TABLE accounts ADD CONSTRAINT short_label CHECK (length(label) <= 3);")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        val finding = report.migrations.flatMap { it.findings }.firstOrNull { it.ruleId == "BOUNDARY_EXECUTION_REJECTED" }
        assertNotNull(finding, report.toString())
        assertEquals(listOf("Account.insert"), finding!!.affectedContractIds)
        assertTrue(finding.evidence.contains("23514"), finding.toString())
    }

    @Test fun `two accepted old INSERTs witness a new unique constraint rejecting duplicates`() {
        val report = verify("ALTER TABLE accounts ADD CONSTRAINT unique_label UNIQUE (label);")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "BOUNDARY_EXECUTION_REJECTED" && it.evidence.contains("23505")
        }, report.toString())
    }

    @Test fun `successful boundary executions do not turn an unproven CHECK into SAFE`() {
        val report = verify("ALTER TABLE accounts ADD CONSTRAINT wide_label CHECK (length(label) <= 1000);", assertEmptyAfter = true)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertEquals(listOf("2", "3"), report.migrations.map { it.migration.version }, report.toString())
        assertFalse(report.migrations.flatMap { it.findings }.any { it.ruleId == "BOUNDARY_EXECUTION_REJECTED" }, report.toString())
    }

    @Test fun `boundary execution is opt-in and defaults do not execute old DML`() {
        val report = verify("ALTER TABLE accounts ADD CONSTRAINT short_label CHECK (length(label) <= 3);", probes = false)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertFalse(report.migrations.flatMap { it.findings }.any { it.ruleId.startsWith("BOUNDARY_EXECUTION") }, report.toString())
    }

    @Test fun `nonunique text index can reject a baseline-accepted large uncompressible value`() {
        val report = verify("CREATE INDEX account_label_lookup ON accounts(label);", labelType = "text", mappedLength = null)
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "BOUNDARY_EXECUTION_REJECTED" && it.evidence.contains("54000")
        }, report.toString())
    }

    @Test fun `nonunique unbounded text index stays unknown without an accepted-input bound or execution witness`() {
        val report = verify("CREATE INDEX account_label_lookup ON accounts(label);", probes = false,
            labelType = "text", mappedLength = null)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }
}
