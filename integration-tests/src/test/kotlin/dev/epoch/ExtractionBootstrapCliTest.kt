package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.fixtures.IdentityAccount
import dev.epoch.hibernate.ExtractionRequest
import org.hibernate.dialect.Dialect
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Run after the preceding checkpoint: fresh JVMs make logger configuration observable. */
class ExtractionBootstrapCliTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(180)
    fun `missing Kotlin-only Java output warns but retains complete mappings in both runtimes`() {
        for ((name, runtime) in runtimes()) {
            val missing = directory.resolve("$name/build/classes/java/main")
            assertFalse(Files.exists(missing))
            val run = extract(name, runtime + missing.toString(), verbose = false)
            assertEquals(0, run.exit, run.diagnostic)
            val contract = requireNotNull(run.contract)
            val warning = "Warning: ignored nonexistent classpath entry: $missing"
            assertTrue(run.errors.contains(warning), run.errors)
            assertEquals(1, run.errors.lineSequence().count { it == warning }, run.errors)
            assertTrue(contract.path("coverage").path("notes").any { it.asText() == warning })
            assertFixture(contract, name)
        }
    }

    @Test @Timeout(180)
    fun `verbose offline extraction has no self-created dialect or legacy setting warnings`() {
        for ((name, runtime) in runtimes()) {
            val run = extract("$name-verbose", runtime, verbose = true, properties = mapOf(
                "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.temp.use_jdbc_metadata_defaults" to "false",
                "javax.persistence.database-product-name" to "H2",
                "javax.persistence.database-major-version" to "99",
                "javax.persistence.database-minor-version" to "1",
                "javax.persistence.database-product-version" to "99.1",
                "jakarta.persistence.database-product-name" to "H2",
                "jakarta.persistence.database-major-version" to "88",
                "jakarta.persistence.database-minor-version" to "2",
                "jakarta.persistence.database-product-version" to "88.2",
            ))
            assertEquals(0, run.exit, run.diagnostic)
            assertFixture(requireNotNull(run.contract), name)
            assertTrue(run.errors.contains("Hibernate ORM core version"), "Verbose logs must actually be enabled: ${run.errors}")
            assertFalse(run.errors.contains("HHH90000025"), run.errors)
            assertFalse(run.errors.contains("does not need to be specified explicitly"), run.errors)
            assertFalse(run.errors.contains("hibernate.temp.use_jdbc_metadata_defaults"), run.errors)
            assertFalse(run.errors.contains("javax.persistence.database-"), run.errors)
        }
    }

    @Test @Timeout(180)
    fun `custom dialect resolver properties are rejected before application code runs`() {
        for ((name, runtime) in runtimes()) {
            val run = extract("$name-resolver-property", runtime, verbose = false, properties = mapOf(
                "hibernate.dialect_resolvers" to UnexpectedApplicationDialectResolver::class.java.name,
            ))
            assertEquals(3, run.exit, run.diagnostic)
            assertTrue(run.errors.contains("hibernate.dialect_resolvers"), run.errors)
            assertTrue(run.errors.contains("PostgreSQL 16.8"), run.errors)
            assertFalse(run.errors.contains(RESOLVER_SENTINEL), "Application resolver must not be instantiated: ${run.errors}")
            assertNull(run.contract)
        }
    }

    @Test @Timeout(180)
    fun `service-loaded application dialect resolvers cannot replace the pinned offline resolver`() {
        val serviceJar = directory.resolve("application-dialect-resolver.jar")
        JarOutputStream(Files.newOutputStream(serviceJar)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/services/org.hibernate.engine.jdbc.dialect.spi.DialectResolver"))
            jar.write((UnexpectedApplicationDialectResolver::class.java.name + "\n").toByteArray())
            jar.closeEntry()
        }
        for ((name, runtime) in runtimes()) {
            val run = extract("$name-service-resolver", runtime + serviceJar.toString(), verbose = true)
            assertEquals(0, run.exit, run.diagnostic)
            assertFixture(requireNotNull(run.contract), name)
            assertFalse(run.errors.contains(RESOLVER_SENTINEL), run.errors)
        }
    }

    private fun assertFixture(contract: JsonNode, runtime: String) {
        assertTrue(contract.path("coverage").path("complete").asBoolean(), contract.toPrettyString())
        assertTrue(contract.path("unresolvedQueries").isEmpty, contract.toPrettyString())
        assertEquals("public.accounts", contract.path("entities").single().path("table").asText())
        val notes = contract.path("coverage").path("notes").map { it.asText() }
        assertTrue(notes.any { "Hibernate runtime $runtime" in it && "PostgreSQL dialect version 16.8" in it }, notes.toString())
        assertTrue(contract.path("queries").any { it.path("operation").asText() == "INSERT" }, contract.toPrettyString())
        assertTrue(contract.path("queries").any { it.path("id").asText().endsWith(".findById") }, contract.toPrettyString())
    }

    private fun runtimes(): List<Pair<String, List<String>>> {
        val fixtures = Path.of(IdentityAccount::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val actual7 = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        assertTrue(actual7.any { it.endsWith("hibernate-core-7.4.9.Final.jar") })
        return listOf("6.6.13.Final" to listOf(fixtures), "7.4.9.Final" to (actual7 + fixtures))
    }

    private data class Invocation(val exit: Int, val contract: JsonNode?, val errors: String, val output: String) {
        val diagnostic: String get() = "$errors\n$output"
    }

    private fun extract(name: String, runtime: List<String>, verbose: Boolean,
                        properties: Map<String, String> = emptyMap()): Invocation {
        val descriptor = directory.resolve("$name-descriptor.json")
        val contract = directory.resolve("$name-contract.json")
        val stdout = directory.resolve("$name.stdout")
        val stderr = directory.resolve("$name.stderr")
        json.writeValue(descriptor.toFile(), ExtractionRequest(entityClasses = listOf(IdentityAccount::class.java.name),
            classpath = runtime, discoverRepositories = false, hibernateProperties = properties))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = requireNotNull(System.getProperty("epoch.cli.test.classpath"))
        val args = listOf("extract", "--descriptor", descriptor.toString(), "--output", contract.toString()) +
            if (verbose) listOf("--verbose") else emptyList()
        val process = ProcessBuilder(listOf(java, "-Xmx512m", "-cp", classpath, "dev.epoch.cli.MainKt") + args)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI extraction timed out: ${Files.readString(stderr)}")
            return Invocation(process.exitValue(), contract.takeIf(Files::isRegularFile)?.let { json.readTree(it.toFile()) },
                Files.readString(stderr), Files.readString(stdout))
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        }
    }
}

private const val RESOLVER_SENTINEL = "EPOCH_APPLICATION_DIALECT_RESOLVER_MUST_NOT_RUN"

/** Epoch-owned application SPI fixture; neither constructor nor resolver may execute offline. */
class UnexpectedApplicationDialectResolver : DialectResolver {
    init { error(RESOLVER_SENTINEL) }
    override fun resolveDialect(info: DialectResolutionInfo): Dialect? = error(RESOLVER_SENTINEL)
}
