package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresCatalog
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

class DirectCatalogObserverIntegrationTest {
    @Test @Timeout(120)
    fun `direct and nested catalog readers observe user DDL despite unchanged system catalog definitions`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute("CREATE TABLE rename_me(id bigint); CREATE TABLE ordinary(id bigint);") }
                    val queries = listOf(
                        QueryContract("direct", Operation.SELECT, "SELECT relname FROM pg_catalog.pg_class",
                            listOf("pg_catalog.pg_class"), listOf(ColumnReference("pg_catalog.pg_class", "relname")), dependenciesComplete = true),
                        QueryContract("nested", Operation.SELECT,
                            "WITH names AS (SELECT relname FROM pg_catalog.pg_class) SELECT relname FROM names",
                            listOf("pg_catalog.pg_class"), listOf(ColumnReference("pg_catalog.pg_class", "relname")), dependenciesComplete = true),
                        QueryContract("information", Operation.SELECT, "SELECT table_name FROM information_schema.tables",
                            listOf("information_schema.tables"), listOf(ColumnReference("information_schema.tables", "table_name")), dependenciesComplete = true),
                    )
                    val regular = QueryContract("regular", Operation.SELECT, "SELECT id FROM public.ordinary",
                        listOf("public.ordinary"), listOf(ColumnReference("public.ordinary", "id")), dependenciesComplete = true)
                    val contract = DatabaseContract(queries = queries + regular, coverage = Coverage(complete = true))
                    val engine = CompatibilityEngine()
                    val before = PostgresCatalog().inspect(connection)
                    val health = engine.inspectBaseline(contract, before)
                    val acceptance = BaselineAcceptance.fromReport(engine.report(contract, emptyList(), health), "Known system catalog coverage debt")
                    val beforeRows = queries.associate { it.id to connection.names(it.sql) }
                    connection.createStatement().use { it.execute("ALTER TABLE rename_me RENAME TO renamed;") }
                    queries.forEach { query ->
                        val newRows = connection.names(query.sql)
                        assertTrue("rename_me" in beforeRows.getValue(query.id))
                        assertFalse("rename_me" in newRows)
                        assertTrue("renamed" in newRows)
                    }
                    val step = engine.verifyStep(contract, before, PostgresCatalog().inspect(connection),
                        MigrationInfo("2", "V2__rename.sql"), health.findings)
                    val report = engine.report(contract, listOf(step), health, acceptance)
                    assertEquals(Verdict.UNKNOWN, report.gateVerdict, report.toString())
                    val findings = report.migrations.single().findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
                    assertEquals(queries.map { it.id }.toSet(), findings.flatMap { it.affectedContractIds }.toSet())
                    assertTrue(findings.none { "regular" in it.affectedContractIds })
                }
            }
    }
    private fun Connection.names(sql: String): Set<String> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
    }
}
