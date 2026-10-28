package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.cli.renderReport
import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class NoOpCatalogTest {
    @TempDir lateinit var directory: Path

    @Test fun `unchanged intermediate catalog is explicit without adding compatibility findings`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__base.sql").writeText("CREATE TABLE accounts(id bigint PRIMARY KEY); CREATE INDEX account_lookup ON accounts(id);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__noop.sql").writeText("CREATE INDEX IF NOT EXISTS account_lookup ON accounts(id);")
        pending.resolve("V3__expand.sql").writeText("ALTER TABLE accounts ADD COLUMN note text;")
        pending.resolve("V4__noop.sql").writeText("CREATE INDEX IF NOT EXISTS account_lookup ON accounts(id);")
        val contract = DatabaseContract(applicationVersion = "noop", queries = listOf(
            QueryContract("read", Operation.SELECT, "SELECT id FROM accounts", tables = listOf("public.accounts"),
                columns = listOf(ColumnReference("public.accounts", "id")), dependenciesComplete = true)),
            coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, pending)
        val tree = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(report)
        for (index in listOf(0, 2)) {
            val step = tree["migrations"][index]
            assertTrue(step.path("catalogChanged").isBoolean, step.toPrettyString())
            assertFalse(step["catalogChanged"].asBoolean(), step.toPrettyString())
            assertEquals("SAFE", step["verdict"].asText(), step.toPrettyString())
            assertTrue(step["findings"].isEmpty, step.toPrettyString())
        }
        assertTrue(tree["migrations"][1]["catalogChanged"].asBoolean())
        assertTrue(renderReport(report).contains("No changes in the inspected catalog"), renderReport(report))
    }
}
