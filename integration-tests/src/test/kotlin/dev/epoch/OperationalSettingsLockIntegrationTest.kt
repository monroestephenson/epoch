package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The actual session retains its DDL lock while a later literal SET changes the timeout. */
class OperationalSettingsLockIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(150)
    fun `literal settings do not reclassify retained locks or erase a prior unprotected acquisition`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE control(id bigint PRIMARY KEY);
            CREATE TABLE accounts(id bigint PRIMARY KEY);
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__protected_then_settings.sql").writeText("""
            SET LOCAL lock_timeout='500ms';
            ALTER TABLE accounts ADD COLUMN first_metadata text;
            SET LOCAL lock_timeout='1000ms';
        """.trimIndent())
        pending.resolve("V3__unprotected_then_settings.sql").writeText("""
            ALTER TABLE accounts ADD COLUMN second_metadata text;
            SET LOCAL lock_timeout='500ms';
            SET LOCAL lock_timeout=0;
        """.trimIndent())
        val contract = DatabaseContract(applicationVersion = "retained-settings",
            queries = listOf(QueryContract("control.read", Operation.SELECT, "select id from control",
                tables = listOf("public.control"), columns = listOf(ColumnReference("public.control", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 2, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 120))
        val protectedStep = requireNotNull(report.migrations[0].operational)
        assertEquals(3, protectedStep.statements.size)
        val protectedAdd = protectedStep.statements[1]
        val changed = protectedStep.statements[2]
        assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, protectedAdd.lockTimeoutBefore.origin)
        assertEquals(500L, protectedAdd.lockTimeoutBefore.milliseconds)
        assertEquals(500L, changed.lockTimeoutBefore.milliseconds)
        assertEquals(1000L, changed.lockTimeoutAfter.milliseconds)
        assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, changed.lockTimeoutAfter.origin)
        for (locks in listOf(changed.heldLocksBefore, changed.heldLocksAfter)) {
            assertTrue(locks.any { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" &&
                it.table?.preExisting == true && it.table?.baselineName == "public.accounts" }, locks.toString())
        }
        assertEquals(OperationalVerdict.SAFE, changed.verdict, changed.toString())
        assertEquals(OperationalVerdict.SAFE, protectedStep.verdict, protectedStep.toString())
        assertTrue(protectedStep.findings.none { it.ruleId == "OP_WRITE_BLOCKING_LOCK" })

        val unprotectedStep = requireNotNull(report.migrations[1].operational)
        assertEquals(3, unprotectedStep.statements.size)
        val unprotectedAdd = unprotectedStep.statements[0]
        assertEquals(OperationalTimeoutOrigin.VERIFIER, unprotectedAdd.lockTimeoutBefore.origin)
        assertEquals(OperationalVerdict.RISKY, unprotectedAdd.verdict)
        assertTrue(unprotectedAdd.findings.single { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }.evidence
            .contains("Migration-authored lock_timeout before statement: none (VERIFIER)"))
        assertEquals(500L, unprotectedStep.statements[1].lockTimeoutAfter.milliseconds)
        assertEquals(0L, unprotectedStep.statements[2].lockTimeoutAfter.milliseconds)
        for (statement in unprotectedStep.statements.drop(1)) {
            assertTrue(statement.heldLocksBefore.any { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" })
            assertEquals(OperationalVerdict.SAFE, statement.verdict, statement.toString())
            assertTrue(statement.findings.none { it.ruleId == "OP_WRITE_BLOCKING_LOCK" })
        }
        assertEquals(OperationalVerdict.RISKY, unprotectedStep.verdict)
        assertEquals(listOf(1), unprotectedStep.findings.filter { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }.map { it.statementOrdinal })
    }
}
