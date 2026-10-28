package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.runCli
import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class OperationalCliIntegrationTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(180)
    fun `verify keeps default compatibility exit while release size input and operational policy change severity and gate`() {
        val contractPath = directory.resolve("contract.json")
        json.writeValue(contractPath.toFile(), DatabaseContract(
            applicationVersion = "operational-cli-proof",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM accounts",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true),
        ))
        val baselineSql = "CREATE TABLE accounts (id bigint PRIMARY KEY);"
        val pendingSql = "CREATE INDEX accounts_id_lookup ON accounts(id);"
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__index.sql").writeText(pendingSql)
        val directReport = directory.resolve("verify.json")

        assertEquals(0, runCli(arrayOf("verify", "--contract", contractPath.toString(),
            "--baseline", baseline.toString(), "--migrations", pending.toString(), "--report", directReport.toString())))
        assertRisk(json.readTree(directReport.toFile()), "HIGH")

        val repository = directory.resolve("fixture-repository").createDirectories()
        val migrations = repository.resolve("db/migration").createDirectories()
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            return output.trim()
        }
        git("init", "--initial-branch=main")
        git("config", "user.name", "Epoch fixture")
        git("config", "user.email", "fixture@example.test")
        git("config", "commit.gpgsign", "false")
        migrations.resolve("V1__baseline.sql").writeText(baselineSql)
        git("add", ".")
        git("commit", "-m", "baseline")
        val baseCommit = git("rev-parse", "HEAD")
        migrations.resolve("V2__index.sql").writeText(pendingSql)
        git("add", ".")
        git("commit", "-m", "index")
        val targetCommit = git("rev-parse", "HEAD")

        val sizesPath = directory.resolve("table-sizes.json")
        json.writeValue(sizesPath.toFile(), TableSizes(tables = mapOf(
            "public.accounts" to TableSize(estimatedRows = 100, totalBytes = 1_048_576),
        )))
        val releaseReport = directory.resolve("release.json")
        assertEquals(4, runCli(arrayOf("verify-release", "--repository", repository.toString(),
            "--base-ref", baseCommit, "--target-ref", targetCommit, "--migration-path", "db/migration",
            "--contract", contractPath.toString(), "--report", releaseReport.toString(),
            "--table-sizes", sizesPath.toString(), "--fail-on-operational")))
        val release = json.readTree(releaseReport.toFile())
        assertEquals(baseCommit, release.path("release").path("baseCommit").asText(), release.toPrettyString())
        assertEquals(targetCommit, release.path("release").path("targetCommit").asText(), release.toPrettyString())
        assertEquals(targetCommit, git("rev-parse", "HEAD"), "Release verification must not move the fixture checkout.")
        assertRisk(release.path("verification"), "LOW")

        // Replaying both portable report shapes performs no second database verification.
        for (path in listOf(directReport, releaseReport)) {
            assertEquals(0, runCli(arrayOf("report", "--input", path.toString(), "--summary")), path.toString())
            assertEquals(4, runCli(arrayOf("report", "--input", path.toString(), "--summary", "--fail-on-operational")), path.toString())
        }
    }

    private fun assertRisk(report: JsonNode, severity: String) {
        assertTrue(report.path("complete").asBoolean(), report.toPrettyString())
        assertEquals("SAFE", report.path("verdict").asText(), report.toPrettyString())
        assertEquals("RISKY", report.path("operationalVerdict").asText(), report.toPrettyString())
        val assessment = report.path("migrations").single().path("operational")
        assertEquals("RISKY", assessment.path("verdict").asText(), assessment.toPrettyString())
        val risks = assessment.path("findings").filter { it.path("ruleId").asText() == "OP_INDEX_BUILD_BLOCKS_WRITES" }
        assertTrue(risks.isNotEmpty(), assessment.toPrettyString())
        assertTrue(risks.all { it.path("verdict").asText() == "RISKY" && it.path("severity").asText() == severity }, assessment.toPrettyString())
    }
}
