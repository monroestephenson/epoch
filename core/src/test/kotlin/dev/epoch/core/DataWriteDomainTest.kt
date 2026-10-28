package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataWriteDomainTest {
    private val relation = "public.accounts"
    private val candidate = ColumnReference(relation, "state")
    private val id = ColumnContract("id", "bigint", nullable = false, declaredNonNull = true,
        hibernateNullable = false, logicalJavaType = "long")
    private val state = ColumnContract("state", "varchar", nullable = true, declaredNonNull = false,
        hibernateNullable = true, logicalJavaType = "State", enumValues = listOf("OPEN", "ARCHIVED"),
        enumConstants = listOf("OPEN", "ARCHIVED"), enumRelationalValues = listOf("OPEN", "ARCHIVED"), enumEncoding = EnumEncoding.NAME)
    private val entity = EntityContract("Account", relation, listOf(id, state))
    private val query = QueryContract("Account.insert", Operation.INSERT,
        "insert into accounts (id,state) values (?,?)", tables = listOf(relation),
        columns = listOf(ColumnReference(relation, "id"), candidate),
        writeColumns = listOf(ColumnReference(relation, "id"), candidate), source = QuerySource.HIBERNATE_MAPPING,
        dependenciesComplete = true, targetTable = relation, mappedEntityIds = listOf(entity.id))
    private val table = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "state" to ColumnSchema("character varying(30)", true, collation = "pg_catalog.default", typeAcceptsImplicitNull = true),
    ), relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val snapshot = SchemaSnapshot(mapOf(relation to table), expressionSearchPath = listOf("pg_catalog", "public"))
    private fun check(value: String? = "ARCHIVED", changed: TableSchema = table, mapped: EntityContract = entity,
                      sql: QueryContract = query, schema: SchemaSnapshot = snapshot) = DataWriteDomain.assess(
        DatabaseContract(entities = listOf(mapped), queries = listOf(sql)), sql, candidate, value,
        schema.copy(tables = mapOf(relation to changed)))
    private fun enumCheck(column: String = "state", labels: String = "'OPEN', 'ARCHIVED'") = ConstraintSchema(
        "c", "CHECK ($column IN ($labels))", columns = listOf(column), checkExpression = "$column IN ($labels)")

    @Test fun `ordinary enum and null row domains have positive witnesses`() {
        assertEquals(true, check().accepted)
        assertEquals(true, check(null).accepted)
        assertTrue(check().evidence.contains("not an executed INSERT"))
    }

    @Test fun `ordinary ordinal row domain is admitted by smallint`() {
        val ordinal = state.copy(typeName = "smallint", enumValues = listOf("0", "1"),
            enumRelationalValues = listOf("0", "1"), enumEncoding = EnumEncoding.ORDINAL)
        val changed = table.copy(columns = table.columns + ("state" to ColumnSchema("smallint", true, typeAcceptsImplicitNull = true)))
        assertEquals(true, check("1", changed, entity.copy(columns = listOf(id, ordinal))).accepted)
    }

    @Test fun `canonical ordinal assignment to varchar retains its admitted stored text`() {
        val ordinal = state.copy(typeName = "smallint", enumValues = listOf("0", "1"),
            enumRelationalValues = listOf("0", "1"), enumEncoding = EnumEncoding.ORDINAL)
        assertEquals(true, check("0", mapped = entity.copy(columns = listOf(id, ordinal))).accepted)
    }

    @Test fun `final NOT NULL excludes null before unsupported UPDATE row construction`() {
        val changed = table.copy(columns = table.columns + ("state" to table.columns.getValue("state").copy(nullable = false)))
        assertEquals(false, check(null, changed, sql = query.copy(operation = Operation.UPDATE)).accepted)
        assertNull(check(sql = query.copy(operation = Operation.UPDATE)).accepted)
        assertTrue(DataWriteDomain.excludesNull(candidate, snapshot.copy(tables = mapOf(relation to changed))))
        val checked = table.copy(constraints = mapOf("required" to ConstraintSchema("c", "CHECK (state IS NOT NULL)",
            checkExpression = "state IS NOT NULL")))
        assertTrue(DataWriteDomain.excludesNull(candidate, snapshot.copy(tables = mapOf(relation to checked))))
        assertEquals(false, DataWriteDomain.excludesNull(candidate, snapshot))
    }

    @Test fun `final enum CHECK excludes the candidate even with unrelated unsupported details`() {
        val changed = table.copy(constraints = mapOf("allowed" to enumCheck(labels = "'OPEN'"),
            "opaque" to ConstraintSchema("c", "CHECK (some_function(id))", checkExpression = "some_function(id)")))
        assertEquals(false, check(changed = changed).accepted)
    }

    @Test fun `contradictory and cross-column unrelated CHECKs prevent whole-row admission`() {
        for (expression in listOf("false", "id > length(state)", "state IS NULL OR id = 7")) {
            val changed = table.copy(constraints = mapOf("unrelated" to ConstraintSchema("c", "CHECK ($expression)", checkExpression = expression)))
            assertNull(check(changed = changed).accepted)
        }
    }

    @Test fun `all independent CHECKs must have a compatible value for other fields`() {
        val extra = ColumnContract("category", "text", nullable = false, declaredNonNull = true,
            hibernateNullable = false, logicalJavaType = "Category", enumRelationalValues = listOf("A"), enumValues = listOf("A"))
        val changed = table.copy(columns = table.columns + ("category" to ColumnSchema("text", false,
            collation = "pg_catalog.default", typeAcceptsImplicitNull = true)),
            constraints = mapOf("category_allowed" to enumCheck("category", "'B'")))
        val extraRef = ColumnReference(relation, "category")
        assertNull(check(changed = changed, mapped = entity.copy(columns = entity.columns + extra),
            sql = query.copy(writeColumns = query.writeColumns + extraRef)).accepted)
    }

    @Test fun `an omitted required column or opaque default prevents a row witness`() {
        val required = ColumnSchema("text", false, typeAcceptsImplicitNull = true)
        for (column in listOf(required, required.copy(defaultExpression = "application_value()"))) {
            val changed = table.copy(columns = table.columns + ("extra" to column))
            assertNull(check(changed = changed).accepted)
        }
        assertEquals(true, check(changed = table.copy(columns = table.columns +
            ("extra" to required.copy(defaultExpression = "'fixed'::text")))).accepted)
    }

    @Test fun `foreign keys and exclusion constraints do not become accepted row proofs`() {
        for (type in listOf("f", "x")) assertNull(check(changed = table.copy(constraints = mapOf("constraint" to
            ConstraintSchema(type, "unsupported constraint", columns = listOf("id"))))).accepted)
    }

    @Test fun `custom writers and table behavior remain unknown`() {
        assertNull(check(mapped = entity.copy(columns = listOf(id, state.copy(inputDomainComplete = false)))).accepted)
        assertNull(check(changed = table.copy(relationSecurity = table.relationSecurity!!.copy(userTriggers = true))).accepted)
        assertNull(check(changed = table.copy(relationSecurity = null)).accepted)
        assertNull(check(changed = table.copy(kind = "view")).accepted)
    }

    @Test fun `missing catalog type proof and unknown type remain unknown`() {
        assertNull(check(changed = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(typeAcceptsImplicitNull = null)))).accepted)
        assertNull(check(changed = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(type = "custom_type")))).accepted)
    }

    @Test fun `physical limits exclude candidate and native enum labels are authoritative`() {
        assertEquals(false, check(changed = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(type = "character varying(2)")))).accepted)
        val native = table.copy(columns = table.columns + ("state" to table.columns.getValue("state").copy(type = "public.state")))
        assertEquals(false, check(changed = native, schema = snapshot.copy(enumTypes = mapOf("public.state" to listOf("OPEN")))).accepted)
        assertEquals(true, check(changed = native, schema = snapshot.copy(enumTypes = mapOf("public.state" to listOf("OPEN", "ARCHIVED")))).accepted)
    }

    @Test fun `varchar trailing spaces can be truncated and do not establish rejection`() {
        val changed = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(type = "character varying(4)")))
        assertNull(check("OPEN  ", changed).accepted)
        assertEquals(false, check("OPENED", changed).accepted)
    }

    @Test fun `numeric rounding is unknown while exact overflow is excluded`() {
        val changed = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(type = "numeric(3,1)")))
        assertNull(check("1.25", changed).accepted)
        assertEquals(false, check("125", changed).accepted)
        val zero = state.copy(enumValues = listOf("0"), enumRelationalValues = listOf("0"), enumEncoding = EnumEncoding.ORDINAL)
        val fractional = table.copy(columns = table.columns +
            ("state" to table.columns.getValue("state").copy(type = "numeric(2,4)")))
        assertEquals(true, check("0", fractional, entity.copy(columns = listOf(id, zero))).accepted)
    }

    @Test fun `read-only mapping and guessed SQL provenance cannot establish a writer`() {
        assertNull(check(mapped = entity.copy(operations = setOf(Operation.SELECT))).accepted)
        assertNull(check(sql = query.copy(mappedEntityIds = emptyList())).accepted)
        assertNull(check(sql = query.copy(source = QuerySource.STATIC)).accepted)
        assertNull(check(sql = query.copy(dependenciesComplete = false)).accepted)
    }

    @Test fun `actual discriminator literal supplies an otherwise unmapped physical column`() {
        val column = ColumnReference(relation, "dtype")
        val changed = table.copy(columns = table.columns + ("dtype" to ColumnSchema("character varying(20)", false,
            collation = "pg_catalog.default", typeAcceptsImplicitNull = true)))
        val sql = query.copy(writeColumns = query.writeColumns + column,
            discriminatorWrites = listOf(DiscriminatorWrite(entity.id, column, "CAT")))
        val result = DataWriteDomain.assess(DatabaseContract(entities = listOf(entity), queries = listOf(sql)),
            sql, column, "CAT", snapshot.copy(tables = mapOf(relation to changed)))
        assertEquals(true, result.accepted, result.evidence)
        assertNull(DataWriteDomain.assess(DatabaseContract(entities = listOf(entity), queries = listOf(sql)),
            sql.copy(discriminatorWrites = emptyList()), column, "CAT", snapshot.copy(tables = mapOf(relation to changed))).accepted)
    }

    @Test fun `bounded actual btree row values are supported but opaque indexes are not`() {
        val indexed = table.copy(indexes = mapOf("idx" to "CREATE INDEX idx ON accounts (state)"),
            plainIndexes = setOf("idx"), indexMethods = mapOf("idx" to "btree"), indexColumns = mapOf("idx" to listOf("state")))
        assertEquals(true, check(changed = indexed).accepted)
        assertNull(check(changed = indexed.copy(plainIndexes = emptySet())).accepted)
    }

    @Test fun `unrelated helper functions do not prevent ordinary direct writes`() {
        assertEquals(true, check(schema = snapshot.copy(behaviors = mapOf("function:public.helper()" to "opaque function body"))).accepted)
    }
}
