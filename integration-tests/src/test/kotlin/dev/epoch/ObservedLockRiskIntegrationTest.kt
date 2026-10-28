package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Lock mode, target identity and origin come from a real owned migration connection. */
class ObservedLockRiskIntegrationTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()
    private val contract = DatabaseContract(applicationVersion = "observed-lock-risk",
        queries = listOf(QueryContract("control.read", Operation.SELECT, "select id from control",
            tables = listOf("public.control"), columns = listOf(ColumnReference("public.control", "id")),
            dependenciesComplete = true)), coverage = Coverage(complete = true))
    private fun verify(schema: String, changes: List<String>, sizes: TableSizes? = null,
                       autocommit: Set<Int> = emptySet()): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE control(id bigint PRIMARY KEY);\n$schema")
        val pending = directory.resolve("pending").createDirectories()
        changes.forEachIndexed { index, sql ->
            val filename = "V${index + 2}__change.sql"
            pending.resolve(filename).writeText(sql)
            if (index in autocommit) pending.resolve("$filename.conf").writeText("executeInTransaction=false\n")
        }
        return PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 2, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 120, tableSizes = sizes))
    }
    private fun statements(report: CompatibilityReport, step: Int) = requireNotNull(report.migrations[step].operational).statements
    private fun risks(statement: OperationalStatementReport) = statement.findings.filter { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }

    @Test @Timeout(150)
    fun `autocommit nullable ADD requires a blocking lock even though its after sample is empty`() {
        val report = verify("CREATE TABLE accounts(id bigint PRIMARY KEY);",
            listOf("ALTER TABLE accounts ADD COLUMN metadata text;"), autocommit = setOf(0))
        val statement = statements(report, 0).single()
        assertTrue(statement.autoCommit)
        assertTrue(statement.heldLocksBefore.none { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" })
        assertTrue(statement.heldLocksAfter.none { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" })
        assertEquals(OperationalVerdict.RISKY, statement.verdict, statement.toString())
        val finding = risks(statement).single { it.objectName == "public.accounts" }
        assertTrue(finding.evidence.contains("required", ignoreCase = true), finding.toString())
        assertTrue(finding.evidence.contains("AccessExclusiveLock"), finding.toString())
    }

    @Test @Timeout(150)
    fun `DROP retains measured dropped-child identity and reports its old FK parent lock with supplied sizes`() {
        val report = verify("""
            CREATE TABLE parent_table(id bigint PRIMARY KEY);
            CREATE TABLE child_table(id bigint PRIMARY KEY, parent_id bigint REFERENCES parent_table(id));
        """.trimIndent(), listOf("DROP TABLE child_table;"), TableSizes(tables = mapOf(
            "public.child_table" to TableSize(20, 4096),
            "public.parent_table" to TableSize(2_000_000, 2_147_483_648),
        )))
        val statement = statements(report, 0).single()
        assertEquals(OperationalTimeoutOrigin.VERIFIER, statement.lockTimeoutBefore.origin)
        assertTrue(statement.findings.any { it.ruleId == "OP_STATEMENT_UNSUPPORTED" && it.verdict == OperationalVerdict.UNKNOWN })
        assertEquals(OperationalVerdict.RISKY, statement.verdict, statement.toString())
        for (name in listOf("public.child_table", "public.parent_table")) {
            assertTrue(statement.heldLocksAfter.any { it.relation == name && it.mode == "AccessExclusiveLock" &&
                it.table?.preExisting == true && it.table?.baselineName == name }, statement.toString())
        }
        val byTable = risks(statement).associateBy { it.objectName }
        assertEquals(OperationalSeverity.LOW, byTable.getValue("public.child_table").severity)
        assertEquals(OperationalSeverity.HIGH, byTable.getValue("public.parent_table").severity)
        val encoded = json.valueToTree<JsonNode>(statement)
        assertEquals(setOf("public.child_table", "public.parent_table"), encoded.path("findings")
            .filter { it.path("ruleId").asText() == "OP_WRITE_BLOCKING_LOCK" }.map { it.path("objectName").asText() }.toSet())
    }

    @Test @Timeout(150)
    fun `actual timeout provenance separates ordinary protected metadata from unprotected and opaque locking`() {
        val report = verify("CREATE TABLE accounts(id bigint PRIMARY KEY);", listOf(
            "ALTER TABLE accounts ADD COLUMN unprotected text;",
            "SET LOCAL lock_timeout='250ms'; ALTER TABLE accounts ADD COLUMN protected text;",
            "SET SESSION lock_timeout='250ms'; DO ${'$'}${'$'} BEGIN LOCK TABLE accounts IN ACCESS EXCLUSIVE MODE; END ${'$'}${'$'};",
            "SET LOCAL lock_timeout=0; ALTER TABLE accounts ADD COLUMN disabled text;",
            "SET SESSION lock_timeout='250ms'; CREATE INDEX accounts_lookup ON accounts(id);",
        ))
        val unprotected = statements(report, 0).single()
        assertEquals(OperationalTimeoutOrigin.VERIFIER, unprotected.lockTimeoutBefore.origin)
        assertTrue(risks(unprotected).isNotEmpty(), unprotected.toString())
        val bounded = statements(report, 1).last()
        assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, bounded.lockTimeoutBefore.origin)
        assertEquals(250L, bounded.lockTimeoutBefore.milliseconds)
        assertEquals(OperationalVerdict.SAFE, bounded.verdict, bounded.toString())
        assertTrue(risks(bounded).isEmpty())
        val procedure = statements(report, 2).last()
        assertEquals(OperationalTimeoutOrigin.MIGRATION_SESSION, procedure.lockTimeoutBefore.origin)
        assertTrue(risks(procedure).isNotEmpty(), "An opaque body cannot establish that the timeout remained enabled before its lock.")
        assertTrue(procedure.findings.any { it.ruleId == "OP_STATEMENT_UNSUPPORTED" && it.verdict == OperationalVerdict.UNKNOWN })
        val disabled = statements(report, 3).last()
        assertEquals(0L, disabled.lockTimeoutBefore.milliseconds)
        assertTrue(risks(disabled).isNotEmpty(), disabled.toString())
        val index = statements(report, 4).last()
        assertEquals(OperationalTimeoutOrigin.MIGRATION_SESSION, index.lockTimeoutBefore.origin)
        assertEquals(OperationalVerdict.RISKY, index.verdict)
        assertTrue(index.findings.any { it.ruleId == "OP_INDEX_BUILD_BLOCKS_WRITES" }, index.toString())
    }

    @Test @Timeout(150)
    fun `renaming then dropping a table retains its original identity for size grading`() {
        val report = verify("""
            CREATE TABLE parent_table(id bigint PRIMARY KEY);
            CREATE TABLE child_table(id bigint PRIMARY KEY, parent_id bigint REFERENCES parent_table(id));
        """.trimIndent(), listOf("ALTER TABLE child_table RENAME TO archived_child; DROP TABLE archived_child;"),
            TableSizes(tables = mapOf(
                "public.child_table" to TableSize(20, 4096),
                "public.archived_child" to TableSize(9_000_000, 9_000_000_000),
                "public.parent_table" to TableSize(2_000_000, 2_147_483_648),
            )))
        val dropped = statements(report, 0).last()
        val childLock = dropped.heldLocksAfter.single { it.relation == "public.archived_child" && it.mode == "AccessExclusiveLock" }
        assertEquals("public.child_table", childLock.table?.baselineName)
        assertEquals(true, childLock.table?.preExisting)
        val childRisk = risks(dropped).single { it.objectName == "public.archived_child" }
        assertEquals(OperationalSeverity.LOW, childRisk.severity,
            "The new display name must not change the baseline estimate lookup.")
        assertTrue(childRisk.evidence.contains("public.child_table"))
        assertTrue(dropped.findings.any { it.verdict == OperationalVerdict.UNKNOWN }, dropped.toString())
        assertEquals(OperationalVerdict.RISKY, dropped.verdict)
    }
}
