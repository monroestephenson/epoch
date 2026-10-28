package dev.epoch

import dev.epoch.core.Verdict
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import jakarta.persistence.Column
import jakarta.persistence.Entity
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
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class StringInputNarrowingTest {
    @TempDir lateinit var directory: Path

    @Test fun `actual default String mapping accepts 501 characters in TEXT and rejects them after narrowing`() {
        val value = "x".repeat(501)
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute(BASELINE) }
                    val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
                        "hibernate.connection.url" to database.jdbcUrl,
                        "hibernate.connection.username" to database.username,
                        "hibernate.connection.password" to database.password,
                        "hibernate.hbm2ddl.auto" to "none",
                        "jakarta.persistence.validation.mode" to "none",
                    )).build()
                    try {
                        MetadataSources(registry).addAnnotatedClass(DefaultStringWrite::class.java)
                            .buildMetadata().buildSessionFactory().use { factory ->
                                persistRolledBack(factory, value)
                                connection.createStatement().use { it.execute(NARROW) }
                                val failure = assertThrows(Exception::class.java) { persistRolledBack(factory, value) }
                                assertEquals("22001", generateSequence<Throwable>(failure) { it.cause }
                                    .filterIsInstance<SQLException>().firstOrNull()?.sqlState, failure.toString())
                            }
                    } finally { StandardServiceRegistryBuilder.destroy(registry) }
                }
            }

        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(DefaultStringWrite::class.java.name), discoverRepositories = false))
        assertEquals(255L, contract.entities.single().columns.single { it.name == "label" }.length,
            "The old Hibernate DDL hint is shorter than this already-proven accepted input")
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(BASELINE)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__narrow.sql").writeText(NARROW)
        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "MAPPED_TYPE_NARROWED" &&
            it.verdict == Verdict.BREAKING && it.objectName == "public.string_input_narrowing.label" }, report.toString())
    }

    private fun persistRolledBack(factory: SessionFactory, value: String) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                session.persist(DefaultStringWrite().apply { label = value })
                session.flush()
                session.doWork { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT length(label) FROM string_input_narrowing WHERE id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(501, result.getInt(1))
                        }
                    }
                }
            } finally { transaction.rollback() }
        }
    }

    companion object {
        private const val BASELINE = "CREATE TABLE string_input_narrowing(id bigint PRIMARY KEY, label text);"
        private const val NARROW = "ALTER TABLE string_input_narrowing ALTER COLUMN label TYPE varchar(500);"
    }
}

@Entity @Table(name = "string_input_narrowing")
open class DefaultStringWrite {
    @field:Id open var id: Long = 1
    @field:Column open var label: String = "value"
}
