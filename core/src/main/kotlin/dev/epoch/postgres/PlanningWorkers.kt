package dev.epoch.postgres

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Bounded, deterministic partitioning. Worker completion order never controls result order. */
internal object PlanningWorkers {
    fun <T, R> mapPartitions(items: List<T>, parallelism: Int, operation: (List<T>) -> R): List<R> {
        require(parallelism in 1..32) { "planningParallelism must be between 1 and 32" }
        if (items.isEmpty()) return emptyList()
        val count = minOf(parallelism, items.size)
        if (count == 1) return listOf(operation(items))
        val partitions = List(count) { mutableListOf<T>() }
        items.forEachIndexed { index, item -> partitions[index % count] += item }
        val executor = Executors.newFixedThreadPool(count) { task -> Thread(task, "epoch-query-planner").apply { isDaemon = true } }
        val futures = partitions.map { batch -> executor.submit(Callable { operation(batch) }) }
        try {
            return futures.map { future ->
                try { future.get() } catch (failure: ExecutionException) { throw failure.cause ?: failure }
            }
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }
}
