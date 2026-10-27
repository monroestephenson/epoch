package dev.epoch.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityEngineTest {
    private val engine = CompatibilityEngine()
    private val migration = MigrationInfo("2", "V2__change.sql")
    private val table = TableSchema(columns = linkedMapOf(
        "id" to ColumnSchema("bigint", false, ordinal = 1),
        "email" to ColumnSchema("text", true, ordinal = 2),
    ))
    private val baseline = SchemaSnapshot(mapOf("public.customers" to table))
    private val entity = EntityContract("Customer", "public.customers", listOf(
        ColumnContract("id", "bigint", false), ColumnContract("email", "text", true),
    ))
    private val contract = DatabaseContract(applicationVersion = "1.0", entities = listOf(entity), coverage = Coverage(complete = true))
    private fun verify(target: TableSchema, c: DatabaseContract = contract) =
        engine.verifyStep(c, baseline, baseline.copy(tables = mapOf("public.customers" to target)), migration)
    private fun assertRule(report: MigrationReport, verdict: Verdict, rule: String) {
        assertEquals(verdict, report.verdict)
        assertTrue(report.findings.any { it.ruleId == rule }, report.findings.toString())
        assertTrue(report.findings.all { it.migration == migration })
    }

    @Test fun `nullable column preserves explicit mapped CRUD`() {
        val result = verify(table.copy(columns = table.columns + ("metadata" to ColumnSchema("text", true, ordinal = 3))))
        assertRule(result, Verdict.SAFE, "NULLABLE_COLUMN_ADDED")
    }

    @Test fun `dropping mapped column identifies exact dependency`() {
        val result = verify(table.copy(columns = table.columns - "email"))
        assertRule(result, Verdict.BREAKING, "REFERENCED_COLUMN_DROPPED")
        assertEquals(listOf("Customer"), result.findings.single { it.verdict == Verdict.BREAKING }.affectedContractIds)
    }

    @Test fun `renaming column breaks old physical SQL name`() {
        val result = verify(table.copy(columns = (table.columns - "email") + ("email_address" to table.columns.getValue("email"))))
        assertRule(result, Verdict.BREAKING, "REFERENCED_COLUMN_DROPPED")
    }

    @Test fun `renaming table breaks old physical SQL name`() {
        val target = SchemaSnapshot(mapOf("public.accounts" to table))
        assertRule(engine.verifyStep(contract, baseline, target, migration), Verdict.BREAKING, "REFERENCED_TABLE_DROPPED")
    }

    @Test fun `new required column breaks known old insert`() {
        val result = verify(table.copy(columns = table.columns + ("status" to ColumnSchema("text", false))))
        assertRule(result, Verdict.BREAKING, "REQUIRED_COLUMN_OMITTED")
    }

    @Test fun `new required column does not invent a breaking read-only operation`() {
        val readOnly = contract.copy(entities = listOf(entity.copy(operations = setOf(Operation.SELECT))))
        val result = verify(table.copy(columns = table.columns + ("status" to ColumnSchema("text", false))), readOnly)
        assertEquals(Verdict.SAFE, result.verdict)
    }

    @Test fun `restrictive check is unknown without input implication proof`() {
        assertRule(verify(table.copy(constraints = mapOf("valid_email" to ConstraintSchema("c", "CHECK (length(email) > 10)")))), Verdict.UNKNOWN, "CONSTRAINT_CHANGED")
    }

    @Test fun `unique is unknown without accepted write proof`() {
        assertRule(verify(table.copy(constraints = mapOf("email_unique" to ConstraintSchema("u", "UNIQUE (email)")))), Verdict.UNKNOWN, "CONSTRAINT_CHANGED")
    }

    @Test fun `type changes do not masquerade as proven breakage or safety`() {
        val target = table.copy(columns = table.columns + ("email" to table.columns.getValue("email").copy(type = "integer")))
        assertRule(verify(target), Verdict.UNKNOWN, "COLUMN_TYPE_CHANGED")
    }

    @Test fun `unrelated simple table is safe within scope`() {
        val target = baseline.copy(tables = baseline.tables + ("public.audit" to TableSchema(mapOf("id" to ColumnSchema("bigint", false)))))
        assertRule(engine.verifyStep(contract, baseline, target, migration), Verdict.SAFE, "UNREFERENCED_TABLE_ADDED")
    }

    @Test fun `new unrelated table foreign key may affect old deletes`() {
        val target = baseline.copy(tables = baseline.tables + ("public.audit" to TableSchema(
            columns = mapOf("customer_id" to ColumnSchema("bigint", false)),
            constraints = mapOf("customer_fk" to ConstraintSchema("f", "FOREIGN KEY (customer_id) REFERENCES public.customers(id)")),
        )))
        assertRule(engine.verifyStep(contract, baseline, target, migration), Verdict.UNKNOWN, "NEW_REFERENTIAL_CONSTRAINT")
    }

    @Test fun `unknown dynamic query prevents safe`() {
        assertRule(verify(table, contract.copy(unresolvedQueries = listOf(UnresolvedQuery("search", "Arbitrary caller-supplied HQL")))), Verdict.UNKNOWN, "UNRESOLVED_QUERY")
    }

    @Test fun `empty and incomplete coverage never safe`() {
        assertRule(verify(table, DatabaseContract(coverage = Coverage(complete = true))), Verdict.UNKNOWN, "EMPTY_CONTRACT")
        assertRule(verify(table, contract.copy(coverage = Coverage())), Verdict.UNKNOWN, "INCOMPLETE_COVERAGE")
    }

    @Test fun `unsupported dialect version and whole-application scope never safe`() {
        assertRule(verify(table, contract.copy(dialect = "MySQL")), Verdict.UNKNOWN, "UNSUPPORTED_DIALECT")
        assertRule(verify(table, contract.copy(schemaVersion = 99)), Verdict.UNKNOWN, "UNSUPPORTED_CONTRACT_VERSION")
        assertRule(verify(table, contract.copy(scope = VerificationScope.WHOLE_APPLICATION)), Verdict.UNKNOWN, "WHOLE_APPLICATION_UNPROVEN")
    }

    @Test fun `runtime observations do not establish exhaustive extraction`() {
        val query = QueryContract("observed", Operation.SELECT, "select email from public.customers", tables = listOf("public.customers"), source = QuerySource.RUNTIME_OBSERVED, verificationStatus = VerificationStatus.OBSERVED)
        assertRule(verify(table, contract.copy(queries = listOf(query))), Verdict.UNKNOWN, "INCOMPLETE_QUERY")
    }

    @Test fun `select star and positional inserts account for added columns`() {
        val query = QueryContract("star", Operation.SELECT, "select * from public.customers", tables = listOf("public.customers"), dependenciesComplete = true, explicitColumnList = false, exactResultShape = true)
        val target = table.copy(columns = table.columns + ("new_column" to ColumnSchema("text", true)))
        assertRule(verify(target, contract.copy(queries = listOf(query))), Verdict.UNKNOWN, "POSITIONAL_SHAPE_CHANGED")
    }

    @Test fun `new default expression may fail old inserts`() {
        val target = table.copy(columns = table.columns + ("new_column" to ColumnSchema("integer", true, defaultExpression = "(1 / 0)")))
        assertRule(verify(target), Verdict.UNKNOWN, "NEW_COLUMN_EXPRESSION")
    }

    @Test fun `changed defaults and nullability remain unknown`() {
        assertRule(verify(table.copy(columns = table.columns + ("email" to table.columns.getValue("email").copy(defaultExpression = "'unknown'::text")))), Verdict.UNKNOWN, "DEFAULT_CHANGED")
        assertRule(verify(table.copy(columns = table.columns + ("email" to table.columns.getValue("email").copy(nullable = false)))), Verdict.UNKNOWN, "NULLABILITY_CHANGED")
    }

    @Test fun `missing baseline mapping is unknown instead of fabricated regression`() {
        val missing = contract.copy(entities = listOf(entity.copy(table = "public.absent")))
        assertRule(verify(table, missing), Verdict.UNKNOWN, "INVALID_BASELINE_TABLE")
        val missingColumn = contract.copy(entities = listOf(entity.copy(columns = entity.columns + ColumnContract("absent"))))
        assertRule(verify(table.copy(columns = table.columns - "email"), missingColumn), Verdict.UNKNOWN, "INVALID_BASELINE_COLUMN")
    }

    @Test fun `finite declared query variants remain independently verifiable`() {
        val queries = listOf("select id from public.customers", "select email from public.customers").mapIndexed { index, sql ->
            QueryContract("variant-$index", Operation.SELECT, sql, tables = listOf("public.customers"),
                columns = listOf(ColumnReference("public.customers", if (index == 0) "id" else "email")),
                source = QuerySource.DYNAMIC_VARIANT, dependenciesComplete = true, variant = "branch-$index")
        }
        assertEquals(Verdict.SAFE, verify(table, contract.copy(queries = queries)).verdict)
        val dropped = verify(table.copy(columns = table.columns - "email"), contract.copy(entities = emptyList(), queries = queries))
        assertRule(dropped, Verdict.BREAKING, "REFERENCED_COLUMN_DROPPED")
        assertEquals(listOf("variant-1"), dropped.findings.single { it.ruleId == "REFERENCED_COLUMN_DROPPED" }.affectedContractIds)
    }

    @Test fun `insert select does not treat read source as write target`() {
        val query = QueryContract("copy", Operation.INSERT,
            "insert into public.archived_customers (id) select id from public.customers",
            tables = listOf("public.archived_customers", "public.customers"),
            columns = listOf(ColumnReference("public.customers", "id")),
            writeColumns = listOf(ColumnReference("public.archived_customers", "id")),
            dependenciesComplete = true, targetTable = "public.archived_customers")
        val old = baseline.copy(tables = baseline.tables + ("public.archived_customers" to table))
        val after = old.copy(tables = old.tables + ("public.customers" to table.copy(columns = table.columns + ("new" to ColumnSchema("text", false)))))
        val c = contract.copy(entities = emptyList(), queries = listOf(query))
        assertEquals(Verdict.SAFE, engine.verifyStep(c, old, after, migration).verdict)
    }

    @Test fun `existing trigger behavior may depend on newly added columns`() {
        val old = baseline.copy(behaviors = mapOf("trigger:public.customers.audit" to "EXECUTE FUNCTION audit_row()"))
        val target = old.copy(tables = mapOf("public.customers" to table.copy(columns = table.columns + ("extra" to ColumnSchema("text", true)))))
        assertRule(engine.verifyStep(contract, old, target, migration), Verdict.UNKNOWN, "EXISTING_DATABASE_BEHAVIOR")
    }

    @Test fun `adapter unknown data effects prevent safe`() {
        val extra = Finding(Verdict.UNKNOWN, "DATA_CHANGE", "database", "Data migration cannot be proven equivalent.", "UPDATE public.customers SET email = NULL")
        assertRule(engine.verifyStep(contract, baseline, baseline, migration, listOf(extra)), Verdict.UNKNOWN, "DATA_CHANGE")
    }

    @Test fun `intermediate breaking state is retained even when final restores baseline`() {
        val broken = verify(table.copy(columns = table.columns - "email"))
        val restored = engine.verifyStep(contract, baseline, baseline, MigrationInfo("3", "V3__restore.sql"))
        assertEquals(Verdict.SAFE, restored.verdict)
        assertEquals(Verdict.BREAKING, engine.report(contract, listOf(broken, restored)).verdict)
        assertEquals(Verdict.UNKNOWN, engine.report(contract, emptyList()).verdict)
    }

    @Test fun `json contract round trip preserves complete facts`() {
        val mapper = jacksonObjectMapper()
        assertEquals(contract, mapper.readValue<DatabaseContract>(mapper.writeValueAsString(contract)))
    }

    @Test fun `dotted schema or table identifiers cannot be declared safe`() {
        val ambiguousName = "public.customer.records"
        val ambiguousSnapshot = SchemaSnapshot(mapOf(ambiguousName to table))
        val ambiguousContract = contract.copy(entities = listOf(entity.copy(table = ambiguousName)))
        assertRule(engine.verifyStep(ambiguousContract, ambiguousSnapshot, ambiguousSnapshot, migration),
            Verdict.UNKNOWN, "UNSUPPORTED_IDENTIFIER")
        assertRule(engine.verifyStep(contract, baseline,
            baseline.copy(tables = baseline.tables + (ambiguousName to table)), migration),
            Verdict.UNKNOWN, "UNSUPPORTED_IDENTIFIER")
        val query = QueryContract("dotted", Operation.SELECT, "select id from public.\"customer.records\"",
            tables = listOf(ambiguousName), dependenciesComplete = true)
        assertRule(verify(table, contract.copy(queries = listOf(query))), Verdict.UNKNOWN, "UNSUPPORTED_IDENTIFIER")
    }

    @Test fun `finding order is deterministic regardless of catalog map insertion order`() {
        val a = verify(table.copy(columns = emptyMap(), constraints = linkedMapOf("z" to ConstraintSchema("c", "CHECK (true)"), "a" to ConstraintSchema("u", "UNIQUE (id)"))))
        val b = verify(table.copy(columns = emptyMap(), constraints = linkedMapOf("a" to ConstraintSchema("u", "UNIQUE (id)"), "z" to ConstraintSchema("c", "CHECK (true)"))))
        assertEquals(a, b)
    }
}
