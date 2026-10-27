package dev.epoch.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class BaselineArchiveManifest(
    val format: Int, val key: String, val archive: String, val archiveSha256: String, val schemaSha256: String,
)
internal data class BaselineArchive(val manifest: BaselineArchiveManifest, val path: Path)
internal data class BaselineArchiveLookup(val archive: BaselineArchive?, val hadEntry: Boolean, val reason: String?)

/** Only the manifest is replaced. Published generations are never overwritten or removed by an active verifier. */
internal class BaselineArchiveStore(directory: Path, private val key: String) {
    val root: Path = directory.also { Files.createDirectories(it) }.toRealPath()
    private val manifestPath = root.resolve("$key.manifest.json")
    private val lockPath = root.resolve("$key.lock")
    private val mapper = jacksonObjectMapper()

    fun lookup(): BaselineArchiveLookup {
        val manifestExists = Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)
        val hadEntry = manifestExists || Files.newDirectoryStream(root, "$key.*.dump").use { it.iterator().hasNext() }
        if (!manifestExists) return BaselineArchiveLookup(null, hadEntry,
            if (hadEntry) "Archive generations exist but no committed baseline manifest is available."
            else "No baseline archive exists for this migration/configuration/image fingerprint.")
        return try {
            require(Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS) && Files.size(manifestPath) <= 16384) {
                "Cached baseline manifest is not a supported regular document."
            }
            val manifest: BaselineArchiveManifest = mapper.readValue(manifestPath.toFile())
            require(manifest.format == FORMAT && manifest.key == key) { "Cached baseline manifest format or fingerprint mismatch." }
            require(shaPattern.matches(manifest.archiveSha256) && shaPattern.matches(manifest.schemaSha256)) {
                "Cached baseline manifest contains a malformed archive or schema checksum."
            }
            val pieces = manifest.archive.split('.')
            require(pieces.size == 4 && pieces[0] == key && pieces[2] == manifest.archiveSha256 && pieces[3] == "dump" &&
                runCatching { UUID.fromString(pieces[1]).toString() == pieces[1] }.getOrDefault(false)) {
                "Cached baseline manifest contains an invalid archive generation name."
            }
            val archive = root.resolve(manifest.archive)
            require(Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) { "Cached dump is missing or is not a regular file." }
            require(sha256(archive) == manifest.archiveSha256) { "Cached archive checksum mismatch; the archive is incomplete or corrupted." }
            BaselineArchiveLookup(BaselineArchive(manifest, archive), true, null)
        } catch (failure: Exception) {
            val reason = if (failure is IllegalArgumentException) failure.message.orEmpty()
                else "Cached baseline manifest/archive could not be read (${failure.javaClass.simpleName})."
            BaselineArchiveLookup(null, true, reason)
        }
    }

    fun <T> withWriterLock(operation: () -> T): T = locks.computeIfAbsent(lockPath) { ReentrantLock() }.withLock {
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { operation() }
        }
    }

    fun publish(temporaryDump: Path, schemaChecksum: String): BaselineArchive {
        require(shaPattern.matches(schemaChecksum)) { "Invalid baseline schema checksum" }
        val checksum = sha256(temporaryDump)
        val archive = root.resolve("$key.${UUID.randomUUID()}.$checksum.dump")
        val manifest = BaselineArchiveManifest(FORMAT, key, archive.fileName.toString(), checksum, schemaChecksum)
        val temporaryManifest = Files.createTempFile(root, "$key-", ".manifest.tmp")
        try {
            // Same-directory atomic moves are required: an unsupported filesystem fails clearly,
            // rather than publishing a potentially half-written manifest.
            check(!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) { "Archive generation already exists; refusing to overwrite it." }
            Files.move(temporaryDump, archive, StandardCopyOption.ATOMIC_MOVE)
            mapper.writeValue(temporaryManifest.toFile(), manifest)
            Files.move(temporaryManifest, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporaryManifest) }
        return BaselineArchive(manifest, archive)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val size = input.read(bytes); if (size < 0) break; digest.update(bytes, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val FORMAT = 1
        private val shaPattern = Regex("[0-9a-f]{64}")
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
