package dev.epoch.hibernate

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Resource-close failures may be suppressed rather than causes; neither may hide cancellation. */
internal object ExtractionFailurePolicy {
    fun rethrowNonRecoverable(error: Throwable, recoverQueryStackOverflow: Boolean = false) {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending += error
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is ThreadDeath || current is VirtualMachineError &&
                (!recoverQueryStackOverflow || current !is StackOverflowError)) throw current
            if (current is CancellationException) throw current
            if (current is InterruptedException) {
                Thread.currentThread().interrupt()
                throw current
            }
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        if (Thread.currentThread().isInterrupted) throw error
    }
}
