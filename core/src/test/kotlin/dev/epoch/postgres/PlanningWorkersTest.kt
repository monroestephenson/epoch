package dev.epoch.postgres

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PlanningWorkersTest {
    @Test @Timeout(10)
    fun `pool is bounded while partition results retain deterministic input order`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val barrier = CyclicBarrier(4)
        val result = PlanningWorkers.mapPartitions((0 until 100).toList(), 4) { batch ->
            val concurrent = active.incrementAndGet()
            maximum.accumulateAndGet(concurrent, ::maxOf)
            try { barrier.await(5, TimeUnit.SECONDS); batch.map { it * 2 } }
            finally { active.decrementAndGet() }
        }
        assertEquals(4, maximum.get())
        assertEquals(0, active.get())
        assertEquals(List(4) { offset -> (offset until 100 step 4).map { it * 2 } }, result)
    }

    @Test fun `worker infrastructure failure escapes instead of becoming a query finding`() {
        val failure = SQLException("Server was stopped", "08006")
        val thrown = assertFailsWith<SQLException> {
            PlanningWorkers.mapPartitions((0 until 8).toList(), 4) { batch ->
                if (0 in batch) throw failure
                batch
            }
        }
        assertSame(failure, thrown)
        assertFailsWith<IllegalArgumentException> { PlanningWorkers.mapPartitions(emptyList<Int>(), 0) { it } }
        assertFailsWith<IllegalArgumentException> { PlanningWorkers.mapPartitions(listOf(1), 33) { it } }
    }
}
