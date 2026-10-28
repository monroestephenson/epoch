package dev.epoch

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

class GuardedExpansionIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(150)
    fun `guarded nullable expansion and actual no-ops preserve identities and local NULL proof`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__accounts.sql").writeText("""
            CREATE TABLE accounts(id bigint PRIMARY KEY, label text CHECK (length(label) > 0));
            INSERT INTO accounts(id) VALUES (1);
        """.trimIndent())
        val migrations = directory.resolve("pending").createDirectories()
        listOf(
            "ALTER TABLE IF EXISTS public.accounts ADD COLUMN IF NOT EXISTS metadata text;",
            // PostgreSQL ignores the conflicting new definition because the column already exists.
            "ALTER TABLE IF EXISTS public.accounts ADD COLUMN IF NOT EXISTS metadata integer NOT NULL;",
            "ALTER TABLE IF EXISTS public.missing ADD COLUMN IF NOT EXISTS metadata text;",
        ).forEachIndexed { index, sql -> migrations.resolve("V${index + 2}__guard.sql").writeText(
            "SET LOCAL lock_timeout = '500ms';\n$sql") }
        val id = ColumnReference("public.accounts", "id")
        val contract = DatabaseContract(queries = listOf(QueryContract("accounts.insert", Operation.INSERT,
            "INSERT INTO accounts(id) VALUES (?)", tables = listOf(id.table), columns = listOf(id),
            writeColumns = listOf(id), parameterTypes = listOf("bigint"), targetTable = id.table,
            dependenciesComplete = true)), coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, migrations,
            VerificationOptions(migrationTimeoutSeconds = 30, globalTimeoutSeconds = 100))
        assertTrue(report.complete)
        val added = report.migrations.first()
        assertTrue(added.findings.any { it.ruleId == "NULLABLE_COLUMN_ADDED" && it.verdict == Verdict.SAFE }, added.toString())
        assertFalse(added.findings.any { it.ruleId == "OMITTED_COLUMN_VALUE_UNPROVEN" }, added.toString())
        for (step in report.migrations) {
            val operational = requireNotNull(step.operational)
            assertEquals(OperationalVerdict.SAFE, operational.verdict, operational.toString())
            assertFalse(operational.findings.any { it.ruleId == "OP_STATEMENT_UNSUPPORTED" }, operational.toString())
            assertFalse(operational.findings.any { ".if=false" in it.evidence }, operational.toString())
            assertTrue(operational.findings.any { it.ruleId == "OP_METADATA_ONLY" }, operational.toString())
        }
        report.migrations.drop(1).forEach { step ->
            assertEquals(false, step.catalogChanged)
            assertEquals(Verdict.SAFE, step.verdict, step.toString())
            assertTrue(step.findings.isEmpty(), step.toString())
        }
        assertTrue(requireNotNull(report.migrations[1].operational).findings.any {
            "existing IF NOT EXISTS no-op=true" in it.evidence
        }, report.toString())
        assertTrue(requireNotNull(report.migrations[2].operational).findings.any {
            "catalog-absent relation public.missing" in it.evidence
        }, report.toString())
    }
    @Test @Timeout(150)
    fun `guarded no-ops never hide applicable database callbacks`() {
        val baseline = directory.resolve("callbacks-baseline").createDirectories()
        baseline.resolve("V1__callback.sql").writeText("""
            CREATE TABLE accounts(id bigint PRIMARY KEY, metadata text);
            CREATE FUNCTION observe_ddl() RETURNS event_trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE NOTICE 'epoch guarded DDL callback'; END
            ${'$'}${'$'};
            CREATE EVENT TRIGGER observe_ddl_end ON ddl_command_end
              WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION observe_ddl();
        """.trimIndent())
        val pending = directory.resolve("callbacks-pending").createDirectories()
        pending.resolve("V2__guarded.sql").writeText("""
            SET LOCAL lock_timeout = '500ms';
            ALTER TABLE IF EXISTS accounts ADD COLUMN IF NOT EXISTS metadata integer;
            ALTER TABLE IF EXISTS missing ADD COLUMN IF NOT EXISTS metadata text;
        """.trimIndent())
        val id = ColumnReference("public.accounts", "id")
        val contract = DatabaseContract(queries = listOf(QueryContract("accounts.read", Operation.SELECT,
            "SELECT id FROM accounts", tables = listOf(id.table), columns = listOf(id), dependenciesComplete = true)),
            coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 30, globalTimeoutSeconds = 100))
        val step = report.migrations.single()
        assertEquals(Verdict.UNKNOWN, step.verdict, step.toString())
        assertTrue(step.findings.any { it.ruleId == "MIGRATION_DATABASE_CALLBACK_UNPROVEN" }, step.toString())
        val statements = requireNotNull(step.operational).statements.drop(1)
        assertEquals(2, statements.size)
        statements.forEach { statement ->
            assertNotEquals(OperationalVerdict.SAFE, statement.verdict, statement.toString())
            assertTrue(statement.findings.any { it.ruleId == "OP_DATABASE_CALLBACK_UNPROVEN" }, statement.toString())
            assertFalse(statement.findings.any { it.verdict == OperationalVerdict.SAFE }, statement.toString())
        }
    }

}
