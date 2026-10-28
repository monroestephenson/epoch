package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import dev.epoch.core.QuerySource
import jakarta.persistence.*
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.DynamicInsert
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.Generated
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLInsert
import org.hibernate.annotations.SQLUpdate
import org.hibernate.annotations.SecondaryRow
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.generator.EventType
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteDomainMetadataTest {
    private val scalarTypes = listOf(DomainDirect::class.java, DomainTransformed::class.java,
        DomainCustomInsert::class.java, DomainCustomUpdate::class.java,
        DomainDynamicInsert::class.java, DomainDynamicUpdate::class.java,
        DomainCreatedValue::class.java, DomainGeneratedValue::class.java, DomainGeneratedComponentOwner::class.java)

    @Test fun `direct input facts exclude transformers custom SQL and dynamic mutations in both runtimes`() {
        for (classpath in runtimes()) {
            val contract = extract(scalarTypes, classpath)
            assertTrue(column(contract, "domain_direct", "required_label").path("inputDomainComplete").asBoolean())
            assertTrue(column(contract, "domain_direct", "optional_label").path("inputDomainComplete").asBoolean())
            for (table in listOf("domain_transformed", "domain_custom_insert", "domain_custom_update",
                "domain_dynamic_insert", "domain_dynamic_update", "domain_generated_value")) {
                assertFalse(column(contract, table, "label").path("inputDomainComplete").asBoolean(),
                    "$table must not claim that every logical input reaches SQL unchanged: $contract")
            }
            assertFalse(column(contract, "domain_created_value", "created_at").path("inputDomainComplete").asBoolean(),
                "The generated timestamp is not a direct application input")
            assertTrue(column(contract, "domain_created_value", "label").path("inputDomainComplete").asBoolean(),
                "An audit timestamp must not invalidate the unrelated label's input domain")
            assertFalse(column(contract, "domain_generated_component", "component_created_at").path("inputDomainComplete").asBoolean(),
                "An embedded generator's physical leaf must not be treated as direct input")
            assertTrue(column(contract, "domain_generated_component", "label").path("inputDomainComplete").asBoolean(),
                "An embedded generator must not invalidate an unrelated entity property")
            val scoped = extract(scalarTypes, classpath, mapOf(
                DomainCustomInsert::class.java.name to EntityOverride(setOf(Operation.UPDATE)),
                DomainCustomUpdate::class.java.name to EntityOverride(setOf(Operation.INSERT)),
                DomainDynamicInsert::class.java.name to EntityOverride(setOf(Operation.UPDATE)),
                DomainDynamicUpdate::class.java.name to EntityOverride(setOf(Operation.INSERT)),
                DomainCreatedValue::class.java.name to EntityOverride(setOf(Operation.UPDATE)),
            ))
            for (table in listOf("domain_custom_insert", "domain_custom_update", "domain_dynamic_insert", "domain_dynamic_update")) {
                assertTrue(column(scoped, table, "label").path("inputDomainComplete").asBoolean(),
                    "Disabled mutation variants must not invalidate the enabled direct operation: $scoped")
            }
            assertTrue(column(scoped, "domain_created_value", "created_at").path("inputDomainComplete").asBoolean(),
                "INSERT-only timestamp generation is outside an UPDATE-only contract")
        }
    }

    @Test fun `generated statements retain exact originating table mappings in both runtimes`() {
        val types = listOf(DomainDirect::class.java, DomainJoinedRoot::class.java, DomainJoinedChild::class.java,
            DomainSecondary::class.java, DomainMandatorySecondary::class.java, DomainCollection::class.java)
        for (classpath in runtimes()) {
            val contract = extract(types, classpath)
            val nodes = jacksonObjectMapper().valueToTree<JsonNode>(contract).path("queries").associateBy { it.path("id").asText() }
            val mappings = contract.entities.associateBy { it.id }
            val generated = contract.queries.filter { it.source == QuerySource.HIBERNATE_MAPPING }
            assertTrue(generated.isNotEmpty(), contract.toString())
            for (query in generated) {
                val links = nodes.getValue(query.id).path("mappedEntityIds")
                assertTrue(links.isArray && links.size() > 0, "Generated statement lacks actual mapping provenance: $query")
                val ids = links.map { it.asText() }
                assertTrue(ids.all { it in mappings }, "Unknown mapping link: $ids")
                if (query.operation != Operation.SELECT) {
                    assertEquals(1, ids.size, query.toString())
                    assertEquals(query.targetTable, mappings.getValue(ids.single()).table, query.toString())
                }
                if (query.id.startsWith(DomainJoinedChild::class.java.name + ".")) {
                    assertTrue(ids.all { it.startsWith(DomainJoinedChild::class.java.name + "@") },
                        "A child statement cannot borrow its parent's separate contract entry: $ids")
                }
                if (query.id.startsWith(DomainCollection::class.java.name + ".tags.")) {
                    assertEquals(listOf(DomainCollection::class.java.name + ".tags@collection"), ids)
                }
            }
            val childDelete = generated.single { it.operation == Operation.DELETE && it.targetTable == "public.domain_joined_child" }
            assertEquals(listOf(DomainJoinedChild::class.java.name + "@public.domain_joined_child"),
                nodes.getValue(childDelete.id).path("mappedEntityIds").map { it.asText() })
            assertTrue(generated.any { it.targetTable == "public.domain_secondary_details" }, contract.toString())
            assertTrue(generated.any { it.targetTable == "public.domain_tags" }, contract.toString())
            assertFalse(column(contract, "domain_secondary_details", "detail").path("inputDomainComplete").asBoolean(),
                "Optional secondary rows can be omitted or deleted instead of storing a logical NULL")
            assertTrue(column(contract, "domain_secondary", "label").path("inputDomainComplete").asBoolean(),
                "Optional secondary-row behavior must not invalidate the primary table's direct inputs")
            assertTrue(column(contract, "domain_secondary_required", "detail").path("inputDomainComplete").asBoolean(),
                "A mandatory secondary row retains its direct input domain")
            generated.filter { it.sql.trimStart().startsWith("merge", ignoreCase = true) }.forEach { query ->
                assertFalse(query.dependenciesComplete, query.toString())
                assertTrue(contract.unresolvedQueries.any { it.id == query.id }, query.toString())
            }
            for (query in contract.queries.filter { it.source != QuerySource.HIBERNATE_MAPPING }) {
                assertEquals(0, nodes.getValue(query.id).path("mappedEntityIds").size(), query.toString())
            }
            assertTrue(contract.queries.any { it.id == "DomainDirect.byRequired" }, contract.toString())
        }
    }

    @Test fun `only explicit unbounded Hibernate SQL declarations establish an unbounded string domain`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DomainDirect::class.java), classpath)
            for (name in listOf("text_label", "varchar_label")) {
                val value = column(contract, "domain_direct", name)
                assertTrue(value.path("lengthUnbounded").isBoolean && value.path("lengthUnbounded").asBoolean(), value.toString())
                assertTrue(value.has("length"), "Retain the separate raw Hibernate DDL bound")
            }
            for (name in listOf("required_label", "optional_label", "bounded_label")) {
                val value = column(contract, "domain_direct", name)
                assertTrue(value.path("lengthUnbounded").isBoolean, value.toString())
                assertFalse(value.path("lengthUnbounded").asBoolean(), value.toString())
            }
        }
    }

    @Test fun `factory extraction without boot metadata does not invent an unbounded declaration`() {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "hibernate.hbm2ddl.auto" to "none",
            "jakarta.persistence.validation.mode" to "none",
        )).build()
        try {
            val factory = MetadataSources(registry).addAnnotatedClass(DomainDirect::class.java)
                .buildMetadata().buildSessionFactory() as SessionFactoryImplementor
            try {
                val contract = HibernateExtractor().extractFactory(factory, ExtractionRequest(discoverRepositories = false))
                val value = column(contract, "domain_direct", "text_label")
                assertTrue(value.path("lengthUnbounded").isBoolean, value.toString())
                assertFalse(value.path("lengthUnbounded").asBoolean(), value.toString())
            } finally { factory.close() }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    @Test fun `unbounded boot declarations are not borrowed by another entity sharing the same column`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DomainSharedText::class.java, DomainSharedBounded::class.java), classpath)
            val entities = jacksonObjectMapper().valueToTree<JsonNode>(contract).path("entities").associateBy { it.path("id").asText() }
            val text = entities.getValue(DomainSharedText::class.java.name).path("columns").single { it.path("name").asText() == "label" }
            val bounded = entities.getValue(DomainSharedBounded::class.java.name).path("columns").single { it.path("name").asText() == "label" }
            assertTrue(text.path("lengthUnbounded").asBoolean(), text.toString())
            assertFalse(bounded.path("lengthUnbounded").asBoolean(), bounded.toString())
        }
    }

    @Test fun `standard native PostgreSQL enums retain finite direct input domains in both runtimes`() {
        for (classpath in runtimes()) {
            val contract = extract(listOf(DomainNativeEnum::class.java), classpath)
            val value = column(contract, "domain_native_enum", "label")
            assertTrue(value.path("inputDomainComplete").asBoolean(), value.toString())
            assertEquals(listOf("CLOSED", "OPEN"), value.path("enumValues").map { it.asText() })
            assertEquals("NAME", value.path("enumEncoding").asText())
        }
    }

    private fun extract(types: List<Class<*>>, classpath: List<String>, overrides: Map<String, EntityOverride> = emptyMap()) =
        HibernateExtractor().extract(ExtractionRequest(entityClasses = types.map { it.name }, classpath = classpath,
            discoverRepositories = false, entityOverrides = overrides))

    private fun column(contract: DatabaseContract, table: String, column: String): JsonNode =
        jacksonObjectMapper().valueToTree<JsonNode>(contract).path("entities")
            .single { it.path("table").asText() == "public.$table" }.path("columns")
            .single { it.path("name").asText() == column }

    private fun runtimes(): List<List<String>> = listOf(emptyList(),
        requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator) +
            Path.of(DomainDirect::class.java.protectionDomain.codeSource.location.toURI()).toString())
}

