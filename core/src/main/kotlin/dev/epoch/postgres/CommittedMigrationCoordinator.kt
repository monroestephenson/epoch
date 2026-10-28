package dev.epoch.postgres

import dev.epoch.core.MigrationInfo
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import java.sql.Connection
import java.util.concurrent.*

/** One callback gives ordering an explicit owner instead of relying on Flyway's name sorting. */
internal class CommittedMigrationCoordinator<T : Any>(
    private val seconds: Long,
    private val observe: (Event, Context) -> Unit,
    private val capture: (Context, PendingMigrationLease) -> T,
    private val complete: (T, PendingMigrationLease) -> Unit,
) : Callback, AutoCloseable {
    private var lease: PendingMigrationLease? = null
    private var pending: T? = null
    var activeMigration: MigrationInfo? = null
        private set

    override fun getCallbackName() = "epoch-committed-migration-verification"
    override fun supports(event: Event, context: Context?) = event in events
    override fun canHandleInTransaction(event: Event, context: Context?) = true

    override fun handle(event: Event, context: Context) {
        if (event == Event.BEFORE_EACH_MIGRATE) {
            // group=false: the predecessor committed before this group began. The next script
            // has not run, and must not run until its predecessor's checks and checkpoint finish.
            flushCommitted()
            check(lease == null && pending == null) { "Previous migration was not finalized" }
            val info = context.migrationInfo
            activeMigration = MigrationInfo(info.version?.toString() ?: "repeatable", info.script)
            lease = PendingMigrationLease(requireNotNull(activeMigration), seconds, context.connection)
        }
        val current = checkNotNull(lease) { "Migration callback has no active budget" }
        current.checkExpired()
        observe(event, context)
        if (event == Event.AFTER_EACH_MIGRATE) {
            check(pending == null) { "Migration was captured more than once" }
            // This remains inside Flyway's transaction, before history insertion and COMMIT.
            // Immutable operational observations and canonical commit evidence are captured now.
            pending = capture(context, current)
        }
        current.checkExpired()
    }

    /** Call only at BEFORE_EACH_MIGRATE or after the sole migrate() call returned successfully. */
    fun flushCommitted() {
        val value = pending ?: return
        val current = checkNotNull(lease)
        current.checkExpired()
        complete(value, current)
        current.finish()
        pending = null
        lease = null
    }

    fun checkExpired() { lease?.checkExpired() }
    override fun close() { lease?.close() }

    private companion object {
        val events = setOf(Event.BEFORE_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE_ERROR,
            Event.BEFORE_EACH_MIGRATE_STATEMENT, Event.AFTER_EACH_MIGRATE_STATEMENT,
            Event.AFTER_EACH_MIGRATE_STATEMENT_ERROR)
    }
}

/** One monotonic budget covers the script, history/commit, committed checks and publication. */
internal class PendingMigrationLease(
    val migration: MigrationInfo,
    private val seconds: Long,
    owner: Connection,
) : AutoCloseable {
    private enum class State { ACTIVE, EXPIRED, FINISHED }
    private val lock = Any()
    private var state = State.ACTIVE
    private val connections = linkedSetOf(owner)
    private val thread = Thread.currentThread()
    private val expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "epoch-pending-migration-timeout").apply { isDaemon = true }
    }
    private val timer = scheduler.schedule(Runnable { expire() }, seconds, TimeUnit.SECONDS)

    private fun expire() {
        val registered = synchronized(lock) {
            if (state != State.ACTIVE) return
            state = State.EXPIRED
            connections.toList()
        }
        // Aborting a JDBC connection must not delay cancellation of other workers or the owner.
        registered.forEach { connection ->
            Thread({ runCatching { connection.abort { it.run() } } }, "epoch-pending-connection-abort")
                .apply { isDaemon = true }.start()
        }
        thread.interrupt()
    }

    private fun timeout(cause: Throwable? = null) = VerificationException("MIGRATION_TIMEOUT", migration,
        "Migration ${migration.filename} and its verification exceeded the per-migration timeout of $seconds seconds.", cause)

    fun checkExpired() {
        if (System.nanoTime() - expiresAt >= 0) expire()
        synchronized(lock) { if (state == State.EXPIRED) throw timeout() }
        if (Thread.currentThread().isInterrupted) throw VerificationException("INTERRUPTED", migration,
            "Verification of migration ${migration.filename} was interrupted.")
    }

    fun <T> withConnection(factory: () -> Connection, action: (Connection) -> T): T {
        checkExpired()
        val connection = factory()
        try {
            return connection.use {
                synchronized(lock) {
                    if (state != State.ACTIVE) throw timeout()
                    connections += connection
                }
                checkExpired()
                action(connection).also { checkExpired() }
            }
        } finally {
            synchronized(lock) { connections -= connection }
        }
    }

    /** Infrastructure failures abort siblings without relabeling the original error as timeout. */
    fun abortWork() {
        synchronized(lock) { connections.toList() }.forEach { connection ->
            Thread({ runCatching { connection.abort { it.run() } } }, "epoch-pending-failure-abort")
                .apply { isDaemon = true }.start()
        }
    }

    fun <T> completed(completion: CompletionService<T>): Future<T> {
        checkExpired()
        return try {
            completion.poll(maxOf(1, expiresAt - System.nanoTime()), TimeUnit.NANOSECONDS)
                ?: run { expire(); throw timeout() }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            checkExpired()
            throw VerificationException("INTERRUPTED", migration,
                "Verification of migration ${migration.filename} was interrupted.", failure)
        }
    }

    fun <T> await(future: Future<T>): T {
        checkExpired()
        return try {
            future.get(maxOf(1, expiresAt - System.nanoTime()), TimeUnit.NANOSECONDS)
        } catch (failure: TimeoutException) {
            expire()
            throw timeout(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            checkExpired()
            throw VerificationException("INTERRUPTED", migration,
                "Verification of migration ${migration.filename} was interrupted.", failure)
        } catch (failure: ExecutionException) {
            checkExpired()
            throw failure.cause ?: failure
        }
    }

    fun finish() {
        checkExpired()
        synchronized(lock) {
            if (state == State.EXPIRED) throw timeout()
            check(state == State.ACTIVE)
            state = State.FINISHED
        }
        timer.cancel(false)
        scheduler.shutdownNow()
    }

    override fun close() {
        synchronized(lock) { if (state == State.ACTIVE) state = State.FINISHED }
        timer.cancel(false)
        scheduler.shutdownNow()
    }
}
