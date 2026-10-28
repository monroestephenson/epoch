package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.ColumnReference
import dev.epoch.core.Coverage
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class NoticeCliDiagnosticsTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(240)
    fun `real PostgreSQL notices are quiet by default and visible in fresh verbose invocations`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__notice.sql").writeText(
            "CREATE TABLE IF NOT EXISTS epoch_notice_accounts(id bigint PRIMARY KEY);\n" +
                "CREATE TABLE IF NOT EXISTS epoch_notice_accounts(id bigint PRIMARY KEY);\n")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__compatible.sql").writeText("ALTER TABLE epoch_notice_accounts ADD COLUMN note text;")
        val contract = directory.resolve("contract.json")
        json.writeValue(contract.toFile(), DatabaseContract(applicationVersion = "notice-cli-fixture",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM epoch_notice_accounts",
                tables = listOf("public.epoch_notice_accounts"), columns = listOf(ColumnReference("public.epoch_notice_accounts", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true)))
        val common = listOf("verify", "--contract", contract.toString(), "--baseline", baseline.toString(),
            "--migrations", pending.toString(), "--migration-timeout-seconds", "30", "--global-timeout-seconds", "60")
        // No baseline cache: both commands must actually execute the NOTICE-emitting SQL.
        val quiet = invoke("quiet", common, false)
        val verbose = invoke("verbose", common, true)
        val notice = "relation \"epoch_notice_accounts\" already exists, skipping"
        assertFalse(quiet.errors.contains(notice), quiet.errors)
        assertFalse(quiet.errors.contains(" WARN "), quiet.errors)
        assertFalse(quiet.errors.contains(" INFO "), quiet.errors)
        assertTrue(verbose.errors.contains(notice), verbose.errors)
        assertEquals(quiet.report, verbose.report, "Logging must not alter report evidence")
    }

    private data class Invocation(val report: JsonNode, val errors: String)

    private fun invoke(name: String, common: List<String>, verbose: Boolean): Invocation {
        val report = directory.resolve("$name.json")
        val stdout = directory.resolve("$name.stdout")
        val stderr = directory.resolve("$name.stderr")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = requireNotNull(System.getProperty("epoch.cli.test.classpath"))
        val args = common + listOf("--report", report.toString()) + if (verbose) listOf("--verbose") else emptyList()
        val process = ProcessBuilder(listOf(java, "-Xmx512m", "-cp", classpath, "dev.epoch.cli.MainKt") + args)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "CLI verification timed out: ${Files.readString(stderr)}")
            val errors = Files.readString(stderr)
            assertEquals(0, process.exitValue(), "$errors\n${Files.readString(stdout)}")
            val result = json.readTree(report.toFile())
            assertTrue(result.path("complete").asBoolean(), result.toPrettyString())
            assertEquals("SAFE", result.path("verdict").asText(), result.toPrettyString())
            assertEquals("DISABLED", result.path("baseline").path("cacheStatus").asText())
            return Invocation(result, errors)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        }
    }
}
