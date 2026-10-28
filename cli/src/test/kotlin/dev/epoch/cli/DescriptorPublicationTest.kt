package dev.epoch.cli

import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.cli.descriptorfixture.PrefixTableNaming
import dev.epoch.cli.descriptorfixture.auditunit.AuditUnitEntity
import dev.epoch.cli.descriptorfixture.mainunit.MainUnitEntity
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

class DescriptorPublicationTest {
    @TempDir lateinit var directory: Path

    private val classes get() = Path.of(MainUnitEntity::class.java.protectionDomain.codeSource.location.toURI())
    private val repositories = "dev.epoch.cli.descriptorfixture.repositories"
    private fun execute(vararg arguments: String): Int = runCli(arguments.toList().toTypedArray()) { CommandLine(Descriptors()) }
    private fun manifest(output: Path): DescriptorManifest = json.readValue(output.resolve("manifest.json").toFile())
    private fun requests(output: Path): Map<String, ExtractionRequest> = manifest(output).units.associate { unit ->
        unit.name to json.readValue(output.resolve(unit.descriptor).toFile())
    }
    private fun assertNoValues(output: Path, vararg forbidden: String) {
        Files.list(output).use { files -> files.forEach { file ->
            val content = Files.readString(file)
            forbidden.forEach { assertFalse(content.contains(it), "A generated file contains a source value that must be omitted") }
        } }
    }

    @Test fun `generated profile-aware Quarkus descriptors extract only their own entity packages and naming settings`() {
        val password = "do-not-copy-production-password"
        val url = "jdbc:postgresql://production.invalid/private"
        val source = directory.resolve("application.properties").also { it.writeText("""
            quarkus.hibernate-orm.packages=${MainUnitEntity::class.java.packageName}
            quarkus.hibernate-orm.database.default-schema=base_schema
            %prod.quarkus.hibernate-orm.database.default-schema=primary_schema
            quarkus.hibernate-orm."audit.history".packages=${AuditUnitEntity::class.java.packageName}
            quarkus.hibernate-orm."audit.history".database.default-schema=audit_schema
            quarkus.hibernate-orm."audit.history".physical-naming-strategy=${PrefixTableNaming::class.java.name}
            quarkus.datasource.jdbc.url=$url
            quarkus.datasource.password=$password
        """.trimIndent()) }
        val output = directory.resolve("quarkus-descriptors")
        assertEquals(0, execute("--quarkus-properties", source.toString(), "--profile", "prod",
            "--classpath", classes.toString(), "--repository-package", repositories,
            "--application-version", "generated-1", "--output-directory", output.toString()))
        val inputs = requests(output)
        assertEquals(setOf("audit.history", "default"), inputs.keys)
        assertTrue(manifest(output).units.all { it.configurationComplete })
        inputs.values.forEach { request ->
            assertEquals(listOf(classes.toAbsolutePath().normalize().toString()), request.classpath)
            assertEquals(listOf(repositories), request.repositoryPackages)
            assertEquals("generated-1", request.applicationVersion)
        }
        assertNull(inputs.getValue("default").physicalNamingStrategy)
        assertEquals(PrefixTableNaming::class.java.name, inputs.getValue("audit.history").physicalNamingStrategy)
        val extracted = inputs.mapValues { (_, request) -> HibernateExtractor().extract(request) }
        assertEquals(listOf(MainUnitEntity::class.java.name), extracted.getValue("default").entities.map { it.id })
        assertEquals(listOf("primary_schema.descriptor_main"), extracted.getValue("default").entities.map { it.table })
        assertEquals(listOf(AuditUnitEntity::class.java.name), extracted.getValue("audit.history").entities.map { it.id })
        assertEquals(listOf("audit_schema.prefixed_descriptor_audit"), extracted.getValue("audit.history").entities.map { it.table })
        assertTrue(extracted.values.all { it.queries.isNotEmpty() })
        assertNoValues(output, password, url)
    }

