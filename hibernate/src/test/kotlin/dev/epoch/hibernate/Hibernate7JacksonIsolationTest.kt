package dev.epoch.hibernate

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class Hibernate7JacksonIsolationTest {
    @Test fun `actual Hibernate 7 bootstrap discovers application Jackson modules without tool runtime providers`() {
        val runtime = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        val jackson = requireNotNull(System.getProperty("epoch.application.jackson.classpath")).split(File.pathSeparator)
        assertTrue(jackson.any { Path.of(it).fileName.toString() == "jackson-databind-2.21.7.jar" })
        assertTrue(jackson.any { Path.of(it).fileName.toString() == "jackson-datatype-jdk8-2.21.7.jar" })
        assertTrue(jackson.none { "jackson-module-kotlin" in it })
        assertNotEquals("2.21.7", com.fasterxml.jackson.databind.ObjectMapper().version().toString(),
            "The tool and application must use independent Jackson versions")
        assertTrue(javaClass.classLoader.getResources("META-INF/services/com.fasterxml.jackson.databind.Module")
            .asSequence().any { resource -> resource.openStream().use { it.reader().readText().contains("KotlinModule") } },
            "The parent must advertise the provider that previously leaked through Hibernate's aggregate loader")
        val fixture = Path.of(Customer::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = listOf(Customer::class.java.name),
            classpath = runtime + jackson + fixture, discoverRepositories = false))
        assertTrue(contract.entities.any { it.table == "public.customers" }, contract.toString())
        assertTrue(contract.queries.isNotEmpty(), contract.toString())
        assertTrue(contract.coverage.notes.any { "Hibernate runtime 7.4.9.Final" in it }, contract.toString())
        assertFalse(contract.unresolvedQueries.any { "ServiceConfigurationError" in it.reason }, contract.toString())
        assertEquals("6.6.13.Final", org.hibernate.Version.getVersionString())
    }
}
