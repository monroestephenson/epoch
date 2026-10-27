package dev.epoch.postgres

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
        field("epoch-baseline-cache-v1;flyway=11.8.2;postgres=${PostgresVerifier.POSTGRES_IMAGE};image=$imageIdentity;placeholders=false;callbacks=false;encoding=UTF-8".toByteArray())
        Files.walk(baselineDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                field(baselineDirectory.relativize(path).toString().replace('\\', '/').toByteArray())
                field(Files.readAllBytes(path))
            }
        }
        return digest.digest().hex()
    }

    fun prepare(database: PostgreSQLContainer, key: String, migrateBaseline: () -> Unit): String {
        val directory = directory ?: run { migrateBaseline(); return "DISABLED" }
        Files.createDirectories(directory)
        val root = directory.toRealPath()
        val lockPath = root.resolve("$key.lock")
        // File locks coordinate independent CLI processes; the JVM lock covers threads in one process.
        return locks.computeIfAbsent(lockPath) { ReentrantLock() }.withLock {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val dump = root.resolve("$key.dump")
                    val checksum = root.resolve("$key.sha256")
                    val schemaChecksum = root.resolve("$key.schema.sha256")
                    val hadEntry = Files.exists(dump) || Files.exists(checksum) || Files.exists(schemaChecksum)
                    val expectedArchive = checksumValue(checksum)
                    val expectedSchema = checksumValue(schemaChecksum)
                    val valid = Files.isRegularFile(dump) && expectedArchive != null && expectedSchema != null &&
                        sha256(dump) == expectedArchive
                    if (valid) {
                        database.copyFileToContainer(MountableFile.forHostPath(dump), CONTAINER_DUMP)
                        val restored = database.execInContainer("pg_restore", "--exit-on-error",
                            "--username", database.username, "--dbname", database.databaseName, CONTAINER_DUMP)
                        if (restored.exitCode == 0 && schemaDigest(database) == expectedSchema) return@withLock "HIT"
                        // An authenticated but unusable archive may have partially restored objects.
                        // Recreate only this verifier-owned database before rebuilding it.
                        command(database, "dropdb", "--force", "--username", database.username, database.databaseName)
                        command(database, "createdb", "--username", database.username, "--owner", database.username, database.databaseName)
                    }
                    migrateBaseline()
                    command(database, "pg_dump", "--format=custom",
                        "--username", database.username, "--dbname", database.databaseName, "--file", CONTAINER_DUMP)
                    val temporaryDump = Files.createTempFile(root, "$key-", ".tmp")
                    val temporaryChecksum = Files.createTempFile(root, "$key-", ".sha256.tmp")
                    val temporarySchemaChecksum = Files.createTempFile(root, "$key-", ".schema.sha256.tmp")
                    try {
                        database.copyFileFromContainer(CONTAINER_DUMP, temporaryDump.toString())
                        Files.writeString(temporaryChecksum, sha256(temporaryDump) + "\n")
                        Files.writeString(temporarySchemaChecksum, schemaDigest(database) + "\n")
                        atomicReplace(temporaryDump, dump)
                        atomicReplace(temporaryChecksum, checksum)
                        atomicReplace(temporarySchemaChecksum, schemaChecksum)
                    } finally {
                        Files.deleteIfExists(temporaryDump)
                        Files.deleteIfExists(temporaryChecksum)
                        Files.deleteIfExists(temporarySchemaChecksum)
                    }
                    if (hadEntry) "REBUILT" else "MISS"
                }
            }
        }
    }

    private fun checksumValue(path: Path): String? = try {
        if (!Files.isRegularFile(path) || Files.size(path) > 256) null
        else Files.readString(path).trim().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    } catch (_: IOException) { null }

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

    private fun atomicReplace(source: Path, target: Path) {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
        private const val CONTAINER_DUMP = "/tmp/epoch-baseline.dump"
    }
}
