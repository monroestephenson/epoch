package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorChangesTest {
    private val table = "public.accounts"
    private val before = SchemaSnapshot(tables = mapOf(table to TableSchema(mapOf("id" to ColumnSchema("integer", false)))))

    @Test fun `column references ignore unrelated column additions but whole relation references do not`() {
        val after = before.copy(tables = mapOf(table to before.tables.getValue(table).copy(columns =
            before.tables.getValue(table).columns + ("label" to ColumnSchema("text", true)))))
        val changes = BehaviorChanges(before, after)
        assertFalse(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.COLUMN, table, "id"))))
        assertTrue(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.RELATION, table))))
        assertTrue(changes.hasChanges)
    }

    @Test fun `relation replacement invalidates column dependencies`() {
        val after = before.copy(tables = mapOf(table to before.tables.getValue(table).copy(kind = "view")))
        assertTrue(BehaviorChanges(before, after).intersects(setOf(BehaviorReference(BehaviorReferenceKind.COLUMN, table, "id"))))
    }

    @Test fun `new attachments count even when the shared node definition does not change`() {
        val node = BehaviorNode("function", "unchanged")
        val a = before.copy(behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true,
            nodes = mapOf("fn" to node), attachments = emptyList()))
        val b = a.copy(behaviorGraph = a.behaviorGraph!!.copy(attachments = listOf(BehaviorAttachment(table, "fn", setOf(Operation.SELECT)))))
        assertTrue("fn" in BehaviorChanges(a, b).changedNodeIds)
    }

    @Test fun `types sequences and extensions have separate exact identities`() {
        val after = before.copy(enumTypes = mapOf("public.status" to listOf("OPEN")), sequences = mapOf("public.ids" to "sequence"),
            extensions = mapOf("pgcrypto" to "1.3"))
        val changes = BehaviorChanges(before, after)
        assertTrue(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.TYPE, "public.status"))))
        assertTrue(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.SEQUENCE, "public.ids"))))
        assertTrue(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.EXTENSION, "pgcrypto"))))
        assertFalse(changes.intersects(setOf(BehaviorReference(BehaviorReferenceKind.TYPE, "public.ids"))))
    }
}
