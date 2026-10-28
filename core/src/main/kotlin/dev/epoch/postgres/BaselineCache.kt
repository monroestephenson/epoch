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
        field("epoch-baseline-cache-v4;catalog=2;transactionalLock=false;flyway=11.8.2;postgres=${PostgresVerifier.POSTGRES_IMAGE};image=$imageIdentity;placeholders=false;callbacks=false;encoding=UTF-8".toByteArray())
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
            command(database, "pg_dump", "--format=custom", "--username", database.username,
                "--dbname", database.databaseName, "--file", CONTAINER_DUMP)
            val temporary = Files.createTempFile(store.root, "$key-", ".dump.tmp")
            try {
                database.copyFileFromContainer(CONTAINER_DUMP, temporary.toString())
                store.publish(temporary, schemaDigest(database))
            } finally { Files.deleteIfExists(temporary) }
            return CachePreparation(if (hadEntry) "REBUILT" else "MISS", reason)
        }

        fun restore(archive: BaselineArchive): String? {
            database.copyFileToContainer(MountableFile.forHostPath(archive.path), CONTAINER_DUMP)
            // Docker/connection failures deliberately propagate; only a completed restore rejection
            // or catalog mismatch is recoverable cache invalidity.
            val result = database.execInContainer("pg_restore", "--exit-on-error", "--username", database.username,
                "--dbname", database.databaseName, CONTAINER_DUMP)
            if (result.exitCode != 0) return "pg_restore rejected the cached archive (exit ${result.exitCode}): ${result.stderr.trim().take(2000)}"
            val actual = schemaDigest(database)
            return if (actual == archive.manifest.schemaSha256) null
                else "Restored catalog fingerprint differs from the saved baseline (expected=${archive.manifest.schemaSha256}; actual=$actual)."
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

    companion object {
        private const val CONTAINER_DUMP = "/tmp/epoch-baseline.dump"
    }
}
