package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NullableOmissionProofTest {
    private val name = "public.accounts"
    private val security = RelationSecurity("epoch", false, false, false, false)
    private val table = TableSchema(mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "label" to ColumnSchema("text", true, typeAcceptsImplicitNull = true),
    ), relationSecurity = security)
    private val reference = ColumnReference(name, "id")
    private val query = QueryContract("insert", Operation.INSERT, "INSERT INTO accounts(id) VALUES (?)",
        tables = listOf(name), columns = listOf(reference), writeColumns = listOf(reference),
        parameterTypes = listOf("bigint"), targetTable = name, dependenciesComplete = true)
    private val contract = DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true))
    private val root = "check:public.accounts.existing"
    private fun snapshot(table: TableSchema) = SchemaSnapshot(mapOf(name to table),
        behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true,
            nodes = mapOf(root to BehaviorNode("check", "CHECK (length(label) > 0)",
                references = listOf(BehaviorReference(BehaviorReferenceKind.COLUMN, name, "label")),
                dependenciesComplete = true, semanticsSupported = false,
                opaqueReasons = listOf("This existing CHECK implication is unsupported."))),
            attachments = listOf(BehaviorAttachment(name, root, setOf(Operation.INSERT, Operation.UPDATE)))))
    private fun verify(target: TableSchema) = CompatibilityEngine().verifyStep(contract, snapshot(table), snapshot(target),
        MigrationInfo("2", "V2__add_nullable.sql"))
    private fun added() = table.copy(columns = table.columns + ("metadata" to
        ColumnSchema("text", true, typeAcceptsImplicitNull = true)))

    @Test fun `an unrelated opaque CHECK does not make an omitted nullable column value unknown`() {
        val step = verify(added())
        assertTrue(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" && it.verdict == Verdict.SAFE }, step.toString())
        assertFalse(step.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, step.toString())
        // This proof concerns the new column assignment. It must not delete unrelated uncertainty.
        assertTrue(step.findings.none { it.verdict == Verdict.BREAKING }, step.toString())
    }

    @Test fun `missing relation facts and possible row transformations still block the local proof`() {
        val variants = listOf(null, security.copy(userTriggers = true), security.copy(userRules = true),
            security.copy(rowSecurity = true), security.copy(inheritance = true))
        for (facts in variants) {
            val step = verify(added().copy(relationSecurity = facts))
            assertTrue(step.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, "$facts: $step")
            assertFalse(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, "$facts: $step")
        }
    }

    @Test fun `domain or missing type facts cannot establish an accepted implicit NULL`() {
        for (fact in listOf(false, null)) {
            val value = added()
            val step = verify(value.copy(columns = value.columns + ("metadata" to value.columns.getValue("metadata").copy(typeAcceptsImplicitNull = fact))))
            assertTrue(step.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, step.toString())
            assertFalse(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, step.toString())
        }
    }

    @Test fun `non-null and generated new columns cannot use the local NULL proof`() {
        for (column in listOf(ColumnSchema("text", false, typeAcceptsImplicitNull = true),
            ColumnSchema("text", true, defaultExpression = "lower('x'::text)", typeAcceptsImplicitNull = true),
            ColumnSchema("text", true, generated = "s", typeAcceptsImplicitNull = true))) {
            val step = verify(table.copy(columns = table.columns + ("metadata" to column)))
            assertFalse(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, step.toString())
        }
    }
    @Test fun `opaque other-column defaults do not erase the new column assignment proof`() {
        val oldTable = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(defaultExpression = "application_default()")))
        val node = BehaviorNode("default", "application_default()", dependenciesComplete = false,
            semanticsSupported = false, opaqueReasons = listOf("Application function effects are unresolved."))
        val before = snapshot(oldTable).copy(behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true,
            nodes = mapOf("default:public.accounts.label" to node), attachments = listOf(
                BehaviorAttachment(name, "default:public.accounts.label", setOf(Operation.INSERT), BehaviorActivation.DEFAULT, "label"))))
        val after = before.copy(tables = mapOf(name to oldTable.copy(columns = oldTable.columns +
            ("metadata" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)))))
        val step = CompatibilityEngine().verifyStep(contract, before, after, MigrationInfo("2", "V2__nullable.sql"))
        assertTrue(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, step.toString())
        assertFalse(step.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, step.toString())
        assertEquals(Verdict.UNKNOWN, step.verdict, "Local assignment proof must not certify the opaque function: $step")
    }

    @Test fun `a restrictive constraint on the new nullable column remains independently unproven or breaking`() {
        val target = added().copy(constraints = mapOf("metadata_required" to ConstraintSchema("c",
            "CHECK (metadata IS NOT NULL)", columns = listOf("metadata"), checkExpression = "metadata IS NOT NULL")))
        val step = verify(target)
        assertTrue(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, step.toString())
        assertTrue(step.verdict != Verdict.SAFE, step.toString())
        assertTrue(step.findings.any { it.verdict != Verdict.SAFE && "metadata" in it.objectName }, step.toString())
    }

    @Test fun `baseline-only row transformations do not establish preservation`() {
        val before = snapshot(table.copy(relationSecurity = security.copy(userTriggers = true)))
        val step = CompatibilityEngine().verifyStep(contract, before, snapshot(added()), MigrationInfo("2", "V2__nullable.sql"))
        assertTrue(step.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, step.toString())
        assertFalse(step.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" }, step.toString())
    }

}
