package dev.epoch.hibernate

import dev.epoch.core.EnumEncoding
import dev.epoch.core.EnumMode
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class Hibernate7EnumEncodingTest {
    @Test fun `Hibernate 7 enumerated values retain aligned explicit codes and native ordinal storage labels`() {
        val fixtures = requireNotNull(System.getProperty("epoch.hibernate7.fixture.classpath"))
            .split(File.pathSeparator).filter { Files.isDirectory(Path.of(it)) }
        val runtime = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf("dev.epoch.hibernate7fixture.ExplicitEnumValues"),
            classpath = runtime + fixtures, discoverRepositories = false,
        ))
        val columns = contract.entities.single().columns.associateBy { it.name }
        val names = listOf("ZEBRA", "ALPHA", "MIDDLE")
        val string = columns.getValue("string_code")
        assertEquals(EnumMode.STRING, string.enumMode)
        assertEquals(EnumEncoding.EXPLICIT, string.enumEncoding)
        assertEquals(names, string.enumConstants)
        assertEquals(listOf("z-code", "a-code", "m-code"), string.enumRelationalValues)
        assertEquals(listOf("a-code", "m-code", "z-code"), string.enumValues)
        assertNull(string.converterClass, "Hibernate's built-in finite-map conversion is not an application AttributeConverter")
        assertTrue(string.inputDomainComplete, "The exact Hibernate finite-map converter has a supported direct input domain")
        assertTrue(string.readDomainComplete)
        val integer = columns.getValue("integer_code")
        assertEquals(EnumMode.ORDINAL, integer.enumMode)
        assertEquals(EnumEncoding.EXPLICIT, integer.enumEncoding)
        assertEquals(names, integer.enumConstants)
        assertEquals(listOf("17", "3", "29"), integer.enumRelationalValues)
        assertTrue(integer.inputDomainComplete)
        assertTrue(integer.readDomainComplete)
        val native = columns.getValue("native_ordered")
        assertEquals(EnumMode.ORDINAL, native.enumMode)
        assertEquals(EnumEncoding.NAME, native.enumEncoding)
        assertEquals(names, native.enumRelationalValues,
            "PostgreSQL's ordinal enum sort order does not turn its stored labels into numeric ordinals")
    }
}
