package dev.epoch

import dev.epoch.core.ColumnReference
import dev.epoch.core.CompatibilityReport
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
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class WarmBaselineReadersTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(150)
    fun `two warm readers restore independently while another writer owns the cache lock`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id bigint PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__pending.sql").writeText("ALTER TABLE accounts ADD COLUMN metadata text;")
        val cache = directory.resolve("cache")
        val contract = DatabaseContract(applicationVersion = "concurrent-cache-readers",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM accounts",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true))
        val options = VerificationOptions(baselineCache = cache, globalTimeoutSeconds = 90)
        val initial = PostgresVerifier().verify(contract, baseline, pending, options)
        assertEquals(Verdict.SAFE, initial.verdict, initial.toString())
        val initialHealth = requireNotNull(initial.baseline)
        assertEquals("MISS", initialHealth.cacheStatus)
        val key = requireNotNull(initialHealth.cacheKey)

        val executor = Executors.newFixedThreadPool(2)
        val entrants = CountDownLatch(2)
        val start = CountDownLatch(1)
        val futures = mutableListOf<Future<CompatibilityReport>>()
        try {
            FileChannel.open(cache.resolve("$key.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { writerLock ->
                    repeat(2) {
                        futures += executor.submit<CompatibilityReport> {
                            entrants.countDown()
                            check(start.await(10, TimeUnit.SECONDS)) { "Reader start barrier did not open" }
                            PostgresVerifier().verify(contract, baseline, pending, options)
                        }
                    }
                    assertTrue(entrants.await(10, TimeUnit.SECONDS), "Both readers must enter before release")
                    start.countDown()
                    // The timeout only prevents a regression from hanging the suite. The
                    // proof is that both HIT results arrive while the writer lock is held.
                    val reports = futures.map { it.get(100, TimeUnit.SECONDS) }
                    assertTrue(writerLock.isValid, "The writer lock must remain held until both restores complete")
                    reports.forEach { report ->
                        assertEquals(Verdict.SAFE, report.verdict, report.toString())
                        assertTrue(report.complete)
                        val health = requireNotNull(report.baseline)
                        assertEquals("HIT", health.cacheStatus, health.toString())
                        assertEquals(key, health.cacheKey)
                        assertEquals(initialHealth.fingerprint, health.fingerprint)
                        assertEquals(listOf("2"), report.migrations.map { it.migration.version })
                    }
                }
            }
        } finally {
            start.countDown()
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }
}
