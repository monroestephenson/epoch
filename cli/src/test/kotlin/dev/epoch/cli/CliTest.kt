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

class CliFailureAndSummaryTest {
    @picocli.CommandLine.Command(name = "crash")
    class CrashingCommand : java.util.concurrent.Callable<Int> {
        override fun call(): Int = throw NoClassDefFoundError("jakarta/validation/Validation")
    }

    @Test fun `linkage error during command execution is runtime exit 3`() {
        assertEquals(3, runCli(emptyArray()) { picocli.CommandLine(CrashingCommand()) })
    }
    @Test fun `failure before command construction is runtime exit 3`() {
        assertEquals(3, runCli(emptyArray()) { throw ExceptionInInitializerError("broken initializer") })
    }
    @Test fun `underlying docker cause is printed`() {
        val buffer = java.io.ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(java.io.PrintStream(buffer))
            val error = IllegalStateException("Can't get Docker image", IllegalArgumentException("client API version too old"))
            assertEquals(3, runCli(emptyArray()) { throw error })
        } finally { System.setErr(previous) }
        assertTrue(buffer.toString().contains("client API version too old"))
        assertTrue(buffer.toString().contains("IllegalArgumentException"))
    }
    @Test fun `verify help documents contract and report paths`() {
        val buffer = java.io.StringWriter()
        val command = picocli.CommandLine(Main())
        command.subcommands.getValue("verify").usage(java.io.PrintWriter(buffer))
        val help = buffer.toString()
        assertTrue(help.contains("Old application's contract JSON"), help)
        assertTrue(help.contains("JSON report destination"), help)
        assertTrue(help.contains("--baseline-cache"), help)
    }
    @Test fun `large report is bounded by default but details remain available`() {
        val migration = MigrationInfo("2", "V2__change.sql")
        val findings = (1..100).map { Finding(Verdict.UNKNOWN, "RULE", "object$it", "Issue", "Evidence$it", migration = migration) }
        val report = CompatibilityReport(applicationVersion = "old", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.UNKNOWN, migrations = listOf(MigrationReport(migration, Verdict.UNKNOWN, findings)))
        val text = renderReport(report)
        assertTrue(text.contains("80 findings omitted"), text)
        assertTrue(text.contains("UNKNOWN RULE: 0 / 100 / 0"), text)
        assertFalse(text.contains("Evidence100"), text)
        assertTrue(renderReport(report, allDetails = true).contains("Evidence100"))
        assertFalse(renderReport(report, summaryOnly = true).contains("Evidence1"))
    }
}
