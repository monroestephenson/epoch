package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulePrecisionTest {
    private val engine = CompatibilityEngine()
    private val migration = MigrationInfo("2", "V2__change.sql")
    private val table = TableSchema(columns = mapOf("id" to ColumnSchema("bigint", false), "status" to ColumnSchema("character varying(20)", true)))
    private val baseline = SchemaSnapshot(tables = mapOf("public.customer" to table))
    private val entity = EntityContract("Customer", "public.customer", listOf(
        ColumnContract("id", "bigint", false, updatable = false),
        ColumnContract("status", "varchar", true, length = 20),
    ))
    private val contract = DatabaseContract(entities = listOf(entity), coverage = Coverage(complete = true))
    private fun verify(target: TableSchema, input: DatabaseContract = contract, old: SchemaSnapshot = baseline) =
        engine.verifyStep(input, old, old.copy(tables = old.tables + ("public.customer" to target)), migration)
    private fun assertRule(result: MigrationReport, verdict: Verdict, rule: String) {
        assertEquals(verdict, result.verdict, result.findings.toString())
        assertTrue(result.findings.any { it.ruleId == rule }, result.findings.toString())
    }

    @Test fun `constant default supplies omitted required column`() {
        assertRule(verify(table.copy(columns = table.columns + ("enabled" to ColumnSchema("boolean", false, "true")))),
            Verdict.SAFE, "CONSTANT_DEFAULT_COLUMN_ADDED")
    }

    @Test fun `volatile and failing defaults remain unsupported`() {
        for (default in listOf("random()", "(1 / 0)", "current_setting('tenant')", "NULL::integer")) {
            assertRule(verify(table.copy(columns = table.columns + ("extra" to ColumnSchema("integer", false, default)))),
                Verdict.UNKNOWN, "NEW_COLUMN_EXPRESSION")
        }
    }

    @Test fun `plain nonunique index is safe but expression index needs proof`() {
        val indexed = table.copy(indexes = mapOf("status_idx" to "CREATE INDEX status_idx ON customer USING btree (status)"),
            uniqueIndexes = mapOf("status_idx" to false), plainIndexes = setOf("status_idx"),
            indexColumns = mapOf("status_idx" to listOf("status")), indexMethods = mapOf("status_idx" to "btree"))
        assertRule(verify(indexed), Verdict.SAFE, "NON_UNIQUE_INDEX_CHANGED")
        assertRule(verify(indexed.copy(plainIndexes = emptySet())), Verdict.UNKNOWN, "INDEX_CHANGED")
    }

    @Test fun `unique removal is safe unless old conflict inference depends on it`() {
        val indexed = table.copy(indexes = mapOf("status_idx" to "CREATE UNIQUE INDEX status_idx ON customer (status)"), uniqueIndexes = mapOf("status_idx" to true))
        val old = baseline.copy(tables = mapOf("public.customer" to indexed))
        assertRule(verify(table, old = old), Verdict.SAFE, "UNIQUE_INDEX_REMOVED")
        val query = QueryContract("upsert", Operation.INSERT, "insert into public.customer(id,status) values (?,?) ON CONFLICT(status) DO NOTHING",
            tables = listOf("public.customer"), writeColumns = listOf(ColumnReference("public.customer", "id"), ColumnReference("public.customer", "status")),
            parameterTypes = listOf("bigint", "varchar"), dependenciesComplete = true, targetTable = "public.customer")
        assertRule(verify(table, contract.copy(queries = listOf(query)), old), Verdict.UNKNOWN, "INDEX_CHANGED")
    }

    @Test fun `unique creation remains unknown`() {
        assertRule(verify(table.copy(indexes = mapOf("status_idx" to "CREATE UNIQUE INDEX status_idx ON customer (status)"), uniqueIndexes = mapOf("status_idx" to true))), Verdict.UNKNOWN, "INDEX_CHANGED")
    }

    @Test fun `new foreign key only affects old deletes and referenced key updates`() {
        val foreign = TableSchema(columns = mapOf("owner_id" to ColumnSchema("bigint", false)), constraints = mapOf("owner_fk" to ConstraintSchema(
            "f", "FOREIGN KEY(owner_id) REFERENCES customer(id)", columns = listOf("owner_id"), referencedTable = "public.customer", referencedColumns = listOf("id"))))
        val after = baseline.copy(tables = baseline.tables + ("public.audit" to foreign))
        val safeContract = contract.copy(entities = listOf(entity.copy(operations = setOf(Operation.SELECT, Operation.INSERT, Operation.UPDATE))))
        assertRule(engine.verifyStep(safeContract, baseline, after, migration), Verdict.SAFE, "NEW_REFERENTIAL_CONSTRAINT_UNREFERENCED")
        val affected = engine.verifyStep(contract, baseline, after, migration)
        assertRule(affected, Verdict.UNKNOWN, "NEW_REFERENTIAL_CONSTRAINT")
        assertEquals(listOf("Customer"), affected.findings.single { it.ruleId == "NEW_REFERENTIAL_CONSTRAINT" }.affectedContractIds)
    }

    @Test fun `dropping outside table and its owned sequence does not poison verdict`() {
        val audit = TableSchema(columns = mapOf("id" to ColumnSchema("bigint", false, "nextval('audit_id_seq')")),
            constraints = mapOf("owner_fk" to ConstraintSchema("f", "FOREIGN KEY(owner_id) REFERENCES customer(id)", referencedTable = "public.customer")))
        val old = baseline.copy(tables = baseline.tables + ("public.audit" to audit), sequences = mapOf("public.audit_id_seq" to "bigint:1:1:999:1:false:1"),
            sequenceOwners = mapOf("public.audit_id_seq" to ColumnReference("public.audit", "id")))
        assertRule(engine.verifyStep(contract, old, baseline, migration), Verdict.SAFE, "UNREFERENCED_TABLE_DROPPED")
    }

    @Test fun `new table owned sequence is outside contract`() {
        val after = baseline.copy(tables = baseline.tables + ("public.audit" to TableSchema(mapOf("id" to ColumnSchema("bigint", false)))),
            sequences = mapOf("public.audit_id_seq" to "bigint:1:1:999:1:false:1"),
            sequenceOwners = mapOf("public.audit_id_seq" to ColumnReference("public.audit", "id")))
        assertRule(engine.verifyStep(contract, baseline, after, migration), Verdict.SAFE, "UNREFERENCED_TABLE_ADDED")
    }

    @Test fun `writable nullable mapping establishes rejected null domain`() {
        assertRule(verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(nullable = false)))), Verdict.BREAKING, "MAPPED_NULL_REJECTED")
        val checked = table.copy(constraints = mapOf("valid" to ConstraintSchema("c", "CHECK(status IS NOT NULL)")))
        assertRule(verify(checked.copy(columns = checked.columns + ("status" to checked.columns.getValue("status").copy(nullable = false))),
            old = baseline.copy(tables = mapOf("public.customer" to checked))), Verdict.UNKNOWN, "NULLABILITY_CHANGED")
    }

    @Test fun `mapped length narrowing proves exclusion while widening preserves string reads`() {
        assertRule(verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(type = "character varying(10)")))), Verdict.BREAKING, "MAPPED_TYPE_NARROWED")
        assertRule(verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(type = "text")))), Verdict.SAFE, "TYPE_WIDENED")
    }

    @Test fun `integer widening does not assert old hydration accepts larger values`() {
        val old = baseline.copy(tables = mapOf("public.customer" to table.copy(columns = table.columns + ("id" to ColumnSchema("integer", false)))))
        assertRule(verify(table, old = old), Verdict.UNKNOWN, "COLUMN_TYPE_CHANGED")
        val writes = contract.copy(entities = listOf(entity.copy(operations = setOf(Operation.INSERT))))
        assertRule(verify(table, writes, old), Verdict.SAFE, "TYPE_WIDENED")
    }

    @Test fun `new check rejects a supported mapped enum label`() {
        val mapped = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(enumValues = listOf("ACTIVE", "RETIRED")) else it })))
        val check = ConstraintSchema("c", "CHECK (status = ANY (ARRAY['ACTIVE'::text]))", columns = listOf("status"), checkExpression = "(status = ANY (ARRAY['ACTIVE'::text]))")
        assertRule(verify(table.copy(constraints = mapOf("allowed" to check)), mapped), Verdict.BREAKING, "ENUM_VALUE_REJECTED")
        val all = check.copy(checkExpression = "status IN ('ACTIVE','RETIRED')")
        assertRule(verify(table.copy(constraints = mapOf("allowed" to all)), mapped), Verdict.SAFE, "ENUM_CHECK_ACCEPTS_MAPPED_VALUES")
    }

    @Test fun `unsupported enum check expression cannot establish safety`() {
        val mapped = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(enumValues = listOf("ACTIVE")) else it })))
        val check = ConstraintSchema("c", "CHECK (status IN ('ACTIVE') AND length(status) > 100)")
        assertRule(verify(table.copy(constraints = mapOf("allowed" to check)), mapped), Verdict.UNKNOWN, "CONSTRAINT_CHANGED")
    }

    @Test fun `required omitted identity removal is breaking`() {
        val oldTable = table.copy(columns = table.columns + ("id" to ColumnSchema("bigint", false, identity = "d")))
        val identity = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "id") it.copy(generated = true) else it }, generatedIdentifier = "org.hibernate.id.IdentityGenerator")))
        assertRule(verify(table, identity, baseline.copy(tables = mapOf("public.customer" to oldTable))), Verdict.BREAKING, "REQUIRED_GENERATION_REMOVED")
    }

    @Test fun `referenced sequence removal is breaking`() {
        val sequence = contract.copy(entities = listOf(entity.copy(identifierSequence = "public.customer_seq")))
        val old = baseline.copy(sequences = mapOf("public.customer_seq" to "bigint:1:1:999:1:false:1"))
        assertRule(engine.verifyStep(sequence, old, baseline, migration), Verdict.BREAKING, "IDENTIFIER_SEQUENCE_DROPPED")
    }

    @Test fun `compatibility view removes false absent table finding without inventing equivalence`() {
        val view = table.copy(kind = "view")
        val after = baseline.copy(tables = mapOf("public.customer" to view, "public.customer_new" to table), behaviors = mapOf("view:public.customer" to "SELECT id,status FROM customer_new"))
        val result = engine.verifyStep(contract, baseline, after, migration)
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.none { it.verdict == Verdict.BREAKING })
        assertTrue(result.findings.any { it.ruleId == "RELATION_KIND_CHANGED" })
    }

    @Test fun `UTF8 identifier truncation matches postgres on codepoint boundary`() {
        val long = "é".repeat(32)
        assertEquals("é".repeat(31), postgresIdentifier(long))
        val old = SchemaSnapshot(mapOf("public.${postgresIdentifier(long)}" to table))
        val input = contract.copy(entities = listOf(entity.copy(table = "public.$long")))
        assertEquals(Verdict.SAFE, engine.verifyStep(input, old, old, migration).verdict)
    }

    @Test fun `noop remains safe and mixed findings sort breaking before unknown`() {
        assertEquals(Verdict.SAFE, verify(table).verdict)
        val result = verify(table.copy(columns = table.columns - "status", constraints = mapOf("u" to ConstraintSchema("u", "UNIQUE(id)"))))
        assertEquals(Verdict.BREAKING, result.findings.first().verdict)
        assertTrue(result.findings.filter { it.verdict == Verdict.UNKNOWN }.all { !it.mitigation.isNullOrBlank() })
    }
    @Test fun `write triggers prevent fabricated null and missing default failures`() {
        val old = baseline.copy(behaviors = mapOf("trigger:public.customer.fill" to "BEFORE INSERT EXECUTE FUNCTION fill_values()"))
        val target = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(nullable = false)))
        val report = verify(target, old = old)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        assertTrue(report.findings.none { it.verdict == Verdict.BREAKING })
        val required = verify(table.copy(columns = table.columns + ("extra" to ColumnSchema("text", false))), old = old)
        assertRule(required, Verdict.UNKNOWN, "NEW_REQUIRED_COLUMN_BEHAVIOR")
    }

    @Test fun `baseline acceptance cannot hide new interaction with opaque database behavior`() {
        val old = baseline.copy(behaviors = mapOf("trigger:public.customer.fill" to "BEFORE INSERT EXECUTE FUNCTION fill_values()"))
        val health = engine.inspectBaseline(contract, old)
        val before = engine.report(contract, emptyList(), health)
        val accepted = BaselineAcceptance.fromReport(before, "Known baseline behavior, not a proof about new schema interactions")
        val target = old.copy(tables = mapOf("public.customer" to table.copy(columns = table.columns + ("extra" to ColumnSchema("text", true)))))
        val step = engine.verifyStep(contract, old, target, migration, health.findings)
        val report = engine.report(contract, listOf(step), health, accepted)
        assertEquals(Verdict.UNKNOWN, report.gateVerdict)
        assertTrue(report.migrations.single().findings.any { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" })
    }

    @Test fun `supported PostgreSQL enum CHECK renderings have identical domains`() {
        for (expression in listOf(
            "label = 'ACTIVE'::text",
            "(label)::text = ANY ((ARRAY['ACTIVE'::character varying])::text[])",
            "label IN ('ACTIVE')",
        )) {
            assertEquals("label" to setOf("ACTIVE"), RuleProofs.enumCheck(ConstraintSchema("c", "", checkExpression = expression)), expression)
        }
    }

    @Test fun `integer narrowing rejects values in the old mapped range`() {
        val oldTable = table.copy(columns = table.columns + ("id" to ColumnSchema("integer", false)))
        val input = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "id") it.copy(typeName = "INTEGER") else it })))
        assertRule(verify(oldTable.copy(columns = oldTable.columns + ("id" to ColumnSchema("smallint", false))), input,
            baseline.copy(tables = mapOf("public.customer" to oldTable))), Verdict.BREAKING, "MAPPED_TYPE_NARROWED")
    }

    @Test fun `unresolved path facts cannot be contradicted by coverage boolean`() {
        val input = contract.copy(accessPaths = listOf(AccessPathCoverage("Repository#search", "Repository", "search", AccessPathStatus.UNRESOLVED, reason = "Dynamic HQL")))
        assertRule(verify(table, input), Verdict.UNKNOWN, "UNRESOLVED_QUERY")
    }

    @Test fun `foreign key domain prevents false null and narrowing proofs`() {
        val foreignKey = ConstraintSchema("f", "FOREIGN KEY (id,status) REFERENCES targets(id,status) MATCH FULL",
            columns = listOf("id", "status"), referencedTable = "public.targets", referencedColumns = listOf("id", "status"))
        val oldTable = table.copy(constraints = mapOf("fk" to foreignKey))
        val old = baseline.copy(tables = mapOf("public.customer" to oldTable))
        val notNull = verify(oldTable.copy(columns = oldTable.columns + ("status" to oldTable.columns.getValue("status").copy(nullable = false))), old = old)
        assertRule(notNull, Verdict.UNKNOWN, "NULLABILITY_CHANGED")
        assertTrue(notNull.findings.none { it.verdict == Verdict.BREAKING })
        val narrowed = verify(oldTable.copy(columns = oldTable.columns + ("status" to oldTable.columns.getValue("status").copy(type = "varchar(10)"))), old = old)
        assertRule(narrowed, Verdict.UNKNOWN, "COLUMN_TYPE_CHANGED")
        assertTrue(narrowed.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `enum mapping does not imply every string up to mapped length is writable`() {
        val finite = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(enumValues = listOf("A", "B")) else it })))
        val narrowed = verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(type = "varchar(10)"))), finite)
        assertTrue(narrowed.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `enum CHECK cannot prove a previously accepted label when FK restricts it`() {
        val finite = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(enumValues = listOf("A", "B")) else it })))
        val oldTable = table.copy(constraints = mapOf("fk" to ConstraintSchema("f", "FOREIGN KEY(status) REFERENCES status_codes(code)", columns = listOf("status"))))
        val target = oldTable.copy(constraints = oldTable.constraints + ("allowed" to ConstraintSchema("c", "CHECK (status IN ('A'))")))
        val result = verify(target, finite, baseline.copy(tables = mapOf("public.customer" to oldTable)))
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `removed PostgreSQL enum label already excluded by CHECK is not a concrete old failure`() {
        val finite = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(enumValues = listOf("A", "B")) else it })))
        val oldTable = table.copy(columns = table.columns + ("status" to ColumnSchema("status_type", true)),
            constraints = mapOf("allowed" to ConstraintSchema("c", "CHECK (status IN ('A'))")))
        val old = baseline.copy(tables = mapOf("public.customer" to oldTable), enumTypes = mapOf("public.status_type" to listOf("A", "B")))
        val target = old.copy(enumTypes = mapOf("public.status_type" to listOf("A")))
        val result = engine.verifyStep(finite, old, target, migration)
        assertRule(result, Verdict.UNKNOWN, "ENUM_TYPE_CHANGED")
        assertTrue(result.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `updatable view can inherit backing identity without a view column default`() {
        val oldTable = table.copy(columns = table.columns + ("id" to ColumnSchema("bigint", false, identity = "d")))
        val identity = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "id") it.copy(generated = true) else it }, generatedIdentifier = "org.hibernate.id.IdentityGenerator")))
        val old = baseline.copy(tables = mapOf("public.customer" to oldTable))
        val target = baseline.copy(tables = mapOf("public.customer" to table.copy(kind = "view"), "public.customer_backing" to oldTable),
            behaviors = mapOf("view:public.customer" to "SELECT id,status FROM customer_backing"))
        val result = engine.verifyStep(identity, old, target, migration)
        assertEquals(Verdict.UNKNOWN, result.verdict)
        assertTrue(result.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `unbounded plain btree key may reject old writes`() {
        val oldTable = table.copy(columns = table.columns + ("status" to ColumnSchema("text", true)))
        val indexed = oldTable.copy(indexes = mapOf("status_idx" to "CREATE INDEX status_idx ON customer USING btree (status)"),
            uniqueIndexes = mapOf("status_idx" to false), plainIndexes = setOf("status_idx"),
            indexColumns = mapOf("status_idx" to listOf("status")), indexMethods = mapOf("status_idx" to "btree"))
        assertRule(verify(indexed, old = baseline.copy(tables = mapOf("public.customer" to oldTable))), Verdict.UNKNOWN, "INDEX_KEY_DOMAIN_UNPROVEN")
        val readOnly = contract.copy(entities = listOf(entity.copy(operations = setOf(Operation.SELECT))))
        assertRule(verify(indexed, readOnly, baseline.copy(tables = mapOf("public.customer" to oldTable))), Verdict.SAFE, "NON_UNIQUE_INDEX_CHANGED")
        assertRule(verify(oldTable, old = baseline.copy(tables = mapOf("public.customer" to indexed))), Verdict.SAFE, "NON_UNIQUE_INDEX_CHANGED")
    }

    @Test fun `btree aggregate bound includes INCLUDE columns and UTF8 expansion`() {
        val columns = TableSchema(columns = mapOf("a" to ColumnSchema("varchar(255)", true), "b" to ColumnSchema("varchar(255)", true)),
            indexMethods = mapOf("ab" to "btree"), indexColumns = mapOf("ab" to listOf("a", "b")))
        assertTrue(!RuleProofs.boundedBtreeKey(columns, "ab"))
        assertTrue(RuleProofs.boundedBtreeKey(columns.copy(indexColumns = mapOf("ab" to listOf("a"))), "ab"))
    }

    @Test fun `primitive and converter domains cannot fabricate mapped boundary failures`() {
        val primitive = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(logicalJavaType = "int") else it })))
        val notNull = verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(nullable = false))), primitive)
        assertEquals(Verdict.UNKNOWN, notNull.verdict)
        assertTrue(notNull.findings.none { it.verdict == Verdict.BREAKING })
        val uuid = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map { if (it.name == "status") it.copy(logicalJavaType = "java.util.UUID", inputDomainComplete = false) else it })))
        val narrowed = verify(table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(type = "varchar(10)"))), uuid)
        assertEquals(Verdict.UNKNOWN, narrowed.verdict)
        assertTrue(narrowed.findings.none { it.verdict == Verdict.BREAKING })
    }

    @Test fun `invalid baseline mapping does not establish a broken sequence operation`() {
        val invalid = contract.copy(entities = listOf(entity.copy(identifierSequence = "public.customer_seq",
            columns = entity.columns + ColumnContract("missing", "text"))))
        val old = baseline.copy(sequences = mapOf("public.customer_seq" to "bigint:1:1:999:1:false:1"))
        val result = engine.verifyStep(invalid, old, baseline, migration)
        assertRule(result, Verdict.UNKNOWN, "SEQUENCE_CHANGED")
        assertTrue(result.findings.any { it.ruleId == "INVALID_BASELINE_COLUMN" })
        assertTrue(result.findings.none { it.verdict == Verdict.BREAKING })
    }

}
