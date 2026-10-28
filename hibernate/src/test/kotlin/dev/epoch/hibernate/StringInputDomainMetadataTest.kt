package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column as SqlColumn
import net.sf.jsqlparser.statement.select.PlainSelect
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class StringInputDomainMetadataTest {
    @Test fun `Hibernate 6 standard String binding is independent of DDL length`() = assertStringFacts(emptyList())

    @Test fun `Hibernate 7_4_9 standard String binding is independent of DDL length`() = assertStringFacts(runtime7())

    private fun assertStringFacts(classpath: List<String>) {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(StringInputMetadataEntity::class.java.name), classpath = classpath,
            discoverRepositories = false))
        val tree = jacksonObjectMapper().valueToTree<JsonNode>(contract)
        val columns = tree.path("entities").single().path("columns").associateBy { it.path("name").asText() }
        // A property with no @Column need not retain Hibernate's default DDL length in its runtime
        // selectable. Preserve that raw metadata; the binding proof must not depend on filling it in.
        assertEquals(255L, columns.getValue("default_ddl").path("length").asLong())
        assertEquals(5L, columns.getValue("short_ddl").path("length").asLong())
        for (name in listOf("ordinary", "default_ddl", "short_ddl", "explicit_text")) {
            val column = columns.getValue(name)
            assertTrue(column.path("inputDomainComplete").asBoolean(), column.toString())
            assertTrue(column.path("stringInputLengthUnrestricted").asBoolean(),
                "A standard String binder has no runtime DDL-length cap: $column")
        }
        assertFalse(columns.getValue("ordinary").path("lengthUnbounded").asBoolean())
        assertFalse(columns.getValue("short_ddl").path("lengthUnbounded").asBoolean())
        assertTrue(columns.getValue("explicit_text").path("lengthUnbounded").asBoolean())
        for (name in listOf("transformed", "converted", "custom_java", "enum_value"))
            assertFalse(columns.getValue(name).path("stringInputLengthUnrestricted").asBoolean(), columns.getValue(name).toString())
    }

    @Test fun `mapped decoder positions come from the actual selected JDBC result slots`() {
        for (classpath in listOf(emptyList(), runtime7())) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(StringInputMetadataEntity::class.java.name), classpath = classpath,
                discoverRepositories = false))
            val query = contract.queries.single { it.id.endsWith(".findById") }
            val reads = jacksonObjectMapper().valueToTree<JsonNode>(query).path("mappedReads")
            assertFalse(reads.isEmpty)
            val selections = (CCJSqlParserUtil.parse(query.sql) as PlainSelect).selectItems
            for (read in reads) {
                val position = read.path("resultPosition").asInt()
                assertTrue(position in 1..selections.size, "Missing actual JDBC position: $read; SQL=${query.sql}")
                val selected = selections[position - 1].expression as SqlColumn
                assertEquals(selected.columnName, read.path("column").path("column").asText(), read.toString())
            }
        }
    }

    @Test fun `existing factory proves standard binding without inventing a boot SQL declaration`() {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "hibernate.hbm2ddl.auto" to "none", "jakarta.persistence.validation.mode" to "none",
        )).build()
        try {
            val factory = MetadataSources(registry).addAnnotatedClass(StringInputMetadataEntity::class.java)
                .buildMetadata().buildSessionFactory() as SessionFactoryImplementor
            try {
                val contract = HibernateExtractor().extractFactory(factory, ExtractionRequest(discoverRepositories = false))
                val text = contract.entities.single().columns.single { it.name == "explicit_text" }
                assertTrue(text.stringInputLengthUnrestricted)
                assertFalse(text.lengthUnbounded, "An existing factory alone does not supply boot SQL declarations")
            } finally { factory.close() }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    private fun runtime7(): List<String> = requireNotNull(System.getProperty("epoch.hibernate749.classpath"))
        .split(File.pathSeparator) + Path.of(StringInputMetadataEntity::class.java.protectionDomain.codeSource.location.toURI()).toString()
}

@Entity @Table(name = "string_input_metadata")
open class StringInputMetadataEntity {
    @field:Id open var id: Long = 1
    open var ordinary: String = "value"
    @field:Column(name = "default_ddl") open var defaultDdl: String = "value"
    @field:Column(name = "short_ddl", length = 5) open var shortDdl: String = "value"
    @field:Column(name = "explicit_text", columnDefinition = "text") open var explicitText: String = "value"
    @field:ColumnTransformer(write = "left(?, 5)") open var transformed: String = "value"
    @field:Convert(converter = DataReadConverter::class) open var converted: String = "value"
    @field:org.hibernate.annotations.JavaType(DataNullProducingJavaType::class)
    @field:Column(name = "custom_java") open var customJava: String = "value"
    @field:Enumerated(EnumType.STRING) @field:Column(name = "enum_value") open var enumValue: DataReadStatus = DataReadStatus.OPEN
}
