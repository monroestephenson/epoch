package dev.epoch

import dev.epoch.core.CompatibilityReport
import dev.epoch.core.ColumnReference
import dev.epoch.core.Coverage
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import dev.epoch.core.Verdict
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BaselinePlanCacheTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(240)
    fun `baseline plan cache hits and invalidates contract baseline and corrupted evidence`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val baselineFile = baseline.resolve("V1__baseline.sql")
        baselineFile.writeText("CREATE TABLE accounts (id bigint PRIMARY KEY, label varchar(255));")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__unrelated.sql").writeText("CREATE TABLE unrelated (id bigint PRIMARY KEY);")
        val cache = directory.resolve("cache")
        val options = VerificationOptions(baselineCache = cache, planningParallelism = 2, globalTimeoutSeconds = 90)
        val contract = DatabaseContract(applicationVersion = "baseline-plan-cache",
            queries = (0 until 4).map { index ->
                QueryContract("Account.read.$index", Operation.SELECT, "SELECT label FROM accounts /* template $index */",
                    tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "label")),
                    dependenciesComplete = true)
            }, coverage = Coverage(complete = true))
        fun verify(input: DatabaseContract = contract): CompatibilityReport =
            PostgresVerifier().verify(input, baseline, pending, options).also {
                assertEquals(Verdict.SAFE, it.verdict, it.toString())
                assertTrue(it.complete)
                assertEquals(0, it.migrations.single().replannedQueries)
                assertEquals(4, it.migrations.single().reusedQueries)
            }

        val cold = requireNotNull(verify().baseline)
        assertEquals("MISS", cold.planCacheStatus, cold.toString())
        assertEquals(4, cold.plannedQueries)
        assertEquals(0, cold.reusedQueries)
        assertFalse(cold.planCacheKey.isNullOrBlank())
        assertFalse(cold.planCacheReason.isNullOrBlank())
        val warm = requireNotNull(verify().baseline)
        assertEquals("HIT", warm.planCacheStatus, warm.toString())
        assertEquals(cold.planCacheKey, warm.planCacheKey)
        assertEquals(0, warm.plannedQueries)
        assertEquals(4, warm.reusedQueries)

        val changed = contract.copy(queries = contract.queries.map { it.copy(sql = it.sql + " /* new contract */") })
        val changedHealth = requireNotNull(verify(changed).baseline)
        assertEquals("HIT", changedHealth.cacheStatus, "The database dump itself remains reusable")
        assertEquals("MISS", changedHealth.planCacheStatus)
        assertNotEquals(cold.planCacheKey, changedHealth.planCacheKey)
        assertEquals(4, changedHealth.plannedQueries)
        val evidenceFile = cache.resolve("checks").resolve("${requireNotNull(changedHealth.planCacheKey)}.json")
        assertTrue(Files.isRegularFile(evidenceFile), "The reported plan cache key must identify its persisted evidence")
        evidenceFile.writeText("corrupted cache evidence")
        val rebuilt = requireNotNull(verify(changed).baseline)
        assertEquals("REBUILT", rebuilt.planCacheStatus, rebuilt.toString())
        assertEquals(changedHealth.planCacheKey, rebuilt.planCacheKey)
        assertFalse(rebuilt.planCacheReason.isNullOrBlank(), "Cache rejection must explain why evidence was not trusted")
        assertEquals(4, rebuilt.plannedQueries)
        assertEquals(0, rebuilt.reusedQueries)

        baselineFile.writeText(Files.readString(baselineFile) + "\n-- changed baseline migration source\n")
        val differentBaseline = requireNotNull(verify(changed).baseline)
        assertEquals("MISS", differentBaseline.cacheStatus)
        assertEquals("MISS", differentBaseline.planCacheStatus)
        assertNotEquals(rebuilt.planCacheKey, differentBaseline.planCacheKey)
        assertEquals(4, differentBaseline.plannedQueries)
        assertEquals(0, differentBaseline.reusedQueries)
    }
}
