package dev.epoch

import dev.epoch.core.*
import dev.epoch.fixtures.IdentityAccount
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
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

/** Additional expand-phase proofs, including omitted INSERT values under new foreign keys. */
class ExpansionCompatibilityTest {
    @TempDir lateinit var directory: Path

    private fun verify(baselineSql: String, contract: DatabaseContract, change: String): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__expand.sql").writeText(change)
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    private fun contract(vararg queries: QueryContract, entities: List<EntityContract> = emptyList()) = DatabaseContract(
        applicationVersion = "expansion-proofs", entities = entities, queries = queries.toList(),
        coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL fixture contract")),
    )

    private fun oldInsert(): QueryContract {
        val id = ColumnReference("public.accounts", "id")
        return QueryContract("Account.insert", Operation.INSERT, "INSERT INTO accounts (id) VALUES (?)",
            tables = listOf("public.accounts"), columns = listOf(id), writeColumns = listOf(id),
            parameterTypes = listOf("bigint"), dependenciesComplete = true, targetTable = "public.accounts")
    }

    @Test fun `added nullable column with constant default preserves omitted-column inserts`() {
        val report = verify("CREATE TABLE accounts (id bigint PRIMARY KEY);", contract(oldInsert()),
            "ALTER TABLE accounts ADD COLUMN region text DEFAULT 'unknown';")
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "CONSTANT_DEFAULT_COLUMN_ADDED" }, report.toString())
    }

    @Test fun `varchar to text widening preserves a write-only contract`() {
        val columns = listOf("id", "label").map { ColumnReference("public.accounts", it) }
        val insert = QueryContract("Account.insert", Operation.INSERT, "INSERT INTO accounts (id, label) VALUES (?, ?)",
            tables = listOf("public.accounts"), columns = columns, writeColumns = columns,
            parameterTypes = listOf("bigint", "varchar"), dependenciesComplete = true, targetTable = "public.accounts")
        val mapping = EntityContract("Account", "public.accounts", listOf(
            ColumnContract("id", "bigint", nullable = false), ColumnContract("label", "varchar", length = 20),
        ), operations = setOf(Operation.INSERT))
        val report = verify("CREATE TABLE accounts (id bigint PRIMARY KEY, label varchar(20));",
            contract(insert, entities = listOf(mapping)), "ALTER TABLE accounts ALTER COLUMN label TYPE text;")
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `Hibernate identity mapping accepts a bigserial generation dependency`() {
        val extracted = HibernateExtractor().extract(ExtractionRequest(
            discoverRepositories = false, entityClasses = listOf(IdentityAccount::class.java.name),
            applicationVersion = "serial-generation-proof",
        ))
        assertTrue(extracted.coverage.complete, extracted.toString())
        assertTrue(extracted.queries.any { it.operation == Operation.INSERT }, extracted.toString())
        val report = verify("CREATE TABLE accounts (id bigserial PRIMARY KEY, label varchar(255));", extracted,
            "ALTER TABLE accounts ADD COLUMN metadata text;")
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `new nullable FK column omitted by old inserts receives NULL safely`() {
        val report = verify(SIMPLE_BASELINE, contract(oldInsert()),
            "ALTER TABLE accounts ADD COLUMN region_id bigint REFERENCES regions(id);")
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `all omitted NULL MATCH FULL inputs need no FK risk finding but grammar remains unsupported`() {
        val report = verify(COMPOSITE_BASELINE, contract(oldInsert()), """
            ALTER TABLE accounts ADD COLUMN region_id bigint, ADD COLUMN region_code bigint;
            ALTER TABLE accounts ADD CONSTRAINT account_region
                FOREIGN KEY (region_id, region_code) REFERENCES regions(id, code) MATCH FULL;
        """.trimIndent())
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "UNSUPPORTED_MIGRATION_EFFECT" }, report.toString())
        assertFalse(report.migrations.single().findings.any { it.ruleId == "CONSTRAINT_CHANGED" }, report.toString())
    }

    @Test fun `new FK default used by omitted old INSERT is unknown without input proof`() {
        val report = verify(SIMPLE_BASELINE, contract(oldInsert()), DEFAULTED_FK)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.UNKNOWN && "Account.insert" in it.affectedContractIds &&
                it.ruleId == "CONSTRAINT_CHANGED"
        }, report.toString())
    }

    @Test fun `one omitted NULL component does not prove MATCH FULL foreign key safety`() {
        val report = verify(COMPOSITE_BASELINE, contract(oldInsert()), PARTIAL_NULL_FULL_FK)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.UNKNOWN && "Account.insert" in it.affectedContractIds &&
                it.ruleId == "CONSTRAINT_CHANGED"
        }, report.toString())
    }

    @Test fun `extension-owned domain default is not mistaken for an omitted NULL FK input`() {
        val report = verify(DOMAIN_BASELINE, contract(oldInsert()), DOMAIN_FK)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.UNKNOWN && "Account.insert" in it.affectedContractIds &&
                it.ruleId == "CONSTRAINT_CHANGED"
        }, report.toString())
    }

    @Test fun `extension-owned NOT NULL domain does not establish a nullable omitted column`() {
        val report = verify(REQUIRED_DOMAIN_BASELINE, contract(oldInsert()), REQUIRED_DOMAIN_COLUMN)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.UNKNOWN && "Account.insert" in it.affectedContractIds
        }, report.toString())
    }

    @Test fun `type-level default prevents fabricated breaking omission of a new NOT NULL column`() {
        val report = verify(DOMAIN_BASELINE, contract(oldInsert()), DEFAULTED_REQUIRED_DOMAIN_COLUMN)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertFalse(report.migrations.single().findings.any { it.verdict == Verdict.BREAKING }, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.UNKNOWN && "Account.insert" in it.affectedContractIds
        }, report.toString())
    }

    @Test fun `PostgreSQL supplies a domain default for a new required column omitted by the old INSERT`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute(DOMAIN_BASELINE) }
                    connection.autoCommit = false
                    connection.prepareStatement(oldInsert().sql).use {
                        it.setLong(1, 1)
                        assertEquals(1, it.executeUpdate())
                    }
                    connection.rollback()
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute(DEFAULTED_REQUIRED_DOMAIN_COLUMN) }
                    connection.prepareStatement(oldInsert().sql).use {
                        it.setLong(1, 1)
                        assertEquals(1, it.executeUpdate())
                    }
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT region_id FROM accounts WHERE id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(0L, result.getLong(1))
                            assertFalse(result.wasNull())
                        }
                    }
                }
            }
    }

    @Test fun `PostgreSQL rejects previously accepted omitted inserts for FK and domain changes`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    listOf(Triple(SIMPLE_BASELINE, DEFAULTED_FK, "23503"),
                        Triple(COMPOSITE_BASELINE, PARTIAL_NULL_FULL_FK, "23503"),
                        Triple(DOMAIN_BASELINE, DOMAIN_FK, "23503"),
                        Triple(REQUIRED_DOMAIN_BASELINE, REQUIRED_DOMAIN_COLUMN, "23502")).forEach { (baseline, change, sqlState) ->
                        connection.createStatement().use { it.execute(baseline) }
                        connection.autoCommit = false
                        connection.prepareStatement(oldInsert().sql).use {
                            it.setLong(1, 1)
                            assertEquals(1, it.executeUpdate())
                        }
                        connection.rollback()
                        connection.autoCommit = true
                        connection.createStatement().use { it.execute(change) }
                        connection.autoCommit = false
                        try {
                            val failure = assertThrows(SQLException::class.java) {
                                connection.prepareStatement(oldInsert().sql).use {
                                    it.setLong(1, 1)
                                    it.executeUpdate()
                                }
                            }
                            assertEquals(sqlState, failure.sqlState, change)
                        } finally {
                            connection.rollback()
                            connection.autoCommit = true
                        }
                        connection.createStatement().use { it.execute("DROP TABLE accounts; DROP TABLE regions;") }
                    }
                }
            }
    }

    companion object {
        private const val SIMPLE_BASELINE = "CREATE TABLE regions (id bigint PRIMARY KEY); CREATE TABLE accounts (id bigint PRIMARY KEY);"
        private const val COMPOSITE_BASELINE = "CREATE TABLE regions (id bigint, code bigint, PRIMARY KEY(id, code)); CREATE TABLE accounts (id bigint PRIMARY KEY);"
        private const val DEFAULTED_FK = "ALTER TABLE accounts ADD COLUMN region_id bigint DEFAULT 0 REFERENCES regions(id);"
        private val DOMAIN_BASELINE = """
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
            CREATE DOMAIN region_key AS bigint DEFAULT 0;
            ALTER EXTENSION pgcrypto ADD DOMAIN region_key;
            CREATE TABLE regions (id bigint PRIMARY KEY);
            CREATE TABLE accounts (id bigint PRIMARY KEY);
        """.trimIndent()
        private const val DOMAIN_FK = "ALTER TABLE accounts ADD COLUMN region_id region_key REFERENCES regions(id);"
        private const val DEFAULTED_REQUIRED_DOMAIN_COLUMN = "ALTER TABLE accounts ADD COLUMN region_id region_key NOT NULL;"
        private val REQUIRED_DOMAIN_BASELINE = """
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
            CREATE DOMAIN required_region_key AS bigint NOT NULL;
            ALTER EXTENSION pgcrypto ADD DOMAIN required_region_key;
            CREATE TABLE regions (id bigint PRIMARY KEY);
            CREATE TABLE accounts (id bigint PRIMARY KEY);
        """.trimIndent()
        private const val REQUIRED_DOMAIN_COLUMN = "ALTER TABLE accounts ADD COLUMN region_id required_region_key;"
        private val PARTIAL_NULL_FULL_FK = """
            ALTER TABLE accounts ADD COLUMN region_id bigint DEFAULT 0, ADD COLUMN region_code bigint;
            ALTER TABLE accounts ADD CONSTRAINT account_region
                FOREIGN KEY (region_id, region_code) REFERENCES regions(id, code) MATCH FULL;
        """.trimIndent()
    }
}
