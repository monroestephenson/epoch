package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raw measured locks carry evidence independently of SQL intent recognition. */
class ObservedWriteBlockingLockTest {
    private val migration = MigrationInfo("2", "V2__unknown_statement.sql")
    private val old = OperationalRelation("public.accounts", "table", true, "public.accounts")
    private val verifier = OperationalTimeout(30_000, "client", OperationalTimeoutOrigin.VERIFIER)
    private fun lock(relation: OperationalRelation = old, mode: String = "AccessExclusiveLock",
                     kind: String = relation.kind) = OperationalLock(relation.name, kind, mode, relation)
    private fun facts(
        locks: List<OperationalLock>, before: List<OperationalLock> = emptyList(),
        timeout: OperationalTimeout = verifier, afterTimeout: OperationalTimeout = timeout,
        action: OperationalAction = OperationalAction(OperationalActionKind.UNKNOWN, evidence = "Unrecognized SQL shape."),
        opaque: List<String> = emptyList(),
    ) = OperationalStatementFacts(migration, 1, "unsupported statement;", false, timeout, afterTimeout,
        before, locks, locks.filter { it !in before }, emptyList(), listOf(action), opaque)
    private fun risks(report: OperationalStatementReport) = report.findings.filter { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }

    @Test fun `an unsupported statement with a measured old-table blocking lock is both risky and unmodeled`() {
        for (mode in listOf("ShareLock", "ShareRowExclusiveLock", "ExclusiveLock", "AccessExclusiveLock")) {
            val report = OperationalAssessmentRules.assess(facts(listOf(lock(mode = mode))))
            assertEquals(OperationalVerdict.RISKY, report.verdict, report.toString())
            assertEquals(listOf(old.name), risks(report).map { it.objectName })
            assertTrue(risks(report).single().evidence.contains(mode))
            assertTrue(risks(report).single().evidence.contains("VERIFIER"))
            assertTrue(report.findings.any { it.ruleId == "OP_STATEMENT_UNSUPPORTED" && it.verdict == OperationalVerdict.UNKNOWN })
        }
    }

    @Test fun `catalog-confirmed autocommit ALTER retains required lock evidence after samples are released`() {
        val metadata = OperationalAction(OperationalActionKind.METADATA_CHANGE, old,
            catalogConfirmed = true, effectsComplete = true, evidence = "An ordinary ALTER TABLE ADD COLUMN was confirmed.")
        val report = OperationalAssessmentRules.assess(facts(emptyList(), action = metadata).copy(autoCommit = true))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        assertTrue(report.heldLocksBefore.isEmpty() && report.heldLocksAfter.isEmpty())
        assertTrue(risks(report).single().evidence.contains("AccessExclusiveLock"))
        assertTrue(risks(report).single().evidence.contains("required", ignoreCase = true))
        val unconfirmed = OperationalAssessmentRules.assess(facts(emptyList(), action = metadata.copy(catalogConfirmed = false)))
        assertEquals(OperationalVerdict.UNKNOWN, unconfirmed.verdict)
        assertTrue(risks(unconfirmed).isEmpty())
        val missingNoOp = OperationalAssessmentRules.assess(facts(emptyList(), action = metadata.copy(target = null, noOp = true)))
        assertEquals(OperationalVerdict.SAFE, missingNoOp.verdict)
        assertTrue(risks(missingNoOp).isEmpty())
    }

    @Test fun `indirect and retained locks use their own original table identity and supplied sizes`() {
        val parent = OperationalRelation("public.renamed_parent", "table", true, "public.parent")
        val held = listOf(lock(), lock(parent))
        val report = OperationalAssessmentRules.assess(facts(held, before = held), TableSizes(tables = mapOf(
            old.name to TableSize(100, 4096), "public.parent" to TableSize(2_000_000, 2_000_000_000),
        )))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        val byTable = risks(report).associateBy { it.objectName }
        assertEquals(OperationalSeverity.LOW, byTable.getValue(old.name).severity)
        assertEquals(OperationalSeverity.HIGH, byTable.getValue(parent.name).severity)
        assertTrue(byTable.getValue(parent.name).evidence.contains("public.parent"))
        assertTrue(report.newlyObservedLocks.isEmpty(), "Retained locks remain observations even without a new lock event.")
    }

