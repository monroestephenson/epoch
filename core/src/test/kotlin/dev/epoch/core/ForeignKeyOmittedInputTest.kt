package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForeignKeyOmittedInputTest {
    private val migration = MigrationInfo("2", "V2__foreign_key.sql")
    private val id = ColumnReference("public.accounts", "id")
    private val insert = QueryContract("Account.insert", Operation.INSERT, "INSERT INTO accounts (id) VALUES (?)",
        tables = listOf("public.accounts"), columns = listOf(id), writeColumns = listOf(id),
        parameterTypes = listOf("bigint"), dependenciesComplete = true, targetTable = "public.accounts")
    private val contract = DatabaseContract(queries = listOf(insert), coverage = Coverage(complete = true))
    private val security = RelationSecurity("epoch", false, false, false, false)
    private val accounts = TableSchema(columns = mapOf("id" to ColumnSchema("bigint", false)), relationSecurity = security)
    private val baseline = SchemaSnapshot(tables = mapOf(
        "public.accounts" to accounts,
        "public.regions" to TableSchema(columns = mapOf(
            "id" to ColumnSchema("bigint", false), "code" to ColumnSchema("bigint", false))),
    ))

    private fun verify(columns: Map<String, ColumnSchema>, full: Boolean = false, input: DatabaseContract = contract,
                       targetSecurity: RelationSecurity? = security): MigrationReport {
        val referenced = listOf("id", "code").take(columns.size)
        val foreignKey = ConstraintSchema("f",
            "FOREIGN KEY (${columns.keys.joinToString()}) REFERENCES regions(${referenced.joinToString()})" + if (full) " MATCH FULL" else "",
            columns = columns.keys.toList(), referencedTable = "public.regions",
            referencedColumns = referenced)
        val after = baseline.copy(tables = baseline.tables + ("public.accounts" to accounts.copy(
            columns = accounts.columns + columns, constraints = mapOf("account_region" to foreignKey), relationSecurity = targetSecurity)))
        return CompatibilityEngine().verifyStep(input, baseline, after, migration)
    }

    private fun assertOmittedInputRisk(report: MigrationReport, expectedIds: List<String> = listOf(insert.id)) {
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        val finding = report.findings.single { it.ruleId == "CONSTRAINT_CHANGED" }
        assertEquals(expectedIds, finding.affectedContractIds, report.toString())
    }

    @Test fun `constant FK default makes an omitting SQL insert relevant`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true, "0"))))
    }

    @Test fun `constant FK default makes an omitting mapped insert relevant`() {
        val mapped = DatabaseContract(entities = listOf(EntityContract("Account", "public.accounts",
            listOf(ColumnContract("id", "bigint", false)), operations = setOf(Operation.INSERT))), coverage = Coverage(complete = true))
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true, "0")), input = mapped), listOf("Account"))
    }

    @Test fun `all omitted NULL components are safe even for MATCH FULL`() {
        val nullable = ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)
        val report = verify(mapOf("region_id" to nullable, "region_code" to nullable), full = true)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertFalse(report.findings.any { it.ruleId == "CONSTRAINT_CHANGED" }, report.toString())
    }

    @Test fun `a partial NULL cannot neutralize other MATCH FULL defaulted components`() {
        assertOmittedInputRisk(verify(mapOf(
            "region_id" to ColumnSchema("bigint", true, "0", typeAcceptsImplicitNull = true),
            "region_code" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)), full = true))
    }

    @Test fun `a domain with no column default does not establish an omitted NULL value`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("region_key", true, typeAcceptsImplicitNull = false))))
    }

    @Test fun `missing type facts cannot prove omitted FK inputs are NULL`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true))))
    }

    @Test fun `unknown relation behavior cannot prove omitted FK inputs are NULL`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)),
            targetSecurity = null))
    }

    @Test fun `extension-owned trigger fact blocks omitted NULL exemption even without a behavior entry`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)),
            targetSecurity = security.copy(userTriggers = true)))
    }

    @Test fun `positional INSERT is not exempted by omitted NULL inference`() {
        val positional = insert.copy(sql = "INSERT INTO accounts VALUES (?)", explicitColumnList = false)
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", true, typeAcceptsImplicitNull = true)),
            input = contract.copy(queries = listOf(positional))))
    }

    @Test fun `identity generation is not an omitted NULL FK value`() {
        assertOmittedInputRisk(verify(mapOf("region_id" to ColumnSchema("bigint", false, identity = "d"))))
    }

    @Test fun `sequence-generated mapped identifier remains a supplied FK input`() {
        val mapped = DatabaseContract(entities = listOf(EntityContract("Account", "public.accounts",
            listOf(ColumnContract("id", "bigint", false, generated = true)), operations = setOf(Operation.INSERT),
            identifierSequence = "public.account_ids")), coverage = Coverage(complete = true))
        val old = baseline.copy(sequences = mapOf("public.account_ids" to "bigint:1:1:999:1:false:1"))
        val foreignKey = ConstraintSchema("f", "FOREIGN KEY (id) REFERENCES regions(id)", columns = listOf("id"),
            referencedTable = "public.regions", referencedColumns = listOf("id"))
        val after = old.copy(tables = old.tables + ("public.accounts" to accounts.copy(constraints = mapOf("account_region" to foreignKey))))
        assertOmittedInputRisk(CompatibilityEngine().verifyStep(mapped, old, after, migration), listOf("Account"))
    }

    @Test fun `omitted FK defaults do not affect unrelated updates on the referencing table`() {
        val update = insert.copy(id = "Account.update", operation = Operation.UPDATE,
            sql = "UPDATE accounts SET id = ? WHERE id = ?", parameterTypes = listOf("bigint", "bigint"))
        val report = verify(mapOf("region_id" to ColumnSchema("bigint", true, "0")), input = contract.copy(queries = listOf(update)))
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.findings.none { it.ruleId == "CONSTRAINT_CHANGED" }, report.toString())
    }
}
