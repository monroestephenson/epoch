package dev.epoch.cli

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/** Compile and run this tests-first before registering or implementing the command. */
class DescriptorRegistrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `descriptors command writes a manifest for two explicitly configured units`() {
        val properties = directory.resolve("application.properties")
        properties.writeText("""
            quarkus.hibernate-orm.packages=example.primary
            quarkus.hibernate-orm."audit".packages=example.audit
        """.trimIndent())
        val compiledRoot = Path.of(javaClass.protectionDomain.codeSource.location.toURI())
        val output = directory.resolve("descriptors")

        assertEquals(0, runCli(arrayOf("descriptors", "--quarkus-properties", properties.toString(),
            "--classpath", compiledRoot.toString(), "--output-directory", output.toString())))
        val manifest = json.readTree(output.resolve("manifest.json").toFile())
        assertEquals(1, manifest.path("schemaVersion").asInt())
        assertEquals(setOf("default", "audit"), manifest.path("units").map { it.path("name").asText() }.toSet())
        manifest.path("units").forEach { unit ->
            val file = unit.path("descriptor").asText()
            assertTrue(file.isNotBlank())
            assertTrue(Files.isRegularFile(output.resolve(file)))
        }
    }
}
