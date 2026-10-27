package dev.epoch.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OperationalLintTest {
    @TempDir lateinit var directory: Path

    @Test fun `operational review identifies concurrent and ordinary index differences without verdicts`() {
        Files.writeString(directory.resolve("V1__index.sql"), "CREATE INDEX normal ON example(id);")
        Files.writeString(directory.resolve("V2__online.sql"), "CREATE INDEX CONCURRENTLY online ON example(id);")
        val report = OperationalLint().inspect(directory)
        assertTrue(report.findings.any { it.ruleId == "INDEX_WRITE_LOCK_REVIEW" && it.filename == "V1__index.sql" })
        assertTrue(report.findings.any { it.ruleId == "CONCURRENT_INDEX_TRANSACTION" && it.filename == "V2__online.sql" })
        assertTrue(report.scope.contains("no compatibility verdict"))
    }
}
