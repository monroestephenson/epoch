package dev.epoch.hibernate

import dev.epoch.core.Operation
import jakarta.persistence.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.*

class ExtractionFidelityTest {
    @TempDir lateinit var directory: Path

    @Test fun `joined loader columns resolve to their actual SQL aliases`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(JoinedParent::class.java.name, JoinedChild::class.java.name), discoverRepositories = false))
        assertFalse(contract.entities.any { it.table == "public.joined_child" && it.columns.any { column -> column.name == "label" } }, contract.toString())
        assertTrue(contract.queries.filter { it.operation == Operation.SELECT }.flatMap { it.columns }.any { it.table == "public.joined_parent" && it.column == "label" })
        assertFalse(contract.queries.flatMap { it.columns }.any { it.table == "public.joined_child" && it.column == "label" }, contract.toString())
        assertTrue(contract.queries.all { it.dependenciesComplete }, contract.toString())
    }

    @Test fun `secondary table columns are never assigned to the main table`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(SecondaryRecord::class.java.name), discoverRepositories = false))
        assertTrue(contract.queries.any { it.columns.any { column -> column.table == "public.secondary_details" && column.column == "description" } })
        assertTrue(contract.entities.single { it.table == "public.secondary_details" }.columns.any { it.name == "id" && it.insertable && !it.generated })
        assertFalse(contract.queries.flatMap { it.columns }.any { it.table == "public.secondary_record" && it.column == "description" }, contract.toString())
        // Hibernate uses MERGE for optional secondary-table updates; the supported SQL model records it unresolved.
        assertTrue(contract.queries.filterNot { it.sql.startsWith("merge ", ignoreCase = true) }.all { it.dependenciesComplete })
        assertFalse(contract.coverage.complete)
        assertTrue(contract.unresolvedQueries.any { it.reason.contains("dependency") })
    }

    @Test fun `identity and nonpooled sequence dependencies no longer make mappings unresolved`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(IdentityRecord::class.java.name, SequenceRecord::class.java.name), discoverRepositories = false))
        assertTrue(contract.coverage.complete, contract.toString())
        assertTrue(contract.entities.single { it.table == "public.identity_record" }.columns.single { it.name == "id" }.generated)
        assertEquals("public.record_id_seq", contract.entities.single { it.table == "public.sequence_record" }.identifierSequence)
        assertEquals(1, contract.entities.single { it.table == "public.sequence_record" }.identifierAllocationSize)
        assertTrue(contract.queries.single { it.operation == Operation.INSERT && it.targetTable == "public.sequence_record" }.writeColumns.any { it.column == "id" })
        val tenant = HibernateExtractor().extract(ExtractionRequest(listOf(SequenceRecord::class.java.name),
            hibernateProperties = mapOf("hibernate.default_schema" to "tenant_a"), discoverRepositories = false))
        assertEquals("tenant_a.record_id_seq", tenant.entities.single().identifierSequence)
    }

    @Test fun `generated identity is omitted only from its owning table while secondary keys are explicit`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(IdentityWithSecondary::class.java.name), discoverRepositories = false))
        assertTrue(contract.entities.single { it.table == "public.identity_primary" }.columns.single { it.name == "id" }.generated)
        assertFalse(contract.entities.single { it.table == "public.identity_secondary" }.columns.single { it.name == "id" }.generated)
        assertTrue(contract.queries.single { it.operation == Operation.INSERT && it.targetTable == "public.identity_secondary" }.writeColumns.any { it.column == "id" })
    }

    @Test fun `mapped bounds and enum relational domain come from Hibernate mappings`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(BoundedRecord::class.java.name), discoverRepositories = false))
        val columns = contract.entities.single().columns.associateBy { it.name }
        assertEquals(42L, columns.getValue("label").length)
        assertEquals(12, columns.getValue("amount").precision)
        assertEquals(3, columns.getValue("amount").scale)
        assertEquals(listOf("ACTIVE", "PAUSED"), columns.getValue("status").enumValues)
        assertEquals(listOf("0", "1"), columns.getValue("ordinal_status").enumValues)
    }

    @Test fun `element collection has actual loader and row mutation contracts`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(CollectionRecord::class.java.name), discoverRepositories = false))
        val queries = contract.queries.filter { "public.record_tags" in it.tables }
        assertTrue(queries.any { it.operation == Operation.SELECT }, contract.toString())
        assertTrue(queries.any { it.operation == Operation.INSERT }, contract.toString())
        assertTrue(queries.any { it.operation == Operation.DELETE }, contract.toString())
        assertTrue(queries.all { it.dependenciesComplete }, contract.toString())
        assertTrue(contract.entities.any { it.table == "public.record_tags" && it.columns.map { column -> column.name }.containsAll(listOf("record_id", "tag")) })
        assertTrue(contract.coverage.complete, contract.toString())
    }

    @Test fun `nonexistent build directories produce warnings without rejecting found entities`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name), classpath = listOf(directory.resolve("absent").toString()), discoverRepositories = false))
        assertTrue(contract.coverage.complete, contract.toString())
        assertTrue(contract.coverage.notes.any { it.contains("ignored nonexistent classpath") })
    }

    @Test fun `logical domains distinguish primitive nullability and custom conversion from DDL bounds`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(LogicalDomainRecord::class.java.name), discoverRepositories = false))
        val columns = contract.entities.single().columns.associateBy { it.name }
        assertEquals(false, columns.getValue("count").nullable)
        assertEquals("java.util.UUID", columns.getValue("external_id").logicalJavaType)
        assertEquals(255L, columns.getValue("external_id").length)
        assertFalse(columns.getValue("code").inputDomainComplete)
        // Its actual SQL/JDBC contract is complete; domain-dependent migration rules must use the separate flag.
        assertTrue(contract.coverage.complete, contract.toString())
    }

    @Test fun `PostgreSQL identifier truncation preserves complete UTF8 code points`() {
        val input = "table_" + "é".repeat(40)
        val sql = "select x.\"$input\" from \"$input\" x"
        val deps = SqlDependencies.inspect(sql)
        assertTrue(deps.complete)
        assertEquals("public." + "table_" + "é".repeat(28), deps.tables.single())
        assertEquals("table_" + "é".repeat(28), deps.columns.single().column)
    }
}

