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
                        CREATE VIEW catalog_flags AS SELECT relisshared FROM pg_catalog.pg_class;
                        CREATE FUNCTION parsed_catalog_count() RETURNS bigint LANGUAGE sql STABLE
                          RETURN (SELECT count(*) FROM pg_catalog.pg_class);
                        CREATE TABLE function_consumer(id bigint, marker bigint DEFAULT parsed_catalog_count());
                    """.trimIndent())
                    val observer = QueryContract("names.read", Operation.SELECT, "SELECT relname FROM public.catalog_names",
                        listOf("public.catalog_names"), listOf(ColumnReference("public.catalog_names", "relname")), dependenciesComplete = true)
                    val ordinary = QueryContract("ordinary.read", Operation.SELECT, "SELECT id FROM public.ordinary",
                        listOf("public.ordinary"), listOf(ColumnReference("public.ordinary", "id")), dependenciesComplete = true)
                    val flags = QueryContract("flags.read", Operation.SELECT, "SELECT relisshared FROM public.catalog_flags",
                        listOf("public.catalog_flags"), listOf(ColumnReference("public.catalog_flags", "relisshared")), dependenciesComplete = true)
                    val contract = DatabaseContract(queries = listOf(observer, flags, ordinary), coverage = Coverage(complete = true))
                    val catalog = PostgresCatalog()
                    val before = catalog.inspect(connection)
                    val scope = BehaviorScope(before).forQuery(observer, BehaviorQuerySyntax().inspect(observer))
                    assertTrue(scope.references.any { it.name == "pg_catalog.pg_class" }, scope.toString())
                    assertTrue(scope.uncertainties.any { "Observed system catalog relation" in it.reason }, scope.toString())
                    val flagScope = BehaviorScope(before).forQuery(flags, BehaviorQuerySyntax().inspect(flags))
                    assertTrue(flagScope.references.any { it.name == "pg_catalog.pg_class" }, flagScope.toString())
                    assertTrue(flagScope.uncertainties.any { "Observed system catalog relation" in it.reason }, flagScope.toString())
                    assertTrue(flagScope.uncertainties.none { it.nodeId.startsWith("collation:") },
                        "A default-collation boolean projection must not rely on unrelated collation opacity")
                    val functionWrite = QueryContract("function.insert", Operation.INSERT, "INSERT INTO public.function_consumer(id) VALUES (?)",
                        listOf("public.function_consumer"), listOf(ColumnReference("public.function_consumer", "id")),
                        listOf(ColumnReference("public.function_consumer", "id")), parameterTypes = listOf("bigint"),
                        targetTable = "public.function_consumer", dependenciesComplete = true)
                    val functionScope = BehaviorScope(before).forQuery(functionWrite, BehaviorQuerySyntax().inspect(functionWrite))
                    assertTrue(functionScope.uncertainties.any { it.nodeId.startsWith("function:public.parsed_catalog_count(") },
                        "Missing pinned edges must never make a parsed function complete: $functionScope")
                    val beforeFlags = connection.flagCount()
                    assertTrue(connection.hasName("rename_me"))
                    connection.execute("ALTER TABLE rename_me RENAME TO was_renamed; CREATE TABLE added_relation(id bigint);")
                    assertFalse(connection.hasName("rename_me"))
                    assertTrue(connection.hasName("was_renamed"))
                    assertTrue(connection.flagCount() > beforeFlags, "The boolean catalog projection observes new relation rows")
                    val report = CompatibilityEngine().verifyStep(contract, before, catalog.inspect(connection),
                        MigrationInfo("2", "V2__rename.sql"))
                    val causes = report.findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
                    assertTrue(causes.isNotEmpty(), report.toString())
                    assertEquals(setOf("names.read", "flags.read"), causes.flatMap { it.affectedContractIds }.toSet())
                    assertTrue(before.behaviorGraph!!.nodes.values.none { it.kind == "pg_language" })
                }
            }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.flagCount(): Long = createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM catalog_flags").use { rows -> assertTrue(rows.next()); rows.getLong(1) }
    }
    private fun Connection.hasName(name: String): Boolean = prepareStatement("SELECT 1 FROM catalog_names WHERE relname=?").use {
        it.setString(1, name)
        it.executeQuery().use { rows -> rows.next() }
    }
}
