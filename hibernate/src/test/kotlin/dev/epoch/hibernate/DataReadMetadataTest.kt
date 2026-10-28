package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import jakarta.persistence.*
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.DiscriminatorFormula
import org.hibernate.annotations.DiscriminatorOptions
import org.hibernate.annotations.SQLRestriction
import org.hibernate.annotations.SoftDelete
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class DataReadMetadataTest {
    @Test fun `actual by-id scalar decoders are distinct from predicate dependencies in both runtimes`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DataReadScalar::class.java), classpath)
            val entity = entity(contract, DataReadScalar::class.java)
            val query = query(contract, DataReadScalar::class.java.name + ".findById")
            assertTrue(query.path("mappedReadCoverageComplete").asBoolean(), query.toString())
            val reads = query.path("mappedReads")
            val decoded = reads.map { it.path("column").path("column").asText() }.toSet()
            val ordinaryValues = setOf("required_label", "optional_label", "status", "write_changed")
            assertTrue(decoded.containsAll(ordinaryValues), query.toString())
            assertTrue((decoded - ordinaryValues).all { it == "id" }, query.toString())
            assertTrue(reads.all { it.path("entityId").asText() == entity.path("id").asText() })
            assertTrue(reads.all { it.path("column").path("table").asText() == "public.data_read_scalar" })
            val changedWrite = entity.path("columns").single { it.path("name").asText() == "write_changed" }
            assertFalse(changedWrite.path("inputDomainComplete").asBoolean())
            assertTrue(changedWrite.path("readDomainComplete").asBoolean(), changedWrite.toString())
            val named = query(contract, "DataReadScalar.idsByStatus")
            assertTrue(named.path("columns").any { it.path("column").asText() == "status" }, named.toString())
            assertTrue(named.path("mappedReads").isArray && named.path("mappedReads").isEmpty, named.toString())
            assertFalse(named.path("mappedReadCoverageComplete").asBoolean(), named.toString())
        }
    }

    @Test fun `custom readers converters and conditional loaders do not acquire direct read proofs`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DataReadTransformed::class.java, DataReadConverted::class.java,
                DataReadRestricted::class.java, DataReadSoftDeleted::class.java, DataReadCustomJavaType::class.java), classpath)
            val transformed = entity(contract, DataReadTransformed::class.java).path("columns").single { it.path("name").asText() == "label" }
            assertFalse(transformed.path("readDomainComplete").asBoolean(), transformed.toString())
            assertFalse(query(contract, DataReadTransformed::class.java.name + ".findById").path("mappedReadCoverageComplete").asBoolean())
            val converted = entity(contract, DataReadConverted::class.java).path("columns").single { it.path("name").asText() == "label" }
            assertFalse(converted.path("readDomainComplete").asBoolean(), converted.toString())
            assertEquals(DataReadConverter::class.java.name, converted.path("converterClass").asText())
            val customType = entity(contract, DataReadCustomJavaType::class.java).path("columns").single { it.path("name").asText() == "label" }
            assertFalse(customType.path("inputDomainComplete").asBoolean(), customType.toString())
            assertFalse(customType.path("readDomainComplete").asBoolean(), customType.toString())
            for (type in listOf(DataReadRestricted::class.java, DataReadSoftDeleted::class.java)) {
                val query = query(contract, type.name + ".findById")
                assertFalse(query.path("mappedReadCoverageComplete").asBoolean(), query.toString())
                assertTrue(query.path("mappedReads").isEmpty, "An extra row predicate cannot become unconditional read evidence: $query")
            }
        }
    }

    @Test fun `physical hierarchy facts name actual root readers and concrete discriminator inserts`() {
        val types = listOf(DataReadAnimal::class.java, DataReadDog::class.java, DataReadCat::class.java)
        for (classpath in runtimes()) {
            val contract = extract(types, classpath, listOf(DataReadAnimal::class.java))
            val root = entity(contract, DataReadAnimal::class.java)
            val rootId = root.path("id").asText()
            val hierarchy = root.path("hierarchy")
            assertEquals("SINGLE_TABLE", hierarchy.path("strategy").asText())
            assertTrue(hierarchy.path("complete").asBoolean(), hierarchy.toString())
            assertFalse(hierarchy.path("concrete").asBoolean())
            assertEquals(setOf("CAT", "DOG"), hierarchy.path("acceptedValues").map { it.asText() }.toSet())
            assertEquals("public.data_read_animals", hierarchy.path("discriminator").path("table").asText())
            assertEquals("dtype", hierarchy.path("discriminator").path("column").asText())
            assertEquals(rootId, hierarchy.path("rootEntityId").asText())
            val read = query(contract, DataReadAnimal::class.java.name + ".findById").path("polymorphicReads").single()
            assertEquals(rootId, read.path("rootEntityId").asText())
            assertTrue(read.path("unrestricted").asBoolean(), read.toString())
            val writes = tree(contract).path("queries").flatMap { it.path("discriminatorWrites").toList() }
            assertEquals(setOf("CAT", "DOG"), writes.map { it.path("value").asText() }.toSet(), writes.toString())
            assertTrue(writes.all { it.path("entityId").asText() != rootId }, writes.toString())
            assertTrue(writes.all { it.path("column").path("column").asText() == "dtype" })
            val repeated = extract(types.reversed(), classpath, listOf(DataReadAnimal::class.java))
            assertEquals(contract, repeated)

            val readOnlyCat = extract(types, classpath, listOf(DataReadAnimal::class.java, DataReadCat::class.java))
            val actualWrites = tree(readOnlyCat).path("queries").flatMap { it.path("discriminatorWrites").toList() }
            assertEquals(setOf("DOG"), actualWrites.map { it.path("value").asText() }.toSet())
        }
    }

    @Test fun `forced formula wildcard and synthetic discriminator paths remain unproven`() {
        val groups = listOf(
            listOf(DataReadForcedRoot::class.java, DataReadForcedDog::class.java),
            listOf(DataReadFormulaRoot::class.java, DataReadFormulaDog::class.java),
            listOf(DataReadWildcardRoot::class.java, DataReadWildcardDog::class.java, DataReadWildcardFallback::class.java),
            listOf(DomainJoinedRoot::class.java, DomainJoinedChild::class.java),
        )
        for (classpath in runtimes()) for (types in groups) {
            val contract = extract(types, classpath, types)
            val hierarchy = entity(contract, types.first()).path("hierarchy")
            if (types.first() != DataReadForcedRoot::class.java) {
                assertFalse(hierarchy.path("complete").asBoolean(), hierarchy.toString())
            }
            val query = query(contract, types.first().name + ".findById")
            assertTrue(query.path("polymorphicReads").none { it.path("unrestricted").asBoolean() }, query.toString())
            assertTrue(tree(contract).path("queries").all { it.path("discriminatorWrites").isEmpty })
        }
    }

    @Test fun `disabled discriminator insertion never invents a concrete stored value`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DataReadNoInsertRoot::class.java, DataReadNoInsertDog::class.java), classpath,
                listOf(DataReadNoInsertRoot::class.java))
            assertTrue(entity(contract, DataReadNoInsertDog::class.java).path("hierarchy").path("complete").asBoolean())
            assertTrue(contract.queries.any { it.operation == Operation.INSERT })
            assertTrue(tree(contract).path("queries").all { it.path("discriminatorWrites").isEmpty })
        }
    }

    @Test fun `disabled Hibernate null checks do not prove a nullable declaration excludes null`() {
        for (classpath in runtimes()) {
            fun contract(checks: Boolean) = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(DataReadNullChecks::class.java.name), classpath = classpath,
                discoverRepositories = false, hibernateProperties = mapOf("hibernate.check_nullability" to checks.toString())
            ))
            val checkedContract = contract(true)
            val uncheckedContract = contract(false)
            val checked = checkedContract.entities.single().columns.single { it.name == "label" }
            assertTrue(checked.inputDomainComplete)
            assertEquals(false, checked.declaredNonNull)
            assertEquals(false, checked.hibernateNullable)
            val unchecked = uncheckedContract.entities.single().columns.single { it.name == "label" }
            assertFalse(unchecked.inputDomainComplete, unchecked.toString())
            assertTrue(unchecked.readDomainComplete)
            assertEquals(false, unchecked.hibernateNullable, "Retain the raw mapping declaration independently of enforcement")
            assertEquals(false, unchecked.declaredNonNull)
            assertTrue(uncheckedContract.coverage.notes.any { "nullability checking is disabled" in it })
            assertTrue(checkedContract.coverage.notes.none { "nullability checking is disabled" in it })
        }
    }

    private fun extract(types: List<Class<*>>, classpath: List<String>, readOnly: List<Class<*>> = emptyList()) =
        HibernateExtractor().extract(ExtractionRequest(entityClasses = types.map { it.name }, classpath = classpath,
            discoverRepositories = false, entityOverrides = readOnly.associate { it.name to EntityOverride(setOf(Operation.SELECT)) }))
    private fun tree(contract: DatabaseContract): JsonNode = jacksonObjectMapper().valueToTree(contract)
    private fun entity(contract: DatabaseContract, type: Class<*>): JsonNode = tree(contract).path("entities")
        .single { it.path("id").asText() == type.name || (it.path("id").asText().startsWith(type.name + "@") && it.path("hierarchy").path("rootEntityId").asText() == it.path("id").asText()) }
    private fun query(contract: DatabaseContract, id: String): JsonNode = tree(contract).path("queries").single { it.path("id").asText() == id }
    private fun runtimes(): List<List<String>> = listOf(emptyList(),
        requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator) +
            Path.of(DataReadScalar::class.java.protectionDomain.codeSource.location.toURI()).toString())
}

