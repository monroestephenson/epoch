package dev.epoch.postgres

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager

internal data class CachePreparation(val status: String, val reason: String? = null)

/** Cache only the fully migrated baseline, never a database touched by pending migrations.
 * Every restore targets a fresh database owned by the current verification container.
 */
internal class BaselineCache(private val directory: Path?) {
    fun key(baselineDirectory: Path, imageIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        field("epoch-baseline-cache-v5;catalog=5;transactionalLock=false;flyway=11.8.2;postgres=${PostgresVerifier.POSTGRES_IMAGE};image=$imageIdentity;placeholders=false;callbacks=false;encoding=UTF-8".toByteArray())
        Files.walk(baselineDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                field(baselineDirectory.relativize(path).toString().replace('\\', '/').toByteArray())
                field(Files.readAllBytes(path))
            }
        }
        return digest.digest().hex()
    }

    fun prepare(database: PostgreSQLContainer, key: String, migrateBaseline: () -> Unit): CachePreparation {
        val directory = directory ?: run { migrateBaseline(); return CachePreparation("DISABLED") }
        val store = BaselineArchiveStore(directory, key)
        val rejected = mutableSetOf<String>()
        var restoreFailure: String? = null
        var hadEntry = false
        var attempts = 0

        fun rebuild(reason: String): CachePreparation {
            migrateBaseline()
            withContainerDump(database) { dump ->
                command(database, "pg_dump", "--format=custom", "--username", database.username,
                    "--dbname", database.databaseName, "--file", dump)
                val temporary = Files.createTempFile(store.root, "$key-", ".dump.tmp")
                var failure: Throwable? = null
                try {
                    database.copyFileFromContainer(dump, temporary.toString())
                    store.publish(temporary, schemaDigest(database))
                } catch (caught: Throwable) { failure = caught; throw caught }
                finally {
                    try { Files.deleteIfExists(temporary) }
                    catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup
                        failure.addSuppressed(cleanup)
                    }
                }
            }
            return CachePreparation(if (hadEntry) "REBUILT" else "MISS", reason)
        }

        fun restore(archive: BaselineArchive): String? = withContainerDump(database) { dump ->
            // Docker preserves host ownership. The enclosing directory is private to the
            // exec user; a readable input permits pg_restore even when those UIDs differ.
            database.copyFileToContainer(MountableFile.forHostPath(archive.path, 0b110_100_100), dump)
            // Docker/connection failures deliberately propagate; only a completed restore rejection
            // or catalog mismatch is recoverable cache invalidity.
            val result = database.execInContainer("pg_restore", "--exit-on-error", "--username", database.username,
                "--dbname", database.databaseName, dump)
            if (result.exitCode != 0) "pg_restore rejected the cached archive (exit ${result.exitCode}): ${result.stderr.trim().take(2000)}"
            else {
                val actual = schemaDigest(database)
                if (actual == archive.manifest.schemaSha256) null
                else "Restored catalog fingerprint differs from the saved baseline (expected=${archive.manifest.schemaSha256}; actual=$actual)."
            }
        }

        fun tryRestore(archive: BaselineArchive): Boolean {
            attempts++
            val failure = restore(archive) ?: return true
            restoreFailure = failure
            rejected += archive.manifest.archive
            // Restore may have committed some objects. Reset only this run's owned database.
            command(database, "dropdb", "--force", "--username", database.username, database.databaseName)
            command(database, "createdb", "--username", database.username, "--owner", database.username, database.databaseName)
            return false
        }

        val initial = store.lookup()
        hadEntry = initial.hadEntry
        if (initial.archive != null && tryRestore(initial.archive)) return CachePreparation("HIT")
        while (true) {
            // The recheck prevents duplicate cold builds. A newly published valid entry is
            // returned from the lock before any restore, so waiting readers never serialize restores.
            val decision = store.withWriterLock {
                val current = store.lookup()
                hadEntry = hadEntry || current.hadEntry
                if (current.archive != null && current.archive.manifest.archive !in rejected && attempts < 3)
                    CacheDecision.Restore(current.archive)
                else CacheDecision.Built(rebuild(restoreFailure ?: current.reason ?: initial.reason ?: "Cached baseline generation could not be restored."))
            }
            when (decision) {
                is CacheDecision.Built -> return decision.preparation
                is CacheDecision.Restore -> if (tryRestore(decision.archive)) return CachePreparation("HIT")
            }
        }
    }

    private sealed interface CacheDecision {
        data class Restore(val archive: BaselineArchive) : CacheDecision
        data class Built(val preparation: CachePreparation) : CacheDecision
    }

    private fun schemaDigest(database: PostgreSQLContainer): String {
        val snapshot = DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use {
            PostgresCatalog().inspect(it)
        }
        val canonical = jacksonObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .writeValueAsBytes(snapshot)
        return MessageDigest.getInstance("SHA-256").digest(canonical).hex()
    }

    private fun command(database: PostgreSQLContainer, vararg command: String) {
        val result = database.execInContainer(*command)
        check(result.exitCode == 0) { "Baseline cache ${command.first()} failed (exit ${result.exitCode}): ${result.stderr.trim()}" }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Never overwrite a Docker-copied archive, particularly in sticky /tmp with
     * fs.protected_regular enabled. Each restore/dump owns a fresh private directory. */
    private fun <T> withContainerDump(database: PostgreSQLContainer, operation: (String) -> T): T {
        val created = database.execInContainer("mktemp", "-d", "/tmp/epoch-baseline.XXXXXXXXXX")
        check(created.exitCode == 0) { "Baseline cache temporary directory failed (exit ${created.exitCode}): ${created.stderr.trim()}" }
        val directory = created.stdout.trim()
        check(Regex("/tmp/epoch-baseline\\.[A-Za-z0-9]{10}").matches(directory)) {
            "Baseline cache temporary directory returned an unexpected path."
        }
        var failure: Throwable? = null
        try { return operation("$directory/archive.dump") }
        catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            try { command(database, "rm", "-rf", "--", directory) }
            catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                failure.addSuppressed(cleanup)
            }
        }
    }
}
