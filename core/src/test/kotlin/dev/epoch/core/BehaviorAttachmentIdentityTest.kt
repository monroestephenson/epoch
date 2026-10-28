package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Identity-only proof tests; production helper is applied after verify-first cases. */
class BehaviorAttachmentIdentityTest {
    private val oldName = "public.accounts"
    private val newName = "public.accounts_v2"
    private val oldId = "default:$oldName.marker"
    private val newId = "default:$newName.marker"
    private val function = "function:public.hidden_lookup()"
    private val columns = mapOf("marker" to ColumnSchema("text", true, "hidden_lookup()", ordinal = 1))
    private val table = TableSchema(columns, relationOid = 91L)
    private val opaqueFunction = BehaviorNode("function", "hidden lookup code", opaqueReasons = listOf("Hidden lookup"))
    private fun node(owner: String) = BehaviorNode("default", "hidden_lookup()", listOf(function),
        references = listOf(BehaviorReference(BehaviorReferenceKind.COLUMN, owner, "marker")),
        opaqueReasons = listOf("Opaque default"))
    private fun graph(owner: String, id: String) = BehaviorGraph(attachmentInventoryComplete = true,
        nodes = mapOf(id to node(owner), function to opaqueFunction),
        attachments = listOf(BehaviorAttachment(owner, id, setOf(Operation.INSERT, Operation.UPDATE),
            BehaviorActivation.DEFAULT, "marker", "$owner.marker")))
    private val before = SchemaSnapshot(mapOf(oldName to table), behaviorGraph = graph(oldName, oldId))
    private val after = SchemaSnapshot(mapOf(newName to table), behaviorGraph = graph(newName, newId))
    private val normalized = after.copy(tables = mapOf(oldName to table))

    @Test fun `opaque attached default identity normalizes while hidden function opacity remains`() {
        assertEquals(mapOf(newId to oldId), BehaviorAttachmentIdentity.renamedRoots(before, normalized, after,
            mapOf(oldName to newName)))
        val query = QueryContract("insert", Operation.INSERT, "insert into public.accounts_v2 default values",
            tables = listOf(newName), targetTable = newName, dependenciesComplete = true)
        val scope = BehaviorScope(after).forQuery(query, BehaviorQuerySyntax().inspect(query))
        assertTrue(scope.uncertainties.any { it.nodeId == function })
        assertTrue(scope.uncertainties.any { it.nodeId == newId })
    }

    @Test fun `changed expression type or reached function inventory cannot pair by name alone`() {
        val changedTable = table.copy(columns = columns + ("marker" to columns.getValue("marker").copy(defaultExpression = "other()")))
        val changed = after.copy(tables = mapOf(newName to changedTable))
        assertTrue(BehaviorAttachmentIdentity.renamedRoots(before, changed.copy(tables = mapOf(oldName to changedTable)), changed,
            mapOf(oldName to newName)).isEmpty())
        val graph = after.behaviorGraph!!
        val alteredNode = graph.nodes.getValue(newId).copy(childNodeIds = listOf("function:public.other()"))
        assertTrue(BehaviorAttachmentIdentity.renamedRoots(before, normalized,
            after.copy(behaviorGraph = graph.copy(nodes = graph.nodes + (newId to alteredNode))), mapOf(oldName to newName)).isEmpty())
        assertTrue(BehaviorAttachmentIdentity.renamedRoots(before, normalized, after, emptyMap()).isEmpty())
    }

    @Test fun `missing complete attachment inventory cannot establish owned identity`() {
        assertTrue(BehaviorAttachmentIdentity.renamedRoots(before, normalized,
            after.copy(behaviorGraph = after.behaviorGraph!!.copy(attachmentInventoryComplete = false)),
            mapOf(oldName to newName)).isEmpty())
    }
}