@Entity @Table(name = "domain_direct")
@NamedQuery(name = "DomainDirect.byRequired", query = "from DomainDirect d where d.requiredLabel = :value")
open class DomainDirect {
    @field:Id open var id: Long = 0
    @field:Column(name = "required_label") open var requiredLabel: String = ""
    @field:Column(name = "optional_label") open var optionalLabel: String? = null
    @field:Column(name = "text_label", columnDefinition = "text") open var textLabel: String? = null
    @field:Column(name = "varchar_label", columnDefinition = "varchar") open var varcharLabel: String? = null
    @field:Column(name = "bounded_label", length = 12) open var boundedLabel: String? = null
}

@Entity @Table(name = "domain_transformed")
open class DomainTransformed {
    @field:Id open var id: Long = 0
    @field:ColumnTransformer(write = "coalesce(?, '')") open var label: String? = null
}

@Entity @Table(name = "domain_custom_insert")
@SQLInsert(sql = "insert into domain_custom_insert(label, id) values (coalesce(?, ''), ?)")
open class DomainCustomInsert {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "domain_custom_update")
@SQLUpdate(sql = "update domain_custom_update set label = coalesce(?, '') where id = ?")
open class DomainCustomUpdate {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "domain_dynamic_insert") @DynamicInsert
open class DomainDynamicInsert {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "domain_dynamic_update") @DynamicUpdate
open class DomainDynamicUpdate {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "domain_created_value")
open class DomainCreatedValue {
    @field:Id open var id: Long = 0
    open var label: String? = null
    @field:CreationTimestamp @field:Column(name = "created_at") open var createdAt: java.time.LocalDateTime? = null
}

