package dev.epoch.postgres

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BaselineArchiveStoreTest {
    @TempDir lateinit var directory: Path
    private val key = "a".repeat(64)
    private val schema = "b".repeat(64)
    private fun dump(content: String) = Files.createTempFile(directory, "dump-", ".tmp").also { Files.writeString(it, content) }

    @Test fun `manifest publication preserves every existing immutable generation even when dump bytes match`() {
        val store = BaselineArchiveStore(directory, key)
        val absent = store.lookup()
        assertFalse(absent.hadEntry)
        assertNotNull(absent.reason)
        val first = store.publish(dump("baseline data"), schema)
        val second = store.publish(dump("baseline data"), schema)
        assertNotEquals(first.path, second.path)
        assertEquals(first.manifest.archiveSha256, second.manifest.archiveSha256)
        assertEquals("baseline data", Files.readString(first.path))
        assertEquals(second, store.lookup().archive)
        Files.writeString(first.path, "external corruption of an old generation")
        assertEquals(second, store.lookup().archive, "An obsolete generation cannot invalidate the published generation")
    }

    @Test fun `manifest validates generation checksum and prevents paths outside its cache directory`() {
        val store = BaselineArchiveStore(directory, key)
        val archive = store.publish(dump("archive"), schema)
        Files.writeString(archive.path, "corrupted archive")
        assertTrue(store.lookup().reason.orEmpty().contains("checksum mismatch"))
        val mapper = jacksonObjectMapper()
        mapper.writeValue(directory.resolve("$key.manifest.json").toFile(), archive.manifest.copy(archive = "../outside.dump"))
        val invalid = store.lookup()
        assertNull(invalid.archive)
        assertTrue(invalid.reason.orEmpty().contains("generation name"))
        assertTrue(invalid.hadEntry)
    }

    @Test @Timeout(10)
    fun `warm immutable reads complete while writer coordination lock is held`() {
        val store = BaselineArchiveStore(directory, key)
        val published = store.publish(dump("archive"), schema)
        val readers = Executors.newFixedThreadPool(2)
        try {
            store.withWriterLock {
                val futures = (1..2).map { readers.submit<BaselineArchiveLookup> { store.lookup() } }
                futures.forEach { assertEquals(published, it.get(5, TimeUnit.SECONDS).archive) }
            }
        } finally { readers.shutdownNow() }
    }
}
