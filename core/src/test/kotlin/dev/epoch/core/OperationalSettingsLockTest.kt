package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalSettingsLockTest {
    private val migration = MigrationInfo("2", "V2__settings.sql")
    private val old = OperationalRelation("public.accounts", "table", true, "public.accounts")
    private val exclusive = OperationalLock(old.name, "table", "AccessExclusiveLock", old)
    private val verifier = OperationalTimeout(30_000, "client", OperationalTimeoutOrigin.VERIFIER)
    private fun authored(milliseconds: Long) = OperationalTimeout(milliseconds, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL)
    private val setting = OperationalAction(OperationalActionKind.SETTINGS_CHANGE, catalogConfirmed = true,
        effectsComplete = true, evidence = "A literal SET LOCAL lock_timeout executed.")
    private val metadata = OperationalAction(OperationalActionKind.METADATA_CHANGE, old,
        catalogConfirmed = true, effectsComplete = true, evidence = "ALTER TABLE added a nullable column.")
    private fun facts(action: OperationalAction = setting, before: OperationalTimeout = authored(500),
                      after: OperationalTimeout = authored(1000), locksBefore: List<OperationalLock> = listOf(exclusive),
                      locksAfter: List<OperationalLock> = locksBefore, opaque: List<String> = emptyList(), ordinal: Int = 1) =
        OperationalStatementFacts(migration, ordinal, "SET LOCAL lock_timeout='1000ms';", false, before, after,
            locksBefore, locksAfter, locksAfter.filter { it !in locksBefore }, emptyList(), listOf(action), opaque)
    private fun risk(report: OperationalStatementReport) = report.findings.filter { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }

    @Test fun `changing an authored timeout does not reclassify an unchanged previously acquired lock`() {
        val add = OperationalAssessmentRules.assess(facts(metadata, after = authored(500), locksBefore = emptyList(),
            locksAfter = listOf(exclusive), ordinal = 1))
        val changed = OperationalAssessmentRules.assess(facts(ordinal = 2))
        assertEquals(OperationalVerdict.SAFE, add.verdict)
        assertEquals(OperationalVerdict.SAFE, changed.verdict)
        assertTrue(risk(changed).isEmpty())
        assertEquals(OperationalVerdict.SAFE, OperationalAssessmentRules.aggregate(listOf(add, changed)).verdict)
    }

    @Test fun `setting or disabling a timeout preserves earlier unprotected risk without inventing a new acquisition`() {
        val add = OperationalAssessmentRules.assess(facts(metadata, before = verifier, after = verifier,
            locksBefore = emptyList(), locksAfter = listOf(exclusive), ordinal = 1))
        val set = OperationalAssessmentRules.assess(facts(before = verifier, after = authored(500), ordinal = 2))
        val disabled = OperationalAssessmentRules.assess(facts(before = authored(500), after = authored(0), ordinal = 3))
        assertEquals(OperationalVerdict.RISKY, add.verdict)
        assertEquals(OperationalVerdict.SAFE, set.verdict)
        assertEquals(OperationalVerdict.SAFE, disabled.verdict)
        assertTrue(risk(set).isEmpty() && risk(disabled).isEmpty())
        val aggregate = OperationalAssessmentRules.aggregate(listOf(add, set, disabled))
        assertEquals(OperationalVerdict.RISKY, aggregate.verdict)
        assertEquals(listOf(1), aggregate.findings.filter { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }.map { it.statementOrdinal })
    }

    @Test fun `opaque or incomplete settings and ordinary unknown commands cannot use the exception`() {
        val variants = listOf(
            facts(opaque = listOf("A callback may acquire a lock.")),
            facts(setting.copy(effectsComplete = false)),
            facts(setting.copy(catalogConfirmed = false)),
            facts(OperationalAction(OperationalActionKind.UNKNOWN, evidence = "An unmodeled procedure.")),
        )
        for (input in variants) {
            val report = OperationalAssessmentRules.assess(input)
            assertEquals(OperationalVerdict.RISKY, report.verdict, report.toString())
            assertTrue(risk(report).isNotEmpty())
            assertTrue(report.findings.any { it.verdict == OperationalVerdict.UNKNOWN })
        }
    }

    @Test fun `a newly observed or upgraded blocking mode is not hidden by a settings label`() {
        val shared = exclusive.copy(mode = "ShareLock")
        val renamed = exclusive.copy(relation = "public.renamed", table = old.copy(name = "public.renamed"))
        val otherIdentity = exclusive.copy(table = old.copy(baselineName = "public.other_original"))
        val variants = listOf(
            facts(locksBefore = emptyList(), locksAfter = listOf(exclusive)),
            facts(locksBefore = listOf(shared), locksAfter = listOf(shared, exclusive)),
            facts(locksAfter = listOf(renamed)),
            facts(locksAfter = listOf(otherIdentity)),
            facts().copy(newlyObservedLocks = listOf(exclusive)),
        )
        for (input in variants) assertTrue(risk(OperationalAssessmentRules.assess(input)).isNotEmpty(), input.toString())
    }

    @Test fun `duplicate lock samples do not prevent the settings-only identity proof`() {
        val report = OperationalAssessmentRules.assess(facts(locksBefore = listOf(exclusive, exclusive)))
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        assertTrue(risk(report).isEmpty())
    }

    @Test fun `risk evidence distinguishes absent unknown disabled and positive authored timeouts`() {
        val samples = listOf(
            verifier to "none (VERIFIER)",
            OperationalTimeout(500, "session", OperationalTimeoutOrigin.UNKNOWN) to "unknown (UNKNOWN)",
            authored(0) to "disabled (0ms; MIGRATION_LOCAL)",
            authored(500) to "500ms (MIGRATION_LOCAL)",
        )
        // Unknown SQL remains risky even with a positive before-sample; its internal settings
        // and lock acquisition are not enumerated. The sample must nevertheless be reported.
        for ((timeout, expected) in samples) {
            val report = OperationalAssessmentRules.assess(facts(OperationalAction(OperationalActionKind.UNKNOWN),
                before = timeout, after = timeout))
            assertTrue(risk(report).single().evidence.contains("Migration-authored lock_timeout before statement: $expected"),
                report.toString())
            assertFalse(report.findings.any { it.verdict == OperationalVerdict.SAFE })
        }
    }
}
