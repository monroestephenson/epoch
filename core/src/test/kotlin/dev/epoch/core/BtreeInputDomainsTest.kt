package dev.epoch.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BtreeInputDomainsTest {
    private val name = "public.accounts"
    private val index = "status_index"
    private fun refs(vararg names: String) = names.map { ColumnReference(name, it) }
    private val status = ColumnContract("status", "VARCHAR", nullable = true, enumValues = listOf("OPEN", "CLOSED"),
        enumConstants = listOf("OPEN", "CLOSED"), enumRelationalValues = listOf("OPEN", "CLOSED"),
        logicalJavaType = "fixture.Status", enumMode = EnumMode.STRING, enumEncoding = EnumEncoding.NAME)
    private val entity = EntityContract("Account", name, listOf(
        ColumnContract("id", "BIGINT", nullable = false, updatable = false), status,
        ColumnContract("payload", "VARCHAR", nullable = true, logicalJavaType = "java.lang.String")),
        operations = setOf(Operation.INSERT, Operation.UPDATE))
    private val insert = QueryContract("Account.insert", Operation.INSERT, "INSERT INTO accounts(id,status,payload) VALUES(?,?,?)",
        tables = listOf(name), columns = refs("id", "status", "payload"), writeColumns = refs("id", "status", "payload"),
        source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true, targetTable = name, mappedEntityIds = listOf(entity.id))
    private val update = QueryContract("Account.update", Operation.UPDATE, "UPDATE accounts SET status=?,payload=? WHERE id=?",
        tables = listOf(name), columns = refs("id", "status", "payload"), writeColumns = refs("status", "payload"),
        source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true, targetTable = name, mappedEntityIds = listOf(entity.id))
    private val contract = DatabaseContract(entities = listOf(entity), queries = listOf(insert, update), coverage = Coverage(complete = true))
    private val table = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "status" to ColumnSchema("text", true, typeAcceptsImplicitNull = true),
        "payload" to ColumnSchema("text", true, typeAcceptsImplicitNull = true)),
        indexes = mapOf(index to "CREATE INDEX status_index ON accounts USING btree(status); valid=true"),
        uniqueIndexes = mapOf(index to false), plainIndexes = setOf(index), indexMethods = mapOf(index to "btree"),
        indexColumns = mapOf(index to listOf("status")), indexValidity = mapOf(index to true), indexReadiness = mapOf(index to true),
        relationSecurity = RelationSecurity("epoch", false, false, false, false))

    private fun withStatus(column: ColumnContract) = contract.copy(entities = listOf(entity.copy(columns = entity.columns.map {
        if (it.name == "status") column else it
    })))
    private fun assess(input: DatabaseContract = contract, target: TableSchema = table,
                       facts: (QueryContract) -> QueryBehaviorFacts = { QueryBehaviorFacts(complete = true, defaultAssignmentsComplete = true) },
                       rejected: Set<String> = emptySet(), effects: (QueryContract) -> Boolean = { true }): BtreeInputDomains.Assessment {
        val ids = input.entities.map { it.id }.toSet() + input.queries.map { it.id } - rejected
        return BtreeInputDomains(input, ids, facts).assess(name, index,
            SchemaSnapshot(tables = mapOf(name to target), expressionSearchPath = listOf("pg_catalog", "public")), effects)
    }

    @Test fun `finite values establish separate INSERT and UPDATE upper bounds`() {
        val result = assess()
        assertTrue(result.proven, result.toString())
        assertEquals(setOf(Operation.INSERT, Operation.UPDATE), result.proofs.map { it.operation }.toSet())
        assertTrue(result.proofs.all { it.maximumTupleBytes == 54L }, result.toString())
        assertTrue(result.summary().contains("maximum bounded tuple bytes=54"), result.summary())
    }

    @Test fun `actual explicit relational codes determine size rather than Java enum names`() {
        val short = assess(withStatus(status.copy(enumValues = listOf("a", "b"), enumRelationalValues = listOf("a", "b"),
            enumConstants = listOf("VERY_LONG_JAVA_CONSTANT_NAME", "ANOTHER_LONG_CONSTANT"), enumEncoding = EnumEncoding.EXPLICIT)))
        assertTrue(short.proven, short.toString())
        assertEquals(49L, short.proofs.first().maximumTupleBytes)
        assertFalse(assess(withStatus(status.copy(enumValues = listOf("x".repeat(1990)),
            enumConstants = listOf("X"), enumEncoding = EnumEncoding.EXPLICIT))).proven)
    }

    @Test fun `native writers cannot borrow another mappings finite values`() {
        val native = insert.copy(id = "Native.insert", source = QuerySource.STATIC, mappedEntityIds = emptyList())
        val result = assess(contract.copy(queries = contract.queries + native))
        assertFalse(result.proven, result.toString())
        assertEquals(listOf(native.id), result.unprovenContractIds)
        assertFalse(assess(contract.copy(queries = listOf(insert.copy(mappedEntityIds = emptyList()), update))).proven)
    }

    @Test fun `DDL length on unrestricted TEXT is not a runtime bound`() {
        val string = status.copy(enumValues = emptyList(), enumRelationalValues = emptyList(), enumConstants = emptyList(),
            enumMode = null, enumEncoding = null, logicalJavaType = "java.lang.String", length = 20)
        assertFalse(assess(withStatus(string)).proven)
    }

    @Test fun `every declared operation needs its actual generated SQL and valid provenance`() {
        assertFalse(assess(contract.copy(queries = listOf(update))).proven)
        assertFalse(assess(rejected = setOf(insert.id)).proven)
        assertFalse(assess(rejected = setOf(entity.id)).proven)
        assertFalse(assess(contract.copy(entities = listOf(entity, entity))).proven)
        assertFalse(assess(contract.copy(queries = listOf(insert, insert, update))).proven)
    }

    @Test fun `omitted INSERT is bounded separately from finite UPDATE values`() {
        val omitted = insert.copy(sql = "INSERT INTO accounts(id,payload) VALUES(?,?)", writeColumns = refs("id", "payload"))
        val input = withStatus(status.copy(insertable = false)).copy(queries = listOf(omitted, update))
        for (default in listOf(null, "NULL::text", "'short'::text")) {
            val target = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(defaultExpression = default)))
            assertTrue(assess(input, target).proven, "default=$default")
        }
        for (default in listOf("repeat('x', 10000)", "custom_default()")) {
            val target = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(defaultExpression = default)))
            assertFalse(assess(input, target).proven, "default=$default")
        }
        val domain = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(typeAcceptsImplicitNull = false)))
        assertFalse(assess(input, domain).proven)
    }

    @Test fun `explicit DEFAULT does not use a finite supplied parameter domain`() {
        val input = contract.copy(queries = listOf(insert.copy(sql = "INSERT INTO accounts(id,status,payload) VALUES(?,DEFAULT,?)"), update))
        val target = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(defaultExpression = "custom_default()")))
        val result = assess(input, target, facts = { query -> QueryBehaviorFacts(complete = true, defaultAssignmentsComplete = true,
            defaultColumns = if (query.operation == Operation.INSERT) refs("status").toSet() else emptySet()) })
        assertFalse(result.proven, result.toString())
    }

    @Test fun `complete unrelated UPDATE preserves the existing valid whole index tuple`() {
        val native = update.copy(id = "Native.payload", source = QuerySource.STATIC, mappedEntityIds = emptyList(),
            sql = "UPDATE accounts SET payload=? WHERE id=?", writeColumns = refs("payload"))
        val result = assess(contract.copy(queries = contract.queries + native))
        assertTrue(result.proven, result.toString())
        assertNull(result.proofs.single { it.contractId == native.id }.maximumTupleBytes)
        assertFalse(assess(contract.copy(queries = contract.queries + native.copy(dependenciesComplete = false))).proven)
        assertFalse(assess(target = table.copy(indexValidity = emptyMap())).proven)
        assertFalse(assess(target = table.copy(indexReadiness = mapOf(index to false))).proven)
        val generated = table.copy(columns = table.columns +
            ("status" to table.columns.getValue("status").copy(generated = "s", defaultExpression = "repeat(payload, 1000)")))
        assertFalse(assess(contract.copy(entities = emptyList(), queries = listOf(native)), generated).proven,
            "An unassigned generated index column can change when its source column is updated")
    }

    @Test fun `changing one key cannot bound a retained unbounded INCLUDE value`() {
        val input = contract.copy(entities = listOf(entity.copy(operations = setOf(Operation.UPDATE))),
            queries = listOf(update.copy(sql = "UPDATE accounts SET status=? WHERE id=?", writeColumns = refs("status"))))
        val target = table.copy(indexColumns = mapOf(index to listOf("status", "payload")))
        val result = assess(input, target)
        assertFalse(result.proven, result.toString())
        assertTrue(result.reasons.any { it.contains("payload") && it.contains("retained-value") }, result.toString())
        val bounded = target.copy(columns = target.columns + ("payload" to target.columns.getValue("payload").copy(type = "varchar(20)")))
        assertTrue(assess(input, bounded).proven)
    }

    @Test fun `all key and INCLUDE columns contribute to one aggregate bound including repeated columns`() {
        val target = table.copy(indexColumns = mapOf(index to listOf("status", "payload")),
            columns = table.columns + ("payload" to table.columns.getValue("payload").copy(type = "varchar(255)")))
        assertFalse(assess(withStatus(status.copy(enumValues = listOf("x".repeat(1000)))), target).proven)
        assertTrue(assess(withStatus(status.copy(enumValues = listOf("x".repeat(100)))), target).proven)
        val repeated = table.copy(indexColumns = mapOf(index to List(10) { "status" }))
        assertFalse(assess(withStatus(status.copy(enumValues = listOf("x".repeat(200)))), repeated).proven)
    }

    @Test fun `UTF8 and malformed representations never borrow Java character counts`() {
        assertTrue(assess(withStatus(status.copy(enumValues = listOf("é".repeat(900))))).proven)
        assertFalse(assess(withStatus(status.copy(enumValues = listOf("é".repeat(1000))))).proven)
        assertFalse(assess(withStatus(status.copy(enumValues = listOf("\uD800")))).proven)
        val padded = table.copy(columns = table.columns + ("status" to table.columns.getValue("status").copy(type = "character(1000)")))
        assertFalse(assess(target = padded).proven)
    }

    @Test fun `opaque conversion generation and local effects retain UNKNOWN`() {
        for (column in listOf(status.copy(inputDomainComplete = false), status.copy(converterClass = "CustomConverter"),
            status.copy(generated = true), status.copy(typeName = "OTHER"))) assertFalse(assess(withStatus(column)).proven)
        assertFalse(assess(effects = { false }).proven)
        assertFalse(assess(facts = { QueryBehaviorFacts(complete = true) }).proven)
        assertFalse(assess(target = table.copy(relationSecurity = table.relationSecurity!!.copy(userTriggers = true))).proven)
    }

    @Test fun `physical bounds reject huge literals before arithmetic can overflow`() {
        for (type in listOf("varchar(9223372036854775807)", "varchar(18446744073709551616)",
            "numeric(9223372036854775807)", "numeric(18446744073709551616)")) {
            assertNull(RuleProofs.btreeColumnBytes(type), type)
            assertFalse(RuleProofs.boundedBtreeKey(table.copy(columns = table.columns +
                ("status" to table.columns.getValue("status").copy(type = type))), index), type)
        }
    }
}
