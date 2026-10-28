package dev.epoch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A finite mapped input domain can bound TEXT storage without pretending DDL length validates Strings. */
class FiniteEnumIndexProofTest {
    @Test fun `finite enum values bound all linked INSERT and UPDATE keys on TEXT`() {
        val table = "public.accounts"
        val mapping = EntityContract("Account", table, listOf(
            ColumnContract("id", "BIGINT", nullable = false, updatable = false, logicalJavaType = "java.lang.Long"),
            ColumnContract("status", "VARCHAR", nullable = true, enumValues = listOf("ACTIVE", "RETIRED"),
                logicalJavaType = "fixture.Status", inputDomainComplete = true, enumMode = EnumMode.STRING,
                enumConstants = listOf("ACTIVE", "RETIRED"), enumRelationalValues = listOf("ACTIVE", "RETIRED"),
                enumEncoding = EnumEncoding.NAME),
            ColumnContract("payload", "VARCHAR", nullable = true, logicalJavaType = "java.lang.String"),
        ), operations = setOf(Operation.INSERT, Operation.UPDATE))
        fun columns(vararg names: String) = names.map { ColumnReference(table, it) }
        val insert = QueryContract("Account.insert", Operation.INSERT,
            "INSERT INTO accounts(id,status,payload) VALUES (?,?,?)", tables = listOf(table),
            columns = columns("id", "status", "payload"), writeColumns = columns("id", "status", "payload"),
            parameterTypes = listOf("BIGINT", "VARCHAR", "VARCHAR"), source = QuerySource.HIBERNATE_MAPPING,
            dependenciesComplete = true, targetTable = table, mappedEntityIds = listOf(mapping.id))
        val update = QueryContract("Account.update", Operation.UPDATE,
            "UPDATE accounts SET status=?,payload=? WHERE id=?", tables = listOf(table),
            columns = columns("id", "status", "payload"), writeColumns = columns("status", "payload"),
            parameterTypes = listOf("VARCHAR", "VARCHAR", "BIGINT"), source = QuerySource.HIBERNATE_MAPPING,
            dependenciesComplete = true, targetTable = table, mappedEntityIds = listOf(mapping.id))
        val contract = DatabaseContract(entities = listOf(mapping), queries = listOf(insert, update), coverage = Coverage(complete = true))
        val original = TableSchema(columns = mapOf(
            "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
            "status" to ColumnSchema("text", true, collation = "pg_catalog.default", typeAcceptsImplicitNull = true),
            "payload" to ColumnSchema("text", true, collation = "pg_catalog.default", typeAcceptsImplicitNull = true),
        ), relationSecurity = RelationSecurity("epoch", false, false, false, false))
        val baseline = SchemaSnapshot(tables = mapOf(table to original), expressionSearchPath = listOf("pg_catalog", "public"),
            behaviorGraph = BehaviorGraph(attachmentInventoryComplete = true, nodes = emptyMap(), attachments = emptyList()))
        val indexed = original.copy(indexes = mapOf("account_status" to "CREATE INDEX account_status ON public.accounts USING btree(status); valid=true"),
            uniqueIndexes = mapOf("account_status" to false), plainIndexes = setOf("account_status"),
            indexColumns = mapOf("account_status" to listOf("status")), indexMethods = mapOf("account_status" to "btree"),
            indexValidity = mapOf("account_status" to true), indexReadiness = mapOf("account_status" to true))
        val report = CompatibilityEngine().verifyStep(contract, baseline, baseline.copy(tables = mapOf(table to indexed)),
            MigrationInfo("2", "V2__status_index.sql"))
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.findings.any { it.ruleId == "NON_UNIQUE_INDEX_CHANGED" && it.verdict == Verdict.SAFE }, report.toString())
    }
}