    @Test fun `verifier unknown disabled and after-only timeouts do not establish authored protection`() {
        val authored = OperationalTimeout(250, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL)
        for (timeout in listOf(verifier, OperationalTimeout(250, "session"), authored.copy(milliseconds = 0))) {
            val report = OperationalAssessmentRules.assess(facts(listOf(lock()), timeout = timeout, afterTimeout = authored))
            assertEquals(OperationalVerdict.RISKY, report.verdict, timeout.toString())
            assertTrue(risks(report).isNotEmpty(), timeout.toString())
        }
    }

    @Test fun `unknown object identity and index-only locks do not fabricate an old-table lock risk`() {
        val fresh = old.copy(name = "public.new_table", preExisting = false, baselineName = null)
        val unknown = old.copy(preExisting = null, baselineName = null)
        val index = OperationalLock("public.accounts_idx", "index", "AccessExclusiveLock", old)
        for (observation in listOf(lock(fresh), lock(unknown), index, lock(mode = "ShareUpdateExclusiveLock"))) {
            val report = OperationalAssessmentRules.assess(facts(listOf(observation)))
            assertEquals(OperationalVerdict.UNKNOWN, report.verdict, report.toString())
            assertTrue(risks(report).isEmpty(), report.toString())
        }
    }

    @Test fun `an after-only callback lock establishes blocking exposure without inventing scan ordering`() {
        val action = OperationalAction(OperationalActionKind.VALIDATE_CONSTRAINT, old,
            catalogConfirmed = true, effectsComplete = true, priorConstraintValidated = false)
        val report = OperationalAssessmentRules.assess(facts(listOf(lock()), action = action,
            opaque = listOf("An end-of-DDL callback acquired this lock after validation.")))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        assertTrue(risks(report).isNotEmpty())
        assertFalse(report.findings.any { it.ruleId == "OP_VALIDATION_UNDER_RETAINED_LOCK" },
            "The risk is the measured blocking lock; its timing does not prove the scan was blocked.")
        assertTrue(report.findings.any { it.ruleId == "OP_DATABASE_CALLBACK_UNPROVEN" && it.verdict == OperationalVerdict.UNKNOWN })
    }

    @Test fun `only ordinary supported commands can use a positive authored acquisition timeout`() {
        val metadata = OperationalAction(OperationalActionKind.METADATA_CHANGE, old,
            catalogConfirmed = true, effectsComplete = true, evidence = "An ordinary catalog-only command.")
        for (origin in listOf(OperationalTimeoutOrigin.MIGRATION_LOCAL, OperationalTimeoutOrigin.MIGRATION_SESSION)) {
            val timeout = OperationalTimeout(250, "session", origin)
            val bounded = OperationalAssessmentRules.assess(facts(listOf(lock()), timeout = timeout, action = metadata))
            assertTrue(risks(bounded).isEmpty(), bounded.toString())
            assertEquals(OperationalVerdict.SAFE, bounded.verdict)
            val opaque = OperationalAssessmentRules.assess(facts(listOf(lock()), timeout = timeout,
                action = metadata, opaque = listOf("A callback can change lock_timeout before acquiring the lock.")))
            assertTrue(risks(opaque).isNotEmpty(), opaque.toString())
            val procedure = OperationalAssessmentRules.assess(facts(listOf(lock()), timeout = timeout))
            assertTrue(risks(procedure).isNotEmpty(), procedure.toString())
            val index = OperationalAssessmentRules.assess(facts(listOf(lock(mode = "ShareLock")), timeout = timeout,
                action = metadata.copy(kind = OperationalActionKind.CREATE_INDEX)))
            assertEquals(OperationalVerdict.RISKY, index.verdict, "Acquisition timeout cannot shorten an established blocking scan.")
            assertTrue(index.findings.any { it.ruleId == "OP_INDEX_BUILD_BLOCKS_WRITES" })
        }
    }

    @Test fun `duplicate samples and several modes give one deterministic risk per table`() {
        val observed = lock()
        val shared = lock(mode = "ShareLock")
        val first = OperationalAssessmentRules.assess(facts(listOf(observed, shared, observed), before = listOf(observed)))
        val second = OperationalAssessmentRules.assess(facts(listOf(shared, observed), before = listOf(observed, observed)))
        assertEquals(1, risks(first).size)
        assertEquals(risks(first), risks(second))
        assertTrue(risks(first).single().evidence.contains("ShareLock"))
        assertTrue(risks(first).single().evidence.contains("AccessExclusiveLock"))
    }
}
