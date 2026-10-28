package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.renderReport
import dev.epoch.core.*
import dev.epoch.postgres.PostgresCatalog
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/** Real catalog attachment graphs, without removing unsupported behavior or its evidence. */
class FindingGroupPostgresTest {
    private val json = jacksonObjectMapper()

    @Test @Timeout(120)
    fun `many real CHECK attachments share an exact function display group while retaining each local limitation`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    val checks = (0 until 128).joinToString(",") { "CONSTRAINT guarded_$it CHECK (public.check_guard(id))" }
                    connection.createStatement().use { it.execute("""
                        CREATE FUNCTION public.check_guard(value bigint) RETURNS boolean LANGUAGE plpgsql IMMUTABLE
                          AS 'BEGIN RETURN value >= 0; END';
                        CREATE TABLE public.accounts(id bigint, $checks);
                    """.trimIndent()) }
                    val snapshot = PostgresCatalog().inspect(connection)
                    val query = QueryContract("Account.insert", Operation.INSERT, "INSERT INTO public.accounts(id) VALUES (?)",
                        tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                        writeColumns = listOf(ColumnReference("public.accounts", "id")), targetTable = "public.accounts",
                        parameterTypes = listOf("bigint"), dependenciesComplete = true)
                    val contract = DatabaseContract(queries = listOf(query), coverage = Coverage(complete = true))
                    val engine = CompatibilityEngine()
                    val health = engine.inspectBaseline(contract, snapshot)
                    assertEquals(Verdict.UNKNOWN, health.verdict)
                    val findings = health.findings.filter { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }
                    assertTrue(findings.size >= 128, "Each CHECK's local semantic limitation remains in the raw report")
                    val tree = json.valueToTree<JsonNode>(health)
                    assertTrue(tree.path("findingGroups").isArray, tree.toPrettyString())
                    val groups = tree["findingGroups"].filter { it["ruleId"].asText() == "EXISTING_DATABASE_BEHAVIOR" &&
                        it["sourceIds"].any { source -> source.asText() == "function:public.check_guard(value bigint)" } }
                    assertEquals(1, groups.size, tree.toPrettyString())
                    val members = groups.single()["findingIndices"].map { health.findings[it.asInt()] }
                    assertTrue(members.count { it.objectName.startsWith("check:public.accounts.guarded_") } >= 128,
                        members.joinToString("\n"))
                    assertTrue(members.all { it.evidence.isNotBlank() && it.migration == null })
                    assertEquals(health.fingerprint, BaselineFingerprints.baseline(contract, snapshot, health.findings, ""))
                    val report = engine.report(contract, emptyList(), health)
                    val text = renderReport(report)
                    assertEquals(1, Regex("Shared catalog dependency:").findAll(text).count(), text)
                    assertTrue(text.contains("all member evidence retained in JSON"), text)
                    val details = renderReport(report, allDetails = true)
                    assertTrue(details.contains("check:public.accounts.guarded_0:"), details)
                    assertTrue(details.contains("check:public.accounts.guarded_127:"), details)
                    assertEquals(Verdict.UNKNOWN, report.verdict)
                }
            }
    }
}
