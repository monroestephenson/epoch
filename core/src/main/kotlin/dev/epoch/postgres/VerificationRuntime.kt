package dev.epoch.postgres

import dev.epoch.core.MigrationInfo
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import java.sql.Connection
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Failure to perform verification is distinct from a completed UNKNOWN compatibility result. */
class VerificationException(
    val stage: String,
    val migration: MigrationInfo? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class VerificationDeadline(private val seconds: Long) {
    fun <T> run(cleanup: () -> Unit, operation: () -> T): T {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "epoch-verification").apply { isDaemon = true } }
        val future = worker.submit(Callable(operation))
        try {
            return future.get(seconds, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            future.cancel(true)
            // Cleanup must not delay delivery of the global timeout when Docker itself is stalled.
            Thread({ runCatching(cleanup) }, "epoch-timeout-cleanup").apply { isDaemon = true }.start()
            throw VerificationException("GLOBAL_TIMEOUT", message = "Verification exceeded the global timeout of $seconds seconds; the owned container is being stopped.", cause = failure)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread({ runCatching(cleanup) }, "epoch-interrupted-cleanup").apply { isDaemon = true }.start()
            Thread.currentThread().interrupt()
            throw VerificationException("INTERRUPTED", message = "Verification was interrupted.", cause = failure)
        } finally {
            worker.shutdownNow()
        }
    }
}

/** Bounds the whole migration, including scripts containing many individually short statements. */
internal class MigrationDeadline(private val seconds: Long) : Callback, AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "epoch-migration-timeout").apply { isDaemon = true }
    }
    private var deadline: ScheduledFuture<*>? = null
    private val expired = AtomicReference<MigrationInfo?>()
    var activeMigration: MigrationInfo? = null
        private set

    override fun getCallbackName() = "epoch-migration-deadline"
    override fun supports(event: Event, context: Context?) = event in setOf(
        Event.BEFORE_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE, Event.AFTER_EACH_MIGRATE_ERROR,
    )
    override fun canHandleInTransaction(event: Event, context: Context?) = true
    override fun handle(event: Event, context: Context) {
        if (event == Event.BEFORE_EACH_MIGRATE) {
            val info = context.migrationInfo
            val migration = MigrationInfo(info.version?.toString() ?: "repeatable", info.script)
            activeMigration = migration
            expired.set(null)
            deadline?.cancel(false)
            val connection: Connection = context.connection
            deadline = scheduler.schedule({
                expired.set(migration)
                runCatching { connection.abort { task -> task.run() } }
            }, seconds, TimeUnit.SECONDS)
        } else {
            deadline?.cancel(false)
            checkExpired()
        }
    }

    fun checkExpired() {
        expired.get()?.let { migration ->
            throw VerificationException("MIGRATION_TIMEOUT", migration,
                "Migration ${migration.filename} exceeded the per-migration timeout of $seconds seconds.")
        }
    }

    override fun close() { deadline?.cancel(false); scheduler.shutdownNow() }
}
