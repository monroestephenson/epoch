package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OperationalInputTest {
    @TempDir lateinit var directory: Path

    private fun input(value: String): TableSizes? {
        val path = directory.resolve("sizes.json")
        Files.writeString(path, value)
        return readTableSizes(path)
    }

    @Test fun `metadata estimates retain exact integers and optional unknown values`() {
        assertNull(readTableSizes(null))
        assertEquals(TableSizes(tables = mapOf("public.accounts" to TableSize(Long.MAX_VALUE, null),
            "public.empty" to TableSize(0, 0), "public.unknown" to TableSize())), input("""
            {"schemaVersion":1,"tables":{"public.accounts":{"estimatedRows":9223372036854775807,"totalBytes":null},
            "public.empty":{"estimatedRows":0,"totalBytes":0},"public.unknown":{}}}
        """.trimIndent()))
    }

    @Test fun `ambiguous malformed and lossy metadata fail before verification`() {
        val values = listOf("-1", "1.5", "\"123\"", "9223372036854775808", "true", "[]")
        for (value in values) assertThrows(Exception::class.java, {
            input("""{"schemaVersion":1,"tables":{"public.accounts":{"estimatedRows":$value}}}""")
        }, value)
        for (value in listOf(
            """{"schemaVersion":1,"tables":{"public.accounts":{},"public.accounts":{}}}""",
            """{"schemaVersion":1,"tables":{"public.accounts":{"totalBytes":1,"totalBytes":2}}}""",
            """{"schemaVersion":1,"tables":{"public.accounts":{"typo":12}}}""",
            """{"schemaVersion":1,"tables":{},"typo":12}""",
            """{"schemaVersion":2,"tables":{}}""",
            """{"schemaVersion":1.0,"tables":{}}""",
            """{"schemaVersion":1,"tables":{"public.accounts":null}}""",
            """{"schemaVersion":1,"tables":[]}""",
            """{"tables":{}}""",
        )) assertThrows(Exception::class.java, { input(value) }, value)
    }

    @Test fun `report shows separate risk with measured facts and migration authored timeout`() {
        val migration = MigrationInfo("2", "V2__index.sql")
        val risk = OperationalRiskFinding("OP_INDEX_BUILD_BLOCKS_WRITES", OperationalVerdict.RISKY,
            "public.accounts", "Index build blocks writes", "Catalog-confirmed ordinary index",
            migration, 2, OperationalSeverity.LOW, "Use a concurrent index build")
        val timeout = OperationalTimeout(750, "session", OperationalTimeoutOrigin.MIGRATION_LOCAL)
        val statement = OperationalStatementReport(migration, 2, "CREATE INDEX idx ON accounts(id)", false,
            timeout, timeout, emptyList(), listOf(OperationalLock("public.accounts", "table", "ShareLock")),
            emptyList(), emptyList(), listOf(risk), OperationalVerdict.RISKY)
        val report = CompatibilityReport(applicationVersion = "v1", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.SAFE, migrations = listOf(MigrationReport(migration, Verdict.SAFE, emptyList(),
                operational = OperationalAssessment(OperationalVerdict.RISKY, listOf(statement), listOf(risk), emptyList()))),
            operationalVerdict = OperationalVerdict.RISKY)
        val compact = renderReport(report)
        assertTrue(compact.contains("Result: SAFE"), compact)
        assertTrue(compact.contains("Operational result: RISKY"), compact)
        assertTrue(compact.contains("[LOW]"), compact)
        assertFalse(compact.contains("lock_timeout:"), compact)
        val details = renderReport(report, allDetails = true)
        assertTrue(details.contains("750ms (MIGRATION_LOCAL)"), details)
        assertTrue(details.contains("Held: public.accounts (table) ShareLock"), details)
        assertTrue(details.contains(statement.sql), details)
        assertFalse(renderReport(report, summaryOnly = true).contains(statement.sql))
    }
}
