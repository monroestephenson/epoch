package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.fixtures.PlainCustomer
import dev.epoch.hibernate.ExtractionRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Fresh CLI processes exercise logging exactly as separate developer invocations do. */
class QueryFailureDiagnosticsTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(150)
    fun `caught query failures retain portable causes and verbose traces without losing healthy SQL`() {
        val descriptor = directory.resolve("descriptor.json")
        json.writeValue(descriptor.toFile(), ExtractionRequest(
            entityClasses = listOf(PlainCustomer::class.java.name), discoverRepositories = false,
            hqlQueries = linkedMapOf(
                BAD_QUERY to "select c.missingProperty from PlainCustomer c",
                GOOD_QUERY to "select c.email from PlainCustomer c where c.id = :id",
            ),
        ))
        val quiet = invoke(descriptor, "quiet", false)
        val verbose = invoke(descriptor, "verbose", true)
        assertEquals(quiet.contract, verbose.contract, "Diagnostic verbosity must not change portable contract evidence")
        for (run in listOf(quiet, verbose)) {
            assertTrue(run.contract.path("queries").any { it.path("id").asText() == GOOD_QUERY }, run.contract.toPrettyString())
            assertFalse(run.contract.path("queries").any { it.path("id").asText() == BAD_QUERY }, run.contract.toPrettyString())
            val failure = run.contract.path("unresolvedQueries").single { it.path("id").asText() == BAD_QUERY }
            assertTrue(failure.path("reason").asText().contains("missingProperty"), failure.toString())
            assertFalse(run.contract.path("coverage").path("complete").asBoolean())
        }
        assertFalse(Regex("(?m)^\\s+at ").containsMatchIn(quiet.errors), quiet.errors)
        assertTrue(verbose.errors.contains(BAD_QUERY), verbose.errors)
        assertTrue(Regex("(?m)^\\s+at org\\.hibernate\\.").containsMatchIn(verbose.errors), verbose.errors)
        assertTrue(verbose.errors.contains("missingProperty"), verbose.errors)
    }

    private data class Invocation(val contract: JsonNode, val errors: String)

    private fun invoke(descriptor: Path, name: String, verbose: Boolean): Invocation {
        val output = directory.resolve("$name.json")
        val stdout = directory.resolve("$name.stdout")
        val stderr = directory.resolve("$name.stderr")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = requireNotNull(System.getProperty("epoch.cli.test.classpath"))
        val args = listOf("extract", "--descriptor", descriptor.toString(), "--output", output.toString(), "--keep-going") +
            if (verbose) listOf("--verbose") else emptyList()
        val process = ProcessBuilder(listOf(java, "-Xmx512m", "-cp", classpath, "dev.epoch.cli.MainKt") + args)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI extraction did not complete: ${Files.readString(stderr)}")
            val errors = Files.readString(stderr)
            assertEquals(0, process.exitValue(), "$errors\n${Files.readString(stdout)}")
            return Invocation(json.readTree(output.toFile()), errors)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }

    companion object {
        private const val BAD_QUERY = "Repository#bad:0"
        private const val GOOD_QUERY = "Repository#healthy:0"
    }
}
