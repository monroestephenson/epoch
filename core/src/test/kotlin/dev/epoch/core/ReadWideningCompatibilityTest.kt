package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual decoder facts distinguish a representable wider result from an unknown old read domain. */
class ReadWideningCompatibilityTest {
    private val tableName = "public.read_values"
    private val id = ColumnReference(tableName, "id")
    private val amount = ColumnReference(tableName, "amount")
    private val migration = MigrationInfo("2", "V2__widen_amount.sql")
    private val table = TableSchema(
        columns = mapOf(
            "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
            "amount" to ColumnSchema("integer", false, typeAcceptsImplicitNull = true),
        ),
        relationSecurity = RelationSecurity("epoch", false, false, false, false),
    )
    private val baseline = SchemaSnapshot(
        tables = mapOf(tableName to table),
        expressionSearchPath = listOf("pg_catalog", "public"),
    )
    private val target = baseline.copy(tables = mapOf(tableName to table.copy(
        columns = table.columns + ("amount" to table.columns.getValue("amount").copy(type = "bigint")),
    )))

    private fun contract(logicalType: String, jdbcType: String): DatabaseContract {
        val entity = EntityContract(
            "ReadValue", tableName,
            listOf(
                ColumnContract("id", "BIGINT", nullable = false, logicalJavaType = "long",
                    declaredNonNull = true, hibernateNullable = false, readDomainComplete = true),
                ColumnContract("amount", jdbcType, nullable = false, logicalJavaType = logicalType,
                    declaredNonNull = true, hibernateNullable = false, readDomainComplete = true),
            ),
            operations = setOf(Operation.SELECT),
        )
        val query = QueryContract(
            "ReadValue.findById", Operation.SELECT, "select id, amount from read_values where id = ?",
            tables = listOf(tableName), columns = listOf(id, amount), parameterTypes = listOf("BIGINT"),
            source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true,
            mappedEntityIds = listOf(entity.id),
            mappedReads = listOf(MappedColumnRead(entity.id, id), MappedColumnRead(entity.id, amount)),
            mappedReadCoverageComplete = true,
        )
        return DatabaseContract(applicationVersion = "old", entities = listOf(entity), queries = listOf(query),
            coverage = Coverage(complete = true, mechanisms = listOf("Exact mapped reader fixture")))
    }

