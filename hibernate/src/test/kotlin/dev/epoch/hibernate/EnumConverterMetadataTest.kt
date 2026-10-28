package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Test
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.annotations.JdbcType
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EnumConverterMetadataTest {
    @Test fun `enum order modes and converter artifact identity are factual in both Hibernate runtimes`() {
        val fixturePath = Path.of(EnumConverterEntity::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val runtimes = listOf(emptyList(),
            requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator) + fixturePath)
        val bytes = MetadataCodeConverter::class.java.getResourceAsStream("MetadataCodeConverter.class")!!.use { it.readAllBytes() }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        for (classpath in runtimes) {
            val request = ExtractionRequest(listOf(EnumConverterEntity::class.java.name), classpath = classpath, discoverRepositories = false)
            val contract = HibernateExtractor().extract(request)
            val columns = jacksonObjectMapper().valueToTree<JsonNode>(contract).path("entities").single().path("columns")
                .associateBy { it.path("name").asText() }
            val names = listOf("ZEBRA", "ALPHA", "MIDDLE")
            assertEquals("STRING", columns.getValue("string_status").path("enumMode").asText(), columns.toString())
            assertEquals("ORDINAL", columns.getValue("ordinal_status").path("enumMode").asText(), columns.toString())
            for (name in listOf("string_status", "ordinal_status")) {
                assertEquals(names, columns.getValue(name).path("enumConstants").map { it.asText() }, name)
            }
            assertEquals(names, columns.getValue("string_status").path("enumRelationalValues").map { it.asText() })
            assertEquals(listOf("0", "1", "2"), columns.getValue("ordinal_status").path("enumRelationalValues").map { it.asText() })
            assertEquals("NAME", columns.getValue("string_status").path("enumEncoding").asText())
            assertEquals("ORDINAL", columns.getValue("ordinal_status").path("enumEncoding").asText())
            assertTrue(columns.getValue("custom_status").path("enumMode").isNull)
            assertTrue(columns.getValue("custom_status").path("enumRelationalValues").isEmpty)
            assertEquals(names, columns.getValue("custom_status").path("enumConstants").map { it.asText() })
            val converted = columns.getValue("converted_code")
            assertEquals(MetadataCodeConverter::class.java.name, converted.path("converterClass").asText(), converted.toString())
            assertEquals(expectedHash, converted.path("converterBytecodeSha256").asText(), converted.toString())
            assertTrue(converted.path("converterBytecodeSha256").asText().matches(Regex("[0-9a-f]{64}")))
            val repeated = jacksonObjectMapper().valueToTree<JsonNode>(HibernateExtractor().extract(request))
                .path("entities").single().path("columns").single { it.path("name").asText() == "converted_code" }
            assertEquals(converted, repeated, "Converter metadata must not vary between extractions")
        }
    }

    @Test fun `application enum converters are fingerprinted without calling their conversion methods`() {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.dialect" to org.hibernate.dialect.PostgreSQLDialect(org.hibernate.dialect.DatabaseVersion.make(16, 8)),
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "hibernate.connection.provider_class" to "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl",
            "hibernate.hbm2ddl.auto" to "none", "jakarta.persistence.validation.mode" to "none",
        )).build()
        try {
            val factory = MetadataSources(registry).addAnnotatedClass(EnumConverterProbeEntity::class.java)
                .buildMetadata().buildSessionFactory() as SessionFactoryImplementor
            factory.use {
                // Hibernate itself may consult converters during bootstrap; measure only epoch's extraction.
                RecordingEnumConverter.calls = 0
                val contract = HibernateExtractor().extractFactory(factory,
                    ExtractionRequest(listOf(EnumConverterProbeEntity::class.java.name), discoverRepositories = false))
                assertEquals(0, RecordingEnumConverter.calls)
                val column = contract.entities.single().columns.single { it.name == "status" }
                assertEquals(RecordingEnumConverter::class.java.name, column.converterClass)
                assertTrue(column.converterBytecodeSha256!!.matches(Regex("[0-9a-f]{64}")))
                assertEquals(listOf("ZEBRA", "ALPHA", "MIDDLE"), column.enumConstants)
                assertNull(column.enumMode)
                assertTrue(column.enumRelationalValues.isEmpty())
                assertTrue(column.enumValues.isEmpty())
                assertFalse(column.inputDomainComplete)
            }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }
}

enum class MetadataStatus { ZEBRA, ALPHA, MIDDLE }
data class MetadataCode(val value: String)

@Converter
class MetadataCodeConverter : AttributeConverter<MetadataCode, String> {
    override fun convertToDatabaseColumn(attribute: MetadataCode?): String? = attribute?.let { "v1:${it.value}" }
    override fun convertToEntityAttribute(dbData: String?): MetadataCode? = dbData?.let { MetadataCode(it.removePrefix("v1:")) }
}

class CustomEnumJdbcType : VarcharJdbcType()

@Converter
class RecordingEnumConverter : AttributeConverter<MetadataStatus, String> {
    override fun convertToDatabaseColumn(attribute: MetadataStatus?): String? {
        calls++
        return attribute?.let { "custom:${it.name}" }
    }
    override fun convertToEntityAttribute(dbData: String?): MetadataStatus? {
        calls++
        return dbData?.let { MetadataStatus.valueOf(it.removePrefix("custom:")) }
    }
    companion object { var calls: Int = 0 }
}

@Entity @Table(name = "enum_converter_probe")
open class EnumConverterProbeEntity {
    @field:Id open var id: Long = 0
    @field:Convert(converter = RecordingEnumConverter::class)
    open var status: MetadataStatus = MetadataStatus.ZEBRA
}

@Entity @Table(name = "enum_converter_metadata")
open class EnumConverterEntity {
    @field:Id open var id: Long = 0
    @field:Enumerated(EnumType.STRING) @field:Column(name = "string_status")
    open var stringStatus: MetadataStatus = MetadataStatus.ZEBRA
    @field:Enumerated(EnumType.ORDINAL) @field:Column(name = "ordinal_status")
    open var ordinalStatus: MetadataStatus = MetadataStatus.ZEBRA
    @field:Convert(converter = MetadataCodeConverter::class) @field:Column(name = "converted_code")
    open var convertedCode: MetadataCode = MetadataCode("")
    @field:Enumerated(EnumType.STRING) @field:JdbcType(CustomEnumJdbcType::class) @field:Column(name = "custom_status")
    open var customStatus: MetadataStatus = MetadataStatus.ZEBRA
}
