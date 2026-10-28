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

class CatalogObserverRegressionTest {
    @Test @Timeout(120)
    fun `system relation reads observe application DDL without traversing system implementation objects`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.execute("""
                        CREATE TABLE ordinary(id bigint PRIMARY KEY);
                        CREATE TABLE rename_me(id bigint PRIMARY KEY);
                        CREATE VIEW catalog_names AS SELECT relname FROM pg_catalog.pg_class;
                    """.trimIndent())
                    val observer = QueryContract("names.read", Operation.SELECT, "SELECT relname FROM public.catalog_names",
                        listOf("public.catalog_names"), listOf(ColumnReference("public.catalog_names", "relname")), dependenciesComplete = true)
                    val ordinary = QueryContract("ordinary.read", Operation.SELECT, "SELECT id FROM public.ordinary",
                        listOf("public.ordinary"), listOf(ColumnReference("public.ordinary", "id")), dependenciesComplete = true)
                    val contract = DatabaseContract(queries = listOf(observer, ordinary), coverage = Coverage(complete = true))
                    val catalog = PostgresCatalog()
                    val before = catalog.inspect(connection)
                    val scope = BehaviorScope(before).forQuery(observer, BehaviorQuerySyntax().inspect(observer))
                    assertTrue(scope.references.any { it.name == "pg_catalog.pg_class" }, scope.toString())
                    assertTrue(scope.uncertainties.any { "Observed system catalog relation" in it.reason }, scope.toString())
                    assertTrue(connection.hasName("rename_me"))
                    connection.execute("ALTER TABLE rename_me RENAME TO was_renamed;")
                    assertFalse(connection.hasName("rename_me"))
                    assertTrue(connection.hasName("was_renamed"))
                    val report = CompatibilityEngine().verifyStep(contract, before, catalog.inspect(connection),
                        MigrationInfo("2", "V2__rename.sql"))
                    val causes = report.findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
                    assertTrue(causes.isNotEmpty(), report.toString())
                    assertEquals(setOf("names.read"), causes.flatMap { it.affectedContractIds }.toSet())
                    assertTrue(before.behaviorGraph!!.nodes.values.none { it.kind == "pg_language" })
                }
            }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.hasName(name: String): Boolean = prepareStatement("SELECT 1 FROM catalog_names WHERE relname=?").use {
        it.setString(1, name)
        it.executeQuery().use { rows -> rows.next() }
    }
}
