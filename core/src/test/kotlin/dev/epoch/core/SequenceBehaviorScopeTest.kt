package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceBehaviorScopeTest {
    private val table = "public.accounts"
    private val sequence = "public.account_ids"
    private val id = ColumnReference(table, "id")
    private val label = ColumnReference(table, "label")
    private val physical = TableSchema(mapOf(
        "id" to ColumnSchema("bigint", false, defaultExpression = "nextval('public.account_ids'::regclass)", typeAcceptsImplicitNull = true),
        "label" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)),
        relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val defaultId = "default:$table.id"
    private val graph = BehaviorGraph(attachmentInventoryComplete = true,
        nodes = mapOf(defaultId to BehaviorNode("default", "nextval('public.account_ids'::regclass)",
            references = listOf(BehaviorReference(BehaviorReferenceKind.SEQUENCE, sequence)),
            dependenciesComplete = true, semanticsSupported = true, planningMayEvaluate = false)),
        attachments = listOf(BehaviorAttachment(table, defaultId, setOf(Operation.INSERT, Operation.UPDATE), BehaviorActivation.DEFAULT, "id", "$table.id")))
    private val baseline = SchemaSnapshot(mapOf(table to physical), sequences = mapOf(sequence to "increment=1"),
        sequenceOwners = mapOf(sequence to id), expressionSearchPath = listOf("pg_catalog", "public"), behaviorGraph = graph)
    private val target = baseline.copy(sequences = mapOf(sequence to "increment=10"))
    private val read = QueryContract("read", Operation.SELECT, "select id from accounts", listOf(table), listOf(id), dependenciesComplete = true)
    private val supplied = QueryContract("supplied", Operation.INSERT, "insert into accounts(id,label) values (?,?)", listOf(table),
        listOf(id, label), listOf(id, label), listOf("BIGINT", "VARCHAR"), dependenciesComplete = true, targetTable = table)
    private val omitted = QueryContract("omitted", Operation.INSERT, "insert into accounts(label) values (?)", listOf(table),
        listOf(label), listOf(label), listOf("VARCHAR"), dependenciesComplete = true, targetTable = table)
    private val migration = MigrationInfo("2", "V2__sequence.sql")
    private fun contract(vararg queries: QueryContract) = DatabaseContract(queries = queries.toList(), coverage = Coverage(complete = true))

    @Test fun `owned sequence change affects only INSERTs invoking its default`() {
        val report = CompatibilityEngine().verifyStep(contract(read, supplied, omitted), baseline, target, migration)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertEquals(listOf("omitted"), report.findings.single { it.ruleId == "SEQUENCE_CHANGED" }.affectedContractIds)
    }

    @Test fun `owned sequence is irrelevant to reads and supplied identifier writes`() {
        val report = CompatibilityEngine().verifyStep(contract(read, supplied), baseline, target, migration)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertFalse(report.findings.any { it.ruleId == "SEQUENCE_CHANGED" })
    }

    @Test fun `unowned unrelated sequence changes need no contract finding with complete graph facts`() {
        val before = baseline.copy(sequences = baseline.sequences + ("public.unused" to "old"))
        val after = before.copy(sequences = before.sequences + ("public.unused" to "new"))
        assertEquals(Verdict.SAFE, CompatibilityEngine().verifyStep(contract(read), before, after, migration).verdict)
    }

    @Test fun `missing attachment inventory never establishes sequence disjointness`() {
        val report = CompatibilityEngine().verifyStep(contract(read), baseline.copy(behaviorGraph = null), target.copy(behaviorGraph = null), migration)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "SEQUENCE_CHANGED" })
    }

    @Test fun `opaque SQL remains a possible sequence caller without contaminating known reads`() {
        val opaque = QueryContract("dynamic", Operation.SELECT, "select choose_sequence()", dependenciesComplete = true)
        val report = CompatibilityEngine().verifyStep(contract(read, opaque), baseline, target, migration)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertEquals(listOf("dynamic"), report.findings.single { it.ruleId == "SEQUENCE_CHANGED" }.affectedContractIds)
    }

    @Test fun `declared identifier generator is an exact sequence caller even without captured SQL`() {
        val entity = EntityContract("Account", table, listOf(ColumnContract("id", "BIGINT", generated = true)),
            operations = setOf(Operation.INSERT), generatedIdentifier = "SequenceStyleGenerator", identifierSequence = sequence)
        val report = CompatibilityEngine().verifyStep(contract(read).copy(entities = listOf(entity)), baseline, target, migration)
        assertEquals(listOf("Account"), report.findings.single { it.ruleId == "SEQUENCE_CHANGED" }.affectedContractIds)
    }
}
