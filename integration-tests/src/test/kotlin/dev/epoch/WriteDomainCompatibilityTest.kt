package dev.epoch

import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.writedomainfixture.*
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Declared logical inputs are useful only when they reach SQL unchanged and the baseline accepts them. */
class WriteDomainCompatibilityTest {
    @TempDir lateinit var directory: Path

    private fun extract(vararg entities: Class<*>) = HibernateExtractor().extract(ExtractionRequest(
        discoverRepositories = false, entityClasses = entities.map { it.name }, applicationVersion = "write-domain-proof",
    ))

    private fun verify(contracts: List<DatabaseContract>, baselineSql: String, vararg changes: String): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        changes.forEachIndexed { index, sql -> pending.resolve("V${index + 2}__write_domain.sql").writeText(sql) }
        return PostgresVerifier().verify(contracts, baseline, pending)
    }

    private fun contract(vararg queries: QueryContract) = DatabaseContract(queries = queries.toList(),
        coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite write-domain fixture SQL")))

    private fun omittedInsert(table: String): QueryContract {
        val id = ColumnReference("public.$table", "id")
        return QueryContract("$table.insert", Operation.INSERT, "INSERT INTO $table (id) VALUES (?)",
            tables = listOf(id.table), columns = listOf(id), writeColumns = listOf(id),
            parameterTypes = listOf("bigint"), dependenciesComplete = true, targetTable = id.table)
    }

    private fun assertHealthyBaseline(report: CompatibilityReport) {
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
    }

    @Test fun `non-null mapped inputs and a validated baseline CHECK prove tightening safe across two contracts`() {
        val first = extract(DomainRequiredWrite::class.java)
        val second = extract(DomainRequiredCheck::class.java, DomainRedundantNull::class.java)
        assertEquals(true, first.entities.single().columns.single { it.name == "label" }.declaredNonNull)
        assertEquals(true, second.entities.single { it.table == "public.required_check" }
            .columns.single { it.name == "label" }.declaredNonNull)
        assertEquals(false, second.entities.single { it.table == "public.redundant_null" }
            .columns.single { it.name == "label" }.declaredNonNull)
        val report = verify(listOf(first, second), """
            CREATE TABLE required_write (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE required_check (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE redundant_null (id bigint PRIMARY KEY, label varchar(255),
                CONSTRAINT already_nonnull CHECK (label IS NOT NULL));
        """.trimIndent(), """
            ALTER TABLE required_write ALTER COLUMN label SET NOT NULL;
            ALTER TABLE required_check ADD CONSTRAINT required_label CHECK (label IS NOT NULL);
            ALTER TABLE redundant_null ALTER COLUMN label SET NOT NULL;
        """.trimIndent())
        assertHealthyBaseline(report)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        val findings = report.migrations.single().findings
        for ((table, prefix) in listOf("required_write" to "contract[1]/", "required_check" to "contract[2]/")) {
            assertTrue(findings.any { it.verdict == Verdict.SAFE && it.objectName.startsWith("public.$table.") &&
                it.affectedContractIds.isNotEmpty() && it.affectedContractIds.all { id -> id.startsWith(prefix) } }, report.toString())
        }
    }

    @Test fun `nullable ORM writes and omitted NULL inserts identify each newly rejected domain`() {
        val mapped = extract(DomainNullableWrite::class.java, DomainNullableCheck::class.java)
        assertTrue(mapped.entities.all { it.columns.single { c -> c.name == "label" }.declaredNonNull == false })
        val omitted = omittedInsert("omitted_null")
        val input = mapped.copy(queries = mapped.queries + omitted)
        val report = verify(listOf(input), """
            CREATE TABLE nullable_write (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE nullable_check (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE omitted_null (id bigint PRIMARY KEY, label text);
        """.trimIndent(), """
            ALTER TABLE nullable_write ALTER COLUMN label SET NOT NULL;
            ALTER TABLE nullable_check ADD CONSTRAINT required_label CHECK (label IS NOT NULL);
            ALTER TABLE omitted_null ALTER COLUMN label SET NOT NULL;
        """.trimIndent())
        assertHealthyBaseline(report)
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        val findings = report.migrations.single().findings
        for (entity in mapped.entities) {
            assertTrue(findings.any { it.verdict == Verdict.BREAKING && it.objectName == "${entity.table}.label" &&
                entity.id in it.affectedContractIds }, report.toString())
        }
        assertTrue(findings.any { it.verdict == Verdict.BREAKING && it.objectName == "public.omitted_null.label" &&
            omitted.id in it.affectedContractIds }, report.toString())
    }

    @Test fun `nullable logical property with a coalescing write transformer cannot fabricate NULL rejection`() {
        val input = extract(DomainTransformedWrite::class.java)
        val report = verify(listOf(input), "CREATE TABLE transformed_write (id bigint PRIMARY KEY, label varchar(255));",
            "ALTER TABLE transformed_write ALTER COLUMN label SET NOT NULL;")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.none { it.verdict == Verdict.BREAKING }, report.toString())
        assertFalse(input.entities.single().columns.single { it.name == "label" }.inputDomainComplete, input.toString())
    }

    @Test fun `all-NULL optional secondary values do not prove a stored NULL write`() {
        val input = extract(DomainOptionalWrite::class.java)
        assertTrue(input.entities.single { it.table == "public.optional_primary" }
            .columns.single { it.name == "label" }.inputDomainComplete, input.toString())
        assertFalse(input.entities.single { it.table == "public.optional_detail" }
            .columns.single { it.name == "detail" }.inputDomainComplete, input.toString())
        val report = verify(listOf(input), """
            CREATE TABLE optional_primary (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE optional_detail (account_id bigint PRIMARY KEY REFERENCES optional_primary(id), detail varchar(255));
        """.trimIndent(), "ALTER TABLE optional_detail ALTER COLUMN detail SET NOT NULL;")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.none { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `actual generated enum writes satisfy the complete CHECK and reject an excluded label`() {
        val input = extract(DomainEnumWrite::class.java)
        assertEquals(listOf("CLOSED", "OPEN"), input.entities.single().columns.single { it.name == "label" }.enumValues)
        val report = verify(listOf(input), "CREATE TABLE enum_write (id bigint PRIMARY KEY, label varchar(255));", """
            ALTER TABLE enum_write ADD CONSTRAINT allowed_label CHECK (label IN ('OPEN', 'CLOSED'));
        """.trimIndent(), """
            ALTER TABLE enum_write DROP CONSTRAINT allowed_label;
            ALTER TABLE enum_write ADD CONSTRAINT allowed_label CHECK (label IN ('OPEN'));
        """.trimIndent())
        assertHealthyBaseline(report)
        assertEquals(Verdict.SAFE, report.migrations[0].verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.migrations[1].verdict, report.toString())
        assertTrue(report.migrations[1].findings.any { it.ruleId == "ENUM_VALUE_REJECTED" &&
            input.entities.single().id in it.affectedContractIds && "CLOSED" in it.evidence }, report.toString())
    }

    @Test fun `renamed PostgreSQL enum label rejects the actual old mapped value`() {
        val input = extract(DomainNativeEnumWrite::class.java)
        val report = verify(listOf(input), """
            CREATE TYPE domain_state AS ENUM ('OPEN', 'CLOSED');
            CREATE TABLE enum_native (id bigint PRIMARY KEY, label domain_state);
        """.trimIndent(), "ALTER TYPE domain_state RENAME VALUE 'CLOSED' TO 'RETIRED';")
        assertHealthyBaseline(report)
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "ENUM_VALUE_REJECTED" &&
            input.entities.single().id in it.affectedContractIds && "CLOSED" in it.evidence }, report.toString())
    }

    @Test fun `explicit unbounded Hibernate string domain is rejected by bounded varchar`() {
        val input = extract(DomainUnboundedWrite::class.java)
        val mapping = input.entities.single()
        assertTrue(mapping.columns.single { it.name == "label" }.lengthUnbounded, input.toString())
        val report = verify(listOf(input), "CREATE TABLE unbounded_text (id bigint PRIMARY KEY, label text);",
            "ALTER TABLE unbounded_text ALTER COLUMN label TYPE varchar(5);")
        assertHealthyBaseline(report)
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.ruleId == "MAPPED_TYPE_NARROWED" &&
            it.objectName == "public.unbounded_text.label" && mapping.id in it.affectedContractIds }, report.toString())
    }

    @Test fun `case-insensitive PostgreSQL collation prevents a fabricated enum CHECK rejection`() {
        val input = extract(DomainCaseEnumWrite::class.java)
        val report = verify(listOf(input), """
            CREATE COLLATION insensitive (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
            CREATE TABLE enum_case (id bigint PRIMARY KEY, label varchar(255) COLLATE insensitive);
        """.trimIndent(), "ALTER TABLE enum_case ADD CONSTRAINT allowed_label CHECK (label IN ('ACTIVE'));")
        val health = requireNotNull(report.baseline)
        assertEquals(Verdict.UNKNOWN, health.verdict, report.toString())
        assertTrue(health.findings.any { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }, report.toString())
        assertTrue(health.findings.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" || it.ruleId.startsWith("INVALID_BASELINE_") }, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.none { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `a dormant mapped column absent from baseline is reported without a crash or pending attribution`() {
        val input = extract(DomainRequiredWrite::class.java)
        val report = verify(listOf(input), "CREATE TABLE required_write (id bigint PRIMARY KEY);",
            "ALTER TABLE required_write ADD COLUMN unrelated_metadata text;")
        assertTrue(report.complete, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        val missing = report.baseline!!.findings.filter { it.ruleId == "INVALID_BASELINE_COLUMN" &&
            it.objectName == "public.required_write.label" }
        assertTrue(missing.isNotEmpty(), report.toString())
        assertTrue(missing.all { it.migration == null }, report.toString())
        assertTrue(report.migrations.single().findings.none { it.ruleId == "INVALID_BASELINE_COLUMN" }, report.toString())
    }

    @Test fun `removing a column default cannot invent failure when a domain still supplies it or baseline already rejects NULL`() {
        val report = verify(listOf(contract(omittedInsert("domain_default"), omittedInsert("null_default"))), DEFAULT_BOUNDARIES,
            "ALTER TABLE domain_default ALTER COLUMN region DROP DEFAULT; ALTER TABLE null_default ALTER COLUMN region DROP DEFAULT;")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.none { it.verdict == Verdict.BREAKING }, report.toString())
    }

    @Test fun `NOT VALID non-null CHECK does not prove safety for unrelated updates of existing NULL rows`() {
        val table = "public.invalid_check"
        val update = QueryContract("invalid_check.updateOther", Operation.UPDATE,
            "UPDATE invalid_check SET other = ? WHERE id = ?", tables = listOf(table),
            columns = listOf(ColumnReference(table, "id"), ColumnReference(table, "other")),
            writeColumns = listOf(ColumnReference(table, "other")), parameterTypes = listOf("text", "bigint"),
            dependenciesComplete = true, targetTable = table)
        val report = verify(listOf(contract(update)), """
            CREATE TABLE invalid_check (id bigint PRIMARY KEY, label text, other text);
            INSERT INTO invalid_check (id, label, other) VALUES (1, NULL, 'before');
        """.trimIndent(), "ALTER TABLE invalid_check ADD CONSTRAINT required_label CHECK (label IS NOT NULL) NOT VALID;")
        assertHealthyBaseline(report)
        assertNotEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.verdict != Verdict.SAFE &&
            update.id in it.affectedContractIds }, report.toString())
    }

    @Test fun `PostgreSQL executes the old Hibernate values before tightening and rejects them afterward`() {
        withDatabase { database, connection ->
            connection.createStatement().use { it.execute("""
                CREATE TABLE nullable_write (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE nullable_check (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE transformed_write (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE required_write (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE enum_write (id bigint PRIMARY KEY, label varchar(255));
                CREATE TYPE domain_state AS ENUM ('OPEN', 'CLOSED');
                CREATE TABLE enum_native (id bigint PRIMARY KEY, label domain_state);
                CREATE COLLATION insensitive (provider = icu, locale = 'und-u-ks-level2', deterministic = false);
                CREATE TABLE enum_case (id bigint PRIMARY KEY, label varchar(255) COLLATE insensitive);
                CREATE TABLE optional_primary (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE optional_detail (account_id bigint PRIMARY KEY REFERENCES optional_primary(id), detail varchar(255));
            """.trimIndent()) }
            val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
                "hibernate.connection.url" to database.jdbcUrl,
                "hibernate.connection.username" to database.username,
                "hibernate.connection.password" to database.password,
                "hibernate.hbm2ddl.auto" to "none",
                "jakarta.persistence.validation.mode" to "none",
            )).build()
            try {
                val sources = MetadataSources(registry)
                listOf(DomainNullableWrite::class.java, DomainNullableCheck::class.java,
                    DomainTransformedWrite::class.java, DomainRequiredWrite::class.java,
                    DomainEnumWrite::class.java, DomainNativeEnumWrite::class.java,
                    DomainCaseEnumWrite::class.java, DomainOptionalWrite::class.java).forEach(sources::addAnnotatedClass)
                sources.buildMetadata().buildSessionFactory().use { factory ->
                    val rejected = listOf(
                        Triple({ DomainNullableWrite() }, "ALTER TABLE nullable_write ALTER COLUMN label SET NOT NULL", "23502"),
                        Triple({ DomainNullableCheck() }, "ALTER TABLE nullable_check ADD CHECK (label IS NOT NULL)", "23514"),
                        Triple({ DomainEnumWrite() }, "ALTER TABLE enum_write ADD CHECK (label IN ('OPEN'))", "23514"),
                        Triple({ DomainNativeEnumWrite() }, "ALTER TYPE domain_state RENAME VALUE 'CLOSED' TO 'RETIRED'", "22P02"),
                    )
                    rejected.forEach { (entity, migration, state) ->
                        persistRolledBack(factory, entity())
                        connection.createStatement().use { it.execute(migration) }
                        val failure = assertThrows(RuntimeException::class.java) { persistRolledBack(factory, entity()) }
                        assertEquals(state, sqlState(failure), migration)
                    }
                    // The logical nullable value is transformed before PostgreSQL sees it.
                    persistRolledBack(factory, DomainTransformedWrite())
                    connection.createStatement().use { it.execute("ALTER TABLE transformed_write ALTER COLUMN label SET NOT NULL") }
                    persistRolledBack(factory, DomainTransformedWrite())
                    persistRolledBack(factory, DomainRequiredWrite())
                    connection.createStatement().use { it.execute("ALTER TABLE required_write ALTER COLUMN label SET NOT NULL") }
                    persistRolledBack(factory, DomainRequiredWrite())
                    // PostgreSQL's declared collation accepts the lower-case mapped value through this CHECK.
                    persistRolledBack(factory, DomainCaseEnumWrite())
                    connection.createStatement().use { it.execute("ALTER TABLE enum_case ADD CHECK (label IN ('ACTIVE'))") }
                    persistRolledBack(factory, DomainCaseEnumWrite())
                    persistOptionalWithoutDetail(factory)
                    connection.createStatement().use { it.execute("ALTER TABLE optional_detail ALTER COLUMN detail SET NOT NULL") }
                    persistOptionalWithoutDetail(factory)
                }
            } finally { StandardServiceRegistryBuilder.destroy(registry) }
        }
    }

    @Test fun `PostgreSQL rejects omitted NULLs and unchanged NULL rows and bounded text after accepting the old inputs`() {
        withDatabase { _, connection ->
            connection.createStatement().use { it.execute("""
                CREATE TABLE omitted_null (id bigint PRIMARY KEY, label text);
                CREATE TABLE invalid_check (id bigint PRIMARY KEY, label text, other text);
                INSERT INTO invalid_check (id, label, other) VALUES (1, NULL, 'before');
                CREATE TABLE unbounded_text (id bigint PRIMARY KEY, label text);
                CREATE TYPE nullable_pair AS (first_value integer, second_value integer);
                CREATE TABLE composite_not_null (id bigint PRIMARY KEY, payload nullable_pair NOT NULL);
            """.trimIndent()) }
            connection.createStatement().use { it.execute(DEFAULT_BOUNDARIES) }
            val scenarios = listOf(
                Triple("INSERT INTO omitted_null (id) VALUES (1)",
                    "ALTER TABLE omitted_null ALTER COLUMN label SET NOT NULL", "23502"),
                Triple("UPDATE invalid_check SET other = 'after' WHERE id = 1",
                    "ALTER TABLE invalid_check ADD CHECK (label IS NOT NULL) NOT VALID", "23514"),
                Triple("INSERT INTO unbounded_text (id, label) VALUES (1, '123456')",
                    "ALTER TABLE unbounded_text ALTER COLUMN label TYPE varchar(5)", "22001"),
                // Composite IS NOT NULL tests its fields; physical NOT NULL only tests the datum.
                Triple("INSERT INTO composite_not_null (id, payload) VALUES (1, ROW(NULL, 1))",
                    "ALTER TABLE composite_not_null ADD CHECK (payload IS NOT NULL)", "23514"),
            )
            scenarios.forEach { (oldSql, migration, state) ->
                connection.autoCommit = false
                connection.createStatement().use { assertEquals(1, it.executeUpdate(oldSql)) }
                connection.rollback()
                connection.autoCommit = true
                connection.createStatement().use { it.execute(migration) }
                connection.autoCommit = false
                try {
                    val failure = assertThrows(SQLException::class.java) {
                        connection.createStatement().use { it.executeUpdate(oldSql) }
                    }
                    assertEquals(state, failure.sqlState, oldSql)
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
            }
            for (expected in listOf("column", "domain")) {
                connection.autoCommit = false
                try {
                    connection.createStatement().use { statement ->
                        assertEquals(1, statement.executeUpdate("INSERT INTO domain_default (id) VALUES (1)"))
                        statement.executeQuery("SELECT region FROM domain_default WHERE id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(expected, result.getString(1))
                        }
                    }
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
                if (expected == "column") connection.createStatement().use {
                    it.execute("ALTER TABLE domain_default ALTER COLUMN region DROP DEFAULT")
                }
            }
            repeat(2) { attempt ->
                connection.autoCommit = false
                try {
                    val failure = assertThrows(SQLException::class.java) {
                        connection.createStatement().use { it.executeUpdate("INSERT INTO null_default (id) VALUES (1)") }
                    }
                    assertEquals("23502", failure.sqlState)
                } finally {
                    connection.rollback()
                    connection.autoCommit = true
                }
                if (attempt == 0) connection.createStatement().use {
                    it.execute("ALTER TABLE null_default ALTER COLUMN region DROP DEFAULT")
                }
            }
        }
    }

    private fun persistRolledBack(factory: SessionFactory, entity: Any) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                session.persist(entity)
                session.flush()
            } finally { transaction.rollback() }
        }
    }

    private fun sqlState(failure: Throwable): String? =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun persistOptionalWithoutDetail(factory: SessionFactory) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                session.persist(DomainOptionalWrite())
                session.flush()
                session.doWork { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT count(*) FROM optional_primary WHERE id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(1, result.getInt(1))
                        }
                        statement.executeQuery("SELECT count(*) FROM optional_detail WHERE account_id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(0, result.getInt(1))
                        }
                    }
                }
            } finally { transaction.rollback() }
        }
    }

    private fun withDatabase(block: (PostgreSQLContainer, Connection) -> Unit) {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection -> block(database, connection) }
            }
    }

    companion object {
        private val DEFAULT_BOUNDARIES = """
            CREATE EXTENSION IF NOT EXISTS pgcrypto;
            CREATE DOMAIN generation_domain AS text DEFAULT 'domain' NOT NULL;
            ALTER EXTENSION pgcrypto ADD DOMAIN generation_domain;
            CREATE TABLE domain_default (id bigint PRIMARY KEY, region generation_domain NOT NULL DEFAULT 'column');
            CREATE TABLE null_default (id bigint PRIMARY KEY, region text NOT NULL DEFAULT NULL);
        """.trimIndent()
    }
}
