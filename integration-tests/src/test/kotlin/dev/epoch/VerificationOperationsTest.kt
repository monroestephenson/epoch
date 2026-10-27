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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Runtime failures are CI errors, independently of compatibility findings. */
class VerificationOperationsTest {
    @TempDir lateinit var directory: Path

    private fun directories(sql: String, baselineSql: String = "CREATE TABLE accounts (id bigint PRIMARY KEY);"): Pair<Path, Path> {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__change.sql").writeText(sql)
        return baseline to pending
    }

    private fun readContract(vararg columns: String) = DatabaseContract(
        applicationVersion = "runtime-regression",
        queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT ${columns.joinToString()} FROM accounts",
            tables = listOf("public.accounts"), columns = columns.map { ColumnReference("public.accounts", it) },
            dependenciesComplete = true)),
        coverage = Coverage(complete = true, mechanisms = listOf("Explicit fixed SQL regression contract")),
    )

    @Test @Timeout(90)
    fun `CREATE INDEX CONCURRENTLY completes with Flyway nontransactional migration and lock`() {
        val (baseline, pending) = directories("CREATE INDEX CONCURRENTLY accounts_id_lookup ON accounts(id);")
        pending.resolve("V2__change.sql.conf").writeText("executeInTransaction=false\n")
        val report = PostgresVerifier().verify(readContract("id"), baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 15, globalTimeoutSeconds = 60))
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test @Timeout(90)
    fun `migration rejected by PostgreSQL is a runtime error with actual migration attribution`() {
        val (baseline, pending) = directories("INSERT INTO nonexistent_table VALUES (1);")
        val failure = assertThrows(VerificationException::class.java) {
            PostgresVerifier().verify(readContract("id"), baseline, pending,
                VerificationOptions(globalTimeoutSeconds = 60))
        }
        assertEquals("V2__change.sql", failure.migration?.filename, failure.toString())
        assertNotNull(failure.cause)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it.message.orEmpty().contains("nonexistent_table")
        }, failure.toString())
    }

    @Test @Timeout(90)
    fun `database connection termination during migration is runtime failure`() {
        val (baseline, pending) = directories("SELECT pg_terminate_backend(pg_backend_pid());")
        val failure = assertThrows(VerificationException::class.java) {
            PostgresVerifier().verify(readContract("id"), baseline, pending,
                VerificationOptions(globalTimeoutSeconds = 60))
        }
        assertNotNull(failure.cause)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it.message.orEmpty().contains("connection", ignoreCase = true) ||
                it.message.orEmpty().contains("terminating", ignoreCase = true)
        }, failure.toString())
    }

    @Test @Timeout(90)
    fun `per-migration timeout cancels slow SQL and reports runtime failure`() {
        val (baseline, pending) = directories("SELECT pg_sleep(5);")
        val failure = assertThrows(VerificationException::class.java) {
            PostgresVerifier().verify(readContract("id"), baseline, pending,
                VerificationOptions(migrationTimeoutSeconds = 1, globalTimeoutSeconds = 60))
        }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it.message.orEmpty().contains("timeout", ignoreCase = true) ||
                it.message.orEmpty().contains("cancel", ignoreCase = true)
        }, failure.toString())
    }

    @Test @Timeout(90)
    fun `new release contract is checked against original schema in bidirectional run`() {
        val (baseline, pending) = directories("ALTER TABLE accounts ADD COLUMN label text;")
        val report = PostgresVerifier().verifyBidirectional(
            oldContracts = listOf(readContract("id")), newContracts = listOf(readContract("id", "label")),
            baselineDirectory = baseline, migrationsDirectory = pending,
            options = VerificationOptions(globalTimeoutSeconds = 60),
        )
        assertEquals(Verdict.SAFE, report.oldOnNew.verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.newOnOld.verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
    }
}
