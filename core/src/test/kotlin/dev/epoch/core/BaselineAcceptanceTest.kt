package dev.epoch.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.*

class BaselineAcceptanceTest {
    private val engine = CompatibilityEngine()
    private val migration = MigrationInfo("2", "V2__add_nullable.sql")
    private val table = TableSchema(mapOf("id" to ColumnSchema("bigint", false, ordinal = 1)),
        relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val snapshot = SchemaSnapshot(mapOf("public.customers" to table))
    private val contract = DatabaseContract(
        entities = listOf(EntityContract("Customer", "public.customers", listOf(ColumnContract("id")))),
        coverage = Coverage(complete = true),
    )
    private val debtContract = contract.copy(unresolvedQueries = listOf(UnresolvedQuery("search", "Unbounded runtime HQL")))
    private val after = snapshot.copy(tables = mapOf("public.customers" to table.copy(
        columns = table.columns + ("metadata" to ColumnSchema("text", true, ordinal = 2, typeAcceptsImplicitNull = true)),
    )))

    private fun report(c: DatabaseContract = debtContract): CompatibilityReport {
        val baseline = engine.inspectBaseline(c, snapshot, fingerprintContext = "migration-checksums")
        val step = engine.verifyStep(c, snapshot, after, migration, baseline.findings)
        return engine.report(c, listOf(step), baseline)
    }

    @Test fun `baseline debt is emitted once without pending migration attribution`() {
        val report = report()
        val baseline = assertNotNull(report.baseline)
        assertEquals(Verdict.UNKNOWN, baseline.verdict)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertEquals(Verdict.SAFE, report.migrations.single().verdict)
        assertTrue(baseline.findings.all { it.migration == null })
        assertEquals(1, baseline.findings.count { it.ruleId == "UNRESOLVED_QUERY" })
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "UNRESOLVED_QUERY" })
        assertNull(report.gateVerdict)
        assertEquals(1, report.ruleSummary.single { it.ruleId == "UNRESOLVED_QUERY" }.baselineCount)
        assertEquals(0, report.ruleSummary.single { it.ruleId == "UNRESOLVED_QUERY" }.migrationCount)
    }

    @Test fun `creating a baseline-missing table does not reattribute grouped baseline findings`() {
        val query = QueryContract("Customer.find", Operation.SELECT, "select id from public.customers",
            tables = listOf("public.customers"), columns = listOf(ColumnReference("public.customers", "id")),
            dependenciesComplete = true)
        val c = contract.copy(queries = listOf(query))
        val empty = SchemaSnapshot()
        val baseline = engine.inspectBaseline(c, empty)
        val step = engine.verifyStep(c, empty, snapshot, migration, baseline.findings)
        val report = engine.report(c, listOf(step), baseline)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.migrations.single().findings.none { it.ruleId.startsWith("INVALID_BASELINE_") })
        assertTrue(baseline.findings.all { it.migration == null })
    }

    @Test fun `same baseline debt does not multiply over many intermediate states`() {
        val baseline = engine.inspectBaseline(debtContract, snapshot)
        val steps = (2..100).map { index -> engine.verifyStep(debtContract, snapshot, after,
            MigrationInfo(index.toString(), "V${index}__add.sql"), baseline.findings) }
        val report = engine.report(debtContract, steps, baseline)
        assertEquals(1, report.baseline!!.findings.count { it.ruleId == "UNRESOLVED_QUERY" })
        assertEquals(0, report.migrations.sumOf { step -> step.findings.count { it.ruleId == "UNRESOLVED_QUERY" } })
    }

    @Test fun `accepted debt changes CI gate without pretending compatibility is proven`() {
        val report = report()
        val accepted = BaselineAcceptance.fromReport(report, "Legacy search query inventory tracked in ISSUE-42")
        val gated = BaselineAcceptance.apply(report, accepted)
        assertEquals(Verdict.UNKNOWN, gated.verdict)
        assertEquals(Verdict.SAFE, gated.gateVerdict)
        assertEquals(accepted.findings, gated.acceptedBaseline)
        assertEquals(1, gated.ruleSummary.single { it.ruleId == "UNRESOLVED_QUERY" }.acceptedBaselineCount)
        assertTrue(gated.limitations.any { it.contains("verdict remains unchanged") })
    }

    @Test fun `accepted baseline never hides new breaking findings`() {
        val baseline = engine.inspectBaseline(debtContract, snapshot)
        val policy = BaselineAcceptance.fromReport(engine.report(debtContract, emptyList(), baseline), "Existing debt")
        val dropped = snapshot.copy(tables = emptyMap())
        val step = engine.verifyStep(debtContract, snapshot, dropped, migration, baseline.findings)
        val report = engine.report(debtContract, listOf(step), baseline, policy)
        assertEquals(Verdict.BREAKING, report.verdict)
        assertEquals(Verdict.BREAKING, report.gateVerdict)
        assertTrue(report.migrations.single().findings.any { it.ruleId == "REFERENCED_TABLE_DROPPED" })
    }

    @Test fun `accepted baseline never hides new unknown migration effects`() {
        val baseline = engine.inspectBaseline(debtContract, snapshot)
        val policy = BaselineAcceptance.fromReport(engine.report(debtContract, emptyList(), baseline), "Existing debt")
        val step = engine.verifyStep(debtContract, snapshot, after, migration, baseline.findings + Finding(
            Verdict.UNKNOWN, "UNSUPPORTED_MIGRATION_EFFECT", "migration", "Data behavior is unproven", "UPDATE customers SET id = id + 1",
        ))
        val report = engine.report(debtContract, listOf(step), baseline, policy)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertEquals(Verdict.UNKNOWN, report.gateVerdict)
    }

    @Test fun `partial acceptance retains unaccepted baseline debt in gate`() {
        val c = debtContract.copy(coverage = Coverage(complete = false))
        val report = report(c)
        val full = BaselineAcceptance.fromReport(report, "Known debt")
        assertTrue(full.findings.size >= 2)
        assertEquals(Verdict.UNKNOWN, BaselineAcceptance.apply(report, full.copy(findings = full.findings.take(1))).gateVerdict)
    }

    @Test fun `justification is mandatory for generation and application`() {
        val report = report()
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.fromReport(report, "  ") }
        val policy = BaselineAcceptance.fromReport(report, "Known debt")
        val missing = policy.copy(findings = policy.findings.map { it.copy(justification = "") })
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, missing) }
    }

    @Test fun `stale and unknown fingerprints fail rather than silently accepting more findings`() {
        val report = report()
        val policy = BaselineAcceptance.fromReport(report, "Known debt")
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, policy.copy(baselineFingerprint = "old")) }
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, policy.copy(findings = listOf(AcceptedBaselineFinding("not-in-baseline", "reason")))) }
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, policy.copy(findings = policy.findings + policy.findings)) }
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, policy.copy(schemaVersion = 2)) }
    }

    @Test fun `migration finding fingerprint cannot be accepted even with valid baseline fingerprint`() {
        val report = report()
        val migrationFinding = report.migrations.single().findings.first()
        val forged = AcceptedBaseline(baselineFingerprint = report.baseline!!.fingerprint,
            findings = listOf(AcceptedBaselineFinding(BaselineFingerprints.finding(migrationFinding), "attempted suppression")))
        assertFailsWith<IllegalArgumentException> { BaselineAcceptance.apply(report, forged) }
    }

    @Test fun `fingerprint changes with contract catalog or migration inputs but not cache status`() {
        val original = engine.inspectBaseline(debtContract, snapshot, fingerprintContext = "V1:abc")
        assertNotEquals(original.fingerprint, engine.inspectBaseline(contract, snapshot, fingerprintContext = "V1:abc").fingerprint)
        assertNotEquals(original.fingerprint, engine.inspectBaseline(debtContract, after, fingerprintContext = "V1:abc").fingerprint)
        assertNotEquals(original.fingerprint, engine.inspectBaseline(debtContract, snapshot, fingerprintContext = "V1:def").fingerprint)
        val policy = BaselineAcceptance.fromReport(engine.report(debtContract, emptyList(), original), "Known debt")
        val reused = engine.report(debtContract, emptyList(), original.copy(cacheStatus = "HIT", cacheKey = "abc"), policy)
        assertEquals(Verdict.SAFE, reused.gateVerdict)
    }

    @Test fun `already invalid baseline SQL cannot invent a breaking regression`() {
        val query = QueryContract("Customer.find", Operation.SELECT, "select id from public.customers", tables = listOf("public.customers"),
            columns = listOf(ColumnReference("public.customers", "id")), dependenciesComplete = true)
        val c = contract.copy(entities = emptyList(), queries = listOf(query))
        val rejected = Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", query.id,
            "Baseline SQL could not be validated", "Parameter types do not match", listOf(query.id))
        val baseline = engine.inspectBaseline(c, snapshot, listOf(rejected))
        val step = engine.verifyStep(c, snapshot, SchemaSnapshot(), migration, baseline.findings)
        val report = engine.report(c, listOf(step), baseline)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.migrations.flatMap { it.findings }.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `runtime observation limitation is conditional`() {
        val ordinary = engine.report(contract, listOf(engine.verifyStep(contract, snapshot, after, migration)))
        assertFalse(ordinary.limitations.any { it.contains("Runtime-observed") })
        val observed = contract.copy(queries = listOf(QueryContract("runtime", Operation.SELECT, "select id from customers",
            source = QuerySource.RUNTIME_OBSERVED, verificationStatus = VerificationStatus.OBSERVED)))
        val report = engine.report(observed, emptyList(), engine.inspectBaseline(observed, snapshot))
        assertTrue(report.limitations.any { it.contains("Runtime-observed") })
    }

    @Test fun `existing behavior belongs to baseline health while changed behavior remains migration finding`() {
        val old = snapshot.copy(behaviors = mapOf("function:app" to "original"))
        val baseline = engine.inspectBaseline(contract, old)
        val changed = old.copy(behaviors = mapOf("function:app" to "changed"))
        val step = engine.verifyStep(contract, old, changed, migration, baseline.findings)
        val report = engine.report(contract, listOf(step), baseline)
        assertEquals(listOf("EXISTING_DATABASE_BEHAVIOR"), baseline.findings.map { it.ruleId })
        assertTrue(report.migrations.single().findings.none { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" })
        assertTrue(report.migrations.single().findings.any { it.ruleId == "DATABASE_BEHAVIOR_CHANGED" })
    }

    @Test fun `unchanged extensions do not count as baseline behavior but version changes remain unknown`() {
        val old = snapshot.copy(extensions = mapOf("pgcrypto" to "1.3"))
        val baseline = engine.inspectBaseline(contract, old)
        assertEquals(Verdict.SAFE, baseline.verdict)
        val unchanged = engine.verifyStep(contract, old, old, migration)
        assertEquals(Verdict.SAFE, unchanged.verdict)
        val changed = engine.verifyStep(contract, old, old.copy(extensions = mapOf("pgcrypto" to "1.4")), migration)
        assertEquals(Verdict.UNKNOWN, changed.verdict)
        assertTrue(changed.findings.any { it.ruleId == "EXTENSION_CHANGED" })
    }

    @Test fun `accepted baseline and report JSON round trip preserves distinct proof and gate`() {
        val mapper = jacksonObjectMapper()
        val policy = BaselineAcceptance.fromReport(report(), "Known debt")
        assertEquals(policy, mapper.readValue<AcceptedBaseline>(mapper.writeValueAsString(policy)))
        val accepted = BaselineAcceptance.apply(report(), policy)
        assertEquals(accepted, mapper.readValue<CompatibilityReport>(mapper.writeValueAsString(accepted)))
    }
}
