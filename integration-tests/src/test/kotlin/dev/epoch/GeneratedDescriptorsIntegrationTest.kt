package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.cli.runCli
import dev.epoch.core.CompatibilityReport
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Verdict
import dev.epoch.descriptorfixture.auditunit.GeneratedAuditEntity
import dev.epoch.descriptorfixture.mainunit.GeneratedMainEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Public CLI chain; this can also be installed before the command to verify the missing feature. */
class GeneratedDescriptorsIntegrationTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(240)
    fun `generated two-unit descriptors extract and verify together with Hibernate 6 and actual 7_4_9`() {
        val fixtures = Path.of(GeneratedMainEntity::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val actual7 = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        assertTrue(actual7.any { it.endsWith("hibernate-core-7.4.9.Final.jar") })
        for ((runtimeVersion, classpath) in listOf("6.6.13.Final" to listOf(fixtures), "7.4.9.Final" to (actual7 + fixtures))) {
            val run = directory.resolve(runtimeVersion).createDirectories()
            val properties = run.resolve("application.properties").also { it.writeText("""
                quarkus.hibernate-orm.enabled=true
                quarkus.hibernate-orm.packages=${GeneratedMainEntity::class.java.packageName}
                quarkus.hibernate-orm.database.default-schema=primary_schema
                quarkus.hibernate-orm."audit".packages=${GeneratedAuditEntity::class.java.packageName}
                quarkus.hibernate-orm."audit".database.default-schema=audit_schema
            """.trimIndent()) }
            val descriptors = run.resolve("descriptors")
            val generationArgs = listOf("descriptors", "--quarkus-properties", properties.toString(),
                "--application-version", "generated-$runtimeVersion", "--output-directory", descriptors.toString()) +
                classpath.flatMap { listOf("--classpath", it) }
            assertEquals(0, runCli(generationArgs.toTypedArray()), "Generate descriptors with $runtimeVersion")
            val manifest = json.readTree(descriptors.resolve("manifest.json").toFile())
            val units = manifest.path("units").map { it.path("name").asText() to it.path("descriptor").asText() }
            assertEquals(listOf("audit", "default"), units.map { it.first })
            assertTrue(manifest.path("units").all { it.path("configurationComplete").asBoolean() })
            val contracts = units.map { (unit, descriptor) ->
                val target = run.resolve("$unit-contract.json")
                assertEquals(0, runCli(arrayOf("extract", "--descriptor", descriptors.resolve(descriptor).toString(),
                    "--output", target.toString())), "Extract $unit with $runtimeVersion")
                val contract: DatabaseContract = json.readValue(target.toFile())
                val expectedEntity = if (unit == "audit") GeneratedAuditEntity::class.java.name else GeneratedMainEntity::class.java.name
                val expectedTable = if (unit == "audit") "audit_schema.generated_audit" else "primary_schema.generated_main"
                assertEquals(listOf(expectedEntity), contract.entities.map { it.id })
                assertEquals(listOf(expectedTable), contract.entities.map { it.table })
                assertTrue(contract.queries.isNotEmpty())
                assertTrue(contract.coverage.notes.any { "Hibernate runtime $runtimeVersion" in it }, contract.coverage.notes.toString())
                target
            }
            val baseline = run.resolve("baseline").createDirectories()
            baseline.resolve("V1__both_units.sql").writeText("""
                CREATE SCHEMA primary_schema;
                CREATE SCHEMA audit_schema;
                CREATE TABLE primary_schema.generated_main (id bigint PRIMARY KEY, label varchar(255));
                CREATE TABLE audit_schema.generated_audit (id bigint PRIMARY KEY, action varchar(255));
            """.trimIndent())
            val pending = run.resolve("pending").createDirectories()
            pending.resolve("V2__audit_break.sql").writeText("ALTER TABLE audit_schema.generated_audit DROP COLUMN action;")
            val output = run.resolve("report.json")
            val verifyArgs = listOf("verify", "--baseline", baseline.toString(), "--migrations", pending.toString(),
                "--report", output.toString()) + contracts.flatMap { listOf("--contract", it.toString()) }
            assertEquals(1, runCli(verifyArgs.toTypedArray()), "A concrete break in the audit unit must fail the shared verification")
            val report: CompatibilityReport = json.readValue(output.toFile())
            assertTrue(report.complete)
            assertEquals(Verdict.BREAKING, report.verdict)
            assertEquals(1, report.migrations.size)
            assertFalse(requireNotNull(report.baseline).findings.any { it.ruleId.startsWith("INVALID_BASELINE_") }, report.toString())
            val dropped = report.migrations.single().findings.filter { it.ruleId == "REFERENCED_COLUMN_DROPPED" }
            assertTrue(dropped.isNotEmpty(), report.toString())
            assertTrue(dropped.all { it.objectName == "audit_schema.generated_audit.action" }, dropped.toString())
            val affected = dropped.flatMap { it.affectedContractIds }
            assertTrue(affected.isNotEmpty())
            assertTrue(affected.all { it.startsWith("contract[1]/") }, affected.toString())
            assertTrue(affected.any { GeneratedAuditEntity::class.java.name in it }, affected.toString())
        }
    }
}
