package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Positive mapping/catalog facts must establish both a rejected input and all-writer safety. */
class DeclaredWriteDomainTest {
    private val migration = MigrationInfo("2", "V2__tighten.sql")
    private val security = RelationSecurity("epoch", false, false, false, false)
    private val table = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "label" to ColumnSchema("text", true, collation = "pg_catalog.default", typeAcceptsImplicitNull = true),
    ), relationSecurity = security)
    private val baseline = SchemaSnapshot(tables = mapOf("public.accounts" to table), expressionSearchPath = listOf("pg_catalog", "public"))
    private val nullable = ColumnContract("label", "text", nullable = true,
        logicalJavaType = "java.lang.String", declaredNonNull = false, hibernateNullable = true)
    private fun mapped(column: ColumnContract = nullable) = EntityContract("Account", "public.accounts",
        listOf(ColumnContract("id", "bigint", nullable = false, updatable = false, declaredNonNull = true), column),
        operations = setOf(Operation.INSERT, Operation.UPDATE))
    private fun contract(column: ColumnContract = nullable, queries: List<QueryContract> = emptyList()) = DatabaseContract(
        entities = listOf(mapped(column)), queries = queries, coverage = Coverage(complete = true))
    private fun verify(input: DatabaseContract = contract(), old: TableSchema = table, target: TableSchema = notNull(old)) =
        CompatibilityEngine().verifyStep(input, baseline.copy(tables = mapOf("public.accounts" to old)),
            baseline.copy(tables = mapOf("public.accounts" to target)), migration)
    private fun notNull(old: TableSchema) = old.copy(columns = old.columns +
        ("label" to old.columns.getValue("label").copy(nullable = false)))
    private fun nullCheck(validated: Boolean = true) = ConstraintSchema("c", "CHECK (label IS NOT NULL)",
        columns = listOf("label"), checkExpression = "(label IS NOT NULL)", validated = validated)
    private fun query(columns: List<String> = listOf("id", "label"), source: QuerySource = QuerySource.STATIC) = QueryContract(
        "Account.insert", Operation.INSERT,
        "INSERT INTO accounts (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
        tables = listOf("public.accounts"), columns = columns.map { ColumnReference("public.accounts", it) },
        writeColumns = columns.map { ColumnReference("public.accounts", it) },
        parameterTypes = columns.map { if (it == "id") "bigint" else "text" },
        dependenciesComplete = true, targetTable = "public.accounts", source = source)
    private fun assertVerdict(expected: Verdict, report: MigrationReport) =
        assertEquals(expected, report.verdict, report.toString())

    @Test fun `declared nullable input establishes a rejected NULL`() {
        val report = verify()
        assertVerdict(Verdict.BREAKING, report)
        assertTrue(report.findings.any { it.ruleId == "MAPPED_NULL_REJECTED" && "Account" in it.affectedContractIds })
    }

    @Test fun `declared non-null input preserves NOT NULL tightening`() {
        assertVerdict(Verdict.SAFE, verify(contract(nullable.copy(declaredNonNull = true))))
    }

    @Test fun `missing declaration is not evidence that an application writes NULL`() {
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(declaredNonNull = null))))
    }

    @Test fun `Hibernate rejection of NULL prevents a fabricated nullable input witness`() {
        val report = verify(contract(nullable.copy(hibernateNullable = false)))
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `validated baseline non-null CHECK makes SET NOT NULL redundant`() {
        val checked = table.copy(constraints = mapOf("label_not_null" to nullCheck()))
        assertVerdict(Verdict.SAFE, verify(old = checked))
    }

    @Test fun `unvalidated baseline CHECK does not establish existing non-null rows`() {
        val checked = table.copy(constraints = mapOf("label_not_null" to nullCheck(validated = false)))
        val report = verify(old = checked)
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `CHECK IS NOT NULL rejects a declared nullable write`() {
        assertVerdict(Verdict.BREAKING, verify(target = table.copy(constraints = mapOf("label_not_null" to nullCheck()))))
    }

    @Test fun `CHECK IS NOT NULL preserves every declared non-null write`() {
        assertVerdict(Verdict.SAFE, verify(contract(nullable.copy(declaredNonNull = true)),
            target = table.copy(constraints = mapOf("label_not_null" to nullCheck()))))
    }

    @Test fun `omitted baseline NULL INSERT is rejected by tightening`() {
        val insert = query(listOf("id"))
        val report = verify(DatabaseContract(queries = listOf(insert), coverage = Coverage(complete = true)))
        assertVerdict(Verdict.BREAKING, report)
        assertTrue(report.findings.any { it.verdict == Verdict.BREAKING && insert.id in it.affectedContractIds }, report.toString())
    }

    @Test fun `opaque omitted value cannot establish a NULL rejection`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(typeAcceptsImplicitNull = false)))
        val report = verify(DatabaseContract(queries = listOf(query(listOf("id"))), coverage = Coverage(complete = true)), old)
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `native write is not covered by another mappings non-null declaration`() {
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(declaredNonNull = true), listOf(query()))))
    }

    @Test fun `generated SQL source alone is not mapping input provenance`() {
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(declaredNonNull = true),
            listOf(query(source = QuerySource.HIBERNATE_MAPPING)))))
    }

    @Test fun `NOT VALID CHECK can reject unrelated updates to existing NULL rows`() {
        val id = ColumnReference("public.accounts", "id")
        val update = QueryContract("Account.updateId", Operation.UPDATE, "UPDATE accounts SET id = ? WHERE id = ?",
            tables = listOf("public.accounts"), columns = listOf(id), writeColumns = listOf(id),
            parameterTypes = listOf("bigint", "bigint"), dependenciesComplete = true, targetTable = "public.accounts")
        assertVerdict(Verdict.UNKNOWN, verify(DatabaseContract(queries = listOf(update), coverage = Coverage(complete = true)),
            target = table.copy(constraints = mapOf("label_not_null" to nullCheck(validated = false)))))
    }

    @Test fun `enum proof cannot borrow an unrelated generated SQL writers domain`() {
        val values = nullable.copy(enumValues = listOf("ACTIVE"), logicalJavaType = "Status")
        val check = ConstraintSchema("c", "CHECK (label IN ('ACTIVE'))", columns = listOf("label"),
            checkExpression = "label IN ('ACTIVE')")
        assertVerdict(Verdict.UNKNOWN, verify(contract(values, listOf(query(source = QuerySource.HIBERNATE_MAPPING))),
            target = table.copy(constraints = mapOf("label_values" to check))))
    }

    @Test fun `enum CHECK also examines omitted column defaults`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(defaultExpression = "'RETIRED'::text")))
        val check = ConstraintSchema("c", "CHECK (label IN ('ACTIVE'))", columns = listOf("label"),
            checkExpression = "label IN ('ACTIVE')")
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(enumValues = listOf("ACTIVE")), listOf(query(listOf("id")))),
            old = old, target = old.copy(constraints = mapOf("label_values" to check))))
    }

    @Test fun `logical byte stored as BIGINT does not establish SMALLINT overflow`() {
        val old = table.copy(columns = table.columns + ("label" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)))
        val target = old.copy(columns = old.columns + ("label" to old.columns.getValue("label").copy(type = "smallint")))
        val report = verify(contract(nullable.copy(typeName = "bigint", logicalJavaType = "java.lang.Byte")), old, target)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `exact ORM mapping provenance establishes generated non-null writes`() {
        val linked = query(source = QuerySource.HIBERNATE_MAPPING).copy(mappedEntityIds = listOf("Account"))
        assertVerdict(Verdict.SAFE, verify(contract(nullable.copy(declaredNonNull = true), listOf(linked))))
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(declaredNonNull = true),
            listOf(linked.copy(mappedEntityIds = listOf("Absent"))))))
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(declaredNonNull = true),
            listOf(linked.copy(source = QuerySource.STATIC)))))
    }

    @Test fun `exact ORM mapping provenance establishes generated finite enum writes`() {
        val linked = query(source = QuerySource.HIBERNATE_MAPPING).copy(mappedEntityIds = listOf("Account"))
        val check = ConstraintSchema("c", "CHECK (label IN ('ACTIVE'))", columns = listOf("label"),
            checkExpression = "label IN ('ACTIVE')")
        assertVerdict(Verdict.SAFE, verify(contract(nullable.copy(enumValues = listOf("ACTIVE")), listOf(linked)),
            target = table.copy(constraints = mapOf("label_values" to check))))
    }

    @Test fun `custom collation cannot fabricate an excluded enum label`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(collation = "public.case_insensitive")))
        val check = ConstraintSchema("c", "CHECK (label IN ('ACTIVE'))", columns = listOf("label"),
            checkExpression = "label IN ('ACTIVE')")
        val report = verify(contract(nullable.copy(enumValues = listOf("ACTIVE", "active"))), old,
            old.copy(constraints = mapOf("label_values" to check)))
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `an explicit unbounded string domain supplies a finite narrowing witness`() {
        val target = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(type = "character varying(5)")))
        val report = verify(contract(nullable.copy(lengthUnbounded = true)), target = target)
        assertVerdict(Verdict.BREAKING, report)
        assertTrue(report.findings.any { it.ruleId == "MAPPED_TYPE_NARROWED" && "6 characters" in it.evidence }, report.toString())
        assertVerdict(Verdict.UNKNOWN, verify(contract(nullable.copy(length = null, lengthUnbounded = false)), target = target))
    }

    @Test fun `mapped and baseline length ranges intersect before selecting a rejected value`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(type = "character varying(20)")))
        val target = old.copy(columns = old.columns + ("label" to old.columns.getValue("label").copy(type = "character varying(5)")))
        assertVerdict(Verdict.BREAKING, verify(contract(nullable.copy(length = 1000)), old, target))
    }

    @Test fun `missing baseline column does not look up a null enum key in a catalog TreeMap`() {
        val input = contract().copy(entities = listOf(mapped().copy(columns = mapped().columns + ColumnContract("absent", "text"))))
        val old = baseline.copy(enumTypes = sortedMapOf("public.status" to listOf("ACTIVE")))
        val report = CompatibilityEngine().verifyStep(input, old, old.copy(tables = mapOf("public.accounts" to notNull(table))), migration)
        assertTrue(report.findings.any { it.ruleId == "INVALID_BASELINE_COLUMN" && it.objectName == "public.accounts.absent" }, report.toString())
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `simple non-null catalog expressions reject embedded predicates`() {
        assertEquals("Mixed Name", RuleProofs.notNullCheck(ConstraintSchema("c", "", checkExpression = "((\"Mixed Name\" IS NOT NULL))")))
        for (expression in listOf("label IS NOT NULL OR id = 0", "coalesce(label, '') IS NOT NULL", "(label::text) IS NOT NULL")) {
            assertEquals(null, RuleProofs.notNullCheck(ConstraintSchema("c", "", checkExpression = expression)), expression)
        }
    }

    @Test fun `NOT VALID does not borrow an INSERT-only property domain for unrelated mapped UPDATEs`() {
        val input = contract(nullable.copy(declaredNonNull = true, updatable = false))
        assertVerdict(Verdict.UNKNOWN, verify(input,
            target = table.copy(constraints = mapOf("label_not_null" to nullCheck(validated = false)))))
    }

    @Test fun `an unverified generated baseline statement invalidates its linked mapping witness`() {
        val linked = query(source = QuerySource.HIBERNATE_MAPPING).copy(mappedEntityIds = listOf("Account"))
        val observation = Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", linked.id,
            "Baseline SQL failed", "The generated old INSERT did not prepare", listOf(linked.id))
        val report = CompatibilityEngine().verifyStep(contract(queries = listOf(linked)), baseline,
            baseline.copy(tables = mapOf("public.accounts" to notNull(table))), migration, listOf(observation))
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `dropping a column default may reveal a type-level default`() {
        val old = table.copy(columns = table.columns + ("label" to ColumnSchema("defaulted_label", false,
            defaultExpression = "'old'::text", typeAcceptsImplicitNull = false)))
        val target = old.copy(columns = old.columns + ("label" to old.columns.getValue("label").copy(defaultExpression = null)))
        val report = verify(DatabaseContract(queries = listOf(query(listOf("id"))), coverage = Coverage(complete = true)), old, target)
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `non-null UPDATE domain cannot cover the same entitys omitted INSERT default`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(defaultExpression = "NULL::text")))
        val input = contract(nullable.copy(declaredNonNull = true, insertable = false, updatable = true))
        assertVerdict(Verdict.UNKNOWN, verify(input, old))
    }

    @Test fun `enum UPDATE domain cannot cover the same entitys omitted INSERT default`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(defaultExpression = "'RETIRED'::text")))
        val input = contract(nullable.copy(enumValues = listOf("ACTIVE"), insertable = false, updatable = true))
        val check = ConstraintSchema("c", "CHECK (label IN ('ACTIVE'))", columns = listOf("label"), checkExpression = "label IN ('ACTIVE')")
        assertVerdict(Verdict.UNKNOWN, verify(input, old, old.copy(constraints = mapOf("label_values" to check))))
    }

    @Test fun `removing a baseline NULL default does not create a valid old INSERT`() {
        val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(nullable = false, defaultExpression = "NULL::text")))
        val target = old.copy(columns = old.columns + ("label" to old.columns.getValue("label").copy(defaultExpression = null)))
        val report = verify(DatabaseContract(queries = listOf(query(listOf("id"))), coverage = Coverage(complete = true)), old, target)
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `enum string-set proof does not model CHAR padding or custom text casts`() {
        for (type in listOf("character(2)", "citext", "public.custom_text")) {
            val old = table.copy(columns = table.columns + ("label" to table.columns.getValue("label").copy(type = type)))
            val check = ConstraintSchema("c", "CHECK (label::text IN ('A'))", columns = listOf("label"), checkExpression = "label::text IN ('A')")
            val report = verify(contract(nullable.copy(enumValues = listOf("A "))), old, old.copy(constraints = mapOf("label_values" to check)))
            assertVerdict(Verdict.UNKNOWN, report)
            assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
        }
    }

    @Test fun `known nextval generation requires an exact builtin expression and catalog sequence`() {
        val snapshot = baseline.copy(sequences = mapOf("public.accounts_ids" to "sequence"))
        assertTrue(RuleProofs.sequenceDefault("nextval('accounts_ids'::regclass)", "bigint", snapshot))
        assertFalse(RuleProofs.sequenceDefault("nextval('missing'::regclass)", "bigint", snapshot))
        assertFalse(RuleProofs.sequenceDefault("nextval('accounts_ids'::regclass) + 1", "bigint", snapshot))
        assertFalse(RuleProofs.sequenceDefault("nextval('accounts_ids'::regclass)", "bigint", snapshot.copy(expressionSearchPath = listOf("public", "pg_catalog"))))
    }

    @Test fun `composite IS NOT NULL is stronger than physical NOT NULL`() {
        val old = table.copy(columns = table.columns + ("label" to ColumnSchema("public.pair", false, typeAcceptsImplicitNull = true)))
        val target = old.copy(constraints = mapOf("pair_not_null" to nullCheck()))
        val input = DatabaseContract(queries = listOf(query()), coverage = Coverage(complete = true))
        val report = verify(input, old, target)
        assertVerdict(Verdict.UNKNOWN, report)
        assertFalse(report.findings.any { it.ruleId == "REDUNDANT_NOT_NULL" }, report.toString())
    }
}
