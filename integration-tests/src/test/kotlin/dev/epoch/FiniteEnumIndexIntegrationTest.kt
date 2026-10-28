package dev.epoch

import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FiniteEnumIndexIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `actual Hibernate enum writes and unchanged index tuple updates remain safe on TEXT indexes`() {
        val entities = listOf(FiniteIndexWrite::class.java, FixedIndexWrite::class.java)
        val contract = extract(entities)
        val statusColumns = contract.entities.flatMap { it.columns }.filter { it.name == "status" }
        assertEquals(2, statusColumns.size)
        assertTrue(statusColumns.all { it.enumValues.toSet() == IndexState.entries.map { state -> state.name }.toSet() &&
            it.inputDomainComplete }, statusColumns.toString())
        val report = verify(contract, ENUM_BASELINE, ENUM_INDEXES)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        val findings = report.migrations.single().findings.filter { it.ruleId == "NON_UNIQUE_INDEX_CHANGED" }
        assertEquals(2, findings.size, report.toString())
        assertTrue(findings.all { it.evidence.contains("Direct operation proofs=") }, findings.toString())
        assertTrue(findings.single { it.objectName.endsWith("fixed_status_lookup") }.evidence.contains("unchanged tuples=1"), findings.toString())

        withHibernate(entities, ENUM_BASELINE) { factory, connection ->
            connection.createStatement().use { it.execute(ENUM_INDEXES) }
            for (value in IndexState.entries) factory.openSession().use { session ->
                val transaction = session.beginTransaction()
                try {
                    val mutable = FiniteIndexWrite().apply { status = value }
                    val fixed = FixedIndexWrite().apply { status = value }
                    session.persist(mutable)
                    session.persist(fixed)
                    session.flush()
                    mutable.status = IndexState.entries[(value.ordinal + 1) % IndexState.entries.size]
                    mutable.payload = "updated"
                    fixed.payload = "updated"
                    session.flush()
                } finally { transaction.rollback() }
            }
        }
    }

    @Test fun `Hibernate DDL length is not an accepted input bound for a TEXT index`() {
        val entities = listOf(UnboundedIndexWrite::class.java)
        val contract = extract(entities)
        val label = contract.entities.single().columns.single { it.name == "label" }
        assertEquals(20L, label.length)
        assertTrue(label.enumValues.isEmpty())
        val report = verify(contract, STRING_BASELINE, STRING_INDEX)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "INDEX_KEY_DOMAIN_UNPROVEN" }, report.toString())

        // Fixed hashes produce a reproducible, poorly compressible value, rather than assuming repeated text fills an index tuple.
        val digest = MessageDigest.getInstance("SHA-256")
        val value = (0 until 256).joinToString("") { ordinal ->
            digest.digest("epoch-index-$ordinal".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        withHibernate(entities, STRING_BASELINE) { factory, connection ->
            persistRolledBack(factory, value)
            connection.createStatement().use { it.execute(STRING_INDEX) }
            val failure = assertThrows(RuntimeException::class.java) { persistRolledBack(factory, value) }
            assertEquals("54000", generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>().firstOrNull()?.sqlState, failure.toString())
        }
    }

    private fun extract(entities: List<Class<*>>) = HibernateExtractor().extract(ExtractionRequest(
        entityClasses = entities.map { it.name }, applicationVersion = "finite-index-fixture", discoverRepositories = false))

    private fun verify(contract: DatabaseContract, baselineSql: String, migration: String): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__indexes.sql").writeText(migration)
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    private fun persistRolledBack(factory: SessionFactory, value: String) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                session.persist(UnboundedIndexWrite().apply { label = value })
                session.flush()
            } finally { transaction.rollback() }
        }
    }

    private fun withHibernate(entities: List<Class<*>>, baseline: String, block: (SessionFactory, Connection) -> Unit) {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute(baseline) }
                    val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
                        "hibernate.connection.url" to database.jdbcUrl,
                        "hibernate.connection.username" to database.username,
                        "hibernate.connection.password" to database.password,
                        "hibernate.hbm2ddl.auto" to "none",
                        "jakarta.persistence.validation.mode" to "none",
                    )).build()
                    try {
                        val sources = MetadataSources(registry)
                        entities.forEach(sources::addAnnotatedClass)
                        sources.buildMetadata().buildSessionFactory().use { factory -> block(factory, connection) }
                    } finally { StandardServiceRegistryBuilder.destroy(registry) }
                }
            }
    }

    companion object {
        private const val ENUM_BASELINE = "CREATE TABLE finite_index_write(id bigint PRIMARY KEY, status text, payload text); " +
            "CREATE TABLE fixed_index_write(id bigint PRIMARY KEY, status text, payload text);"
        private const val ENUM_INDEXES = "CREATE INDEX finite_status_lookup ON finite_index_write(status); " +
            "CREATE INDEX fixed_status_lookup ON fixed_index_write(status);"
        private const val STRING_BASELINE = "CREATE TABLE unbounded_index_write(id bigint PRIMARY KEY, label text);"
        private const val STRING_INDEX = "CREATE INDEX unbounded_label_lookup ON unbounded_index_write(label);"
    }
}

enum class IndexState { OPEN, CLOSED, RETIRED }

@Entity @Table(name = "finite_index_write")
open class FiniteIndexWrite {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) @field:Column(columnDefinition = "text") open var status: IndexState = IndexState.OPEN
    open var payload: String = "before"
}

@Entity @Table(name = "fixed_index_write")
open class FixedIndexWrite {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING) @field:Column(columnDefinition = "text", updatable = false) open var status: IndexState = IndexState.OPEN
    open var payload: String = "before"
}

@Entity @Table(name = "unbounded_index_write")
open class UnboundedIndexWrite {
    @field:Id open var id: Long = 1
    @field:Column(length = 20, columnDefinition = "text") open var label: String = "before"
}
