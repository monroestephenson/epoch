package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BehaviorScopeTest {
    private val table = "public.accounts"
    private val other = "public.audit"
    private val id = ColumnReference(table, "id")
    private val value = ColumnReference(table, "value")
    private val ordinary = TableSchema(mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "value" to ColumnSchema("text", true, typeAcceptsImplicitNull = true),
    ), relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val known = QueryBehaviorFacts(complete = true, defaultAssignmentsComplete = true)
    private fun opaque(kind: String = "function", refs: List<BehaviorReference> = emptyList()) =
        BehaviorNode(kind, "opaque $kind", references = refs, opaqueReasons = listOf("hidden lookup"))
    private fun supported(kind: String = "check", refs: List<BehaviorReference> = emptyList(), children: List<String> = emptyList()) =
        BehaviorNode(kind, "builtin $kind", children, refs, true, true, false)
    private fun snapshot(nodes: Map<String, BehaviorNode> = emptyMap(), roots: List<BehaviorAttachment> = emptyList()) =
        SchemaSnapshot(mapOf(table to ordinary, other to ordinary), behaviorGraph = BehaviorGraph(
            attachmentInventoryComplete = true, nodes = nodes, attachments = roots))
    private fun query(op: Operation = Operation.SELECT, target: String = table, writes: List<ColumnReference> = emptyList()) =
        QueryContract("q", op, "select id from accounts", tables = listOf(target), columns = listOf(ColumnReference(target, "id")),
            writeColumns = writes, dependenciesComplete = true, targetTable = target.takeIf { op != Operation.SELECT })

    @Test fun `unused behavior does not become a caller dependency`() {
        val scope = BehaviorScope(snapshot(mapOf("function:unused" to opaque())))
            .forQuery(query(), known)
        assertTrue(scope.semanticNodeIds.isEmpty())
        assertTrue(scope.uncertainties.isEmpty())
        assertFalse(scope.planningMayEvaluate)
    }

    @Test fun `view dependencies are followed only from the view reader`() {
        val node = supported("view", listOf(BehaviorReference(BehaviorReferenceKind.RELATION, table, access = BehaviorAccess.SELECT)))
        val db = snapshot(mapOf("view:$other" to node), listOf(BehaviorAttachment(other, "view:$other", setOf(Operation.SELECT))))
        assertTrue(BehaviorScope(db).forQuery(query(Operation.INSERT, table, listOf(id)), known).semanticNodeIds.isEmpty())
        assertEquals(setOf("view:$other"), BehaviorScope(db).forQuery(query(target = other), known).semanticNodeIds)
    }

    @Test fun `metadata reference is not a read of its owning table`() {
        val db = snapshot(mapOf("check" to supported(refs = listOf(BehaviorReference(BehaviorReferenceKind.COLUMN, table, "id"))),
            "view" to opaque("view")), listOf(
            BehaviorAttachment(table, "check", setOf(Operation.INSERT)),
            BehaviorAttachment(table, "view", setOf(Operation.SELECT)),
        ))
        val scope = BehaviorScope(db).forQuery(query(Operation.INSERT, writes = listOf(id)), known)
        assertEquals(setOf("check"), scope.semanticNodeIds)
        assertTrue(scope.uncertainties.isEmpty())
    }

    @Test fun `omitted and explicit defaults invoke a default while supplied parameters do not`() {
        val db = snapshot(mapOf("default" to opaque("default")), listOf(
            BehaviorAttachment(table, "default", setOf(Operation.INSERT, Operation.UPDATE), BehaviorActivation.DEFAULT, "value")))
        val resolver = BehaviorScope(db)
        assertTrue("default" in resolver.forQuery(query(Operation.INSERT, writes = listOf(id)), known).semanticNodeIds)
        assertFalse("default" in resolver.forQuery(query(Operation.INSERT, writes = listOf(id, value)), known).semanticNodeIds)
        assertTrue("default" in resolver.forQuery(query(Operation.INSERT, writes = listOf(id, value)),
            known.copy(defaultColumns = setOf(value))).semanticNodeIds)
        assertFalse("default" in resolver.forQuery(query(), known).semanticNodeIds)
    }

    @Test fun `unknown assignment grammar does not suppress defaults`() {
        val db = snapshot(mapOf("default" to opaque("default")), listOf(
            BehaviorAttachment(table, "default", setOf(Operation.INSERT), BehaviorActivation.DEFAULT, "value")))
        assertTrue("default" in BehaviorScope(db).forQuery(query(Operation.INSERT, writes = listOf(id, value)),
            known.copy(defaultAssignmentsComplete = false)).semanticNodeIds)
    }

    @Test fun `trigger event and planning activation remain independent`() {
        val db = snapshot(mapOf("trigger" to opaque("trigger")), listOf(
            BehaviorAttachment(table, "trigger", setOf(Operation.DELETE), planningRelevant = false)))
        val resolver = BehaviorScope(db)
        assertTrue(resolver.forQuery(query(), known).uncertainties.isEmpty())
        assertTrue(resolver.forQuery(query(Operation.INSERT, writes = listOf(id)), known).uncertainties.isEmpty())
        val delete = resolver.forQuery(query(Operation.DELETE), known)
        assertEquals(setOf("trigger"), delete.semanticNodeIds)
        assertTrue(delete.planningNodeIds.isEmpty())
        assertTrue(delete.planningUncertainties.isEmpty())
        assertFalse(delete.planningMayEvaluate)
    }

    @Test fun `cascade deletion reaches child behavior without planning runtime triggers`() {
        val fk = ConstraintSchema("f", "FK", listOf("id"), table, listOf("id"), "CASCADE", "NO ACTION")
        val db = snapshot(mapOf("child-trigger" to opaque("trigger")), listOf(
            BehaviorAttachment(other, "child-trigger", setOf(Operation.DELETE), planningRelevant = false)))
            .let { it.copy(tables = it.tables + (other to ordinary.copy(constraints = mapOf("fk" to fk)))) }
        val scope = BehaviorScope(db).forQuery(query(Operation.DELETE), known)
        assertTrue("child-trigger" in scope.semanticNodeIds)
        assertTrue(scope.planningNodeIds.isEmpty())
    }

    @Test fun `unmodified referenced key does not enter FK update effects`() {
        val fk = ConstraintSchema("f", "FK", listOf("id"), table, listOf("id"), "CASCADE", "CASCADE")
        val db = snapshot(mapOf("child-trigger" to opaque("trigger")), listOf(
            BehaviorAttachment(other, "child-trigger", setOf(Operation.UPDATE), planningRelevant = false)))
            .let { it.copy(tables = it.tables + (other to ordinary.copy(constraints = mapOf("fk" to fk)))) }
        assertFalse("child-trigger" in BehaviorScope(db).forQuery(query(Operation.UPDATE, writes = listOf(value)), known).semanticNodeIds)
        assertTrue("child-trigger" in BehaviorScope(db).forQuery(query(Operation.UPDATE, writes = listOf(id)), known).semanticNodeIds)
    }

    @Test fun `missing child facts remain local unknown`() {
        val db = snapshot(mapOf("default" to supported("default", children = listOf("missing"))), listOf(
            BehaviorAttachment(table, "default", setOf(Operation.INSERT), BehaviorActivation.DEFAULT, "value")))
        val resolver = BehaviorScope(db)
        assertTrue(resolver.forQuery(query(), known).uncertainties.isEmpty())
        assertTrue(resolver.forQuery(query(Operation.INSERT, writes = listOf(id)), known).uncertainties.any { it.nodeId == "missing" })
    }

    @Test fun `catalog cycles terminate without losing leaf dependencies`() {
        val db = snapshot(mapOf("a" to supported(children = listOf("b")), "b" to supported(children = listOf("a", "missing"))),
            listOf(BehaviorAttachment(table, "a", setOf(Operation.SELECT))))
        val resolver = BehaviorScope(db)
        val first = resolver.forQuery(query(), known)
        assertEquals(setOf("a", "b", "missing"), first.semanticNodeIds)
        assertEquals(first, resolver.forQuery(query(), known))
        assertTrue(first.uncertainties.any { it.nodeId == "missing" })
    }

    @Test fun `legacy nonempty inventory cannot establish disjointness`() {
        val db = snapshot().copy(behaviors = mapOf("function:unknown" to "opaque"), behaviorGraph = null)
        val scope = BehaviorScope(db).forQuery(query(), known)
        assertTrue(scope.uncertainties.any { it.nodeId == "function:unknown" })
        assertTrue("function:unknown" in scope.semanticNodeIds)
    }

    @Test fun `incomplete SQL remains local even on a complete empty graph`() {
        val scope = BehaviorScope(snapshot()).forQuery(query(), known.copy(complete = false,
            unresolvedReasons = listOf("unresolved custom operator")))
        assertEquals(listOf(BehaviorUncertainty("query:q", "unresolved custom operator")), scope.uncertainties)
    }

    @Test fun `custom type dependencies work without physical table roots`() {
        val db = snapshot(mapOf("type:public.secret" to opaque("type")))
        val scope = BehaviorScope(db).forQuery(QueryContract("cast", Operation.SELECT, "select ?::public.secret"),
            known.copy(references = setOf(BehaviorReference(BehaviorReferenceKind.TYPE, "public.secret"))))
        assertTrue("type:public.secret" in scope.semanticNodeIds)
        assertTrue(scope.planningMayEvaluate)
    }

    @Test fun `semantic opacity alone does not prevent safe planning`() {
        val db = snapshot(mapOf("view" to supported("view").copy(semanticsSupported = false)), listOf(
            BehaviorAttachment(table, "view", setOf(Operation.SELECT))))
        val scope = BehaviorScope(db).forQuery(query(), known)
        assertTrue(scope.uncertainties.isNotEmpty())
        assertTrue(scope.planningUncertainties.isEmpty())
        assertFalse(scope.planningMayEvaluate)
    }

    @Test fun `a materialized reader does not invoke its defining query`() {
        val db = snapshot(mapOf("materialized" to supported("materialized-view"), "base-trigger" to opaque()), listOf(
            BehaviorAttachment(other, "materialized", setOf(Operation.SELECT)),
            BehaviorAttachment(table, "base-trigger", setOf(Operation.SELECT))))
        assertEquals(setOf("materialized"), BehaviorScope(db).forQuery(query(target = other), known).semanticNodeIds)
    }
    @Test fun `pure builtin calls are scoped against matching user overloads only`() {
        val db = snapshot(mapOf("function:public.unused(text)" to opaque(), "function:public.lower(custom_type)" to opaque()))
            .copy(expressionSearchPath = listOf("pg_catalog", "public"))
        val resolver = BehaviorScope(db)
        val count = resolver.forQuery(query(), known.copy(builtinFunctionCalls = setOf("count")))
        assertTrue(count.uncertainties.isEmpty())
        val lower = resolver.forQuery(query(), known.copy(builtinFunctionCalls = setOf("lower")))
        assertEquals(setOf("function:public.lower(custom_type)"), lower.semanticNodeIds)
        assertTrue(lower.uncertainties.isNotEmpty())
        val qualified = resolver.forQuery(query(), known.copy(builtinFunctionCalls = setOf("pg_catalog.lower")))
        assertTrue(qualified.uncertainties.isEmpty())
    }

    @Test fun `unsafe builtin resolution cannot be inferred from the function name`() {
        val db = snapshot().copy(expressionSearchPath = listOf("public", "pg_catalog"))
        val scope = BehaviorScope(db).forQuery(query(), known.copy(builtinFunctionCalls = setOf("lower")))
        assertTrue(scope.planningUncertainties.any { it.nodeId == "function:lower" })
    }

    @Test fun `JDBC inference markers are not invented PostgreSQL types`() {
        val resolver = BehaviorScope(snapshot())
        val parameter = resolver.forQuery(query().copy(parameterTypes = listOf("OTHER", "UNKNOWN", "NULL")), known)
        assertTrue(parameter.uncertainties.isEmpty())
        val explicit = resolver.forQuery(query(), known.copy(references = setOf(BehaviorReference(BehaviorReferenceKind.TYPE, "other"))))
        assertTrue(explicit.uncertainties.any { it.nodeId == "type:other" })
    }

    @Test fun `catalog-proven implicit NULL does not invoke custom type input behavior`() {
        val db = snapshot(mapOf("type:public.custom" to opaque("type")), listOf(
            BehaviorAttachment(table, "type:public.custom", Operation.entries.toSet(), BehaviorActivation.COLUMN_TYPE, "value")))
            .let { it.copy(tables = it.tables + (table to ordinary.copy(columns = ordinary.columns +
                ("value" to ordinary.columns.getValue("value").copy(type = "public.custom"))))) }
        val resolver = BehaviorScope(db)
        assertTrue(resolver.forQuery(query(Operation.INSERT, writes = listOf(id)), known).uncertainties.isEmpty())
        assertTrue(resolver.forQuery(query(Operation.INSERT, writes = listOf(id, value)), known).uncertainties.isNotEmpty())
        val noFact = db.copy(tables = db.tables + (table to db.tables.getValue(table).copy(columns = db.tables.getValue(table).columns +
            ("value" to db.tables.getValue(table).columns.getValue("value").copy(typeAcceptsImplicitNull = null)))))
        assertTrue(BehaviorScope(noFact).forQuery(query(Operation.INSERT, writes = listOf(id)), known).uncertainties.isNotEmpty())
    }

    @Test fun `synthetic FK UPDATE roots are inactive for a proven unchanged referenced key`() {
        val fk = ConstraintSchema("f", "FOREIGN KEY(id) REFERENCES accounts(id)", listOf("id"), table, listOf("id"), "a", "c")
        val node = "foreign-key:$other.fk:UPDATE"
        val db = snapshot(mapOf(node to supported("foreign_key_action", refs = listOf(
            BehaviorReference(BehaviorReferenceKind.COLUMN, other, "id", BehaviorAccess.UPDATE)))),
            listOf(BehaviorAttachment(table, node, setOf(Operation.UPDATE), planningRelevant = false, updateColumns = setOf("id"))))
            .let { it.copy(tables = it.tables + (other to ordinary.copy(constraints = mapOf("fk" to fk)))) }
        val unrelated = query(Operation.UPDATE, writes = listOf(value))
        val resolver = BehaviorScope(db)
        assertFalse(node in resolver.forQuery(unrelated, known).semanticNodeIds)
        assertFalse(resolver.forQuery(unrelated, known).references.any { it.name == other && it.access == BehaviorAccess.UPDATE })
        assertTrue(node in resolver.forQuery(query(Operation.UPDATE, writes = listOf(id)), known).semanticNodeIds)
        for (parent in listOf(ordinary.copy(columns = ordinary.columns + ("id" to ordinary.columns.getValue("id").copy(generated = "s"))),
            ordinary.copy(relationSecurity = ordinary.relationSecurity!!.copy(userTriggers = true)))) {
            val guarded = BehaviorScope(db.copy(tables = db.tables + (table to parent))).forQuery(unrelated, known)
            assertTrue(node in guarded.semanticNodeIds)
            assertTrue(guarded.references.any { it.name == other && it.access == BehaviorAccess.UPDATE })
        }
        assertTrue(node in resolver.forQuery(unrelated, known.copy(complete = false)).semanticNodeIds)
    }

    @Test fun `serial result decorations stay distinct from physical or parameter types`() {
        val resolver = BehaviorScope(snapshot())
        for (alias in listOf("smallserial", "serial2", "serial", "serial4", "bigserial", "serial8")) {
            assertTrue(resolver.forQuery(query().copy(resultColumns = listOf(ResultColumn("id", alias))), known).uncertainties.isEmpty(), alias)
        }
        assertTrue(resolver.forQuery(query().copy(parameterTypes = listOf("serial")), known).uncertainties.any { it.nodeId == "type:serial" })
        assertTrue(resolver.forQuery(query(), known.copy(references = setOf(BehaviorReference(BehaviorReferenceKind.TYPE, "serial"))))
            .uncertainties.any { it.nodeId == "type:serial" })
    }

}
