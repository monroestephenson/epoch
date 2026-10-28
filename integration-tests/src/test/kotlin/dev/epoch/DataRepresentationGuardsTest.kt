package dev.epoch

import dev.epoch.core.*
import dev.epoch.datadifffixture.DataOldName
import dev.epoch.datadifffixture.DataOldOrdinal
import dev.epoch.datadifffixture.DataOldState
import dev.epoch.hibernate.EntityOverride
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import jakarta.persistence.*
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Literal spellings and raw mapping flags are not necessarily the values consumed by old decoders. */
class DataRepresentationGuardsTest {
    @TempDir lateinit var directory: Path

    private fun extract(classes: List<Class<*>>, overrides: Map<String, EntityOverride> = emptyMap(),
                        properties: Map<String, String> = emptyMap()) = HibernateExtractor().extract(ExtractionRequest(
        entityClasses = classes.map { it.name }, discoverRepositories = false,
        entityOverrides = overrides, hibernateProperties = properties,
    ))

    private fun verify(old: DatabaseContract, new: DatabaseContract, sql: String): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(sql)
        val pending = directory.resolve("pending").createDirectories()
        return requireNotNull(PostgresVerifier().verifyBidirectional(listOf(old), listOf(new), baseline, pending).dataCompatibility)
    }

    @Test fun `numeric discriminator storage may normalize a new string literal to an old accepted value`() {
        val old = extract(listOf(IntegerRoot::class.java, IntegerOne::class.java), mapOf(
            IntegerRoot::class.java.name to EntityOverride(operations = setOf(Operation.SELECT)),
            IntegerOne::class.java.name to EntityOverride(operations = setOf(Operation.SELECT)),
        ))
        val new = extract(listOf(StringRoot::class.java, StringZeroOne::class.java), mapOf(
            StringRoot::class.java.name to EntityOverride(operations = setOf(Operation.SELECT)),
            StringZeroOne::class.java.name to EntityOverride(operations = setOf(Operation.INSERT)),
        ))
        assertEquals("1", old.entities.single { it.id == IntegerOne::class.java.name }.hierarchy?.entityValue)
        assertEquals("01", new.entities.single { it.id == StringZeroOne::class.java.name }.hierarchy?.entityValue)
        val data = verify(old, new, DISCRIMINATOR_SCHEMA)
        assertEquals(Verdict.UNKNOWN, data.verdict, data.toString())
        assertTrue(data.migrations.flatMap { it.findings }.none { it.verdict == Verdict.BREAKING }, data.toString())
        assertTrue(data.migrations.flatMap { it.findings }.any {
            it.ruleId == "DATA_TYPE_MAPPING_UNPROVEN" && it.objectName == "public.representation_discriminator.dtype"
        }, data.toString())
    }

    @Test fun `disabled Hibernate null checks cannot turn a nullable producer into a non-null proof`() {
        val old = extract(listOf(NonNullReader::class.java))
        val new = extract(listOf(NullableUncheckedWriter::class.java), properties = NULL_CHECKS_OFF)
        val column = new.entities.single().columns.single { it.name == "label" }
        assertEquals(false, column.declaredNonNull)
        assertEquals(false, column.hibernateNullable)
        assertFalse(column.inputDomainComplete, new.toString())
        val data = verify(old, new, NULLABILITY_SCHEMA)
        assertEquals(Verdict.UNKNOWN, data.verdict, data.toString())
        assertTrue(data.migrations.flatMap { it.findings }.none { it.verdict == Verdict.BREAKING }, data.toString())
    }

    @Test fun `real Hibernate normalizes enum and discriminator representations and can write NULL with checks disabled`() {
        withDatabase { database ->
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { it.execute("""
                    CREATE TABLE data_name (id bigint PRIMARY KEY, state text);
                    CREATE TABLE data_ordinal_add (id bigint PRIMARY KEY, state text);
                    INSERT INTO data_name VALUES (1, ' OPEN ');
                    INSERT INTO data_ordinal_add VALUES (1, '01');
                    $DISCRIMINATOR_SCHEMA
                    $NULLABILITY_SCHEMA
                """.trimIndent()) }
                withFactory(database, listOf(DataOldName::class.java, DataOldOrdinal::class.java)) { old ->
                    old.openSession().use { session ->
                        assertEquals(DataOldState.OPEN, session.find(DataOldName::class.java, 1L).state)
                        assertEquals(DataOldState.CLOSED, session.find(DataOldOrdinal::class.java, 1L).state)
                    }
                }
                withFactory(database, listOf(StringRoot::class.java, StringZeroOne::class.java)) { new ->
                    persist(new, StringZeroOne())
                }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT dtype::text FROM representation_discriminator WHERE id = 1").use { result ->
                        assertTrue(result.next())
                        assertEquals("1", result.getString(1))
                    }
                }
                withFactory(database, listOf(IntegerRoot::class.java, IntegerOne::class.java)) { old ->
                    old.openSession().use { session ->
                        val loaded = session.find(IntegerRoot::class.java, 1L)
                        assertInstanceOf(IntegerOne::class.java, loaded)
                        assertEquals("known child", loaded.label)
                    }
                }
                withFactory(database, listOf(NullableUncheckedWriter::class.java), NULL_CHECKS_OFF) { new ->
                    persist(new, NullableUncheckedWriter())
                }
                withFactory(database, listOf(NonNullReader::class.java)) { old ->
                    old.openSession().use { session ->
                        val loaded = session.find(NonNullReader::class.java, 1L)
                        val label = NonNullReader::class.java.getDeclaredField("label").apply { isAccessible = true }
                        assertNull(label.get(loaded), "The raw nullable=false mapping flag did not prevent a stored NULL when runtime checks were disabled.")
                    }
                }
            }
        }
    }

    private fun persist(factory: SessionFactory, entity: Any) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                session.persist(entity)
                transaction.commit()
            } catch (failure: Throwable) {
                if (transaction.isActive) transaction.rollback()
                throw failure
            }
        }
    }

    private fun withFactory(database: PostgreSQLContainer, classes: List<Class<*>>,
                            properties: Map<String, String> = emptyMap(), block: (SessionFactory) -> Unit) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.url" to database.jdbcUrl,
            "hibernate.connection.username" to database.username,
            "hibernate.connection.password" to database.password,
            "hibernate.hbm2ddl.auto" to "none",
            "jakarta.persistence.validation.mode" to "none",
        ) + properties).build()
        try {
            val sources = MetadataSources(registry)
            classes.forEach(sources::addAnnotatedClass)
            sources.buildMetadata().buildSessionFactory().use(block)
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    private fun withDatabase(block: (PostgreSQLContainer) -> Unit) {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                block(database)
            }
    }

    @Entity @Table(name = "representation_discriminator")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "dtype", discriminatorType = DiscriminatorType.INTEGER)
    abstract class IntegerRoot {
        @field:Id open var id: Long = 1
        open var label: String = "known child"
    }

    @Entity @DiscriminatorValue("1")
    open class IntegerOne : IntegerRoot()

    @Entity @Table(name = "representation_discriminator")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "dtype", discriminatorType = DiscriminatorType.STRING)
    abstract class StringRoot {
        @field:Id open var id: Long = 1
        open var label: String = "known child"
    }

    @Entity @DiscriminatorValue("01")
    open class StringZeroOne : StringRoot()

    @Entity @Table(name = "representation_nullability")
    open class NonNullReader {
        @field:Id open var id: Long = 1
        open var label: String = "old"
    }

    @Entity @Table(name = "representation_nullability")
    open class NullableUncheckedWriter {
        @field:Id open var id: Long = 1
        @field:Column(nullable = false) open var label: String? = null
    }

    companion object {
        private val NULL_CHECKS_OFF = mapOf("hibernate.check_nullability" to "false")
        private const val DISCRIMINATOR_SCHEMA = "CREATE TABLE representation_discriminator (id bigint PRIMARY KEY, dtype integer NOT NULL, label varchar(255));"
        private const val NULLABILITY_SCHEMA = "CREATE TABLE representation_nullability (id bigint PRIMARY KEY, label varchar(255));"
    }
}