@Entity @Table(name = "secondary_record") @SecondaryTable(name = "secondary_details")
open class SecondaryRecord {
    @field:Id open var id: Long = 0
    @field:Column(table = "secondary_details") open var description: String? = null
}
@Entity @Table(name = "identity_record")
open class IdentityRecord {
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY) open var id: Long? = null
    open var label: String? = null
}
@Entity @Table(name = "sequence_record")
open class SequenceRecord {
    @field:Id @field:GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "record_sequence")
    @field:SequenceGenerator(name = "record_sequence", sequenceName = "record_id_seq", allocationSize = 1)
    open var id: Long? = null
    open var label: String? = null
}
enum class RecordStatus { ACTIVE, PAUSED }
@Entity @Table(name = "bounded_record")
open class BoundedRecord {
    @field:Id open var id: Long = 0
    @field:Column(length = 42) open var label: String? = null
    @field:Column(precision = 12, scale = 3) open var amount: BigDecimal? = null
    @field:Enumerated(EnumType.STRING) open var status: RecordStatus = RecordStatus.ACTIVE
    @field:Enumerated(EnumType.ORDINAL) @field:Column(name = "ordinal_status") open var ordinalStatus: RecordStatus = RecordStatus.ACTIVE
}
@Entity @Table(name = "collection_record")
open class CollectionRecord {
    @field:Id open var id: Long = 0
    @field:ElementCollection
    @field:CollectionTable(name = "record_tags", joinColumns = [JoinColumn(name = "record_id")])
    @field:Column(name = "tag") open var tags: MutableSet<String> = linkedSetOf()
}

@Converter
class PrefixedCodeConverter : AttributeConverter<String, String> {
    override fun convertToDatabaseColumn(attribute: String?): String? = attribute?.let { "prefix-$it" }
    override fun convertToEntityAttribute(value: String?): String? = value?.removePrefix("prefix-")
}
@Entity @Table(name = "logical_domain_record")
open class LogicalDomainRecord {
    @field:Id open var id: Long = 0
    @field:Column(nullable = true) open var count: Int = 0
    @field:org.hibernate.annotations.JdbcTypeCode(java.sql.Types.VARCHAR)
    @field:Column(name = "external_id", length = 255) open var externalId: java.util.UUID? = null
    @field:Convert(converter = PrefixedCodeConverter::class) @field:Column(length = 32) open var code: String? = null
}

@Entity @Table(name = "identity_primary") @SecondaryTable(name = "identity_secondary")
open class IdentityWithSecondary {
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY) open var id: Long? = null
    @field:Column(table = "identity_secondary") open var detail: String? = null
}
