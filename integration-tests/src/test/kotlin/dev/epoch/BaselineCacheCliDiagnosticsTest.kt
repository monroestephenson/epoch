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

/** Each CLI process configures logging independently, as a real command invocation does. */
class BaselineCacheCliDiagnosticsTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(240)
    fun `cold verbose miss and corrupt rebuild explain themselves while a warm hit stays quiet`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts(id bigint PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__compatible.sql").writeText("ALTER TABLE accounts ADD COLUMN note text;")
        val cache = directory.resolve("cache")
        val contractPath = directory.resolve("contract.json")
        val contract = DatabaseContract(applicationVersion = "cache-cli-diagnostics",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM accounts",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                dependenciesComplete = true)), coverage = Coverage(complete = true))
        json.writeValue(contractPath.toFile(), contract)
        val common = listOf("verify", "--contract", contractPath.toString(), "--baseline", baseline.toString(),
            "--migrations", pending.toString(), "--baseline-cache", cache.toString(),
            "--migration-timeout-seconds", "30", "--global-timeout-seconds", "60")

        val cold = invoke("cold", common, verbose = true)
        assertEquals("MISS", cold.baseline["cacheStatus"].asText())
        assertLoggedReason(cold, "MISS")

        val warm = invoke("warm", common, verbose = false)
        assertEquals("HIT", warm.baseline["cacheStatus"].asText())
        assertEquals(cold.baseline["cacheKey"], warm.baseline["cacheKey"])
        assertFalse(warm.errors.contains(" INFO "), warm.errors)
        assertFalse(warm.errors.contains(" WARN "), warm.errors)
        assertFalse(warm.errors.contains("Baseline cache"), warm.errors)

        val manifest = cache.resolve("${warm.baseline["cacheKey"].asText()}.manifest.json")
        val archive = cache.resolve(json.readTree(manifest.toFile())["archive"].asText()).normalize()
        assertEquals(cache, archive.parent, "The published archive must belong to this test's cache directory")
        archive.writeText("intentionally corrupted baseline archive")
        val rebuilt = invoke("rebuilt", common, verbose = true)
        assertEquals("REBUILT", rebuilt.baseline["cacheStatus"].asText())
        assertEquals(cold.baseline["cacheKey"], rebuilt.baseline["cacheKey"])
        assertLoggedReason(rebuilt, "REBUILT")
        assertTrue(rebuilt.baseline["cacheReason"].asText().contains("checksum", ignoreCase = true), rebuilt.baseline.toPrettyString())
    }

    private fun assertLoggedReason(run: Invocation, status: String) {
        val reason = run.baseline.path("cacheReason").asText()
        assertTrue(reason.isNotBlank(), "A $status cache result must retain its reason")
        assertTrue(run.errors.contains(reason), "Verbose stderr omitted the JSON reason '$reason': ${run.errors}")
        assertTrue(run.errors.lineSequence().any { it.contains(status) && it.contains(reason) }, run.errors)
    }

    private data class Invocation(val baseline: JsonNode, val errors: String)

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
            assertTrue(process.waitFor(75, TimeUnit.SECONDS), "CLI $name did not finish: ${Files.readString(stderr)}")
            val errors = Files.readString(stderr)
            assertEquals(0, process.exitValue(), "CLI $name failed: $errors\n${Files.readString(stdout)}")
            val tree = json.readTree(report.toFile())
            assertTrue(tree["complete"].asBoolean(), tree.toPrettyString())
            assertEquals("SAFE", tree["verdict"].asText(), tree.toPrettyString())
            return Invocation(tree["baseline"], errors)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }
}
