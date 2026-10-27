package dev.epoch.hibernate

import dev.epoch.core.Operation
import dev.epoch.core.QuerySource
import dev.epoch.scanfixture.ScannedCustomer
import jakarta.persistence.*
import org.hibernate.annotations.Formula
import org.hibernate.annotations.Immutable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.*

class ExtractionFeedbackTest {
    @TempDir lateinit var directory: Path

    @Test fun `joined inheritance does not declare synthetic discriminator as a physical column`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(JoinedParent::class.java.name, JoinedChild::class.java.name), discoverRepositories = false))
        assertTrue(contract.entities.isNotEmpty())
        assertFalse(contract.entities.any { entity -> entity.columns.any { it.name.contains("discriminator") || it.name.startsWith("{") } }, contract.toString())
        assertTrue(contract.entities.any { it.table == "public.joined_parent" })
        assertTrue(contract.entities.any { it.table == "public.joined_child" })
    }

    @Test fun `real formula remains unresolved but never becomes a physical column`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(FormulaProjection::class.java.name), discoverRepositories = false))
        assertFalse(contract.entities.flatMap { it.columns }.any { it.name.contains("length") })
        assertTrue(contract.unresolvedQueries.any { it.reason.contains("Formula dependency") }, contract.toString())
    }

    @Test fun `Hibernate runtime mismatch fails before loading entity classes`() {
        val jar = directory.resolve("application-hibernate-7.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = "7.0.0.Final"
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            output.putNextEntry(JarEntry("org/hibernate/Version.class"))
            javaClass.classLoader.getResourceAsStream("org/hibernate/Version.class")!!.use { it.copyTo(output) }
            output.closeEntry()
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            HibernateExtractor().extract(ExtractionRequest(listOf("missing.ApplicationEntity"), classpath = listOf(jar.toString()), discoverRepositories = false))
        }
        assertTrue(failure.message!!.contains("7.0.0.Final"))
        assertTrue(failure.message!!.contains("6.6.13.Final"))
        assertTrue(failure.message!!.contains(jar.toString()))
    }

    @Test fun `validation is disabled even when application requests callbacks`() {
        // No Bean Validation provider is installed. CALLBACK would fail bootstrap without the forced NONE setting.
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            hibernateProperties = mapOf("jakarta.persistence.validation.mode" to "callback"), discoverRepositories = false))
        assertTrue(contract.coverage.complete, contract.toString())
        assertTrue(contract.coverage.notes.any { it.contains("PostgreSQL dialect version 16.8") }, contract.coverage.notes.toString())
    }

    @Test fun `application Hibernate settings can customize mappings without enabling connections`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            hibernateProperties = mapOf("hibernate.default_schema" to "tenant_a"), discoverRepositories = false))
        assertEquals("tenant_a.customers", contract.entities.single().table)
        val error = assertFailsWith<IllegalArgumentException> {
            HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
                hibernateProperties = mapOf("hibernate.connection.url" to "jdbc:postgresql://invalid.example/app"), discoverRepositories = false))
        }
        assertTrue(error.message!!.contains("hibernate.connection.url"))
        assertFalse(error.message!!.contains("invalid.example"))
    }

    @Test fun `directory package discovery finds compiled entities`() {
        val classes = Path.of(ScannedCustomer::class.java.protectionDomain.codeSource.location.toURI())
        val contract = HibernateExtractor().extract(ExtractionRequest(classpath = listOf(classes.toString()),
            scanPackages = listOf("dev.epoch.scanfixture"), discoverRepositories = false))
        assertEquals(listOf("public.scanned_customers"), contract.entities.map { it.table })
    }

    @Test fun `jar package discovery reads bytecode annotations without initializing classes`() {
        val jar = directory.resolve("entities.jar")
        val entityName = "dev.epoch.poisonfixture.UninitializedEntity"
        val resource = entityName.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(resource))
            javaClass.classLoader.getResourceAsStream(resource)!!.use { it.copyTo(output) }
            output.closeEntry()
        }
        assertEquals(listOf(entityName), ClasspathInspection.entities(listOf(jar.toString()), listOf("dev.epoch.poisonfixture")))
        assertFailsWith<ExceptionInInitializerError> { Class.forName(entityName, true, javaClass.classLoader) }
    }

    @Test fun `keep-going retains healthy entities and records malformed components`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            listOf(Customer::class.java.name, MissingIdentifier::class.java.name), keepGoing = true, discoverRepositories = false))
        assertTrue(contract.entities.any { it.table == "public.customers" }, contract.toString())
        assertTrue(contract.unresolvedQueries.any { it.id == MissingIdentifier::class.java.name && it.reason.contains("bootstrap") }, contract.toString())
        assertFalse(contract.coverage.complete)
    }

    @Test fun `keep-going does not silently cut association dependencies`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            listOf(Customer::class.java.name, BrokenAssociationOwner::class.java.name, MissingIdentifier::class.java.name), keepGoing = true, discoverRepositories = false))
        assertTrue(contract.entities.any { it.table == "public.customers" }, contract.toString())
        assertTrue(contract.entities.any { it.table == "public.broken_owner" }, contract.toString())
        assertTrue(contract.unresolvedQueries.any { it.id.startsWith(BrokenAssociationOwner::class.java.name) })
        assertTrue(contract.unresolvedQueries.any { it.id == MissingIdentifier::class.java.name })
    }

    @Test fun `immutable projections default to SELECT while overrides can explicitly enable operations`() {
        val extractor = HibernateExtractor()
        val request = ExtractionRequest(listOf(ReadOnlyProjection::class.java.name), discoverRepositories = false)
        val readOnly = extractor.extract(request)
        assertEquals(setOf(Operation.SELECT), readOnly.entities.single().operations)
        assertEquals(setOf(Operation.SELECT), readOnly.queries.map { it.operation }.toSet())
        val overridden = extractor.extract(request.copy(entityOverrides = mapOf(
            ReadOnlyProjection::class.java.name to EntityOverride(operations = setOf(Operation.SELECT, Operation.DELETE)))))
        assertEquals(setOf(Operation.SELECT, Operation.DELETE), overridden.queries.map { it.operation }.toSet())
    }

    @Test fun `result mapping only entity has no fabricated table or CRUD dependencies`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name, ResultProjection::class.java.name),
            entityOverrides = mapOf("ResultProjection" to EntityOverride(resultMappingOnly = true)),
            nativeQueryParameterTypes = mapOf("projection.native" to listOf("bigint")), discoverRepositories = false))
        assertFalse(contract.entities.any { it.table.contains("not_a_table") })
        assertFalse(contract.queries.any { it.source == QuerySource.HIBERNATE_MAPPING && it.id.startsWith(ResultProjection::class.java.name) })
        assertEquals(listOf("bigint"), contract.queries.single { it.id == "projection.native" }.parameterTypes)
        assertTrue(contract.coverage.notes.any { it.contains("result mapping") })
    }

    @Test fun `explicit exclusion is recorded without bootstrapping the excluded class`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name, "missing.ExcludedEntity"),
            entityOverrides = mapOf("missing.ExcludedEntity" to EntityOverride(exclude = true)), discoverRepositories = false))
        assertTrue(contract.coverage.complete, contract.toString())
        assertTrue(contract.coverage.notes.any { it.contains("missing.ExcludedEntity") })
    }

    @Test fun `native query parameter declarations retain JDBC order`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            nativeQueries = mapOf("native.lookup" to "select email_address from customers where id = :id and email_address = :email"),
            nativeQueryParameterTypes = mapOf("native.lookup" to listOf("bigint", "text")), discoverRepositories = false))
        val query = contract.queries.single { it.id == "native.lookup" }
        assertEquals(listOf("bigint", "text"), query.parameterTypes)
        assertTrue(query.sql.contains("?"))
        assertTrue(contract.unresolvedQueries.any { it.id == query.id && it.reason.contains("declared parameter types") })
    }

    @Test fun `double and tiny integer JDBC types use PostgreSQL names`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(NumericEntity::class.java.name), discoverRepositories = false))
        val types = contract.queries.flatMap { it.parameterTypes }.toSet()
        assertTrue("double precision" in types, types.toString())
        assertTrue("smallint" in types, types.toString())
        assertFalse("DOUBLE" in types || "TINYINT" in types)
    }
}

