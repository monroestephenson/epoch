package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompatibilityViewsTest {
    private val name = "public.customer"
    private val backingName = "public.customer_archive"
    private val security = RelationSecurity("app_owner", false, false, false, false)
    private val physical = TableSchema(columns = linkedMapOf(
        "id" to ColumnSchema("bigint", false, identity = "d", ordinal = 1),
        "name" to ColumnSchema("text", true, defaultExpression = "'initial'::text", ordinal = 2),
    ), relationSecurity = security, relationOid = 41)
    private val baseline = SchemaSnapshot(tables = mapOf(name to physical), expressionSearchPath = listOf("pg_catalog", "public"))
    private val viewFacts = ViewSchema("SELECT id, name FROM public.customer_archive", 28, 28,
        setOf("id", "name"), setOf("id", "name"), backingName, true)
    private val view = TableSchema(columns = physical.columns.mapValues { (_, column) ->
        column.copy(nullable = true, defaultExpression = null, identity = null)
    }, kind = "view", view = viewFacts, relationSecurity = security, relationOid = 42)
    private val after = SchemaSnapshot(tables = mapOf(name to view, backingName to physical),
        behaviors = mapOf("view:$name" to viewFacts.definition), expressionSearchPath = baseline.expressionSearchPath)
    private val entity = EntityContract("Customer", name, listOf(
        ColumnContract("id", "bigint", false, generated = true, updatable = false),
        ColumnContract("name", "text", true),
    ))
    private val contract = DatabaseContract(entities = listOf(entity), coverage = Coverage(complete = true))
    private val migration = MigrationInfo("2", "V2__compatibility_view.sql")
    private fun verify(target: SchemaSnapshot = after, supplied: DatabaseContract = contract, old: SchemaSnapshot = baseline,
                       extra: List<Finding> = emptyList()) = CompatibilityEngine().verifyStep(supplied, old, target, migration, extra)
    private fun replacingView(replacement: TableSchema) = after.copy(tables = after.tables + (name to replacement))

    @Test fun `same physical table through complete view preserves each old CRUD operation`() {
        Operation.entries.forEach { operation ->
            val result = verify(supplied = contract.copy(entities = listOf(entity.copy(operations = setOf(operation)))))
            assertEquals(Verdict.SAFE, result.verdict, result.findings.toString())
            assertTrue(result.findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" })
            assertFalse(result.findings.any { it.ruleId == "REFERENCED_TABLE_DROPPED" })
        }
        assertEquals(baseline, CompatibilityViews.normalize(baseline, after).after)
    }

    @Test fun `backing metadata remains authoritative including subsequent pending steps`() {
        val required = physical.copy(columns = physical.columns + ("required" to ColumnSchema("text", false, ordinal = 3,
            typeAcceptsImplicitNull = true)))
        val facts = viewFacts.copy(updatableColumns = viewFacts.updatableColumns + "required",
            updatableColumnsWithTriggers = viewFacts.updatableColumnsWithTriggers + "required")
        val exposed = view.copy(columns = view.columns + ("required" to ColumnSchema("text", true, ordinal = 3)), view = facts)
        val changed = after.copy(tables = mapOf(name to exposed, backingName to required))
        val result = verify(changed)
        assertEquals(Verdict.BREAKING, result.verdict)
        assertTrue(result.findings.any { it.ruleId == "REQUIRED_COLUMN_OMITTED" })
        assertTrue(result.findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" })
    }

    @Test fun `missing catalog facts and recreated lookalike never establish transparency`() {
        listOf(
            after.copy(tables = after.tables + (backingName to physical.copy(relationOid = 99))),
            replacingView(view.copy(relationSecurity = null)),
            replacingView(view.copy(view = null)),
            after.copy(tables = after.tables + (backingName to physical.copy(relationOid = null))),
        ).forEach { target ->
            assertTrue(CompatibilityViews.provenViewNames(baseline, target).isEmpty())
            assertEquals(Verdict.UNKNOWN, verify(target).verdict)
        }
        assertTrue(CompatibilityViews.provenViewNames(baseline.copy(tables = mapOf(name to physical.copy(relationOid = null))), after).isEmpty())
    }

    @Test fun `unknown or shadowable expression search paths cannot establish a transparent view`() {
        listOf(emptyList(), listOf("public", "pg_catalog"), listOf("custom_types", "public", "pg_catalog")).forEach { searchPath ->
            val old = baseline.copy(expressionSearchPath = searchPath)
            val target = after.copy(expressionSearchPath = searchPath)
            assertTrue(CompatibilityViews.provenViewNames(old, after).isEmpty())
            assertTrue(CompatibilityViews.provenViewNames(baseline, target).isEmpty())
            assertEquals(Verdict.UNKNOWN, verify(target).verdict)
            assertEquals(Verdict.UNKNOWN, verify(old = old).verdict)
        }
    }

    @Test fun `security changes or opaque write behavior on any participant prevent proof`() {
        val unsafe = listOf(security.copy(owner = "other"), security.copy(explicitPrivileges = true),
            security.copy(rowSecurity = true), security.copy(userTriggers = true), security.copy(userRules = true),
            security.copy(inheritance = true))
        unsafe.forEach { facts ->
            assertTrue(CompatibilityViews.provenViewNames(baseline, replacingView(view.copy(relationSecurity = facts))).isEmpty())
            assertTrue(CompatibilityViews.provenViewNames(baseline,
                after.copy(tables = after.tables + (backingName to physical.copy(relationSecurity = facts)))).isEmpty())
            assertTrue(CompatibilityViews.provenViewNames(
                baseline.copy(tables = mapOf(name to physical.copy(relationSecurity = facts))), after).isEmpty())
        }
    }

    @Test fun `filtered expressions options reordered columns and default overrides remain unknown`() {
        listOf(
            view.copy(view = viewFacts.copy(completeDirectProjection = false)),
            view.copy(view = viewFacts.copy(options = listOf("security_barrier=true"))),
            view.copy(columns = view.columns + ("name" to view.columns.getValue("name").copy(defaultExpression = "'override'::text"))),
            view.copy(columns = view.columns.mapValues { (_, column) -> column.copy(ordinal = 3 - column.ordinal) }),
            view.copy(view = viewFacts.copy(updatableColumns = setOf("id"), updatableColumnsWithTriggers = setOf("id"))),
        ).forEach { replacement ->
            assertTrue(CompatibilityViews.provenViewNames(baseline, replacingView(replacement)).isEmpty())
            assertEquals(Verdict.UNKNOWN, verify(replacingView(replacement)).verdict)
        }
    }

    @Test fun `whole operation unavailable even with triggers proves breaking only for valid baseline writers`() {
        val nonWritable = view.copy(view = viewFacts.copy(updatableMask = 0, updatableMaskWithTriggers = 0,
            updatableColumns = emptySet(), updatableColumnsWithTriggers = emptySet(), completeDirectProjection = false))
        val target = replacingView(nonWritable)
        val report = verify(target)
        assertEquals(Verdict.BREAKING, report.verdict)
        val failures = report.findings.filter { it.ruleId == "VIEW_WRITE_UNSUPPORTED" }
        assertEquals(3, failures.size)
        assertTrue(failures.all { it.affectedContractIds == listOf(entity.id) })
        val invalid = verify(target, old = baseline.copy(tables = mapOf(name to physical.copy(columns = emptyMap()))))
        assertEquals(Verdict.UNKNOWN, invalid.verdict)
        assertFalse(invalid.findings.any { it.ruleId == "VIEW_WRITE_UNSUPPORTED" })
        val unplanned = Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", name, "Baseline failed", "invalid SQL", listOf(entity.id))
        assertEquals(Verdict.UNKNOWN, verify(target, extra = listOf(unplanned)).verdict)
    }

    @Test fun `trigger supported relation writes remain unknown instead of fabricated breaking`() {
        val triggerView = view.copy(view = viewFacts.copy(updatableMask = 0), relationSecurity = security.copy(userTriggers = true))
        val report = verify(replacingView(triggerView))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING })
    }

    @Test fun `computed column flags alone do not prove default assignment fails`() {
        val defaultWrite = QueryContract("write-default", Operation.UPDATE, "UPDATE public.customer SET name = DEFAULT",
            tables = listOf(name), writeColumns = listOf(ColumnReference(name, "name")), dependenciesComplete = true, targetTable = name)
        val target = replacingView(view.copy(view = viewFacts.copy(completeDirectProjection = false,
            updatableColumns = setOf("id"), updatableColumnsWithTriggers = setOf("id"))))
        val report = verify(target, contract.copy(entities = emptyList(), queries = listOf(defaultWrite)))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "VIEW_COLUMN_WRITE_UNPROVEN" })
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING })
    }

    @Test fun `normalization remaps foreign keys index relation operands and sequence owners without rewriting literals`() {
        val index = "CREATE INDEX customer_idx ON public.customer USING btree ((length('public.customer_archive'))); valid=true"
        val foreignKey = ConstraintSchema("f", "FOREIGN KEY (id) REFERENCES public.customer(id); validated=true", listOf("id"), name, listOf("id"))
        val check = ConstraintSchema("c", "CHECK (name <> 'public.customer_archive')")
        val beforeTable = physical.copy(indexes = mapOf("customer_idx" to index),
            constraints = mapOf("self_fk" to foreignKey, "literal_check" to check))
        val dependent = TableSchema(constraints = mapOf("customer_fk" to foreignKey))
        val before = baseline.copy(tables = mapOf(name to beforeTable, "public.dependent" to dependent),
            sequences = mapOf("public.customer_id_seq" to "bigint:1:1:999:1:false:1"),
            sequenceOwners = mapOf("public.customer_id_seq" to ColumnReference(name, "id")))
        val renamedFk = foreignKey.copy(definition = foreignKey.definition.replace("REFERENCES public.customer(", "REFERENCES public.customer_archive("), referencedTable = backingName)
        val renamed = beforeTable.copy(indexes = mapOf("customer_idx" to index.replace(" ON public.customer ", " ON public.customer_archive ")),
            constraints = beforeTable.constraints + ("self_fk" to renamedFk))
        val target = after.copy(tables = mapOf(name to view, backingName to renamed,
            "public.dependent" to dependent.copy(constraints = mapOf("customer_fk" to renamedFk))),
            sequences = before.sequences, sequenceOwners = mapOf("public.customer_id_seq" to ColumnReference(backingName, "id")))
        val normalized = CompatibilityViews.normalize(before, target)
        assertEquals(before, normalized.after)
        val report = verify(target, old = before)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.findings.any { it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" && it.objectName == "$name.customer_idx" })
        val changedLiteral = renamed.copy(constraints = renamed.constraints + ("literal_check" to check.copy(definition = "CHECK (name <> 'public.customer')")))
        val preserved = CompatibilityViews.normalize(before, target.copy(tables = target.tables + (backingName to changedLiteral))).after
        assertEquals("CHECK (name <> 'public.customer')", preserved.tables.getValue(name).constraints.getValue("literal_check").definition)
    }

    @Test fun `normalization removes only the proven view behavior and preserves data findings`() {
        val extra = after.copy(behaviors = after.behaviors + ("view:public.filtered" to "SELECT id FROM public.customer_archive WHERE id > 0"))
        assertEquals(mapOf("view:public.filtered" to "SELECT id FROM public.customer_archive WHERE id > 0"), CompatibilityViews.normalize(baseline, extra).after.behaviors)
        assertEquals(Verdict.UNKNOWN, verify(extra).verdict)
        val effect = Finding(Verdict.UNKNOWN, "UNSUPPORTED_MIGRATION_EFFECT", migration.filename, "Data changed", "UPDATE customer_archive SET name = NULL")
        assertEquals(Verdict.UNKNOWN, verify(extra = listOf(effect)).verdict)
    }

    @Test fun `accepted preexisting behavior cannot waive its new interaction with the real view`() {
        val behavior = mapOf("function:public.relation_kind()" to "SELECT relkind FROM pg_class WHERE oid = 'public.customer'::regclass")
        val old = baseline.copy(behaviors = behavior)
        val target = after.copy(behaviors = after.behaviors + behavior)
        val engine = CompatibilityEngine()
        val health = engine.inspectBaseline(contract, old)
        val step = verify(target, old = old)
        assertEquals(Verdict.UNKNOWN, step.verdict)
        assertTrue(step.findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" })
        assertTrue(step.findings.any { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" })
        val accepted = AcceptedBaseline(baselineFingerprint = health.fingerprint,
            findings = health.findings.map { AcceptedBaselineFinding(BaselineFingerprints.finding(it), "Existing behavior reviewed") })
        val report = engine.report(contract, listOf(step), health, accepted)
        assertEquals(Verdict.UNKNOWN, report.gateVerdict)
    }

    @Test fun `name observing default remains unknown when backing definition is unchanged`() {
        val observing = physical.copy(columns = physical.columns + ("name" to physical.columns.getValue("name").copy(
            nullable = false, defaultExpression = "pg_get_serial_sequence('public.customer'::text, 'id'::text)")))
        val old = baseline.copy(tables = mapOf(name to observing))
        val target = after.copy(tables = after.tables + (backingName to observing))
        val result = verify(target, old = old)
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" })
        val risks = result.findings.filter { it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" }
        assertEquals(1, risks.size)
        assertEquals("$name.name", risks.single().objectName)
        assertEquals(listOf(entity.id), risks.single().affectedContractIds)
        assertTrue(risks.single().evidence.contains("DEFAULT"))
    }

    @Test fun `name observing check remains unknown despite successful structural normalization`() {
        val check = ConstraintSchema("c", "CHECK (pg_get_serial_sequence('public.customer'::text, 'id'::text) IS NOT NULL)",
            checkExpression = "(pg_get_serial_sequence('public.customer'::text, 'id'::text) IS NOT NULL)")
        val observing = physical.copy(constraints = mapOf("has_sequence" to check))
        val result = verify(after.copy(tables = after.tables + (backingName to observing)), old = baseline.copy(tables = mapOf(name to observing)))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.any { it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" && it.objectName == "$name.has_sequence" && it.evidence.contains("CHECK") })
    }

    @Test fun `stored expressions on other tables retain their own old operation attribution`() {
        val logName = "public.logs"
        val logs = TableSchema(columns = mapOf("name" to ColumnSchema("text", false,
            defaultExpression = "pg_get_serial_sequence('public.customer'::text, 'id'::text)")))
        val old = baseline.copy(tables = baseline.tables + (logName to logs))
        val target = after.copy(tables = after.tables + (logName to logs))
        val logInsert = QueryContract("logs.insert", Operation.INSERT, "INSERT INTO public.logs DEFAULT VALUES",
            tables = listOf(logName), targetTable = logName, dependenciesComplete = true)
        val supplied = contract.copy(queries = listOf(logInsert))
        val report = verify(target, supplied, old)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        val risk = report.findings.single { it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" }
        assertEquals("public.logs.name", risk.objectName)
        assertEquals(listOf(logInsert.id), risk.affectedContractIds)
    }

    @Test fun `builtin simple checks integer generation and bound sequence defaults remain proven`() {
        val sequence = "public.customer_id_seq"
        val supported = physical.copy(columns = physical.columns +
            ("id" to physical.columns.getValue("id").copy(identity = null, defaultExpression = "nextval('public.customer_id_seq'::regclass)")) +
            ("twice_id" to ColumnSchema("bigint", true, defaultExpression = "(id * 2)", generated = "s", ordinal = 3)),
            constraints = mapOf(
                "positive" to ConstraintSchema("c", "CHECK ((id > 0))", checkExpression = "(id > 0)"),
                "named" to ConstraintSchema("c", "CHECK ((name IS NOT NULL))", checkExpression = "(name IS NOT NULL)"),
                "known_name" to ConstraintSchema("c", "CHECK (name = ANY (ARRAY['initial'::text, 'next'::text]))",
                    checkExpression = "(name = ANY (ARRAY['initial'::text, 'next'::text]))"),
            ))
        val old = baseline.copy(tables = mapOf(name to supported), sequences = mapOf(sequence to "bigint:1:1:999:1:false:1"))
        val exposed = view.copy(columns = view.columns + ("twice_id" to ColumnSchema("bigint", true, ordinal = 3)))
        val target = after.copy(tables = mapOf(name to exposed, backingName to supported), sequences = old.sequences)
        assertTrue(CompatibilityViews.expressionRisks(old).isEmpty(), CompatibilityViews.expressionRisks(old).toString())
        val result = verify(target, old = old)
        assertEquals(Verdict.SAFE, result.verdict, result.findings.toString())
    }

    @Test fun `opaque generated casts operators functions and index predicates are unproven`() {
        val opaque = physical.copy(columns = physical.columns +
            ("generated" to ColumnSchema("text", true, defaultExpression = "opaque_lookup(id)", generated = "s")) +
            ("domain_default" to ColumnSchema("custom_domain", true, defaultExpression = "1")),
            constraints = mapOf(
                "custom_operator" to ConstraintSchema("c", "CHECK (id OPERATOR(public.===) 1)", checkExpression = "(id OPERATOR(public.===) 1)"),
                "custom_cast" to ConstraintSchema("c", "CHECK ((id)::public.custom_type IS NOT NULL)", checkExpression = "((id)::public.custom_type IS NOT NULL)"),
                "function_named_like_column" to ConstraintSchema("c", "CHECK (id(id) > 0)", checkExpression = "(id(id) > 0)"),
            ), indexes = mapOf("partial" to "CREATE INDEX partial ON public.customer (id) WHERE opaque_lookup(id) IS NOT NULL"),
            indexMethods = mapOf("partial" to "btree"), indexColumns = mapOf("partial" to listOf("id")))
        val risks = CompatibilityViews.expressionRisks(baseline.copy(tables = mapOf(name to opaque)))
        assertEquals(setOf("$name.generated", "$name.domain_default", "$name.custom_operator", "$name.custom_cast", "$name.function_named_like_column", "$name.partial"), risks.map { it.objectName }.toSet())
    }

    @Test fun `another baseline relation cannot be removed as a duplicate backing table`() {
        val existingName = baseline.copy(tables = baseline.tables + (backingName to physical.copy(relationOid = 99)))
        assertTrue(CompatibilityViews.provenViewNames(existingName, after).isEmpty())
    }

    @Test fun `same type with changed collation is unknown and cannot be hidden by a compatibility view`() {
        val collated = physical.copy(columns = physical.columns + ("name" to physical.columns.getValue("name").copy(collation = "pg_catalog.default")))
        val changed = collated.copy(columns = collated.columns + ("name" to collated.columns.getValue("name").copy(collation = "pg_catalog.C")))
        val old = baseline.copy(tables = mapOf(name to collated))
        val direct = verify(old.copy(tables = mapOf(name to changed)), old = old)
        assertEquals(Verdict.UNKNOWN, direct.verdict)
        assertTrue(direct.findings.any { it.ruleId == "COLUMN_COLLATION_CHANGED" && it.affectedContractIds == listOf(entity.id) })
        val exposed = view.copy(columns = view.columns + ("name" to view.columns.getValue("name").copy(collation = "pg_catalog.C")))
        val target = after.copy(tables = mapOf(name to exposed, backingName to changed))
        assertTrue(CompatibilityViews.provenViewNames(old, target).isEmpty())
        val result = verify(target, old = old)
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.any { it.ruleId == "COLUMN_COLLATION_CHANGED" })
    }

    @Test fun `physical object identities never leak into deterministic findings`() {
        val old = baseline.copy(tables = mapOf(name to physical.copy(relationOid = 1337)))
        val target = after.copy(tables = after.tables + (backingName to physical.copy(relationOid = 1337)) + (name to view.copy(relationOid = 1338)))
        assertEquals(verify(), verify(target, old = old))
    }
}
