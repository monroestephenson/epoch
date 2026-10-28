package dev.epoch

import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.readwideningfixture.*
import org.hibernate.HibernateException
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadWideningPostgresTest {
    @TempDir lateinit var directory: Path
    private val classes = listOf(ReadWideInt::class.java, ReadWideLong::class.java, ReadWideString::class.java)
    private fun extract() = HibernateExtractor().extract(ExtractionRequest(
        entityClasses = classes.map { it.name }, discoverRepositories = false))

    private fun verify(contract: DatabaseContract, migrations: List<String>): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(SCHEMA)
        val pending = directory.resolve("pending").createDirectories()
        migrations.forEachIndexed { index, sql -> pending.resolve("V${index + 2}__widen.sql").writeText(sql) }
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    @Test fun `actual mapped String Long and bounded Int readers accept value preserving widening`() {
        val contract = extract()
        assertTrue(contract.queries.filter { it.operation == Operation.SELECT }.all { query ->
            query.mappedReadCoverageComplete && query.mappedReads.isNotEmpty() && query.mappedReads.all { it.resultPosition != null }
        }, contract.toString())
        // TEXT widening can be operationally UNKNOWN without a heap rewrite. The still-physical
        // integer bound is independently re-established before the final plain integer widening.
        val report = verify(contract, listOf(
            "ALTER TABLE read_wide_string ALTER COLUMN label TYPE text;",
            "ALTER TABLE read_wide_long ALTER COLUMN amount TYPE bigint;",
            "ALTER TABLE read_wide_int ALTER COLUMN amount TYPE bigint;",
        ))
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertFalse(report.migrations.flatMap { it.findings }.any { it.ruleId == "RESULT_SIGNATURE_CHANGED" }, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "TYPE_WIDENED" && it.objectName == "public.read_wide_int.amount" && it.evidence.contains("signed 32-bit range")
        }, report.toString())
    }

    @Test fun `native declared result types remain strict despite a compatible Long entity decoder`() {
        val native = QueryContract("native.exactLong", Operation.SELECT, "select amount from read_wide_long",
            tables = listOf("public.read_wide_long"), columns = listOf(ColumnReference("public.read_wide_long", "amount")),
            resultColumns = listOf(ResultColumn("amount", "integer")), source = QuerySource.NAMED_NATIVE,
            dependenciesComplete = true, exactResultShape = true)
        val input = extract().let { it.copy(queries = it.queries + native) }
        val report = verify(input, listOf("ALTER TABLE read_wide_long ALTER COLUMN amount TYPE bigint;"))
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "RESULT_SIGNATURE_CHANGED" && it.verdict == Verdict.BREAKING && native.id in it.affectedContractIds
        }, report.toString())
    }

    @Test fun `a native bigint producer prevents a fabricated bounded old Int read proof`() {
        val native = QueryContract("native.wideInt", Operation.INSERT,
            "insert into read_wide_int (id,amount) values (?,?)", tables = listOf("public.read_wide_int"),
            columns = listOf("id", "amount").map { ColumnReference("public.read_wide_int", it) },
            writeColumns = listOf("id", "amount").map { ColumnReference("public.read_wide_int", it) },
            parameterTypes = listOf("bigint", "bigint"), source = QuerySource.STATIC, dependenciesComplete = true,
            targetTable = "public.read_wide_int")
        val input = extract().let { it.copy(queries = it.queries + native) }
        val report = verify(input, listOf("ALTER TABLE read_wide_int ALTER COLUMN amount TYPE bigint;"))
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any { it.ruleId == "RESULT_SIGNATURE_CHANGED" }, report.toString())
    }

    @Test fun `real old Hibernate getters preserve supported values but getInt rejects out of range bigint`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:16.8")).use { database ->
            database.start()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(SCHEMA)
                    statement.execute("INSERT INTO read_wide_int VALUES (1,2147483647),(2,-2147483648); INSERT INTO read_wide_long VALUES (1,2147483647); INSERT INTO read_wide_string VALUES (1,'old');")
                }
                val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
                    "hibernate.connection.url" to database.jdbcUrl,
                    "hibernate.connection.username" to database.username,
                    "hibernate.connection.password" to database.password,
                    "hibernate.hbm2ddl.auto" to "none",
                    "jakarta.persistence.validation.mode" to "none",
                )).build()
                try {
                    val metadata = MetadataSources(registry)
                    classes.forEach(metadata::addAnnotatedClass)
                    metadata.buildMetadata().buildSessionFactory().use { factory ->
                        factory.openSession().use { session ->
                            assertEquals(Int.MAX_VALUE, session.find(ReadWideInt::class.java, 1L).amount)
                            assertEquals(Int.MIN_VALUE, session.find(ReadWideInt::class.java, 2L).amount)
                            assertEquals("old", session.find(ReadWideString::class.java, 1L).label)
                            session.clear()
                            connection.createStatement().use { statement ->
                                statement.execute("ALTER TABLE read_wide_int ALTER COLUMN amount TYPE bigint; ALTER TABLE read_wide_long ALTER COLUMN amount TYPE bigint; ALTER TABLE read_wide_string ALTER COLUMN label TYPE text;")
                                statement.execute("UPDATE read_wide_long SET amount=2147483648 WHERE id=1;")
                            }
                            assertEquals(Int.MAX_VALUE, session.find(ReadWideInt::class.java, 1L).amount)
                            assertEquals(Int.MIN_VALUE, session.find(ReadWideInt::class.java, 2L).amount)
                            assertEquals(2147483648L, session.find(ReadWideLong::class.java, 1L).amount)
                            assertEquals("old", session.find(ReadWideString::class.java, 1L).label)
                            session.beginTransaction()
                            session.persist(ReadWideInt().also { it.id = 3L; it.amount = Int.MAX_VALUE })
                            session.transaction.commit()
                            session.clear()
                            assertEquals(Int.MAX_VALUE, session.find(ReadWideInt::class.java, 3L).amount)
                            session.clear()
                            connection.createStatement().use { it.execute("UPDATE read_wide_int SET amount=2147483648 WHERE id=1") }
                            val failure = assertFailsWith<HibernateException> { session.find(ReadWideInt::class.java, 1L) }
                            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is SQLException && it.sqlState == "22003" }, failure.toString())
                        }
                    }
                } finally { StandardServiceRegistryBuilder.destroy(registry) }
            }
        }
    }

    companion object {
        private val SCHEMA = """
            CREATE TABLE read_wide_int(id bigint PRIMARY KEY, amount integer NOT NULL);
            CREATE TABLE read_wide_long(id bigint PRIMARY KEY, amount integer NOT NULL);
            CREATE TABLE read_wide_string(id bigint PRIMARY KEY, label varchar(20) NOT NULL);
        """.trimIndent()
    }
}
