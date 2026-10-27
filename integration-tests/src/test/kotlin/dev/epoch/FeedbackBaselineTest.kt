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

/** Regressions for baseline attribution and explicitly accepted preexisting limitations. */
class FeedbackBaselineTest {
    @TempDir lateinit var directory: Path

    private fun baseline(): Path = directory.resolve("baseline").createDirectories().also {
        it.resolve("V1__baseline.sql").writeText(PostgresCompatibilityTest.BASELINE)
    }

    private fun pending(name: String, vararg migrations: Pair<String, String>): Path =
        directory.resolve(name).createDirectories().also { path ->
            migrations.forEach { (filename, sql) -> path.resolve(filename).writeText(sql) }
        }

    private fun incompleteContract(): DatabaseContract = PostgresCompatibilityTest.customerContract().copy(
        unresolvedQueries = listOf(UnresolvedQuery("legacy.dynamic", "A legacy query builder has no exhaustive variant set")),
    )

    @Test fun `existing mapping and SQL problems are attributed to baseline only once`() {
        val original = incompleteContract()
        val contract = original.copy(
            entities = original.entities + EntityContract("LegacyProjection", "public.nonexistent_projection",
                listOf(ColumnContract("id", "bigint")), setOf(Operation.SELECT)),
            queries = original.queries.map { if (it.id == "Customer.find") it.copy(parameterTypes = listOf("bigint", "text")) else it },
        )
        val report = PostgresVerifier().verify(contract, baseline(), pending("pending",
            "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;",
            "V3__audit_details.sql" to "ALTER TABLE audit_log ADD COLUMN details text;",
        ))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        val health = requireNotNull(report.baseline)
        assertEquals(Verdict.UNKNOWN, health.verdict)
        assertTrue(health.findings.all { it.migration == null }, health.toString())
        for (rule in listOf("INVALID_BASELINE_TABLE", "BASELINE_SQL_UNVERIFIED", "UNRESOLVED_QUERY")) {
            assertTrue(health.findings.any { it.ruleId == rule }, "Missing baseline rule $rule: $report")
            assertTrue(report.migrations.all { step -> step.findings.none { it.ruleId == rule } },
                "Baseline rule $rule repeated under pending migrations: $report")
        }
        assertEquals(listOf("2", "3"), report.migrations.map { it.migration.version })
        assertEquals(listOf(Verdict.SAFE, Verdict.SAFE), report.migrations.map { it.verdict })
        assertNull(report.gateVerdict, "Acceptance must always be explicit")
    }

    @Test fun `accepted baseline permits a delta gate but never erases an introduced break`() {
        val contract = incompleteContract()
        val baseline = baseline()
        val compatible = pending("compatible", "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;")
        val first = PostgresVerifier().verify(contract, baseline, compatible)
        val accepted = BaselineAcceptance.fromReport(first, "Tracked legacy query coverage debt; reviewed under issue EPOCH-123")
        assertTrue(accepted.findings.isNotEmpty())
        val options = VerificationOptions(acceptedBaseline = accepted)
        val second = PostgresVerifier().verify(contract, baseline, compatible, options)
        assertEquals(Verdict.UNKNOWN, second.verdict, "Accepted debt is not proof of compatibility")
        assertEquals(Verdict.SAFE, second.gateVerdict, second.toString())
        assertEquals(accepted.findings.size, second.acceptedBaseline.size)
        assertTrue(second.ruleSummary.any { it.acceptedBaselineCount > 0 })
        assertTrue(requireNotNull(second.baseline).findings.all { it.migration == null })

        val breaking = pending("breaking", "V2__drop_email.sql" to "ALTER TABLE customers DROP COLUMN email;")
        val third = PostgresVerifier().verify(contract, baseline, breaking, options)
        assertEquals(Verdict.BREAKING, third.verdict, third.toString())
        assertEquals(Verdict.BREAKING, third.gateVerdict, third.toString())
        assertTrue(third.migrations.single().findings.any { it.verdict == Verdict.BREAKING && it.migration?.version == "2" })
    }

    @Test fun `accepted baseline is invalidated by changed migration bytes even with identical schema`() {
        val baseline = baseline()
        val migrations = pending("pending", "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;")
        val contract = incompleteContract()
        val first = PostgresVerifier().verify(contract, baseline, migrations)
        val accepted = BaselineAcceptance.fromReport(first, "Existing limitation reviewed before migration changes")
        baseline.resolve("V1__baseline.sql").writeText(PostgresCompatibilityTest.BASELINE + "\n-- New baseline revision\n")
        assertThrows(IllegalArgumentException::class.java) {
            PostgresVerifier().verify(contract, baseline, migrations, VerificationOptions(acceptedBaseline = accepted))
        }
    }

    @Test fun `accepted baseline callback cannot suppress a new pending callback with the same filename`() {
        val baseline = baseline()
        baseline.resolve("afterMigrate.sql").writeText("UPDATE customers SET email = lower(email);")
        val contract = PostgresCompatibilityTest.customerContract()
        val compatible = pending("compatible", "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;")
        val original = PostgresVerifier().verify(contract, baseline, compatible)
        assertEquals(Verdict.UNKNOWN, original.verdict)
        assertTrue(requireNotNull(original.baseline).findings.any {
            it.ruleId == "FLYWAY_CALLBACK_UNSUPPORTED" && it.objectName == "baseline/afterMigrate.sql" && it.migration == null
        }, original.toString())
        val accepted = BaselineAcceptance.fromReport(original, "Baseline callback reviewed separately; pending callbacks are not approved")
        val pendingWithCallback = pending("with-callback", "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;")
        pendingWithCallback.resolve("afterMigrate.sql").writeText("DELETE FROM customers;")
        val report = PostgresVerifier().verify(contract, baseline, pendingWithCallback,
            VerificationOptions(acceptedBaseline = accepted))
        assertEquals(Verdict.UNKNOWN, report.gateVerdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "FLYWAY_CALLBACK_UNSUPPORTED" && it.objectName == "pending/afterMigrate.sql" && it.migration?.version == "callback" && it.migration?.filename == "afterMigrate.sql"
        }, report.toString())
    }

    @Test fun `several contracts share one migration sequence while preserving duplicate local IDs`() {
        val customer = PostgresCompatibilityTest.customerContract()
        val audit = DatabaseContract(
            applicationVersion = "audit-1.0",
            entities = listOf(EntityContract("Customer", "public.audit_log", listOf(ColumnContract("id", "bigint")), setOf(Operation.SELECT))),
            queries = listOf(QueryContract("Customer.find", Operation.SELECT, "select id from audit_log where id = ?",
                tables = listOf("public.audit_log"), columns = listOf(ColumnReference("public.audit_log", "id")),
                parameterTypes = listOf("bigint"), dependenciesComplete = true)),
            coverage = Coverage(complete = true),
        )
        val report = PostgresVerifier().verify(listOf(customer, audit), baseline(), pending("pending",
            "V2__drop_email.sql" to "ALTER TABLE customers DROP COLUMN email;",
        ))
        assertEquals(Verdict.SAFE, requireNotNull(report.baseline).verdict, report.toString())
        assertEquals(1, report.migrations.size, "A migration should appear once regardless of contract count")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        val impacted = report.migrations.single().findings.filter { it.verdict == Verdict.BREAKING }.flatMap { it.affectedContractIds }
        assertTrue(impacted.isNotEmpty())
        assertTrue(impacted.all { it.startsWith("contract[1]/") }, impacted.toString())
        assertFalse(impacted.any { it.startsWith("contract[2]/") }, "Unchanged audit contract must not inherit customer findings")
    }
}
