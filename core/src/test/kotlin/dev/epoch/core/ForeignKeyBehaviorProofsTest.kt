package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForeignKeyBehaviorProofsTest {
    private val table = "public.accounts"
    private val backing = "public.accounts_v2"
    private val name = "parent_fk"
    private val security = RelationSecurity("epoch", false, false, false, false)
    private val fk = ConstraintSchema("f", "FOREIGN KEY (parent_id) REFERENCES public.accounts(id) ON DELETE CASCADE ON UPDATE CASCADE",
        listOf("parent_id"), table, listOf("id"), "c", "c")
    private val physical = TableSchema(mapOf("id" to ColumnSchema("bigint", false, ordinal = 1),
        "parent_id" to ColumnSchema("bigint", true, ordinal = 2)), constraints = mapOf(name to fk), relationSecurity = security, relationOid = 1)
    private val delete = QueryContract("delete", Operation.DELETE, "delete from public.accounts where id = ?", listOf(table),
        listOf(ColumnReference(table, "id")), parameterTypes = listOf("BIGINT"), dependenciesComplete = true, targetTable = table)
    private val contract = DatabaseContract(queries = listOf(delete), coverage = Coverage(complete = true))
    private val migration = MigrationInfo("2", "V2__rename.sql")
    private fun graph(owner: String, constraint: ConstraintSchema): BehaviorGraph {
        val parent = requireNotNull(constraint.referencedTable)
        val roots = listOf(Operation.DELETE, Operation.UPDATE).map { operation ->
            val id = "foreign-key:$owner.$name:${operation.name}"
            val action = if (operation == Operation.DELETE) constraint.onDelete else constraint.onUpdate
            val access = when (action) { "c" -> if (operation == Operation.DELETE) BehaviorAccess.DELETE else BehaviorAccess.UPDATE
                "n", "d" -> BehaviorAccess.UPDATE
                else -> BehaviorAccess.SELECT }
            val child = if (access == BehaviorAccess.UPDATE) listOf(BehaviorReference(BehaviorReferenceKind.COLUMN, owner, "parent_id", access))
                else listOf(BehaviorReference(BehaviorReferenceKind.RELATION, owner, access = access))
            Triple(id, BehaviorNode("foreign_key_action", constraint.definition,
                references = child + BehaviorReference(BehaviorReferenceKind.COLUMN, parent, "id"),
                dependenciesComplete = true, semanticsSupported = true, planningMayEvaluate = false),
                BehaviorAttachment(parent, id, setOf(operation), objectName = "$owner.$name", planningRelevant = false,
                    updateColumns = if (operation == Operation.UPDATE) setOf("id") else emptySet()))
        }
        return BehaviorGraph(attachmentInventoryComplete = true, nodes = roots.associate { it.first to it.second }, attachments = roots.map { it.third })
    }
    private val baseline = SchemaSnapshot(mapOf(table to physical), expressionSearchPath = listOf("pg_catalog", "public"), behaviorGraph = graph(table, fk))
    private fun target(constraint: ConstraintSchema = fk): SchemaSnapshot {
        val renamed = constraint.copy(referencedTable = backing,
            definition = constraint.definition.replace("REFERENCES public.accounts(", "REFERENCES public.accounts_v2("))
        val view = TableSchema(physical.columns.mapValues { it.value.copy(nullable = true) }, kind = "view", relationSecurity = security,
            relationOid = 2, view = ViewSchema("SELECT id, parent_id FROM public.accounts_v2", 28, 28, physical.columns.keys,
                physical.columns.keys, backing, true))
        return SchemaSnapshot(mapOf(table to view, backing to physical.copy(constraints = mapOf(name to renamed))),
            expressionSearchPath = baseline.expressionSearchPath, behaviorGraph = graph(backing, renamed))
    }

    @Test fun `unchanged self cascade nodes and runtime targets retain physical identity through a proved view`() {
        val target = target()
        val normalized = CompatibilityViews.normalize(baseline, target)
        assertEquals(setOf(table), normalized.provenViews.keys)
        assertEquals(4, ForeignKeyBehaviorProofs.preservedRoots(baseline, normalized.after, target, normalized.provenViews).size)
        val report = CompatibilityEngine().verifyStep(contract, baseline, target, migration)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertFalse(report.findings.any { it.ruleId == "INDIRECT_WRITE_DEPENDENCY_UNPROVEN" || it.ruleId == "DATABASE_BEHAVIOR_CHANGED" })
    }

    @Test fun `changed referential action is not hidden by the compatibility view`() {
        val target = target(fk.copy(onDelete = "n", definition = fk.definition.replace("ON DELETE CASCADE", "ON DELETE SET NULL")))
        val report = CompatibilityEngine().verifyStep(contract, baseline, target, migration)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "DATABASE_BEHAVIOR_CHANGED" && "delete" in it.affectedContractIds }, report.toString())
    }

    @Test fun `inconsistent synthetic node definition does not inherit the FK proof`() {
        val target = target().let { snapshot -> snapshot.copy(behaviorGraph = snapshot.behaviorGraph!!.copy(nodes =
            snapshot.behaviorGraph.nodes.mapValues { (_, node) -> node.copy(definition = "different operation") })) }
        val normalized = CompatibilityViews.normalize(baseline, target)
        assertTrue(ForeignKeyBehaviorProofs.preservedRoots(baseline, normalized.after, target, normalized.provenViews).isEmpty())
        assertEquals(Verdict.UNKNOWN, CompatibilityEngine().verifyStep(contract, baseline, target, migration).verdict)
    }
}