    @Test fun `generated XML descriptors retain explicit two-unit membership and omit connection configuration`() {
        val password = "xml-production-password-sentinel"
        val url = "jdbc:postgresql://xml-production.invalid/private"
        val source = directory.resolve("persistence.xml").also { it.writeText("""
            <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2">
              <persistence-unit name="main" transaction-type="JTA">
                <provider>org.hibernate.jpa.HibernatePersistenceProvider</provider>
                <jta-data-source>java:/production-secret-datasource</jta-data-source>
                <class>${MainUnitEntity::class.java.name}</class>
                <exclude-unlisted-classes>true</exclude-unlisted-classes>
                <properties>
                  <property name="hibernate.default_schema" value="primary_schema"/>
                  <property name="jakarta.persistence.jdbc.url" value="$url"/>
                  <property name="jakarta.persistence.jdbc.password" value="$password"/>
                  <property name="hibernate.hbm2ddl.auto" value="create-drop"/>
                </properties>
              </persistence-unit>
              <persistence-unit name="audit">
                <class>${AuditUnitEntity::class.java.name}</class>
                <exclude-unlisted-classes>true</exclude-unlisted-classes>
                <properties><property name="hibernate.default_schema" value="audit_schema"/></properties>
              </persistence-unit>
            </persistence>
        """.trimIndent()) }
        val output = directory.resolve("xml-descriptors")
        assertEquals(0, execute("--persistence-xml", source.toString(), "--classpath", classes.toString(),
            "--repository-package", repositories, "--output-directory", output.toString()))
        val inputs = requests(output)
        assertEquals(setOf("audit", "main"), inputs.keys)
        assertTrue(manifest(output).units.all { it.configurationComplete })
        assertEquals(listOf(MainUnitEntity::class.java.name), inputs.getValue("main").entityClasses)
        assertEquals(listOf(AuditUnitEntity::class.java.name), inputs.getValue("audit").entityClasses)
        assertTrue(inputs.values.all { it.scanPackages.isEmpty() })
        val tables = inputs.mapValues { (_, request) -> HibernateExtractor().extract(request).entities.map { it.table } }
        assertEquals(listOf("primary_schema.descriptor_main"), tables.getValue("main"))
        assertEquals(listOf("audit_schema.descriptor_audit"), tables.getValue("audit"))
        assertNoValues(output, password, url, "java:/production-secret-datasource", "create-drop")
    }

    @Test fun `publication uses deterministic relative filenames and refuses existing output without deleting unrelated files`() {
        val request = ExtractionRequest(entityClasses = listOf(MainUnitEntity::class.java.name), classpath = listOf(classes.toString()))
        val generated = DescriptorGeneration(format = DescriptorFormat.PERSISTENCE_XML, profile = null, units = listOf(
            UnitDescriptor("../../escape", request, true), UnitDescriptor("audit.history", request, true),
            UnitDescriptor("audit/history", request, true),
        ))
        val first = directory.resolve("first")
        val second = directory.resolve("second")
        val manifest = publishDescriptors(generated, first)
        publishDescriptors(generated, second)
        assertEquals(3, manifest.units.map { it.descriptor }.toSet().size)
        for (entry in manifest.units) {
            val relative = Path.of(entry.descriptor)
            assertFalse(relative.isAbsolute)
            assertEquals(1, relative.nameCount)
            assertEquals(first, first.resolve(relative).normalize().parent)
            assertTrue(Files.isRegularFile(first.resolve(relative)))
        }
        val firstFiles = Files.list(first).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        val secondFiles = Files.list(second).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(firstFiles, secondFiles)
        firstFiles.forEach { name -> assertArrayEquals(Files.readAllBytes(first.resolve(name)), Files.readAllBytes(second.resolve(name)), name) }
        val marker = first.resolve("unrelated.txt").also { it.writeText("keep-this-file") }
        assertThrows(Exception::class.java) { publishDescriptors(generated, first) }
        assertEquals("keep-this-file", Files.readString(marker))
        assertFalse(Files.exists(directory.resolve("escape")))
    }

    @Test fun `ambiguous multi-unit configuration publishes no partial descriptors and source choice is exclusive`() {
        val source = directory.resolve("application.properties").also { it.writeText("""
            quarkus.hibernate-orm.packages=${MainUnitEntity::class.java.packageName}
            quarkus.hibernate-orm."missing-packages".database.default-schema=audit_schema
        """.trimIndent()) }
        val output = directory.resolve("not-published")
        assertEquals(3, execute("--quarkus-properties", source.toString(), "--classpath", classes.toString(),
            "--output-directory", output.toString()))
        assertFalse(Files.exists(output))
        val xml = directory.resolve("persistence.xml").also { it.writeText("<persistence/>") }
        assertNotEquals(0, execute("--quarkus-properties", source.toString(), "--persistence-xml", xml.toString(),
            "--classpath", classes.toString(), "--output-directory", output.toString()))
        assertFalse(Files.exists(output))
    }
}