@Entity @Table(name = "domain_generated_value")
open class DomainGeneratedValue {
    @field:Id open var id: Long = 0
    @field:Generated(event = [EventType.INSERT]) open var label: String? = null
}

@Embeddable
open class DomainGeneratedComponent {
    @field:CreationTimestamp @field:Column(name = "component_created_at")
    open var createdAt: java.time.LocalDateTime? = null
}

@Entity @Table(name = "domain_generated_component")
open class DomainGeneratedComponentOwner {
    @field:Id open var id: Long = 0
    open var label: String? = null
    @field:Embedded open var audit: DomainGeneratedComponent = DomainGeneratedComponent()
}

@Entity @Table(name = "domain_shared")
open class DomainSharedText {
    @field:Id open var id: Long = 0
    @field:Column(columnDefinition = "text") open var label: String? = null
}

@Entity @Table(name = "domain_shared")
open class DomainSharedBounded {
    @field:Id open var id: Long = 0
    @field:Column(length = 12) open var label: String? = null
}

enum class DomainNativeState { OPEN, CLOSED }

@Entity @Table(name = "domain_native_enum")
open class DomainNativeEnum {
    @field:Id open var id: Long = 0
    @field:Enumerated(EnumType.STRING) @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(columnDefinition = "domain_native_values") open var label: DomainNativeState = DomainNativeState.OPEN
}

@Entity @Table(name = "domain_joined_root") @Inheritance(strategy = InheritanceType.JOINED)
open class DomainJoinedRoot {
    @field:Id open var id: Long = 0
    open var label: String? = null
}

@Entity @Table(name = "domain_joined_child")
open class DomainJoinedChild : DomainJoinedRoot() {
    open var detail: String? = null
}

@Entity @Table(name = "domain_secondary")
@SecondaryTable(name = "domain_secondary_details", pkJoinColumns = [PrimaryKeyJoinColumn(name = "account_id")])
open class DomainSecondary {
    @field:Id open var id: Long = 0
    open var label: String? = null
    @field:Column(table = "domain_secondary_details") open var detail: String? = null
}

@Entity @Table(name = "domain_mandatory_secondary")
@SecondaryTable(name = "domain_secondary_required", pkJoinColumns = [PrimaryKeyJoinColumn(name = "account_id")])
@SecondaryRow(table = "domain_secondary_required", optional = false)
open class DomainMandatorySecondary {
    @field:Id open var id: Long = 0
    open var label: String? = null
    @field:Column(table = "domain_secondary_required") open var detail: String? = null
}

@Entity @Table(name = "domain_collection")
open class DomainCollection {
    @field:Id open var id: Long = 0
    @field:ElementCollection @field:CollectionTable(name = "domain_tags", joinColumns = [JoinColumn(name = "account_id")])
    @field:Column(name = "tag") open var tags: MutableSet<String> = mutableSetOf()
}
