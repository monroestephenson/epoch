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

/** Real catalog noise must not become an execution dependency of every old SQL template. */
class BehaviorScopeSmokeTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(120)
    fun `unused helpers views and unrelated CHECKs do not poison expansion or transparent rename`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val checks = (0 until 128).joinToString(",\n") { ordinal ->
            "CONSTRAINT checked_nonnegative_$ordinal CHECK (id >= 0)"
        }
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE accounts (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE independent (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE checked (id bigint PRIMARY KEY, $checks);
            CREATE FUNCTION unused_constant() RETURNS bigint LANGUAGE sql IMMUTABLE AS 'SELECT 1::bigint';
            CREATE FUNCTION unused_lookup() RETURNS bigint LANGUAGE plpgsql STABLE AS
              'DECLARE total bigint; BEGIN EXECUTE ''SELECT count(*) FROM accounts'' INTO total; RETURN total; END';
            CREATE VIEW unused_accounts AS SELECT id, label FROM accounts;
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__expand.sql").writeText("ALTER TABLE accounts ADD COLUMN metadata text;")
        pending.resolve("V3__compatibility_view.sql").writeText("""
            ALTER TABLE accounts RENAME TO accounts_v2;
            CREATE VIEW accounts AS SELECT * FROM accounts_v2;
        """.trimIndent())
        fun read(table: String, column: String): QueryContract = QueryContract(
            "$table.read", Operation.SELECT, "SELECT $column FROM $table",
            tables = listOf("public.$table"), columns = listOf(ColumnReference("public.$table", column)),
            dependenciesComplete = true,
        )
        val checkedId = ColumnReference("public.checked", "id")
        val contract = DatabaseContract(applicationVersion = "behavior-scope-smoke", queries = listOf(
            read("accounts", "label"), read("independent", "label"), read("checked", "id"),
            QueryContract("checked.insert", Operation.INSERT, "INSERT INTO checked (id) VALUES (?)",
                tables = listOf("public.checked"), columns = listOf(checkedId), writeColumns = listOf(checkedId),
                parameterTypes = listOf("bigint"), targetTable = "public.checked", dependenciesComplete = true),
        ), coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL fixture contract")))

        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        val health = requireNotNull(report.baseline)
        val findings = health.findings + report.migrations.flatMap { it.findings }
        assertAll(
            { assertEquals(Verdict.SAFE, health.verdict, health.toString()) },
            { assertEquals(Verdict.SAFE, report.verdict, report.toString()) },
            { assertEquals(listOf(Verdict.SAFE, Verdict.SAFE), report.migrations.map { it.verdict }) },
            { assertEquals(listOf(0, 1), report.migrations.map { it.replannedQueries }, report.toString()) },
            { assertEquals(listOf(4, 3), report.migrations.map { it.reusedQueries }, report.toString()) },
            { assertTrue(findings.none { it.ruleId in setOf("EXISTING_DATABASE_BEHAVIOR",
                "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN", "RELATION_RENAME_EXPRESSION_UNPROVEN") }, findings.toString()) },
            { assertTrue(report.migrations[1].findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" &&
                it.objectName == "public.accounts" && "accounts.read" in it.affectedContractIds }, report.toString()) },
        )
    }
}
