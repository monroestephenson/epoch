package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.runCli
import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class VerificationCheckpointIntegrationTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    private fun arguments(slowSeconds: Int, timeoutSeconds: Int): Pair<Array<String>, Path> {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts(id bigint PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__compatible.sql").writeText("ALTER TABLE accounts ADD COLUMN note text;")
        pending.resolve("V3__slow.sql").writeText("SELECT pg_sleep($slowSeconds);")
        val contract = DatabaseContract(applicationVersion = "checkpoint-integration",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM accounts",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true))
        val contractPath = directory.resolve("contract.json")
        json.writeValue(contractPath.toFile(), contract)
        val report = directory.resolve("report.json")
        return arrayOf("verify", "--contract", contractPath.toString(), "--baseline", baseline.toString(),
            "--migrations", pending.toString(), "--report", report.toString(), "--planning-parallelism", "2",
            "--migration-timeout-seconds", timeoutSeconds.toString(), "--global-timeout-seconds", "90") to report
    }

    @Test @Timeout(100)
    fun `migration timeout retains verified first step and partial report exits 3`() {
        val (args, report) = arguments(slowSeconds = 10, timeoutSeconds = 3)
        assertEquals(3, runCli(args))
        val partial = json.readTree(report.toFile())
        assertCheckpoint(partial)
        assertEquals("MIGRATION_TIMEOUT", partial["failureStage"].asText(), partial.toPrettyString())
        assertEquals(3, runCli(arrayOf("report", "--input", report.toString(), "--summary")))
    }

    @Test @Timeout(120)
    fun `SIGTERM leaves an atomic incomplete checkpoint from a real CLI process`() {
        val (args, report) = arguments(slowSeconds = 60, timeoutSeconds = 75)
        val classpath = requireNotNull(System.getProperty("epoch.cli.test.classpath"))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val logs = directory.resolve("child-cli.log")
        val process = ProcessBuilder(listOf(java, "-Xmx512m", "-cp", classpath, "dev.epoch.cli.MainKt") + args)
            .redirectErrorStream(true).redirectOutput(logs.toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var checkpoint: JsonNode? = null
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(report)) {
                    // Atomic replacement must never expose truncated JSON to a concurrent reader.
                    val observed = json.readTree(report.toFile())
                    if (observed.path("reports").path("old-on-new").path("migrations").size() == 1) {
                        checkpoint = observed
                        break
                    }
                }
                assertTrue(process.isAlive, "CLI exited before a step checkpoint: ${Files.readString(logs)}")
                Thread.sleep(100)
            }
            assertNotNull(checkpoint, "No verified step checkpoint appeared: ${Files.readString(logs)}")
            assertCheckpoint(requireNotNull(checkpoint))
            assertTrue(process.isAlive, "The CLI must still be running when SIGTERM is sent")
            process.destroy() // SIGTERM on the supported Unix CI runner, never SIGKILL.
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI did not stop after SIGTERM")
            assertNotEquals(0, process.exitValue())
            val partial = json.readTree(report.toFile())
            assertCheckpoint(partial)
            assertEquals("INTERRUPTED", partial["failureStage"].asText(), partial.toPrettyString())
            assertEquals(3, runCli(arrayOf("report", "--input", report.toString(), "--summary")))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun assertCheckpoint(partial: JsonNode) {
        assertEquals(1, partial["schemaVersion"].asInt())
        assertFalse(partial["complete"].asBoolean(), partial.toPrettyString())
        val forward = partial["reports"]["old-on-new"]
        assertFalse(forward["complete"].asBoolean(), partial.toPrettyString())
        assertEquals("UNKNOWN", forward["verdict"].asText(), partial.toPrettyString())
        assertEquals(1, forward["migrations"].size(), partial.toPrettyString())
        assertEquals("V2__compatible.sql", forward["migrations"][0]["migration"]["filename"].asText())
    }
}
