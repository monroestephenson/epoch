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
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Tests-first candidate: migration-local session state is not the old application's session. */
class TransientSessionPlanningTest {
    @TempDir lateinit var directory: Path

    private val baseline = """
        CREATE TABLE public.accounts(id bigint PRIMARY KEY, label varchar(255));
        INSERT INTO public.accounts VALUES (1, 'old-application-value');
    """.trimIndent()
    private val contract = DatabaseContract(applicationVersion = "transient-session-regression",
        queries = (0..3).map { index ->
            QueryContract("Account.read.$index", Operation.SELECT, "SELECT label FROM accounts /* $index */",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "label")),
                dependenciesComplete = true)
        }, coverage = Coverage(complete = true))

    @Test @Timeout(180)
    fun `SET LOCAL search path cannot turn a committed compatible schema into a breaking query`() {
        verify("CREATE SCHEMA shadow; CREATE TABLE shadow.accounts(id bigint);", """
            SET LOCAL search_path = shadow, public;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent(), "42703")
    }

    @Test @Timeout(180)
    fun `ON COMMIT DROP shadow cannot produce an old application missing column finding`() {
        verify("", """
            CREATE TEMP TABLE accounts(id bigint) ON COMMIT DROP;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent(), "42703")
    }

    @Test @Timeout(180)
    fun `SET LOCAL ROLE denial cannot produce an old application permission regression`() {
        verify(deniedRoleSetup(), """
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
            SET LOCAL ROLE epoch_denied;
        """.trimIndent(), "42501")
    }

    @Test @Timeout(180)
    fun `session role survives owner commit but cannot become an old application permission regression`() {
        verify(deniedRoleSetup(), """
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
            SET ROLE epoch_denied;
        """.trimIndent(), "42501", checkOwnerAfterCommit = true)
    }

    private fun deniedRoleSetup() = """
        CREATE ROLE epoch_denied NOLOGIN;
        GRANT USAGE ON SCHEMA public TO epoch_denied;
        DO ${'$'}${'$'} BEGIN
          IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
            GRANT SELECT, INSERT, UPDATE ON public.flyway_schema_history TO epoch_denied;
          END IF;
        END ${'$'}${'$'};
    """.trimIndent()

    @Test @Timeout(180)
    fun `boundary probes use committed verification session instead of a transient restrictive shadow`() {
        val write = QueryContract("Account.insert", Operation.INSERT, "INSERT INTO accounts(id,label) VALUES (?,?)",
            tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id"), ColumnReference("public.accounts", "label")),
            parameterTypes = listOf("BIGINT", "VARCHAR"), targetTable = "public.accounts",
            writeColumns = listOf(ColumnReference("public.accounts", "id"), ColumnReference("public.accounts", "label")),
            dependenciesComplete = true)
        val baselineDirectory = directory.resolve("baseline").createDirectories()
        baselineDirectory.resolve("V1__baseline.sql").writeText(baseline)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__transient_write_shadow.sql").writeText("""
            CREATE TEMP TABLE accounts(id bigint PRIMARY KEY, label varchar(255) CHECK(label IS NULL)) ON COMMIT DROP;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent())
        val report = PostgresVerifier().verify(contract.copy(queries = listOf(write)), baselineDirectory, pending,
            VerificationOptions(executeBoundaries = true, planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        assertTrue(report.complete, report.toString())
        assertTrue(report.limitations.any { it.contains("baseline-accepted INSERT witnesses") && !it.contains("retained 0 ") }, report.limitations.toString())
        assertTrue(report.migrations.single().findings.none { it.ruleId == "BOUNDARY_EXECUTION_REJECTED" || it.verdict == Verdict.BREAKING }, report.toString())
    }

    private fun verify(extraBaseline: String, migration: String, transientState: String, checkOwnerAfterCommit: Boolean = false) {
        // Establish actual PostgreSQL semantics independently of the verifier. The same SQL
        // fails in the migration's local session, then succeeds on a fresh application-style
        // session after COMMIT. A local diagnostic is therefore not a breaking witness.
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE)).use { database ->
            database.start()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { owner ->
                owner.createStatement().use { it.execute("$baseline\n$extraBaseline") }
                owner.autoCommit = false
                owner.createStatement().use { it.execute(migration) }
                val savepoint = owner.setSavepoint()
                val local = assertThrows(java.sql.SQLException::class.java) {
                    owner.createStatement().use { it.executeQuery("SELECT label FROM accounts").close() }
                }
                assertEquals(transientState, local.sqlState)
                owner.rollback(savepoint)
                owner.releaseSavepoint(savepoint)
                owner.commit()
                if (checkOwnerAfterCommit) {
                    // SET ROLE (without LOCAL) remains on this connection after COMMIT;
                    // a separate application connection must still use its own login role.
                    owner.autoCommit = true
                    val persistent = assertThrows(java.sql.SQLException::class.java) {
                        owner.createStatement().use { it.executeQuery("SELECT label FROM accounts").close() }
                    }
                    assertEquals(transientState, persistent.sqlState)
                }
            }
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { application ->
                application.createStatement().use { statement ->
                    statement.executeQuery("SELECT label FROM accounts").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("old-application-value", rows.getString(1))
                        assertFalse(rows.next())
                    }
                }
            }
        }

        val baselineDirectory = directory.resolve("baseline").createDirectories()
        baselineDirectory.resolve("V1__baseline.sql").writeText("$baseline\n$extraBaseline")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__transient_session.sql").writeText(migration)
        val report = PostgresVerifier().verify(contract, baselineDirectory, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        assertTrue(report.complete, report.toString())
        assertTrue(requireNotNull(report.baseline).findings.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
        val findings = report.migrations.single().findings
        assertTrue(findings.none { it.verdict == Verdict.BREAKING },
            "A transient owner-session rejection does not prove failure against the committed schema: $findings")
        assertTrue(findings.none { it.ruleId == "POSTGRES_SQL_REJECTED" && it.verdict == Verdict.BREAKING && transientState in it.evidence },
            "Do not publish the discarded migration-local failure as committed compatibility evidence.")
        // Unmodeled SET/TEMP effects may retain UNKNOWN. This test does not demand unconditional SAFE.
    }
}
