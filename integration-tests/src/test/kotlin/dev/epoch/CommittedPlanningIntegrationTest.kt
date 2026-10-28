package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationException
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Verify first against Epoch's real Flyway/PostgreSQL path; no mocked schema visibility. */
class CommittedPlanningIntegrationTest {
    @TempDir lateinit var directory: Path

    private val contract = DatabaseContract(applicationVersion = "committed-planning", queries = (0..3).map { index ->
        QueryContract("Account.read.$index", Operation.SELECT, "SELECT label FROM accounts /* $index */",
            tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "label")),
            dependenciesComplete = true)
    }, coverage = Coverage(complete = true))

    private fun directories(extraBaseline: String = ""): Pair<Path, Path> {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(
            "CREATE TABLE accounts(id bigint PRIMARY KEY, label varchar(255));\n$extraBaseline")
        return baseline to directory.resolve("pending").createDirectories()
    }

    @Test @Timeout(120)
    fun `eligible final transactional step uses configured workers after commit`() {
        val (baseline, pending) = directories()
        pending.resolve("V2__widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);")
        val checkpoints = mutableListOf<CompatibilityReport>()
        val report = PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(
            planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90,
            progress = { _, snapshot -> checkpoints += snapshot }))
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        val step = report.migrations.single()
        assertEquals(4, step.replannedQueries)
        assertEquals(4, step.planningParallelism, "The migration is committed before pooled readers run.")
        assertNotNull(step.operational, "Original statement measurements survive the deferred flush.")
        assertEquals(listOf(0, 1), checkpoints.map { it.migrations.size })
        assertTrue(checkpoints.all { !it.complete })
    }

    @Test @Timeout(120)
    fun `parallel checks retain an intermediate break before the restoring migration runs`() {
        val (baseline, pending) = directories()
        pending.resolve("V2__rename.sql").writeText("ALTER TABLE accounts RENAME COLUMN label TO renamed;")
        pending.resolve("V3__restore.sql").writeText("ALTER TABLE accounts RENAME COLUMN renamed TO label;")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        val first = report.migrations[0]
        assertEquals(Verdict.BREAKING, first.verdict, first.toString())
        assertEquals(4, first.planningParallelism)
        val failures = first.findings.filter { it.ruleId == "POSTGRES_SQL_REJECTED" }
        assertEquals(contract.queries.map { it.id }.toSet(), failures.flatMap { it.affectedContractIds }.toSet())
        assertTrue(failures.all { "42703" in it.evidence }, failures.toString())
        assertEquals(Verdict.SAFE, report.migrations[1].verdict, report.migrations[1].toString())
        assertEquals(4, report.migrations[1].planningParallelism)
        assertEquals(Verdict.BREAKING, report.verdict, "The restored final schema cannot erase V2's evidence.")
    }

    @Test @Timeout(120)
    fun `failure at COMMIT never publishes the rolled back migration as completed`() {
        val (baseline, pending) = directories("""
            CREATE TABLE parents(id bigint PRIMARY KEY);
            CREATE TABLE children(id bigint PRIMARY KEY, parent_id bigint,
              CONSTRAINT parent_fk FOREIGN KEY(parent_id) REFERENCES parents(id) DEFERRABLE INITIALLY DEFERRED);
        """.trimIndent())
        pending.resolve("V2__widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);")
        // The INSERT and AFTER_EACH_MIGRATE callback succeed. COMMIT is the failing operation.
        pending.resolve("V3__deferred_violation.sql").writeText("INSERT INTO children VALUES (1,999);")
        val checkpoints = mutableListOf<CompatibilityReport>()
        val failure = assertThrows(VerificationException::class.java) {
            PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(
                planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90,
                progress = { _, snapshot -> checkpoints += snapshot }))
        }
        assertEquals("V3__deferred_violation.sql", failure.migration?.filename, failure.toString())
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>()
            .any { it.sqlState == "23503" }, failure.toString())
        assertEquals(listOf("2"), checkpoints.last().migrations.map { it.migration.version })
        assertTrue(checkpoints.none { report -> report.migrations.any { it.migration.version == "3" } },
            "A successful precommit callback is not a successful migration.")
        assertTrue(checkpoints.all { !it.complete })
    }

    @Test @Timeout(120)
    fun `transaction local plan settings do not alter the committed verification session`() {
        val (baseline, pending) = directories()
        pending.resolve("V2__local_setting.sql").writeText("""
            SET LOCAL enable_seqscan = off;
            ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent())
        pending.resolve("V3__normal_setting.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(257);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        assertEquals(4, report.migrations[0].planningParallelism, "Committed checks use the baseline verification session.")
        assertEquals(4, report.migrations[1].planningParallelism, "COMMIT releases the transaction-local setting.")
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "POSTGRES_SQL_REJECTED" }, report.toString())
    }

    @Test @Timeout(180)
    fun `versioned CIC and final repeatables preserve Flyway ordering and one committed checkpoint each`() {
        val (baseline, pending) = directories()
        pending.resolve("V2__widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);")
        pending.resolve("V3__concurrent_index.sql").writeText("CREATE INDEX CONCURRENTLY accounts_label_lookup ON accounts(label);")
        pending.resolve("V3__concurrent_index.sql.conf").writeText("executeInTransaction=false\n")
        pending.resolve("V4__widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(257);")
        pending.resolve("R__a_widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(258);")
        pending.resolve("R__z_widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(259);")
        val checkpoints = mutableListOf<CompatibilityReport>()
        val report = PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(
            planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 150,
            progress = { _, snapshot -> checkpoints += snapshot }))
        assertEquals(listOf("V2__widen.sql", "V3__concurrent_index.sql", "V4__widen.sql", "R__a_widen.sql", "R__z_widen.sql"),
            report.migrations.map { it.migration.filename })
        assertEquals(listOf("2", "3", "4", "repeatable", "repeatable"), report.migrations.map { it.migration.version })
        assertTrue(report.migrations.all { it.replannedQueries == 4 && it.planningParallelism == 4 }, report.toString())
        assertEquals((0..5).toList(), checkpoints.map { it.migrations.size })
        assertTrue(checkpoints.all { !it.complete })
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        val concurrent = report.migrations[1].operational!!.statements.single()
        assertTrue(concurrent.autoCommit, concurrent.toString())
        assertTrue(concurrent.findings.any { it.ruleId == "OP_ONLINE_INDEX_BUILD" }, concurrent.toString())
        assertTrue(report.migrations[0].operational!!.statements.none { it.autoCommit })
    }

    @Test @Timeout(180)
    fun `local search path and temporary shadows do not produce committed query failures`() {
        val (baseline, pending) = directories("CREATE SCHEMA shadow; CREATE TABLE shadow.accounts(id bigint);")
        pending.resolve("V2__local_path.sql").writeText("""
            SET LOCAL search_path = shadow, public;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent())
        pending.resolve("V3__temporary_shadow.sql").writeText("""
            CREATE TEMP TABLE accounts(id bigint) ON COMMIT DROP;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(257);
        """.trimIndent())
        pending.resolve("V4__ordinary.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(258);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 150))
        report.migrations.take(2).forEach { step ->
            assertEquals(4, step.planningParallelism, step.toString())
            assertTrue(step.findings.none { it.ruleId == "POSTGRES_SQL_REJECTED" }, step.toString())
            assertNotEquals(Verdict.BREAKING, step.verdict, "The owner-only shadow disappears at COMMIT.")
        }
        assertEquals(4, report.migrations.last().planningParallelism)
        assertTrue(report.migrations.last().findings.none { it.ruleId == "POSTGRES_SQL_REJECTED" })
    }

    @Test @Timeout(120)
    fun `local role is operational context and does not change committed query workers`() {
        val (baseline, pending) = directories("""
            CREATE ROLE epoch_reader NOLOGIN;
            GRANT USAGE ON SCHEMA public TO epoch_reader;
            GRANT SELECT ON accounts TO epoch_reader;
            GRANT SELECT, INSERT, UPDATE ON public.flyway_schema_history TO epoch_reader;
        """.trimIndent())
        pending.resolve("V2__local_role.sql").writeText("""
            ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);
            SET LOCAL ROLE epoch_reader;
        """.trimIndent())
        pending.resolve("V3__ordinary.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(257);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90))
        assertEquals(listOf(4, 4), report.migrations.map { it.planningParallelism })
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "POSTGRES_SQL_REJECTED" }, report.toString())
    }

    @Test @Timeout(120)
    fun `deferred commit time catalog mutation rejects stale plans and retains preceding checkpoint`() {
        val (baseline, pending) = directories("""
            CREATE TABLE commit_requests(id bigint);
            CREATE FUNCTION commit_catalog_change() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              EXECUTE 'ALTER TABLE public.accounts ADD COLUMN at_commit text';
              RETURN NEW;
            END ${'$'}${'$'};
            CREATE CONSTRAINT TRIGGER deferred_catalog_change AFTER INSERT ON commit_requests
              DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION commit_catalog_change();
        """.trimIndent())
        pending.resolve("V2__widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);")
        pending.resolve("V3__commit_change.sql").writeText("INSERT INTO commit_requests VALUES (1);")
        pending.resolve("V4__must_not_execute.sql").writeText("SELECT 1/0;")
        val checkpoints = mutableListOf<CompatibilityReport>()
        val failure = assertThrows(VerificationException::class.java) {
            PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(
                planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90,
                progress = { _, snapshot -> checkpoints += snapshot }))
        }
        assertEquals("COMMITTED_SCHEMA_CHANGED", failure.stage, failure.toString())
        assertEquals("V3__commit_change.sql", failure.migration?.filename)
        assertEquals(listOf("2"), checkpoints.last().migrations.map { it.migration.version })
        assertTrue(checkpoints.none { report -> report.migrations.any { it.migration.version == "3" } })
    }

    @Test @Timeout(180)
    fun `persistent session policy is one unknown cause and restoration replans without dependent DDL`() {
        val (baseline, pending) = directories("CREATE SCHEMA shadow; CREATE TABLE shadow.accounts(id bigint);")
        pending.resolve("V2__persistent_path.sql").writeText("ALTER ROLE epoch SET search_path = shadow, public;")
        pending.resolve("V3__unrelated.sql").writeText("CREATE TABLE public.unrelated(id bigint);")
        pending.resolve("V4__restore_policy.sql").writeText("ALTER ROLE epoch RESET search_path;")
        pending.resolve("V5__unrelated.sql").writeText("CREATE TABLE public.another_unrelated(id bigint);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 150))
        report.migrations.take(2).forEach { step ->
            val uncertainty = step.findings.filter { it.ruleId == "QUERY_SESSION_UNPROVEN" }
            assertEquals(1, uncertainty.size, step.toString())
            assertEquals(contract.queries.map { it.id }.toSet(), uncertainty.single().affectedContractIds.toSet())
            assertTrue(uncertainty.single().evidence.contains("epoch.effective_search_path"))
            assertFalse(uncertainty.single().evidence.contains("shadow"), "Report setting names, not values.")
            assertEquals(0, step.replannedQueries)
            assertEquals(0, step.reusedQueries)
            assertEquals(0, step.planningParallelism)
            assertTrue(step.findings.none { it.ruleId == "POSTGRES_SQL_REJECTED" && it.verdict == Verdict.BREAKING })
        }
        val restored = report.migrations[2]
        assertEquals(4, restored.replannedQueries, "Session restoration must invalidate unverified cached plans even without a column diff.")
        assertEquals(4, restored.planningParallelism)
        assertTrue(restored.findings.none { it.ruleId in setOf("QUERY_SESSION_UNPROVEN", "POSTGRES_SQL_REJECTED") }, restored.toString())
        assertEquals(0, report.migrations.last().replannedQueries)
        assertEquals(4, report.migrations.last().reusedQueries)
        assertTrue(report.migrations.last().findings.none { it.ruleId in setOf("QUERY_SESSION_UNPROVEN", "POSTGRES_SQL_REJECTED") })
    }

    @Test @Timeout(180)
    fun `database policy changes invalidate all plans and resetting policy restores parallel checks`() {
        val (baseline, pending) = directories()
        pending.resolve("V2__database_policy.sql").writeText("ALTER DATABASE epoch SET enable_seqscan = off;")
        pending.resolve("V3__unrelated.sql").writeText("CREATE TABLE public.unrelated(id bigint);")
        pending.resolve("V4__reset_policy.sql").writeText("ALTER DATABASE epoch RESET enable_seqscan;")
        pending.resolve("V5__unrelated.sql").writeText("CREATE TABLE public.another_unrelated(id bigint);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 150))
        report.migrations.take(2).forEach { step ->
            val uncertainty = step.findings.filter { it.ruleId == "QUERY_SESSION_UNPROVEN" }
            assertEquals(1, uncertainty.size, step.toString())
            assertEquals(contract.queries.map { it.id }.toSet(), uncertainty.single().affectedContractIds.toSet())
            assertTrue(uncertainty.single().evidence.contains("enable_seqscan"), uncertainty.toString())
            assertEquals(0, step.replannedQueries)
            assertEquals(0, step.reusedQueries)
            assertEquals(0, step.planningParallelism)
            assertTrue(step.findings.none { it.ruleId == "POSTGRES_SQL_REJECTED" }, step.toString())
        }
        val restored = report.migrations[2]
        assertEquals(4, restored.replannedQueries, "Restoration must invalidate unverified plans even without relation DDL.")
        assertEquals(4, restored.planningParallelism)
        assertTrue(restored.findings.none { it.ruleId in setOf("QUERY_SESSION_UNPROVEN", "POSTGRES_SQL_REJECTED") }, restored.toString())
        assertEquals(0, report.migrations.last().replannedQueries)
        assertEquals(4, report.migrations.last().reusedQueries)
        assertTrue(report.migrations.last().findings.none { it.ruleId in setOf("QUERY_SESSION_UNPROVEN", "POSTGRES_SQL_REJECTED") })
    }

    @Test @Timeout(180)
    fun `session search path on migration connection does not become application connection policy`() {
        val (baseline, pending) = directories("CREATE SCHEMA shadow; CREATE TABLE shadow.accounts(id bigint);")
        pending.resolve("V2__session_path.sql").writeText("""
            SET search_path = shadow, public;
            ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(256);
        """.trimIndent())
        pending.resolve("V3__fully_qualified.sql").writeText("ALTER TABLE public.accounts ALTER COLUMN label TYPE varchar(257);")
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 30, globalTimeoutSeconds = 150))
        report.migrations.forEach { step ->
            assertEquals(4, step.replannedQueries, step.toString())
            assertEquals(4, step.planningParallelism, "Workers use independently established verification policy.")
            assertTrue(step.findings.none { it.ruleId in setOf("QUERY_SESSION_UNPROVEN", "POSTGRES_SQL_REJECTED") }, step.toString())
            assertNotEquals(Verdict.BREAKING, step.verdict)
        }
    }

}
