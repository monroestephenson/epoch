package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataContractCompatibilityTest {
    private val relation = "public.records"
    private val id = ColumnContract("id", "BIGINT", nullable = false, updatable = false, logicalJavaType = "long",
        declaredNonNull = true, hibernateNullable = false, readDomainComplete = true)
    private val state = ColumnContract("state", "VARCHAR", nullable = false, logicalJavaType = "OldState",
        declaredNonNull = true, hibernateNullable = false, enumValues = listOf("OPEN", "CLOSED"), enumMode = EnumMode.STRING,
        enumConstants = listOf("OPEN", "CLOSED"), enumRelationalValues = listOf("OPEN", "CLOSED"), enumEncoding = EnumEncoding.NAME,
        readDomainComplete = true)
    private val table = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "state" to ColumnSchema("character varying(255)", true, collation = "pg_catalog.default", typeAcceptsImplicitNull = true),
    ), relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val schema = SchemaSnapshot(tables = mapOf(relation to table), expressionSearchPath = listOf("pg_catalog", "public"))
    private fun contract(name: String, column: ColumnContract = state, write: Boolean = true): DatabaseContract {
        val references = listOf("id", "state").map { ColumnReference(relation, it) }
        val mapping = EntityContract(name, relation, listOf(id, column),
            operations = if (write) setOf(Operation.SELECT, Operation.INSERT) else setOf(Operation.SELECT))
        val select = QueryContract("$name.read", Operation.SELECT, "select id, state from records where id=?", listOf(relation), references,
            parameterTypes = listOf("BIGINT"), source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true,
            mappedEntityIds = listOf(name), mappedReads = references.map { MappedColumnRead(name, it) }, mappedReadCoverageComplete = true)
        val insert = QueryContract("$name.insert", Operation.INSERT, "insert into records (id,state) values (?,?)", listOf(relation), references,
            writeColumns = references, parameterTypes = listOf("BIGINT", column.typeName ?: "VARCHAR"), source = QuerySource.HIBERNATE_MAPPING,
            dependenciesComplete = true, targetTable = relation, mappedEntityIds = listOf(name))
        return DatabaseContract(applicationVersion = name, entities = listOf(mapping), queries = listOf(select) + if (write) listOf(insert) else emptyList(),
            coverage = Coverage(complete = true))
    }
    private fun extended() = state.copy(logicalJavaType = "NewState", enumConstants = state.enumConstants + "ARCHIVED",
        enumRelationalValues = state.enumRelationalValues + "ARCHIVED", enumValues = state.enumValues + "ARCHIVED")
    private fun assess(old: DatabaseContract = contract("Old"), new: DatabaseContract = contract("New", extended()),
                       final: SchemaSnapshot = schema, oldIds: Set<String> = old.queries.map { it.id }.toSet(),
                       newIds: Set<String> = new.queries.map { it.id }.toSet()) =
        DataContractCompatibility().assess(old, new, final, oldIds, newIds)
    private fun findings(report: CompatibilityReport) = report.migrations.flatMap { it.findings }
    private fun rule(report: CompatibilityReport, id: String, verdict: Verdict) =
        assertTrue(findings(report).any { it.ruleId == id && it.verdict == verdict }, report.toString())
    private fun noBreak(report: CompatibilityReport) = assertFalse(findings(report).any { it.verdict == Verdict.BREAKING }, report.toString())
    private fun check(vararg values: String) = ConstraintSchema("c", "CHECK (state IN (${values.joinToString { "'$it'" }}))",
        listOf("state"), checkExpression = "state IN (${values.joinToString { "'$it'" }})")

    @Test fun `new NAME value names an old reader and actual new writer`() {
        val report = assess()
        assertEquals(Verdict.BREAKING, report.verdict)
        val finding = findings(report).single { it.ruleId == "DATA_ENUM_VALUE_UNREADABLE" }
        assertEquals("public.records.state", finding.objectName)
        assertTrue("old/Old.read" in finding.affectedContractIds && "new/New.insert" in finding.affectedContractIds)
        assertTrue("ARCHIVED" in finding.evidence)
        assertEquals("<old-vs-new-contract>", finding.migration?.filename)
        assertEquals(null, report.baseline)
    }

    @Test fun `unchanged or narrower NAME domains remain readable across renamed enum classes`() {
        assertEquals(Verdict.SAFE, assess(new = contract("New", state.copy(logicalJavaType = "RenamedState"))).verdict)
        val subset = state.copy(enumConstants = listOf("OPEN"), enumRelationalValues = listOf("OPEN"), enumValues = listOf("OPEN"))
        assertEquals(Verdict.SAFE, assess(new = contract("New", subset)).verdict)
    }

    @Test fun `final CHECK excludes new enum candidate instead of blaming old reader`() {
        val final = schema.copy(tables = mapOf(relation to table.copy(constraints = mapOf("states" to check("OPEN", "CLOSED")))))
        assertEquals(Verdict.SAFE, assess(final = final).verdict)
    }

    @Test fun `ordinal addition and reorder have different concrete failures`() {
        fun ordinal(values: List<String>) = state.copy(typeName = "SMALLINT", enumMode = EnumMode.ORDINAL, enumEncoding = EnumEncoding.ORDINAL,
            enumConstants = values, enumRelationalValues = values.indices.map(Int::toString), enumValues = values.indices.map(Int::toString))
        val physical = schema.copy(tables = mapOf(relation to table.copy(columns = table.columns +
            ("state" to ColumnSchema("smallint", true, typeAcceptsImplicitNull = true)))))
        val old = contract("Old", ordinal(listOf("OPEN", "CLOSED")))
        rule(assess(old, contract("New", ordinal(listOf("OPEN", "CLOSED", "ARCHIVED"))), physical), "DATA_ENUM_VALUE_UNREADABLE", Verdict.BREAKING)
        rule(assess(old, contract("New", ordinal(listOf("CLOSED", "OPEN"))), physical), "DATA_ENUM_VALUE_REINTERPRETED", Verdict.BREAKING)
    }

    @Test fun `explicit stable codes reordered and native NAME storage do not invent ordinal breakage`() {
        val explicit = state.copy(enumMode = EnumMode.ORDINAL, enumEncoding = EnumEncoding.EXPLICIT,
            enumRelationalValues = listOf("O", "C"), enumValues = listOf("O", "C"))
        val reordered = explicit.copy(enumConstants = explicit.enumConstants.reversed(), enumRelationalValues = explicit.enumRelationalValues.reversed())
        assertEquals(Verdict.SAFE, assess(contract("Old", explicit), contract("New", reordered)).verdict)
        val nameOrdinal = state.copy(enumMode = EnumMode.ORDINAL)
        val nameReordered = nameOrdinal.copy(enumConstants = state.enumConstants.reversed(), enumRelationalValues = state.enumRelationalValues.reversed())
        assertEquals(Verdict.SAFE, assess(contract("Old", nameOrdinal), contract("New", nameReordered)).verdict)
    }

    @Test fun `known NAME to ordinal text storage exposes the incompatible value`() {
        val ordinal = state.copy(typeName = "SMALLINT", enumMode = EnumMode.ORDINAL, enumEncoding = EnumEncoding.ORDINAL,
            enumRelationalValues = listOf("0", "1"), enumValues = listOf("0", "1"))
        rule(assess(new = contract("New", ordinal)), "DATA_ENUM_VALUE_UNREADABLE", Verdict.BREAKING)
    }

    private fun string(nullable: Boolean) = ColumnContract("state", "VARCHAR", nullable = nullable, logicalJavaType = "java.lang.String",
        declaredNonNull = !nullable, hibernateNullable = nullable, readDomainComplete = true)

    @Test fun `nullable new values violate an old declared non-null read domain`() {
        val report = assess(contract("Old", string(false)), contract("New", string(true)))
        rule(report, "DATA_NULLABILITY_RELAXED", Verdict.BREAKING)
        assertTrue(findings(report).any { it.evidence.contains("not a claim that all field hydration throws") })
    }

    @Test fun `final physical and CHECK NOT NULL block a new null candidate`() {
        val old = contract("Old", string(false))
        val new = contract("New", string(true))
        val nonNull = table.copy(columns = table.columns + ("state" to table.columns.getValue("state").copy(nullable = false)))
        assertEquals(Verdict.SAFE, assess(old, new, schema.copy(tables = mapOf(relation to nonNull))).verdict)
        val checked = table.copy(constraints = mapOf("not_null" to ConstraintSchema("c", "CHECK (state IS NOT NULL)", listOf("state"), checkExpression = "state IS NOT NULL")))
        assertEquals(Verdict.SAFE, assess(old, new, schema.copy(tables = mapOf(relation to checked))).verdict)
    }

    @Test fun `Hibernate null rejection prevents a fabricated null producer`() {
        val new = string(true).copy(hibernateNullable = false)
        assertEquals(Verdict.SAFE, assess(contract("Old", string(false)), contract("New", new)).verdict)
    }

    @Test fun `converter fingerprints never substitute for semantic evidence`() {
        val old = string(false).copy(converterClass = "OldConverter", converterBytecodeSha256 = "a", readDomainComplete = false, inputDomainComplete = false)
        for (next in listOf(old, old.copy(converterClass = "NewConverter", converterBytecodeSha256 = "b"))) {
            val report = assess(contract("Old", old), contract("New", next))
            assertEquals(Verdict.UNKNOWN, report.verdict)
            rule(report, "DATA_CONVERTER_SEMANTICS_UNPROVEN", Verdict.UNKNOWN)
            noBreak(report)
        }
    }

    @Test fun `changed logical mapping is unknown but primitive boxing preserves a known scalar`() {
        rule(assess(contract("Old", string(false)), contract("New", string(false).copy(logicalJavaType = "java.lang.Character"))),
            "DATA_TYPE_MAPPING_UNPROVEN", Verdict.UNKNOWN)
        val old = contract("Old", string(false))
        val new = contract("New", string(false)).let { it.copy(entities = it.entities.map { entity -> entity.copy(columns = entity.columns.map { column ->
            if (column.name == "id") column.copy(logicalJavaType = "java.lang.Long") else column }) }) }
        assertEquals(Verdict.SAFE, assess(old, new).verdict)
    }

    @Test fun `predicate-only column and absent decoder provenance cannot become reader evidence`() {
        val old = contract("Old").let { contract -> contract.copy(queries = contract.queries.map { query ->
            if (query.operation == Operation.SELECT) query.copy(sql = "select id from records where state=?", mappedReads = emptyList(), mappedReadCoverageComplete = false) else query }) }
        val report = assess(old = old)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        noBreak(report)
        rule(report, "DATA_READ_PROVENANCE_UNPROVEN", Verdict.UNKNOWN)
    }

    @Test fun `read-only new mappings cannot produce incompatible stored values`() {
        assertEquals(Verdict.SAFE, assess(new = contract("New", extended(), write = false)).verdict)
    }

    @Test fun `missing accepted plans or final columns cannot establish data breakage`() {
        noBreak(assess(newIds = emptySet()))
        noBreak(assess(oldIds = emptySet()))
        val missing = schema.copy(tables = mapOf(relation to table.copy(columns = table.columns - "state")))
        val report = assess(final = missing)
        assertEquals(Verdict.UNKNOWN, report.verdict)
        noBreak(report)
    }

    @Test fun `unknown unrelated CHECK and mandatory foreign key prevent a row-domain witness`() {
        val constraints = listOf(
            ConstraintSchema("c", "CHECK (false)", checkExpression = "false"),
            ConstraintSchema("f", "FOREIGN KEY (id) REFERENCES owners(id)", listOf("id"), "public.owners", listOf("id")),
        )
        for (constraint in constraints) {
            val report = assess(final = schema.copy(tables = mapOf(relation to table.copy(constraints = mapOf("restriction" to constraint)))))
            rule(report, "DATA_WRITE_ACCEPTANCE_UNPROVEN", Verdict.UNKNOWN)
            noBreak(report)
        }
    }

    @Test fun `opaque relation effects cannot prove safe equal direct domains`() {
        val triggered = table.copy(relationSecurity = table.relationSecurity!!.copy(userTriggers = true))
        val report = assess(new = contract("New", state), final = schema.copy(tables = mapOf(relation to triggered)))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        rule(report, "DATA_WRITE_EFFECT_UNPROVEN", Verdict.UNKNOWN)
    }

    @Test fun `shared physical columns retain every old decoder and deterministic ordering`() {
        val old1 = contract("Old", state)
        val old2 = contract("Other", extended())
        val old = old1.copy(entities = old1.entities + old2.entities, queries = old1.queries + old2.queries)
        val normal = assess(old)
        val reversed = assess(old.copy(entities = old.entities.reversed(), queries = old.queries.reversed()))
        assertEquals(normal, reversed)
        assertEquals(Verdict.BREAKING, normal.verdict)
        assertTrue(findings(normal).any { it.ruleId == "DATA_DOMAIN_PRESERVED" && "old/Other.read" in it.affectedContractIds })
    }

    @Test fun `ambiguous IDs and unsupported format remain unknown without guessed evidence`() {
        val old = contract("Old")
        noBreak(assess(old.copy(queries = old.queries + old.queries.first())))
        noBreak(assess(old.copy(schemaVersion = 2)))
    }

    @Test fun `a new explicit code is not assumed to throw in the old finite converter`() {
        val old = state.copy(enumEncoding = EnumEncoding.EXPLICIT, enumRelationalValues = listOf("O", "C"), enumValues = listOf("O", "C"))
        val next = old.copy(enumConstants = old.enumConstants + "ARCHIVED", enumRelationalValues = old.enumRelationalValues + "A", enumValues = old.enumValues + "A")
        val report = assess(contract("Old", old), contract("New", next))
        rule(report, "DATA_CONVERTER_SEMANTICS_UNPROVEN", Verdict.UNKNOWN)
        noBreak(report)
    }

    @Test fun `a compatibility view is not disjoint from writes to its backing relation`() {
        val old = contract("Old")
        val new = contract("New", extended()).let { contract -> contract.copy(
            entities = contract.entities.map { it.copy(table = "public.records_v2") }, queries = contract.queries.map { query -> query.copy(
                tables = listOf("public.records_v2"), columns = query.columns.map { it.copy(table = "public.records_v2") },
                writeColumns = query.writeColumns.map { it.copy(table = "public.records_v2") }, targetTable = query.targetTable?.let { "public.records_v2" }) }) }
        val view = table.copy(kind = "view", view = ViewSchema("SELECT * FROM records_v2", 28, 28, setOf("id", "state"), setOf("id", "state"), "public.records_v2", true))
        val report = assess(old, new, schema.copy(tables = mapOf(relation to view, "public.records_v2" to table)))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        rule(report, "DATA_READ_EFFECT_UNPROVEN", Verdict.UNKNOWN)
    }

    @Test fun `physical identifier normalization includes new decoded bindings`() {
        val long = "records_" + "x".repeat(60)
        fun rename(input: DatabaseContract) = input.copy(entities = input.entities.map { it.copy(table = "public.$long") },
            queries = input.queries.map { query -> query.copy(tables = listOf("public.$long"), targetTable = query.targetTable?.let { "public.$long" },
                columns = query.columns.map { it.copy(table = "public.$long") }, writeColumns = query.writeColumns.map { it.copy(table = "public.$long") },
                mappedReads = query.mappedReads.map { it.copy(column = it.column.copy(table = "public.$long")) }) })
        val report = assess(rename(contract("Old")), rename(contract("New", extended())), schema.copy(tables = mapOf("public.${postgresIdentifier(long)}" to table)))
        rule(report, "DATA_ENUM_VALUE_UNREADABLE", Verdict.BREAKING)
    }
    @Test fun `incomplete disjoint targets and SELECT side effects cannot establish SAFE`() {
        val native = QueryContract("New.cte", Operation.INSERT,
            "with x as (update records set state='BAD') insert into audit(id) values (1)",
            tables = listOf("public.audit"), targetTable = "public.audit", dependenciesComplete = false)
        val next = DatabaseContract(queries = listOf(native), coverage = Coverage(complete = true))
        val report = assess(new = next, final = schema.copy(tables = schema.tables + ("public.audit" to table)))
        assertEquals(Verdict.UNKNOWN, report.verdict)
        rule(report, "DATA_WRITE_EFFECT_UNPROVEN", Verdict.UNKNOWN)
        val sideEffect = next.copy(queries = listOf(native.copy(id = "New.sideEffect", operation = Operation.SELECT,
            sql = "select modify_records()", targetTable = null, tables = emptyList())))
        assertEquals(Verdict.UNKNOWN, assess(new = sideEffect).verdict)
    }

    @Test fun `noncanonical numeric and trimmed NAME values cannot create false decoder failures`() {
        val ordinal = state.copy(typeName = "SMALLINT", enumEncoding = EnumEncoding.ORDINAL, enumMode = EnumMode.ORDINAL,
            enumRelationalValues = listOf("0", "1"), enumValues = listOf("0", "1"))
        val explicit = state.copy(enumEncoding = EnumEncoding.EXPLICIT, enumRelationalValues = listOf("00", "01"), enumValues = listOf("00", "01"))
        val numeric = assess(contract("Old", ordinal), contract("New", explicit))
        assertEquals(Verdict.UNKNOWN, numeric.verdict)
        noBreak(numeric)
        val padded = state.copy(enumEncoding = EnumEncoding.EXPLICIT, enumRelationalValues = listOf("OPEN ", "CLOSED "), enumValues = listOf("OPEN ", "CLOSED "))
        val trimmed = assess(new = contract("New", padded))
        assertEquals(Verdict.UNKNOWN, trimmed.verdict)
        noBreak(trimmed)
    }

}
