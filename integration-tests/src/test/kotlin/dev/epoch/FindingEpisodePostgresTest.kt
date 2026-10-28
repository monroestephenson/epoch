package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.runCli
import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FindingEpisodePostgresTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()
    private val contract = DatabaseContract(applicationVersion = "episodes-postgres", queries = listOf(
        QueryContract("Account.read", Operation.SELECT, "SELECT email FROM accounts WHERE id = ?",
            tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id"),
                ColumnReference("public.accounts", "email")), parameterTypes = listOf("bigint"), dependenciesComplete = true)),
        coverage = Coverage(complete = true))

    private fun migrations(failAfterThird: Boolean = false): Pair<Path, Path> {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__base.sql").writeText("CREATE TABLE accounts(id bigint PRIMARY KEY, email text); CREATE TABLE audit_log(id bigint PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__drop.sql").writeText("ALTER TABLE accounts DROP COLUMN email;")
        pending.resolve("V3__unrelated.sql").writeText("ALTER TABLE audit_log ADD COLUMN note text;")
        if (failAfterThird) pending.resolve("V4__invalid.sql").writeText("THIS IS NOT VALID SQL;")
        else {
            pending.resolve("V4__restore.sql").writeText("ALTER TABLE accounts ADD COLUMN email text;")
            pending.resolve("V5__drop_again.sql").writeText("ALTER TABLE accounts DROP COLUMN email;")
        }
        return baseline to pending
    }

    private fun transitions(step: JsonNode): List<JsonNode> {
        assertTrue(step.path("findingChanges").isArray, step.toPrettyString())
        return step["findingChanges"].toList()
    }

    @Test @Timeout(120)
    fun `real PostgreSQL drop unrelated restore and recurrence keep independent origins and prefix checkpoints`() {
        val (baseline, pending) = migrations()
        val checkpoints = mutableListOf<CompatibilityReport>()
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 30, globalTimeoutSeconds = 90, progress = { direction, value ->
                assertEquals("old-on-new", direction)
                checkpoints += value
            }))
        assertEquals(Verdict.SAFE, requireNotNull(report.baseline).verdict, report.toString())
        assertEquals(listOf(Verdict.BREAKING, Verdict.BREAKING, Verdict.SAFE, Verdict.BREAKING), report.migrations.map { it.verdict })
        assertEquals(Verdict.BREAKING, report.verdict)
        val steps = json.valueToTree<JsonNode>(report)["migrations"]
        for ((index, kind, origin) in listOf(Triple(0, "NEW", "2"), Triple(1, "PERSISTING", "2"),
            Triple(2, "RESOLVED", "2"), Triple(3, "NEW", "5"))) {
            val changes = transitions(steps[index]).filter { it["verdict"].asText() == "BREAKING" }
            assertTrue(changes.isNotEmpty(), steps[index].toPrettyString())
            assertTrue(changes.all { it["kind"].asText() == kind && it["firstObserved"]["version"].asText() == origin }, steps[index].toPrettyString())
            assertTrue(changes.any { it["ruleId"].asText() == "POSTGRES_SQL_REJECTED" }, steps[index].toPrettyString())
        }
        assertTrue(report.migrations[1].findings.filter { it.verdict == Verdict.BREAKING }.all { it.migration?.version == "3" })
        assertEquals(listOf(0, 1, 2, 3, 4), checkpoints.map { it.migrations.size })
        for (checkpoint in checkpoints) {
            assertFalse(checkpoint.complete)
            val snapshot = json.valueToTree<JsonNode>(checkpoint)["migrations"]
            snapshot.forEachIndexed { index, step -> assertEquals(steps[index]["findingChanges"], step["findingChanges"]) }
        }
    }

    @Test @Timeout(120)
    fun `failure after a persisting step retains its active episode and incomplete replay exits 3`() {
        val (baseline, pending) = migrations(failAfterThird = true)
        val contractFile = directory.resolve("contract.json")
        json.writeValue(contractFile.toFile(), contract)
        val reportFile = directory.resolve("checkpoint.json")
        assertEquals(3, runCli(arrayOf("verify", "--contract", contractFile.toString(), "--baseline", baseline.toString(),
            "--migrations", pending.toString(), "--report", reportFile.toString(), "--migration-timeout-seconds", "30",
            "--global-timeout-seconds", "90", "--fail-on", "breaking")))
        val envelope = json.readTree(reportFile.toFile())
        assertFalse(envelope["complete"].asBoolean())
        assertEquals("MIGRATION_EXECUTION_FAILED", envelope["failureStage"].asText(), envelope.toPrettyString())
        val retained = envelope["reports"]["old-on-new"]
        assertEquals(2, retained["migrations"].size(), envelope.toPrettyString())
        val changes = transitions(retained["migrations"][1]).filter { it["verdict"].asText() == "BREAKING" }
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it["kind"].asText() == "PERSISTING" && it["firstObserved"]["version"].asText() == "2" }, retained.toPrettyString())
        assertEquals(3, runCli(arrayOf("report", "--input", reportFile.toString(), "--summary", "--fail-on", "breaking")))
    }
}
