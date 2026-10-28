package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.runCli
import dev.epoch.datadifffixture.DataNewName
import dev.epoch.datadifffixture.DataOldName
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class DataCompatibilityCliTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()

    @Test @Timeout(300)
    fun `verify and verify-release fail CI for unreadable new data with both schema directions SAFE`() {
        val old = directory.resolve("old.json")
        val new = directory.resolve("new.json")
        for ((path, entity) in listOf(old to DataOldName::class.java, new to DataNewName::class.java)) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(entity.name), discoverRepositories = false))
            json.writeValue(path.toFile(), contract)
        }
        val baselineSql = "CREATE TABLE data_name (id bigint PRIMARY KEY, state varchar(255));"
        val pendingSql = "ALTER TABLE data_name ADD COLUMN note text;"
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__expand.sql").writeText(pendingSql)

        val repository = directory.resolve("fixture-repository").createDirectories()
        val migrations = repository.resolve("db/migration").createDirectories()
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", repository.toString()) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        }
        git("init", "--initial-branch=main")
        git("config", "user.name", "Epoch fixture")
        git("config", "user.email", "fixture@example.test")
        migrations.resolve("V1__baseline.sql").writeText(baselineSql)
        git("add", ".")
        git("commit", "-m", "baseline")
        migrations.resolve("V2__expand.sql").writeText(pendingSql)
        git("add", ".")
        git("commit", "-m", "expand")

        val commands = listOf(
            listOf("verify", "--baseline", baseline.toString(), "--migrations", pending.toString()),
            listOf("verify-release", "--repository", repository.toString(), "--base-ref", "HEAD~1",
                "--target-ref", "HEAD", "--migration-path", "db/migration"),
        )
        for (command in commands) {
            val reportPath = directory.resolve("${command.first()}.json")
            val sarifPath = directory.resolve("${command.first()}.sarif")
            val args = command + listOf("--contract", old.toString(), "--new-contract", new.toString(),
                "--report", reportPath.toString(), "--sarif", sarifPath.toString())
            assertEquals(1, runCli(args.toTypedArray()), command.toString())
            val tree = json.readTree(reportPath.toFile())
            val report = tree.get("verification") ?: tree
            assertEquals("SAFE", report["oldOnNew"]["verdict"].asText(), report.toPrettyString())
            assertEquals("SAFE", report["newOnOld"]["verdict"].asText(), report.toPrettyString())
            assertEquals("BREAKING", report["dataCompatibility"]["verdict"].asText(), report.toPrettyString())
            assertEquals("BREAKING", report["verdict"].asText(), report.toPrettyString())
            assertEquals(1, runCli(arrayOf("report", "--input", reportPath.toString(), "--summary")))
            val results = json.readTree(sarifPath.toFile())["runs"][0]["results"]
            val data = results.filter { it["ruleId"].asText() == "DATA_ENUM_VALUE_UNREADABLE" }
            assertTrue(data.isNotEmpty(), results.toPrettyString())
            assertTrue(data.all { !it.has("locations") && it["properties"]["locationPrecision"].asText() == "contract" })
        }
    }
}
