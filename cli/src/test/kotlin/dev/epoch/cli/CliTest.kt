package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliTest {
    @Test fun `every verdict has distinct CI exit code`() {
        assertEquals(0, exitCode(Verdict.SAFE))
        assertEquals(1, exitCode(Verdict.BREAKING))
        assertEquals(2, exitCode(Verdict.UNKNOWN))
    }
    @Test fun `text report preserves evidence and affected contract`() {
        val migration = MigrationInfo("2", "V2__drop.sql")
        val report = CompatibilityReport(applicationVersion = "old", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.BREAKING, migrations = listOf(MigrationReport(migration, Verdict.BREAKING, listOf(
                Finding(Verdict.BREAKING, "REFERENCED_COLUMN_DROPPED", "public.customers.email", "Missing column",
                    "Baseline column exists, migrated column absent", listOf("Customer.find"), migration, "Retain the old column")
            ))))
        val text = renderReport(report)
        for (required in listOf("V2__drop.sql", "Customer.find", "REFERENCED_COLUMN_DROPPED", "Baseline column exists", "Retain the old column", "Result: BREAKING", "STRUCTURAL_CONTRACT")) {
            assertTrue(text.contains(required), "Missing report detail: $required")
        }
    }
}
