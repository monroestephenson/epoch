package dev.epoch.postgres

import dev.epoch.core.DatabaseContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VerificationRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test fun `global timeout interrupts stuck verification and requests cleanup`() {
        val stopped = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val started = System.nanoTime()
        val failure = assertThrows(VerificationException::class.java) {
            VerificationDeadline(1).run(cleanup = { stopped.countDown() }) { blocked.await() }
        }
        assertEquals("GLOBAL_TIMEOUT", failure.stage)
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5)
    }

    @Test fun `unexpected fatal errors remain runtime errors instead of compatibility reports`() {
        val error = assertThrows(NoClassDefFoundError::class.java) {
            VerificationDeadline(5).run(cleanup = {}) { throw NoClassDefFoundError("missing/database/driver") }
        }
        assertEquals("missing/database/driver", error.message)
    }

    @Test fun `invalid timeouts are rejected before database startup`() {
        val invalid = listOf(
            VerificationOptions(migrationTimeoutSeconds = 0), VerificationOptions(migrationTimeoutSeconds = 86401),
            VerificationOptions(globalTimeoutSeconds = 0), VerificationOptions(globalTimeoutSeconds = 604801),
        )
        invalid.forEach { options ->
            assertThrows(IllegalArgumentException::class.java) {
                PostgresVerifier().verify(DatabaseContract(), directory, directory, options)
            }
        }
    }
}
