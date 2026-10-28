package dev.epoch.postgres

import dev.epoch.core.MigrationInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException

class PendingMigrationLeaseTest {
    private fun connection(aborted: CountDownLatch): Connection = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(Connection::class.java),
    ) { proxy, method, arguments -> when (method.name) {
        "abort" -> { aborted.countDown(); null }
        "close" -> null
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments?.singleOrNull()
        "toString" -> "FixtureConnection"
        else -> throw UnsupportedOperationException(method.name)
    } } as Connection

    @Test @Timeout(20) fun `one old migration deadline cancels its owner and committed workers`() {
        val ownerAborted = CountDownLatch(1)
        val workerAborted = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val migration = MigrationInfo("2", "V2__before_next_callback.sql")
        // Keep the deliberately interrupted lease-owning thread out of the JUnit runner.
        val failure = assertThrows(VerificationException::class.java) {
            VerificationDeadline(15).run(cleanup = {}) {
                PendingMigrationLease(migration, 5, connection(ownerAborted)).use { lease ->
                    val executor = Executors.newSingleThreadExecutor()
                    val future = executor.submit(Callable {
                        lease.withConnection({ connection(workerAborted) }) { entered.countDown(); never.await() }
                    })
                    try {
                        assertTrue(entered.await(2, TimeUnit.SECONDS))
                        lease.await(future)
                    } finally { future.cancel(true); executor.shutdownNow() }
                }
            }
        }
        assertEquals("MIGRATION_TIMEOUT", failure.stage)
        assertEquals(migration, failure.migration, "The next Flyway context must not replace the queued step's identity.")
        assertTrue(ownerAborted.await(2, TimeUnit.SECONDS))
        assertTrue(workerAborted.await(2, TimeUnit.SECONDS))
    }

    @Test @Timeout(10) fun `completed previous lease cannot abort the next migration's shared owner connection`() {
        val aborted = CountDownLatch(1)
        val owner = connection(aborted)
        PendingMigrationLease(MigrationInfo("2", "V2__done.sql"), 1, owner).use { it.finish() }
        PendingMigrationLease(MigrationInfo("3", "V3__active.sql"), 60, owner).use { next ->
            assertFalse(aborted.await(1500, TimeUnit.MILLISECONDS))
            next.checkExpired()
            next.finish()
        }
    }

    @Test @Timeout(10)
    fun `successive deferred waits receive the remaining original budget`() {
        val aborted = CountDownLatch(1)
        val allowances = mutableListOf<Long>()
        val future = object : Future<Unit> {
            override fun cancel(interrupt: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = false
            override fun get(): Unit = error("The lease must supply its remaining deadline")
            override fun get(timeout: Long, unit: TimeUnit) {
                allowances += unit.toNanos(timeout)
                if (allowances.size == 2) throw TimeoutException("Fixture records an expired timed wait")
            }
        }
        // Observe the timeout passed to Future.get directly. This checks that a later
        // wait has less of the same budget, without a scheduler-sensitive pre-expiry sleep.
        // The other test independently checks real timer cancellation of owner/workers.
        val failure = assertThrows(VerificationException::class.java) {
            VerificationDeadline(5).run(cleanup = {}) {
                PendingMigrationLease(MigrationInfo("2", "V2__queued.sql"), 60, connection(aborted)).use { lease ->
                    lease.await(future)
                    lease.await(future)
                }
            }
        }
        assertEquals("MIGRATION_TIMEOUT", failure.stage)
        assertEquals("2", failure.migration?.version)
        assertEquals(2, allowances.size)
        assertTrue(allowances[0] < TimeUnit.SECONDS.toNanos(60))
        assertTrue(allowances[1] in 1 until allowances[0], "A second wait must not receive a renewed budget: $allowances")
        assertTrue(aborted.await(2, TimeUnit.SECONDS))
    }

}