enum class DataReadStatus { OPEN, CLOSED }

@Entity @Table(name = "data_read_scalar")
@NamedQuery(name = "DataReadScalar.idsByStatus", query = "select d.id from DataReadScalar d where d.status = :status")
open class DataReadScalar {
    @field:Id open var id: Long = 0
    @field:Column(name = "required_label") open var required: String = ""
    @field:Column(name = "optional_label") open var optional: String? = null
    @field:Enumerated(EnumType.STRING) open var status: DataReadStatus = DataReadStatus.OPEN
    @field:Column(name = "write_changed") @field:ColumnTransformer(write = "upper(?)") open var writeChanged: String? = null
}

@Entity @Table(name = "data_read_transformed")
open class DataReadTransformed {
    @field:Id open var id: Long = 0
    @field:ColumnTransformer(read = "lower(label)") open var label: String? = null
}

@Converter
class DataReadConverter : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.reversed()
    override fun convertToEntityAttribute(dbData: String?): String? = dbData?.reversed()
}

@Entity @Table(name = "data_read_converted")
open class DataReadConverted {
    @field:Id open var id: Long = 0
    @field:Convert(converter = DataReadConverter::class) open var label: String? = null
}

@Entity @Table(name = "data_read_restricted") @SQLRestriction("active = true")
open class DataReadRestricted {
    @field:Id open var id: Long = 0
    open var label: String? = null
    open var active: Boolean = true
}

