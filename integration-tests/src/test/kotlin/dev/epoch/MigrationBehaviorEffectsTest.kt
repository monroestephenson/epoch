package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** DDL callbacks are migration effects even when no application query invokes their function. */
class MigrationBehaviorEffectsTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(120)
    fun `applicable DDL callbacks prevent compatibility SAFE and group the exact statement evidence`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(BASELINE)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__unrelated_tag.sql").writeText(UNRELATED_TAG)
        pending.resolve("V3__nullable_columns.sql").writeText(NULLABLE_COLUMNS)
        val contract = DatabaseContract(applicationVersion = "migration-callback-effects", queries = listOf(
            QueryContract("accounts.read", Operation.SELECT, "SELECT label FROM accounts WHERE id = ?",
                tables = listOf("public.accounts"), columns = listOf("id", "label").map { ColumnReference("public.accounts", it) },
                parameterTypes = listOf("bigint"), dependenciesComplete = true),
        ), coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL fixture contract")))

        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        assertEquals(Verdict.SAFE, requireNotNull(report.baseline).verdict, report.toString())
        assertEquals(Verdict.SAFE, report.migrations[0].verdict, report.toString())
        assertTrue(report.migrations[0].findings.none { it.ruleId == "MIGRATION_DATABASE_CALLBACK_UNPROVEN" }, report.toString())
        val triggered = report.migrations[1]
        assertEquals(Verdict.UNKNOWN, triggered.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        val finding = triggered.findings.single { it.ruleId == "MIGRATION_DATABASE_CALLBACK_UNPROVEN" }
        assertEquals(Verdict.UNKNOWN, finding.verdict)
        assertEquals(triggered.migration, finding.migration)
        assertEquals("V3__nullable_columns.sql", finding.objectName)
        assertTrue(finding.evidence.contains("mutate_after_alter"), finding.toString())
        assertTrue(finding.evidence.contains("Observed statement ordinals: 1, 2"), finding.toString())
        val measuredCauses = requireNotNull(triggered.operational).statements.flatMap { it.findings }
            .filter { it.ruleId == "OP_DATABASE_CALLBACK_UNPROVEN" }.map { it.evidence }.distinct()
        assertEquals(1, measuredCauses.size)
        assertTrue(finding.evidence.contains(measuredCauses.single()), finding.toString())
        assertTrue(report.migrations.flatMap { it.findings }.none { it.verdict == Verdict.BREAKING ||
            it.ruleId == "POSTGRES_SQL_REJECTED" }, report.toString())
    }

    @Test @Timeout(90)
    fun `PostgreSQL event trigger actually changes old rows only for the matching DDL tag`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.execute(BASELINE)
                    assertEquals("legacy", connection.label())
                    connection.execute(UNRELATED_TAG)
                    assertEquals("legacy", connection.label(), "CREATE INDEX must not invoke the ALTER TABLE callback")
                    connection.execute(NULLABLE_COLUMNS)
                    assertEquals("legacy:ddl:ddl", connection.label(),
                        "Both nullable-column additions execute the callback and change an existing application row")
                }
            }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.label(): String = prepareStatement("SELECT label FROM accounts WHERE id = ?").use { statement ->
        statement.setLong(1, 1)
        statement.executeQuery().use { result -> assertTrue(result.next()); result.getString(1) }
    }

    companion object {
        private const val BASELINE = """
            CREATE TABLE accounts (id bigint PRIMARY KEY, label text);
            INSERT INTO accounts VALUES (1, 'legacy');
            CREATE FUNCTION mutate_existing_rows() RETURNS event_trigger LANGUAGE plpgsql AS
              'BEGIN UPDATE accounts SET label = label || '':ddl''; RETURN; END';
            CREATE EVENT TRIGGER mutate_after_alter ON ddl_command_end WHEN TAG IN ('ALTER TABLE')
              EXECUTE FUNCTION mutate_existing_rows();
        """
        private const val UNRELATED_TAG = "CREATE INDEX accounts_id_lookup ON accounts(id);"
        private const val NULLABLE_COLUMNS = """
            ALTER TABLE accounts ADD COLUMN metadata text;
            ALTER TABLE accounts ADD COLUMN notes text;
        """
    }
}
