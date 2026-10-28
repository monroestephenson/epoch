package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorEngineTest {
    private val t = "public.accounts"
    private val unrelated = "public.audit"
    private val id = ColumnReference(t, "id")
    private val value = ColumnReference(t, "value")
    private val columns = mapOf("id" to ColumnSchema("bigint", false, ordinal = 1, typeAcceptsImplicitNull = true),
        "value" to ColumnSchema("text", true, ordinal = 2, typeAcceptsImplicitNull = true))
    private val table = TableSchema(columns, relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val graph = BehaviorGraph(attachmentInventoryComplete = true, nodes = emptyMap(), attachments = emptyList())
    private val snapshot = SchemaSnapshot(mapOf(t to table, unrelated to table), expressionSearchPath = listOf("pg_catalog", "public"), behaviorGraph = graph)
    private val read = QueryContract("read", Operation.SELECT, "select id from public.accounts", listOf(t), listOf(id), dependenciesComplete = true)
    private val insert = QueryContract("insert", Operation.INSERT, "insert into public.accounts(id) values (?)", listOf(t), listOf(id), listOf(id),
        parameterTypes = listOf("BIGINT"), dependenciesComplete = true, targetTable = t)
    private val contract = DatabaseContract(queries = listOf(read, insert), coverage = Coverage(complete = true))
    private val migration = MigrationInfo("2", "V2__pending.sql")
    private fun opaque(kind: String) = BehaviorNode(kind, "user-defined $kind", opaqueReasons = listOf("Hidden name lookup is unresolved."))

    @Test fun `unused helper and view have no baseline or migration finding`() {
        val baseline = snapshot.copy(behaviors = mapOf("function:unused" to "select 1", "view:unused" to "select 1"),
            behaviorGraph = graph.copy(nodes = mapOf("function:unused" to opaque("function"), "view:unused" to opaque("view"))))
        val target = baseline.copy(tables = baseline.tables + (t to table.copy(columns = columns + ("extra" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)))))
        val engine = CompatibilityEngine()
        assertEquals(Verdict.SAFE, engine.inspectBaseline(contract, baseline).verdict)
        val report = engine.verifyStep(contract, baseline, target, migration)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertFalse(report.findings.any { it.ruleId.startsWith("DATABASE_BEHAVIOR") })
    }

    @Test fun `same opaque default cause is grouped and only omitted writers are callers`() {
        val function = "function:public.choose_value()"
        val default = "default:$t.value"
        val baseline = snapshot.copy(behaviorGraph = graph.copy(nodes = mapOf(
            function to opaque("function"), default to BehaviorNode("default", "choose_value()", listOf(function),
                dependenciesComplete = true, semanticsSupported = true, planningMayEvaluate = false)),
            attachments = listOf(BehaviorAttachment(t, default, setOf(Operation.INSERT, Operation.UPDATE), BehaviorActivation.DEFAULT, "value", "$t.value"))))
        val supplied = insert.copy(id = "supplied", sql = "insert into public.accounts(id,value) values (?,?)", columns = listOf(id, value),
            writeColumns = listOf(id, value), parameterTypes = listOf("BIGINT", "VARCHAR"))
        val consumers = (1..20).map { insert.copy(id = "omitted-$it") }
        val all = contract.copy(queries = consumers + read + supplied)
        val engine = CompatibilityEngine()
        val health = engine.inspectBaseline(all, baseline)
        val cause = health.findings.single { it.objectName == function }
        assertEquals(consumers.map { it.id }.sorted(), cause.affectedContractIds)
        assertFalse(health.findings.any { "read" in it.affectedContractIds || "supplied" in it.affectedContractIds })
        val target = baseline.copy(tables = baseline.tables + (unrelated to table.copy(columns = columns + ("extra" to ColumnSchema("text", true)))))
        val report = engine.verifyStep(all, baseline, target, migration)
        assertEquals(consumers.map { it.id }.sorted(), report.findings.single { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }.affectedContractIds)
    }

    @Test fun `new trigger attaches only to its event callers`() {
        val target = snapshot.copy(behaviors = mapOf("trigger:new" to "INSERT trigger"), behaviorGraph = graph.copy(
            nodes = mapOf("trigger:new" to opaque("trigger")),
            attachments = listOf(BehaviorAttachment(t, "trigger:new", setOf(Operation.INSERT), planningRelevant = false))))
        val report = CompatibilityEngine().verifyStep(contract, snapshot, target, migration)
        val change = report.findings.single { it.ruleId == "DATABASE_BEHAVIOR_CHANGED" }
        assertEquals(listOf("insert"), change.affectedContractIds)
        assertFalse(report.findings.any { "read" in it.affectedContractIds })
    }

    @Test fun `unrelated trigger does not block a concrete required-column proof`() {
        val baseline = snapshot.copy(behaviorGraph = graph.copy(nodes = mapOf("trigger:other" to opaque("trigger")),
            attachments = listOf(BehaviorAttachment(unrelated, "trigger:other", setOf(Operation.INSERT), planningRelevant = false))))
        val target = baseline.copy(tables = baseline.tables + (t to table.copy(columns = columns +
            ("required" to ColumnSchema("text", false, typeAcceptsImplicitNull = true)))))
        val report = CompatibilityEngine().verifyStep(contract, baseline, target, migration)
        assertTrue(report.findings.any { it.ruleId == "REQUIRED_COLUMN_OMITTED" && it.verdict == Verdict.BREAKING }, report.toString())
        assertFalse(report.findings.any { it.ruleId.startsWith("DATABASE_BEHAVIOR") })
    }

    @Test fun `changed unrelated behavior is ignored only with complete attachments`() {
        val baseline = snapshot.copy(behaviors = mapOf("function:unused" to "old"),
            behaviorGraph = graph.copy(nodes = mapOf("function:unused" to opaque("function"))))
        val target = baseline.copy(behaviors = mapOf("function:unused" to "new"),
            behaviorGraph = graph.copy(nodes = mapOf("function:unused" to opaque("function").copy(definition = "new"))))
        assertEquals(Verdict.SAFE, CompatibilityEngine().verifyStep(contract, baseline, target, migration).verdict)
        assertEquals(Verdict.UNKNOWN, CompatibilityEngine().verifyStep(contract,
            baseline.copy(behaviorGraph = null), target.copy(behaviorGraph = null), migration).verdict)
    }

    @Test fun `new attachment to an existing opaque root is still a new interaction`() {
        val baseline = snapshot.copy(behaviorGraph = graph.copy(nodes = mapOf("function:already-present" to opaque("function"))))
        val target = baseline.copy(behaviorGraph = baseline.behaviorGraph!!.copy(attachments = listOf(
            BehaviorAttachment(t, "function:already-present", setOf(Operation.INSERT)))))
        val report = CompatibilityEngine().verifyStep(contract, baseline, target, migration)
        assertTrue(report.findings.any { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" && it.affectedContractIds == listOf("insert") })
    }
    @Test fun `parent delete notices tightened child column reached by SET NULL`() {
        val fk = ConstraintSchema("f", "FOREIGN KEY (value) REFERENCES accounts(value) ON DELETE SET NULL", listOf("value"), t,
            listOf("value"), "SET NULL", "NO ACTION")
        val baseline = snapshot.copy(tables = snapshot.tables + (unrelated to table.copy(constraints = mapOf("fk" to fk))))
        val deletion = QueryContract("delete-parent", Operation.DELETE, "delete from public.accounts where id = ?", listOf(t), listOf(id),
            parameterTypes = listOf("BIGINT"), dependenciesComplete = true, targetTable = t)
        val onlyParent = contract.copy(queries = listOf(deletion))
        val engine = CompatibilityEngine()
        assertEquals(Verdict.SAFE, engine.inspectBaseline(onlyParent, baseline).verdict)
        val target = baseline.copy(tables = baseline.tables + (unrelated to baseline.tables.getValue(unrelated).copy(
            columns = columns + ("value" to columns.getValue("value").copy(nullable = false)))))
        val report = engine.verifyStep(onlyParent, baseline, target, migration)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertEquals(listOf("delete-parent"), report.findings.single { it.ruleId == "INDIRECT_WRITE_DEPENDENCY_UNPROVEN" }.affectedContractIds)
        val scope = BehaviorScope(baseline).forQuery(deletion, BehaviorQuerySyntax().inspect(deletion))
        assertTrue(BehaviorReference(BehaviorReferenceKind.COLUMN, unrelated, "value", BehaviorAccess.UPDATE) in scope.references)
        assertFalse(scope.planningReferences.any { it.name == unrelated })
    }

    @Test fun `new child CHECK applies to cascade UPDATE even on an unchanged column`() {
        val fk = ConstraintSchema("f", "FOREIGN KEY (value) REFERENCES accounts(value) ON DELETE SET NULL", listOf("value"), t,
            listOf("value"), "SET NULL", "NO ACTION")
        val baseline = snapshot.copy(tables = snapshot.tables + (unrelated to table.copy(constraints = mapOf("fk" to fk))))
        val deletion = QueryContract("delete-parent", Operation.DELETE, "delete from public.accounts where id = ?", listOf(t), listOf(id),
            parameterTypes = listOf("BIGINT"), dependenciesComplete = true, targetTable = t)
        val target = baseline.copy(tables = baseline.tables + (unrelated to baseline.tables.getValue(unrelated).copy(
            constraints = mapOf("fk" to fk, "positive" to ConstraintSchema("c", "CHECK (id > 0) NOT VALID", listOf("id"), checkExpression = "id > 0", validated = false)))))
        val report = CompatibilityEngine().verifyStep(contract.copy(queries = listOf(deletion)), baseline, target, migration)
        assertTrue(report.findings.any { it.ruleId == "INDIRECT_WRITE_DEPENDENCY_UNPROVEN" && it.affectedContractIds == listOf("delete-parent") }, report.toString())
    }

}
