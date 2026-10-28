package dev.epoch.hibernate

import dev.epoch.core.DatabaseContract
import dev.epoch.hibernate.queryisolationfixture.QueryIsolationRecord
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import kotlin.test.*

class BootstrapCancellationTest {
    private val classes = listOf(Customer::class.java, QueryIsolationRecord::class.java)
    private val request = ExtractionRequest(classes.map { it.name }, discoverRepositories = false, keepGoing = true)

    @Test fun `public keep-going extraction propagates a bootstrap cancellation without retrying`() {
        CancellingNamingStrategy.calls = 0
        val cancellation = assertFailsWith<CancellationException> {
            HibernateExtractor().extract(ExtractionRequest(listOf(QueryIsolationRecord::class.java.name),
                discoverRepositories = false, keepGoing = true, physicalNamingStrategy = CancellingNamingStrategy::class.java.name))
        }
        assertEquals("fixture naming cancellation", cancellation.message)
        assertEquals(1, CancellingNamingStrategy.calls)
    }

    @Test fun `wrapped and suppressed cancellation aborts recovery before any bootstrap attempts`() {
        for (suppressed in listOf(false, true)) {
            var attempts = 0
            val cancellation = CancellationException("fixture cancelled")
            val failure = if (suppressed) IllegalArgumentException("ordinary failure").apply { addSuppressed(cancellation) }
                else InvocationTargetException(cancellation)
            val thrown = assertFails {
                BootstrapRecovery.recover(classes, request, failure, bootstrap = { attempts++; DatabaseContract() })
            }
            assertSame(cancellation, thrown)
            assertEquals(0, attempts)
        }
    }

    @Test fun `cancellation during graph and component recovery cannot become unresolved entities`() {
        for (graph in listOf(false, true)) {
            var attempts = 0
            val cancellation = CancellationException("fixture cancelled during retry")
            val retry: (List<Class<*>>) -> DatabaseContract = {
                attempts++
                throw IllegalStateException("ordinary retry failure").apply { addSuppressed(cancellation) }
            }
            val thrown = assertFails {
                BootstrapRecovery.recover(classes, request, IllegalArgumentException("Malformed ${Customer::class.java.name}"),
                    bootstrap = retry, bootstrapPruned = if (graph) { retained, _ -> retry(retained) } else null)
            }
            assertSame(cancellation, thrown)
            assertEquals(1, attempts)
        }
    }

    @Test fun `bootstrap stack overflow remains fatal including wrappers and close failures`() {
        for (error in listOf<Throwable>(StackOverflowError("bootstrap recursion"), OutOfMemoryError("fixture memory"), ThreadDeath())) {
            for (failure in listOf(InvocationTargetException(error),
                IllegalArgumentException("ordinary failure").apply { addSuppressed(error) })) {
                var attempts = 0
                assertSame(error, assertFails {
                    BootstrapRecovery.recover(classes, request, failure, bootstrap = { attempts++; DatabaseContract() })
                })
                assertEquals(0, attempts)
            }
        }
    }

    @Test fun `wrapped interruption and an existing interrupt flag cannot be swallowed by keep-going`() {
        for (suppressed in listOf(false, true)) {
            val interrupted = InterruptedException("fixture interrupted")
            val failure = if (suppressed) IllegalArgumentException("ordinary failure").apply { addSuppressed(interrupted) }
                else InvocationTargetException(interrupted)
            try {
                assertSame(interrupted, assertFails {
                    BootstrapRecovery.recover(classes, request, failure, bootstrap = { error("Cancellation must not retry") })
                })
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
        }
        val ordinary = IllegalStateException("query failed after cancellation")
        try {
            Thread.currentThread().interrupt()
            assertSame(ordinary, assertFails {
                BootstrapRecovery.recover(classes, request, ordinary, bootstrap = { error("Interrupted extraction must not retry") })
            })
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}

class CancellingNamingStrategy : PhysicalNamingStrategyStandardImpl() {
    override fun toPhysicalTableName(logicalName: Identifier, jdbcEnvironment: JdbcEnvironment): Identifier {
        calls++
        throw CancellationException("fixture naming cancellation")
    }
    companion object { var calls = 0 }
}
