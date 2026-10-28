package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.nullabilityfixture.JavaNullabilityEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeclaredNullabilityTest {
    @Test fun `Kotlin declared nullability is independent of Hibernate nullability in both runtimes`() {
        for (classpath in runtimes(KotlinNullabilityEntity::class.java, KotlinPropertyNullabilityEntity::class.java)) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(KotlinNullabilityEntity::class.java.name, KotlinPropertyNullabilityEntity::class.java.name),
                classpath = classpath, discoverRepositories = false,
            ))
            assertTrue(contract.coverage.complete, contract.toString())
            val entities = jacksonObjectMapper().valueToTree<JsonNode>(contract).path("entities")
            val fields = entities.single { it.path("table").asText() == "public.kotlin_nullability" }.path("columns")
                .associateBy { it.path("name").asText() }
            assertFact(fields.getValue("required_text"), "declaredNonNull", true)
            assertFact(fields.getValue("optional_text"), "declaredNonNull", false)
            assertFact(fields.getValue("schema_required"), "declaredNonNull", false)
            assertFact(fields.getValue("required_text"), "hibernateNullable", true)
            assertFact(fields.getValue("optional_text"), "hibernateNullable", true)
            assertFact(fields.getValue("schema_required"), "hibernateNullable", false)
            assertFact(fields.getValue("id"), "declaredNonNull", true)
            assertFact(fields.getValue("inherited_required"), "declaredNonNull", true)
            assertFact(fields.getValue("parent_id"), "declaredNonNull", true)
            assertFact(fields.getValue("parent_id"), "inputDomainComplete", false)

            val properties = entities.single { it.path("table").asText() == "public.kotlin_property_nullability" }.path("columns")
                .associateBy { it.path("name").asText() }
            assertFact(properties.getValue("getter_required"), "declaredNonNull", true)
            assertFact(properties.getValue("getter_optional"), "declaredNonNull", false)
        }
    }

    @Test fun `Java CLASS retained declaration annotations are read from the mapped member`() {
        for (classpath in runtimes(JavaNullabilityEntity::class.java)) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(JavaNullabilityEntity::class.java.name), classpath = classpath,
                discoverRepositories = false,
            ))
            val columns = jacksonObjectMapper().valueToTree<JsonNode>(contract).path("entities").single().path("columns")
                .associateBy { it.path("name").asText() }
            assertFact(columns.getValue("required_text"), "declaredNonNull", true)
            assertFact(columns.getValue("optional_text"), "declaredNonNull", false)
            assertFact(columns.getValue("required_text"), "hibernateNullable", true)
            assertTrue(columns.getValue("unspecified_text").path("declaredNonNull").isNull,
                "Unannotated Java references have unknown declared nullability: $columns")
            assertTrue(JavaNullabilityEntity::class.java.getField("required").annotations.none {
                it.annotationClass.java.name == "org.jetbrains.annotations.NotNull"
            }, "The fixture must require bytecode inspection, not runtime reflection")
        }
    }

    private fun assertFact(column: JsonNode, name: String, expected: Boolean) {
        assertTrue(column.path(name).isBoolean, "$name must be a factual Boolean in $column")
        assertEquals(expected, column.path(name).booleanValue(), "$name in $column")
    }

    private fun runtimes(vararg fixtures: Class<*>): List<List<String>> = listOf(
        emptyList(),
        requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator) +
            fixtures.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct(),
    )
}

@Entity @Table(name = "kotlin_nullability")
open class KotlinNullabilityEntity : NullabilityBase() {
    @field:Id open var id: Long = 0
    @field:Column(name = "required_text") open var x: String = ""
    @field:Column(name = "optional_text") open var y: String? = null
    @field:Column(name = "schema_required", nullable = false) open var schemaRequired: String? = null
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id")
    open lateinit var parent: KotlinNullabilityEntity
}

@MappedSuperclass
open class NullabilityBase {
    @field:Column(name = "inherited_required") open var inherited: String = ""
}

@Entity @Table(name = "kotlin_property_nullability")
open class KotlinPropertyNullabilityEntity {
    @get:Id open var id: Long = 0
    @get:Column(name = "getter_required") open var required: String = ""
    @get:Column(name = "getter_optional") open var optional: String? = null
}
