package dev.epoch

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Cache assertions inspect real restored schema, data, and Flyway history in disposable servers. */
class FeedbackBaselineCacheTest {
    @TempDir lateinit var directory: Path

    @Test fun `cached baseline restores data without pending state and invalidates on content or corruption`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val baselineFile = baseline.resolve("V1__baseline.sql")
        val contract = PostgresCompatibilityTest.customerContract()
        val cache = directory.resolve("cache")
        val options = VerificationOptions(baselineCache = cache)
        fun archiveFor(key: String): Path {
            val manifest = jacksonObjectMapper().readTree(cache.resolve("$key.manifest.json").toFile())
            return cache.resolve(manifest.path("archive").asText())
        }
        fun writeBaseline(email: String) {
            // pg_dump/restore compacts attnum gaps. Logical visible column order must
            // produce the same catalog fingerprint after restoring real migration history.
            baselineFile.writeText(PostgresCompatibilityTest.BASELINE + """

                ALTER TABLE audit_log ADD COLUMN historical_gap text;
                ALTER TABLE audit_log ADD COLUMN preserved text;
                ALTER TABLE audit_log DROP COLUMN historical_gap;
                INSERT INTO customers (id, email) VALUES (1, '$email');
            """.trimIndent())
        }
        fun pending(name: String, sql: String) = directory.resolve(name).createDirectories().also {
            it.resolve("V2__pending.sql").writeText(sql)
        }
        fun assertHealthyExecution(report: CompatibilityReport) {
            assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "MIGRATION_EXECUTION_FAILED" }, report.toString())
            assertEquals(listOf("2"), report.migrations.map { it.migration.version }, report.toString())
        }
        fun ensureSeed(email: String): String = """
            DO ${'$'}${'$'} BEGIN
              IF NOT EXISTS (SELECT 1 FROM customers WHERE id = 1 AND email = '$email') THEN
                RAISE EXCEPTION 'Expected baseline seed was not restored';
              END IF;
            END ${'$'}${'$'};
            ALTER TABLE customers ADD COLUMN metadata text;
        """.trimIndent()

        writeBaseline("original@example.test")
        val first = PostgresVerifier().verify(contract, baseline,
            pending("delete", "DELETE FROM customers; ALTER TABLE customers ADD COLUMN changed_by_pending text;"), options)
        assertHealthyExecution(first)
        assertEquals("MISS", requireNotNull(first.baseline).cacheStatus)
        assertFalse(requireNotNull(first.baseline).cacheReason.isNullOrBlank(), "Cache misses must explain their cause")
        val firstKey = requireNotNull(first.baseline).cacheKey
        assertFalse(firstKey.isNullOrBlank())

        val second = PostgresVerifier().verify(contract, baseline,
            pending("restore", ensureSeed("original@example.test")), options)
        assertHealthyExecution(second)
        assertEquals("HIT", requireNotNull(second.baseline).cacheStatus)
        assertEquals(firstKey, requireNotNull(second.baseline).cacheKey)
        assertEquals(requireNotNull(first.baseline).fingerprint, requireNotNull(second.baseline).fingerprint)
        assertTrue(second.migrations.flatMap { it.findings }.none { it.objectName.contains("changed_by_pending") },
            "Cached baseline must not include a previous run's pending schema")

        writeBaseline("new@example.test")
        val changed = PostgresVerifier().verify(contract, baseline,
            pending("changed", ensureSeed("new@example.test")), options)
        assertHealthyExecution(changed)
        val changedHealth = requireNotNull(changed.baseline)
        assertEquals("MISS", changedHealth.cacheStatus)
        assertNotEquals(firstKey, changedHealth.cacheKey)
        assertNotEquals(requireNotNull(first.baseline).fingerprint, changedHealth.fingerprint)

        val archive = archiveFor(requireNotNull(changedHealth.cacheKey))
        archive.writeText("intentionally corrupted archive fixture")
        val rebuilt = PostgresVerifier().verify(contract, baseline,
            pending("rebuilt", ensureSeed("new@example.test")), options)
        assertHealthyExecution(rebuilt)
        assertEquals("REBUILT", requireNotNull(rebuilt.baseline).cacheStatus)
        assertFalse(requireNotNull(rebuilt.baseline).cacheReason.isNullOrBlank(), "Cache rebuild must explain its cause")
        assertEquals(changedHealth.cacheKey, requireNotNull(rebuilt.baseline).cacheKey)
        assertTrue(Files.size(archiveFor(requireNotNull(changedHealth.cacheKey))) > 100,
            "The published manifest must reference a repaired immutable archive generation")

        // The archive still authenticates and restores successfully; only its claimed
        // catalog digest is wrong. Rebuilding must reset the partially restored owned
        // database before replaying the baseline, including its seed and Flyway history.
        val manifestFile = cache.resolve("${changedHealth.cacheKey}.manifest.json")
        val mapper = jacksonObjectMapper()
        val manifest = mapper.readTree(manifestFile.toFile()) as ObjectNode
        manifest.put("schemaSha256", "0".repeat(64))
        mapper.writeValue(manifestFile.toFile(), manifest)
        val catalogRebuilt = PostgresVerifier().verify(contract, baseline,
            pending("catalog-rebuilt", ensureSeed("new@example.test")), options)
        assertHealthyExecution(catalogRebuilt)
        val catalogHealth = requireNotNull(catalogRebuilt.baseline)
        assertEquals("REBUILT", catalogHealth.cacheStatus)
        assertTrue(catalogHealth.cacheReason.orEmpty().contains("Restored catalog fingerprint differs"),
            catalogHealth.toString())
        assertEquals(changedHealth.cacheKey, catalogHealth.cacheKey)
        assertEquals(changedHealth.fingerprint, catalogHealth.fingerprint)
        assertNotEquals("0".repeat(64), mapper.readTree(manifestFile.toFile()).path("schemaSha256").asText())
        assertTrue(Files.size(archiveFor(requireNotNull(catalogHealth.cacheKey))) > 100)
    }
}