@Entity @Inheritance(strategy = InheritanceType.JOINED) @Table(name = "joined_parent")
open class JoinedParent { @field:Id open var id: Long = 0; open var label: String = "" }
@Entity @Table(name = "joined_child")
open class JoinedChild : JoinedParent() { open var detail: String = "" }
@Entity @Table(name = "formula_projection")
open class FormulaProjection {
    @field:Id open var id: Long = 0
    @field:Formula("length(label)") open var length: Int = 0
}
@Entity @Table(name = "broken_mapping")
open class MissingIdentifier { open var value: String = "" }
@Entity @Table(name = "broken_owner")
open class BrokenAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne open var broken: MissingIdentifier? = null
}
@Entity @Immutable @Table(name = "read_only_projection")
open class ReadOnlyProjection { @field:Id open var id: Long = 0; open var label: String = "" }
@Entity(name = "ResultProjection") @Table(name = "not_a_table")
@NamedNativeQuery(name = "projection.native", query = "select id from customers where id = :id", resultClass = ResultProjection::class)
open class ResultProjection { @field:Id open var id: Long = 0 }
@Entity @Table(name = "numeric_entity")
open class NumericEntity {
    @field:Id open var id: Long = 0
    open var fraction: Double = 0.0
    open var small: Byte = 0
}