@Entity @Table(name = "data_read_soft_deleted") @SoftDelete
open class DataReadSoftDeleted {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "data_read_animals") @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
abstract class DataReadAnimal {
    @field:Id open var id: Long = 0
    open var label: String = ""
}
@Entity @DiscriminatorValue("DOG") open class DataReadDog : DataReadAnimal()
@Entity @DiscriminatorValue("CAT") open class DataReadCat : DataReadAnimal()

@Entity @Table(name = "data_read_forced") @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype") @DiscriminatorOptions(force = true)
abstract class DataReadForcedRoot { @field:Id open var id: Long = 0 }
@Entity @DiscriminatorValue("DOG") open class DataReadForcedDog : DataReadForcedRoot()

@Entity @Table(name = "data_read_formula") @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorFormula("kind")
abstract class DataReadFormulaRoot { @field:Id open var id: Long = 0 }
@Entity @DiscriminatorValue("DOG") open class DataReadFormulaDog : DataReadFormulaRoot()

@Entity @Table(name = "data_read_wildcard") @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype")
abstract class DataReadWildcardRoot { @field:Id open var id: Long = 0 }
@Entity @DiscriminatorValue("DOG") open class DataReadWildcardDog : DataReadWildcardRoot()
@Entity @DiscriminatorValue("not null") open class DataReadWildcardFallback : DataReadWildcardRoot()

@Entity @Table(name = "data_read_no_insert") @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "dtype") @DiscriminatorOptions(insert = false)
abstract class DataReadNoInsertRoot { @field:Id open var id: Long = 0 }
@Entity @DiscriminatorValue("DOG") open class DataReadNoInsertDog : DataReadNoInsertRoot()

class DataNullProducingJavaType : org.hibernate.type.descriptor.java.StringJavaType() {
    override fun <X : Any?> unwrap(value: String?, type: Class<X>?, options: org.hibernate.type.descriptor.WrapperOptions?): X? = null
}
@Entity @Table(name = "data_read_custom_java")
open class DataReadCustomJavaType {
    @field:Id open var id: Long = 0
    @field:org.hibernate.annotations.JavaType(DataNullProducingJavaType::class)
    open var label: String = "nonnull"
}

@Entity @Table(name = "data_read_null_checks")
open class DataReadNullChecks {
    @field:Id open var id: Long = 0
    @field:Column(nullable = false) open var label: String? = null
}
