package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationalAssessmentRulesTest {
    private val migration = MigrationInfo("2", "V2__change.sql")
    private val old = OperationalRelation("public.accounts", "table", true, "public.accounts")
    private val fresh = OperationalRelation("public.fresh", "table", false)
    private val verifierTimeout = OperationalTimeout(30_000, "client", OperationalTimeoutOrigin.VERIFIER)
    private val authoredTimeout = OperationalTimeout(750, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL)
    private fun action(kind: OperationalActionKind, target: OperationalRelation? = old) = OperationalAction(kind,
        target = target, catalogConfirmed = true, effectsComplete = true, evidence = "Catalog confirms the requested operation.")
    private fun facts(action: OperationalAction, before: List<OperationalLock> = emptyList(), after: List<OperationalLock> = emptyList(),
                      rewrites: List<OperationalRewrite> = emptyList(), autoCommit: Boolean = false, opaque: List<String> = emptyList(),
                      timeout: OperationalTimeout = verifierTimeout) =
        OperationalStatementFacts(migration, 1, "actual Flyway statement;", autoCommit, timeout, timeout,
            before, after, after.filter { it !in before }, rewrites, listOf(action), opaque)
    private fun lock(table: OperationalRelation = old, mode: String = "AccessExclusiveLock", kind: String = "table") =
        OperationalLock(if (kind == "index") "${table.name}_idx" else table.name, kind, mode, table)
    private fun assess(action: OperationalAction, before: List<OperationalLock> = emptyList(), after: List<OperationalLock> = emptyList(),
                       rewrites: List<OperationalRewrite> = emptyList(), autoCommit: Boolean = false, opaque: List<String> = emptyList(),
                       sizes: TableSizes? = null, timeout: OperationalTimeout = verifierTimeout) =
        OperationalAssessmentRules.assess(facts(action, before, after, rewrites, autoCommit, opaque, timeout), sizes)
    private fun rule(report: OperationalStatementReport, id: String, verdict: OperationalVerdict) =
        assertTrue(report.findings.any { it.ruleId == id && it.verdict == verdict }, report.toString())

    @Test fun `ordinary old-table index remains RISKY after autocommit releases its locks`() {
        val report = assess(action(OperationalActionKind.CREATE_INDEX), autoCommit = true)
        assertEquals(emptyList(), report.heldLocksAfter)
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        rule(report, "OP_INDEX_BUILD_BLOCKS_WRITES", OperationalVerdict.RISKY)
        assertEquals(OperationalSeverity.HIGH, report.findings.first().severity)
    }

    @Test fun `confirmed supported concurrent index is SAFE within the explicit model`() {
        val report = assess(action(OperationalActionKind.CREATE_INDEX).copy(concurrent = true), autoCommit = true)
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        rule(report, "OP_ONLINE_INDEX_BUILD", OperationalVerdict.SAFE)
    }

    @Test fun `unsupported or transactional concurrent index cannot use the online proof`() {
        val concurrent = action(OperationalActionKind.CREATE_INDEX).copy(concurrent = true)
        assertEquals(OperationalVerdict.UNKNOWN, assess(concurrent).verdict)
        assertEquals(OperationalVerdict.UNKNOWN, assess(concurrent.copy(catalogConfirmed = false), autoCommit = true).verdict)
        assertEquals(OperationalVerdict.UNKNOWN, assess(concurrent.copy(effectsComplete = false), autoCommit = true).verdict)
    }

    @Test fun `SET NOT NULL needs actual prior invariant facts`() {
        val scan = action(OperationalActionKind.SET_NOT_NULL).copy(priorNotNull = false, priorValidatedNonNullCheck = false)
        rule(assess(scan), "OP_NOT_NULL_SCAN", OperationalVerdict.RISKY)
        rule(assess(scan.copy(priorValidatedNonNullCheck = true), timeout = authoredTimeout), "OP_VALIDATED_NOT_NULL", OperationalVerdict.SAFE)
        rule(assess(scan.copy(priorNotNull = true), timeout = authoredTimeout), "OP_VALIDATED_NOT_NULL", OperationalVerdict.SAFE)
        assertEquals(OperationalVerdict.UNKNOWN, assess(scan.copy(priorValidatedNonNullCheck = null), timeout = authoredTimeout).verdict)
    }

    @Test fun `validated no-op does not claim a new scan`() {
        val noOp = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = true)
        val authored = OperationalTimeout(750, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL)
        val report = OperationalAssessmentRules.assess(facts(noOp, before = listOf(lock()), after = listOf(lock()))
            .copy(lockTimeoutBefore = authored, lockTimeoutAfter = authored))
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        rule(report, "OP_METADATA_ONLY", OperationalVerdict.SAFE)
    }

    @Test fun `NOT VALID defers row scans while ordinary immediate validation is risky`() {
        for (kind in listOf(OperationalActionKind.ADD_CHECK, OperationalActionKind.ADD_FOREIGN_KEY)) {
            rule(assess(action(kind)), "OP_CONSTRAINT_VALIDATION_BLOCKS_WRITES", OperationalVerdict.RISKY)
            rule(assess(action(kind).copy(notValid = true), timeout = authoredTimeout), "OP_DEFERRED_CONSTRAINT_VALIDATION", OperationalVerdict.SAFE)
        }
    }

    @Test fun `validation notices strong locks retained from an earlier statement`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false)
        val report = assess(validate, before = listOf(lock()), after = listOf(lock()))
        assertEquals(emptyList(), report.newlyObservedLocks)
        rule(report, "OP_VALIDATION_UNDER_RETAINED_LOCK", OperationalVerdict.RISKY)
        assertEquals(OperationalVerdict.RISKY, report.verdict)
    }

    @Test fun `each mode conflicting with ordinary writes blocks the validation proof`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false)
        for (mode in listOf("ShareLock", "ShareRowExclusiveLock", "ExclusiveLock", "AccessExclusiveLock"))
            assertEquals(OperationalVerdict.RISKY, assess(validate, before = listOf(lock(mode = mode))).verdict, mode)
        assertEquals(OperationalVerdict.SAFE, assess(validate, before = listOf(lock(mode = "ShareUpdateExclusiveLock"))).verdict)
    }

    @Test fun `after-only callback lock cannot establish that validation ran under it`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false)
        val report = assess(validate, after = listOf(lock()), opaque = listOf("ddl_command_end trigger can acquire a lock after validation"))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        rule(report, "OP_WRITE_BLOCKING_LOCK", OperationalVerdict.RISKY)
        rule(report, "OP_DATABASE_CALLBACK_UNPROVEN", OperationalVerdict.UNKNOWN)
        assertFalse(report.findings.any { it.ruleId == "OP_VALIDATION_UNDER_RETAINED_LOCK" })
    }

    @Test fun `index locks are kept distinct from owning table locks`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false)
        val index = lock(kind = "index")
        val report = assess(validate, before = listOf(index), after = listOf(index))
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        assertEquals("index", report.heldLocksBefore.single().relationKind)
        assertEquals(old, report.heldLocksBefore.single().table)
    }

    @Test fun `new child cannot hide an old referenced parent blocking lock`() {
        val fk = action(OperationalActionKind.ADD_FOREIGN_KEY, fresh).copy(relatedTables = listOf(old))
        val report = assess(fk, before = listOf(lock()), after = listOf(lock()))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        assertTrue(report.findings.any { it.objectName == old.name && it.verdict == OperationalVerdict.RISKY })
    }

    @Test fun `new scan prolongs an unrelated old table lock too`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT, fresh).copy(priorConstraintValidated = false)
        val report = assess(validate, before = listOf(lock()))
        rule(report, "OP_VALIDATION_UNDER_RETAINED_LOCK", OperationalVerdict.RISKY)
        assertTrue(report.findings.any { it.objectName == old.name })
    }

    @Test fun `supported new-table operations and rewrites have no old-storage scan risk`() {
        rule(assess(action(OperationalActionKind.CREATE_INDEX, fresh)), "OP_NEW_RELATION_OPERATION", OperationalVerdict.SAFE)
        val rewrite = OperationalRewrite(fresh, fresh.name, fresh.name)
        val report = assess(action(OperationalActionKind.ALTER_COLUMN_TYPE, fresh), rewrites = listOf(rewrite))
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        assertEquals(listOf(rewrite), report.rewrittenTables)
    }

    @Test fun `unknown new-table statements do not become SAFE from the target exemption`() {
        val rewrite = OperationalRewrite(fresh, fresh.name, fresh.name)
        val report = assess(action(OperationalActionKind.UNKNOWN, fresh), rewrites = listOf(rewrite))
        assertEquals(OperationalVerdict.UNKNOWN, report.verdict)
    }

    @Test fun `an observed old rewrite establishes risk even for unsupported SQL`() {
        val rewrite = OperationalRewrite(old, old.name, old.name)
        val report = assess(action(OperationalActionKind.UNKNOWN), rewrites = listOf(rewrite))
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        rule(report, "OP_TABLE_REWRITE", OperationalVerdict.RISKY)
        rule(report, "OP_STATEMENT_UNSUPPORTED", OperationalVerdict.UNKNOWN)
    }

    @Test fun `unknown old-versus-new identity never receives a new-table exemption`() {
        val relation = old.copy(preExisting = null, baselineName = null)
        val report = assess(action(OperationalActionKind.ALTER_COLUMN_TYPE, relation), rewrites = listOf(OperationalRewrite(relation, relation.name, relation.name)))
        assertEquals(OperationalVerdict.UNKNOWN, report.verdict)
    }

    @Test fun `existing type change without a rewrite is not assumed scan free`() {
        assertEquals(OperationalVerdict.UNKNOWN, assess(action(OperationalActionKind.ALTER_COLUMN_TYPE), timeout = authoredTimeout).verdict)
        // The independent blocking exposure does not pretend an unobserved scan/rewrite occurred.
        val observed = assess(action(OperationalActionKind.ALTER_COLUMN_TYPE), before = listOf(lock()))
        assertEquals(OperationalVerdict.RISKY, observed.verdict)
        rule(observed, "OP_WRITE_BLOCKING_LOCK", OperationalVerdict.RISKY)
        rule(observed, "OP_STATEMENT_UNSUPPORTED", OperationalVerdict.UNKNOWN)
        assertFalse(observed.findings.any { it.ruleId == "OP_TABLE_REWRITE" })
    }

    @Test fun `callbacks prevent intrinsic metadata SAFE but do not erase measured risk`() {
        val metadata = action(OperationalActionKind.METADATA_CHANGE)
        val unknown = assess(metadata, opaque = listOf("applicable event trigger public.observe_ddl"))
        assertEquals(OperationalVerdict.RISKY, unknown.verdict)
        rule(unknown, "OP_WRITE_BLOCKING_LOCK", OperationalVerdict.RISKY)
        rule(unknown, "OP_DATABASE_CALLBACK_UNPROVEN", OperationalVerdict.UNKNOWN)
        val risky = assess(metadata, rewrites = listOf(OperationalRewrite(old, old.name, old.name)), opaque = listOf("public.observe_ddl"))
        assertEquals(OperationalVerdict.RISKY, risky.verdict)
    }

    @Test fun `catalog confirmation and complete effects are positive SAFE prerequisites`() {
        val metadata = action(OperationalActionKind.METADATA_CHANGE)
        assertEquals(OperationalVerdict.SAFE, assess(metadata, timeout = authoredTimeout).verdict)
        assertEquals(OperationalVerdict.UNKNOWN, assess(metadata.copy(catalogConfirmed = false)).verdict)
        val incomplete = assess(metadata.copy(effectsComplete = false), timeout = authoredTimeout)
        assertEquals(OperationalVerdict.RISKY, incomplete.verdict)
        rule(incomplete, "OP_STATEMENT_UNSUPPORTED", OperationalVerdict.UNKNOWN)
        rule(incomplete, "OP_WRITE_BLOCKING_LOCK", OperationalVerdict.RISKY)
    }

    @Test fun `renamed old rewrite looks up its baseline size and retains risk at every size`() {
        val renamed = old.copy(name = "public.renamed")
        val rewrite = OperationalRewrite(renamed, old.name, renamed.name)
        val small = TableSizes(tables = mapOf(old.name to TableSize(10, 1024)))
        val low = assess(action(OperationalActionKind.ALTER_COLUMN_TYPE, renamed), rewrites = listOf(rewrite), sizes = small)
        val missing = assess(action(OperationalActionKind.ALTER_COLUMN_TYPE, renamed), rewrites = listOf(rewrite))
        assertEquals(OperationalVerdict.RISKY, low.verdict)
        assertEquals(OperationalSeverity.LOW, low.findings.first { it.ruleId == "OP_TABLE_REWRITE" }.severity)
        assertEquals(OperationalSeverity.HIGH, missing.findings.first { it.ruleId == "OP_TABLE_REWRITE" }.severity)
        assertTrue(low.findings.first { it.ruleId == "OP_TABLE_REWRITE" }.evidence.contains(old.name))
    }

    @Test fun `timeout origin and zero are facts not a risk downgrade`() {
        val base = facts(action(OperationalActionKind.CREATE_INDEX)).copy(
            lockTimeoutBefore = OperationalTimeout(0, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL),
            lockTimeoutAfter = OperationalTimeout(750, "session", OperationalTimeoutOrigin.MIGRATION_SESSION))
        val report = OperationalAssessmentRules.assess(base)
        assertEquals(0L, report.lockTimeoutBefore.milliseconds)
        assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, report.lockTimeoutBefore.origin)
        assertEquals(750L, report.lockTimeoutAfter.milliseconds)
        assertEquals(OperationalVerdict.RISKY, report.verdict)
    }

    @Test fun `recognized setting change does not claim deployment protection from verifier defaults`() {
        val report = assess(action(OperationalActionKind.SETTINGS_CHANGE, null))
        assertEquals(OperationalVerdict.SAFE, report.verdict)
        assertTrue(report.findings.single().evidence.contains("not deployment-authored"))
    }

    @Test fun `aggregation keeps established risk and incomplete windows cannot certify SAFE`() {
        val safe = assess(action(OperationalActionKind.METADATA_CHANGE), timeout = authoredTimeout)
        val risky = assess(action(OperationalActionKind.CREATE_INDEX))
        val unknown = assess(action(OperationalActionKind.UNKNOWN))
        assertEquals(OperationalVerdict.RISKY, OperationalAssessmentRules.aggregate(listOf(safe, unknown, risky)).verdict)
        assertEquals(OperationalVerdict.UNKNOWN, OperationalAssessmentRules.aggregate(listOf(safe), complete = false).verdict)
        assertEquals(OperationalVerdict.RISKY, OperationalAssessmentRules.aggregate(listOf(risky), complete = false).verdict)
        assertEquals(OperationalVerdict.SAFE, OperationalAssessmentRules.aggregate(emptyList()).verdict)
        assertEquals(OperationalVerdict.UNKNOWN, OperationalAssessmentRules.aggregate(emptyList(), complete = false).verdict)
    }

    @Test fun `returned observations are deterministic snapshots rather than collector views`() {
        val locks = mutableListOf(lock(mode = "ShareUpdateExclusiveLock"), lock(kind = "index"))
        val input = facts(action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false), before = locks, after = locks)
        val first = OperationalAssessmentRules.assess(input)
        val second = OperationalAssessmentRules.assess(input.copy(heldLocksBefore = locks.reversed(), heldLocksAfter = locks.reversed()))
        assertEquals(first, second)
        val reports = mutableListOf(first)
        val aggregate = OperationalAssessmentRules.aggregate(reports)
        locks.clear()
        reports.clear()
        assertEquals(2, aggregate.statements.single().heldLocksBefore.size)
        assertEquals(1, aggregate.statements.size)
    }
    @Test fun `compound ALTER acquires its strongest lock before otherwise online validation`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT).copy(priorConstraintValidated = false)
        val compound = facts(validate, after = listOf(lock())).copy(actions = listOf(
            action(OperationalActionKind.METADATA_CHANGE), validate))
        val report = OperationalAssessmentRules.assess(compound)
        assertEquals(emptyList(), report.heldLocksBefore)
        rule(report, "OP_VALIDATION_UNDER_COMPOUND_LOCK", OperationalVerdict.RISKY)
        assertEquals(OperationalVerdict.RISKY, report.verdict)
    }

    @Test fun `uncertain compound after-only lock timing cannot establish online safety`() {
        val validate = action(OperationalActionKind.VALIDATE_CONSTRAINT, fresh).copy(priorConstraintValidated = false)
        val fk = action(OperationalActionKind.ADD_FOREIGN_KEY, fresh).copy(notValid = true, relatedTables = listOf(old))
        val compound = facts(validate, after = listOf(lock())).copy(actions = listOf(fk, validate))
        val report = OperationalAssessmentRules.assess(compound)
        assertEquals(OperationalVerdict.RISKY, report.verdict)
        rule(report, "OP_WRITE_BLOCKING_LOCK", OperationalVerdict.RISKY)
        assertTrue(report.findings.any { it.verdict == OperationalVerdict.UNKNOWN })
        assertFalse(report.findings.any { it.ruleId == "OP_VALIDATION_UNDER_COMPOUND_LOCK" })
    }

}
