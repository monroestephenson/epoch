package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import dev.epoch.core.QuerySource
import dev.epoch.core.VerificationStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RuntimeSqlInputTest {
    @TempDir lateinit var directory: Path

    @Test fun `StatementInspector writes reusable SQL templates with factual stack provenance`() {
        val file = directory.resolve("observed.jsonl")
        val previous = System.getProperty("epoch.sql.capture")
        try {
            System.setProperty("epoch.sql.capture", file.toString())
            val collector = SqlCollector()
            assertEquals("select id from customers", collector.inspect("select id from customers"))
            collector.inspect("select id from customers")
        } finally {
            if (previous == null) System.clearProperty("epoch.sql.capture") else System.setProperty("epoch.sql.capture", previous)
        }
        val records = SqlCollector.read(file)
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.callSites.any { frame -> frame.contains("RuntimeSqlInputTest") } })
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            runtimeSqlFiles = listOf(file.toString()), discoverRepositories = false))
        val observed = contract.queries.single { it.source == QuerySource.RUNTIME_OBSERVED }
        assertEquals(VerificationStatus.OBSERVED, observed.verificationStatus)
        assertFalse(observed.dependenciesComplete)
        assertFalse(contract.coverage.complete)
        assertTrue(observed.observationFrames.isNotEmpty())
        assertTrue(contract.accessPaths.any { it.status == AccessPathStatus.OBSERVED && observed.id in it.queryIds })
    }

    @Test fun `invalid runtime evidence file fails with exact source location`() {
        val file = directory.resolve("invalid.jsonl")
        Files.writeString(file, "{\"schemaVersion\":99,\"sql\":\"select 1\"}\n")
        val error = assertFailsWith<IllegalArgumentException> { SqlCollector.read(file) }
        assertTrue(error.message!!.contains("invalid.jsonl:1"))
    }
}
