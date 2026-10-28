package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresCatalog
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

class CompatibilityViewExpressionTest {
    @TempDir lateinit var directory: Path

    @Test fun `relation name observers in backing and unrelated stored expressions prevent SAFE`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(BASELINE)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__compatibility_view.sql").writeText(RENAME_AND_VIEW)
        val queries = TABLES.map { table ->
            val id = ColumnReference("public.$table", "id")
            QueryContract("$table.insert", Operation.INSERT, "INSERT INTO $table (id) VALUES (?)",
                tables = listOf("public.$table"), columns = listOf(id), writeColumns = listOf(id),
                parameterTypes = listOf("bigint"), dependenciesComplete = true, targetTable = "public.$table")
        }
        val contract = DatabaseContract(applicationVersion = "stored-relation-name-observers", queries = queries,
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit omitted-column INSERT contracts")))

        val report = PostgresVerifier().verify(contract, baseline, pending)
        val health = requireNotNull(report.baseline)
        // PREPARE succeeds at baseline, but these reached name-observing expressions are
        // explicitly opaque even before any rename. Baseline debt and the new interaction differ.
        assertEquals(Verdict.UNKNOWN, health.verdict, report.toString())
        val baselineObservers = health.findings.filter { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }
        assertEquals(mapOf(
            "default:public.accounts.label" to listOf("accounts.insert"),
            "default:public.logs.label" to listOf("logs.insert"),
            "check:public.checked_log.checked_log_sequence_visible" to listOf("checked_log.insert"),
        ), baselineObservers.associate { it.objectName to it.affectedContractIds }, report.toString())
        assertTrue(health.findings.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" ||
            it.ruleId.startsWith("INVALID_BASELINE_") }, report.toString())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        val observers = report.migrations.single().findings.filter { it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" }
        assertTrue(observers.isNotEmpty(), report.toString())
        val observedObjects = mapOf("accounts" to "public.accounts.label", "logs" to "public.logs.label",
            "checked_log" to "public.checked_log.checked_log_sequence_visible")
        observedObjects.forEach { (table, objectName) ->
            assertTrue(observers.any { it.objectName == objectName && "$table.insert" in it.affectedContractIds }, "$table: $report")
        }
    }

    @Test fun `PostgreSQL executes old inserts before rename and rejects name observing defaults and check after view`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute(BASELINE) }
                    connection.autoCommit = false
                    TABLES.forEach { table ->
                        connection.createStatement().use {
                            assertEquals(1, it.executeUpdate("INSERT INTO $table (id) VALUES (1)"))
                        }
                        connection.rollback()
                    }
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute(RENAME_AND_VIEW) }
                    connection.autoCommit = false
                    TABLES.forEach { table ->
                        try {
                            val failure = assertThrows(SQLException::class.java) {
                                connection.createStatement().use { it.executeUpdate("INSERT INTO $table (id) VALUES (1)") }
                            }
                            assertEquals(if (table == "checked_log") "23514" else "23502", failure.sqlState, table)
                        } finally {
                            connection.rollback()
                        }
                    }
                }
            }
    }

    @Test fun `a shadowed arithmetic operator cannot establish a transparent view proof`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("""
                            SET search_path TO public, pg_catalog;
                            CREATE TABLE accounts (id integer PRIMARY KEY);
                            CREATE FUNCTION public.relation_sensitive_add(integer, integer) RETURNS integer
                            LANGUAGE sql VOLATILE AS 'SELECT CASE WHEN
                                (SELECT relkind FROM pg_catalog.pg_class
                                 WHERE oid = pg_catalog.to_regclass(''public.accounts'')) = ''r''
                                THEN pg_catalog.int4pl($1, $2) ELSE NULL END';
                            CREATE OPERATOR public.+ (
                                FUNCTION = public.relation_sensitive_add, LEFTARG = integer, RIGHTARG = integer);
                            ALTER TABLE accounts ADD COLUMN marker integer NOT NULL DEFAULT (1 + 1);
                        """.trimIndent())
                    }
                    val catalog = PostgresCatalog()
                    val baseline = catalog.inspect(connection)
                    assertEquals(listOf("public", "pg_catalog"), baseline.expressionSearchPath)
                    // PostgreSQL deparses the visible custom operator as ordinary arithmetic.
                    assertEquals("(1 + 1)", baseline.tables.getValue("public.accounts").columns.getValue("marker").defaultExpression)
                    connection.autoCommit = false
                    connection.createStatement().use { statement ->
                        assertEquals(1, statement.executeUpdate("INSERT INTO accounts (id) VALUES (1)"))
                        statement.executeQuery("SELECT marker FROM accounts WHERE id = 1").use { result ->
                            assertTrue(result.next())
                            assertEquals(2, result.getInt(1))
                        }
                    }
                    connection.rollback()
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute(RENAME_AND_VIEW) }
                    val after = catalog.inspect(connection)
                    assertEquals(listOf("public", "pg_catalog"), after.expressionSearchPath)
                    assertTrue(after.tables.getValue("public.accounts").view!!.completeDirectProjection)
                    assertTrue(CompatibilityViews.provenViewNames(baseline, after).isEmpty())

                    val id = ColumnReference("public.accounts", "id")
                    val contract = DatabaseContract(queries = listOf(QueryContract(
                        "accounts.insert", Operation.INSERT, "INSERT INTO accounts (id) VALUES (?)",
                        tables = listOf("public.accounts"), columns = listOf(id), writeColumns = listOf(id),
                        parameterTypes = listOf("integer"), dependenciesComplete = true, targetTable = "public.accounts",
                    )), coverage = Coverage(complete = true))
                    val report = CompatibilityEngine().verifyStep(contract, baseline, after,
                        MigrationInfo("2", "V2__compatibility_view.sql"))
                    assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
                    assertTrue(report.findings.none { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" }, report.toString())

                    connection.createStatement().use { statement ->
                        statement.execute("PREPARE old_insert(integer) AS INSERT INTO accounts (id) VALUES ($1)")
                        statement.executeQuery("EXPLAIN EXECUTE old_insert(1)").use { assertTrue(it.next()) }
                        statement.execute("DEALLOCATE old_insert")
                    }
                    connection.autoCommit = false
                    try {
                        val failure = assertThrows(SQLException::class.java) {
                            connection.createStatement().use { it.executeUpdate("INSERT INTO accounts (id) VALUES (1)") }
                        }
                        assertEquals("23502", failure.sqlState)
                    } finally {
                        connection.rollback()
                    }
                }
            }
    }

    companion object {
        private val TABLES = listOf("accounts", "logs", "checked_log")
        private const val BASELINE = """
            CREATE TABLE accounts (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                label text NOT NULL DEFAULT pg_get_serial_sequence('accounts', 'id'));
            CREATE TABLE logs (id bigint PRIMARY KEY,
                label text NOT NULL DEFAULT pg_get_serial_sequence('accounts', 'id'));
            CREATE TABLE checked_log (id bigint PRIMARY KEY,
                CONSTRAINT checked_log_sequence_visible CHECK (pg_get_serial_sequence('accounts', 'id') IS NOT NULL));
        """
        private const val RENAME_AND_VIEW = """
            ALTER TABLE accounts RENAME TO accounts_v2;
            CREATE VIEW accounts AS SELECT * FROM accounts_v2;
        """
    }
}