    @Test fun `standard Long decoder covers the entire widened bigint range`() {
        val report = CompatibilityEngine().verifyStep(contract("java.lang.Long", "BIGINT"), baseline, target, migration)

        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.findings.any {
            it.ruleId == "TYPE_WIDENED" && it.verdict == Verdict.SAFE && it.objectName == "$tableName.amount"
        }, report.toString())
        assertFalse(report.findings.any { it.ruleId == "COLUMN_TYPE_CHANGED" }, report.toString())
    }

    @Test fun `standard Int decoder does not cover unrestricted bigint values`() {
        // This read-only contract supplies no bounded producer invariant or final range CHECK.
        // The target admits 2147483648, which is outside the old JDBC getInt domain.
        val report = CompatibilityEngine().verifyStep(contract("java.lang.Integer", "INTEGER"), baseline, target, migration)

        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.findings.any {
            it.ruleId == "COLUMN_TYPE_CHANGED" && it.verdict == Verdict.UNKNOWN && it.objectName == "$tableName.amount"
        }, report.toString())
        assertFalse(report.findings.any { it.ruleId == "TYPE_WIDENED" }, report.toString())
        assertFalse(report.findings.any { it.verdict == Verdict.BREAKING }, report.toString())
    }

    private fun positioned(input: DatabaseContract) = input.copy(queries = input.queries.map { query ->
        query.copy(mappedReads = query.mappedReads.mapIndexed { index, read -> read.copy(resultPosition = index + 1) })
    })

    private fun writable(): DatabaseContract {
        val input = positioned(contract("java.lang.Integer", "INTEGER"))
        val entity = input.entities.single().copy(operations = setOf(Operation.SELECT, Operation.INSERT, Operation.UPDATE),
            columns = input.entities.single().columns.map { if (it.name == "id") it.copy(updatable = false) else it })
        val insert = QueryContract("ReadValue.insert", Operation.INSERT,
            "insert into read_values (id,amount) values (?,?)", tables = listOf(tableName), columns = listOf(id, amount),
            writeColumns = listOf(id, amount), parameterTypes = listOf("BIGINT", "INTEGER"),
            source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true, targetTable = tableName,
            mappedEntityIds = listOf(entity.id))
        val update = insert.copy(id = "ReadValue.update", operation = Operation.UPDATE,
            sql = "update read_values set amount=? where id=?", writeColumns = listOf(amount), parameterTypes = listOf("INTEGER", "BIGINT"))
        return input.copy(entities = listOf(entity), queries = input.queries + insert + update)
    }

    private fun proof(input: DatabaseContract, preserved: Set<ColumnReference> = emptySet(), after: SchemaSnapshot = target) =
        ReadCompatibility(input, baseline, after, (input.entities.map { it.id } + input.queries.map { it.id }).toSet(), preserved)

    @Test fun `preserved original integers and complete direct Int producers establish a bounded read invariant`() {
        val input = writable()
        assertFalse(proof(input).columnCompatible(tableName, "amount"))
        val supported = proof(input, setOf(amount))
        assertTrue(supported.columnCompatible(tableName, "amount"))
        assertTrue(supported.columnEvidence(tableName, "amount").any { it.contains("signed 32-bit range") })
        assertTrue(supported.resultCompatible(input.queries.first(),
            listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "integer")),
            listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "bigint"))))
    }

    @Test fun `a wider actual producer invalidates the old Int invariant`() {
        val input = writable()
        val writer = input.entities.single().copy(id = "LongWriter", operations = setOf(Operation.INSERT),
            columns = input.entities.single().columns.map { if (it.name == "amount")
                it.copy(logicalJavaType = "java.lang.Long", typeName = "BIGINT") else it })
        val query = input.queries.single { it.operation == Operation.INSERT }.copy(id = "LongWriter.insert",
            mappedEntityIds = listOf(writer.id), parameterTypes = listOf("BIGINT", "BIGINT"))
        val changed = input.copy(entities = input.entities + writer, queries = input.queries + query)
        assertFalse(proof(changed, setOf(amount)).columnCompatible(tableName, "amount"))
        val native = input.copy(queries = input.queries + query.copy(id = "native.insert", source = QuerySource.STATIC, mappedEntityIds = emptyList()))
        assertFalse(proof(native, setOf(amount)).columnCompatible(tableName, "amount"))
    }

    @Test fun `finite UPDATE does not cover the same entity's omitted out of range INSERT default`() {
        val input = writable().let { c -> c.copy(
            entities = c.entities.map { entity -> entity.copy(columns = entity.columns.map {
                if (it.name == "amount") it.copy(insertable = false) else it
            }) },
            queries = c.queries.map { if (it.operation == Operation.INSERT)
                it.copy(sql = "insert into read_values (id) values (?)", columns = listOf(id), writeColumns = listOf(id), parameterTypes = listOf("BIGINT")) else it },
        ) }
        fun after(value: String) = target.copy(tables = mapOf(tableName to target.tables.getValue(tableName).let { t ->
            t.copy(columns = t.columns + ("amount" to t.columns.getValue("amount").copy(defaultExpression = value)))
        }))
        assertFalse(proof(input, setOf(amount), after("2147483648::bigint")).columnCompatible(tableName, "amount"))
        assertTrue(proof(input, setOf(amount), after("12::bigint")).columnCompatible(tableName, "amount"))
    }

    @Test fun `declared mutations need actual accepted generated query coverage`() {
        val input = writable()
        assertFalse(proof(input.copy(queries = input.queries.filter { it.operation != Operation.UPDATE }), setOf(amount))
            .columnCompatible(tableName, "amount"))
        val missingLinks = input.copy(queries = input.queries.map { if (it.operation == Operation.INSERT) it.copy(mappedEntityIds = emptyList()) else it })
        assertFalse(proof(missingLinks, setOf(amount)).columnCompatible(tableName, "amount"))
    }

    @Test fun `an incoming FK action on another table cannot enlarge the widened parent value`() {
        val input = writable().let { original -> original.copy(
            entities = original.entities.map { it.copy(operations = it.operations + Operation.DELETE) },
            queries = original.queries + QueryContract("ReadValue.delete", Operation.DELETE,
                "delete from read_values where id=?", tables = listOf(tableName), columns = listOf(id),
                parameterTypes = listOf("BIGINT"), source = QuerySource.HIBERNATE_MAPPING,
                dependenciesComplete = true, targetTable = tableName, mappedEntityIds = listOf("ReadValue")),
        ) }
        val childName = "public.read_children"
        val child = TableSchema(
            columns = mapOf("parent_id" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)),
            constraints = mapOf("parent_fk" to ConstraintSchema("f",
                "FOREIGN KEY (parent_id) REFERENCES read_values(id) ON UPDATE CASCADE ON DELETE SET NULL",
                columns = listOf("parent_id"), referencedTable = tableName, referencedColumns = listOf("id"),
                onDelete = "n", onUpdate = "c")),
            relationSecurity = table.relationSecurity,
        )
        fun withChild(snapshot: SchemaSnapshot) = snapshot.copy(tables = snapshot.tables + (childName to child) +
            (tableName to snapshot.tables.getValue(tableName).copy(constraints = mapOf(
                "read_values_pk" to ConstraintSchema("p", "PRIMARY KEY (id)", columns = listOf("id"))))))
        val ids = (input.entities.map { it.id } + input.queries.map { it.id }).toSet()
        val supported = ReadCompatibility(input, withChild(baseline), withChild(target), ids, setOf(amount))

        assertTrue(supported.columnCompatible(tableName, "amount"))
        assertTrue(supported.resultCompatible(input.queries.first(),
            listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "integer")),
            listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "bigint"))))
    }

    @Test fun `an assigning FK producer blocks only the child column it can change`() {
        val parentName = "public.read_parents"
        val parentId = ColumnReference(parentName, "id")
        val parent = EntityContract("ReadParent", parentName,
            listOf(ColumnContract("id", "BIGINT", nullable = false, logicalJavaType = "java.lang.Long")),
            operations = setOf(Operation.UPDATE))
        val updateParent = QueryContract("ReadParent.update", Operation.UPDATE,
            "update read_parents set id=? where id=?", tables = listOf(parentName), columns = listOf(parentId),
            writeColumns = listOf(parentId), parameterTypes = listOf("BIGINT", "BIGINT"),
            source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true, targetTable = parentName,
            mappedEntityIds = listOf(parent.id))
        val input = writable().let { it.copy(entities = it.entities + parent, queries = it.queries + updateParent) }
        val parentTable = TableSchema(
            columns = mapOf("id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true)),
            constraints = mapOf("parent_pk" to ConstraintSchema("p", "PRIMARY KEY (id)", columns = listOf("id"))),
            relationSecurity = table.relationSecurity,
        )
        fun withKey(snapshot: SchemaSnapshot, column: String, action: String?) = snapshot.copy(
            tables = snapshot.tables + (parentName to parentTable) + (tableName to snapshot.tables.getValue(tableName).copy(
                constraints = mapOf("child_fk" to ConstraintSchema("f",
                    "FOREIGN KEY ($column) REFERENCES read_parents(id) ON UPDATE CASCADE",
                    columns = listOf(column), referencedTable = parentName, referencedColumns = listOf("id"),
                    onDelete = "a", onUpdate = action)),
            )),
        )
        val ids = (input.entities.map { it.id } + input.queries.map { it.id }).toSet()
        fun supports(column: String, action: String? = "c") = ReadCompatibility(input,
            withKey(baseline, column, action), withKey(target, column, action), ids, setOf(amount))
            .columnCompatible(tableName, "amount")

        assertTrue(supports("id"), "An implicit write to id does not enlarge the amount column.")
        assertFalse(supports("amount"), "The parent's Long update can cascade a value outside the old Int range.")
        assertFalse(supports("amount", null), "Missing referential-action facts cannot establish a bounded producer.")
    }

    @Test fun `only actual typed result positions relax an implicit mapped signature`() {
        val input = positioned(contract("java.lang.Long", "BIGINT"))
        val before = listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "integer"))
        val after = listOf(ResultColumn("id", "bigint"), ResultColumn("amount", "bigint"))
        assertTrue(proof(input).resultCompatible(input.queries.single(), before, after))
        assertFalse(proof(input).resultCompatible(input.queries.single(), before, after.reversed()))
        assertFalse(proof(input).resultCompatible(input.queries.single(), before, after + ResultColumn("extra", "text")))
        assertFalse(proof(input).resultCompatible(input.queries.single(), before, after.map { it.copy(name = "other") }))
        fun changed(query: QueryContract): DatabaseContract = input.copy(queries = listOf(query))
        for (query in listOf(
            input.queries.single().copy(exactResultShape = true),
            input.queries.single().copy(resultColumns = before),
            input.queries.single().copy(source = QuerySource.NAMED_NATIVE),
            input.queries.single().copy(mappedReads = input.queries.single().mappedReads.map { it.copy(resultPosition = null) }),
            input.queries.single().copy(mappedReads = input.queries.single().mappedReads.map { it.copy(resultPosition = 1) }),
        )) assertFalse(proof(changed(query)).resultCompatible(query, before, after), query.toString())
    }

    @Test fun `custom decoder and rejected baseline mapping never establish a read proof`() {
        val input = positioned(contract("java.lang.Long", "BIGINT"))
        val changed = input.copy(entities = input.entities.map { entity -> entity.copy(columns = entity.columns.map {
            if (it.name == "amount") it.copy(readDomainComplete = false) else it
        }) })
        assertFalse(proof(changed).columnCompatible(tableName, "amount"))
        val ids = ReadCompatibility.establishedBaselineIds(input, baseline, input.queries.map { it.id }.toSet(),
            listOf(Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", input.queries.single().id,
                "Rejected baseline", "fixture", listOf(input.queries.single().id))))
        assertFalse(ReadCompatibility(input, baseline, target, ids).columnCompatible(tableName, "amount"))
    }
}
