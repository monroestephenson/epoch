package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class IdentityResultTypeTest {
    @TempDir lateinit var directory: Path

    @Test fun `new enum named serial cannot reinterpret an established JDBC integer result alias`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id serial PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        // The unrelated enum no longer invalidates every plan. A real index change on the
        // selected relation still forces Describe, exercising the frozen JDBC alias boundary.
        pending.resolve("V2__serial_enum.sql").writeText("""
            CREATE TYPE serial AS ENUM ('x');
            CREATE INDEX accounts_id_lookup ON accounts(id);
        """.trimIndent())
        val query = QueryContract("accounts.read", Operation.SELECT, "SELECT id FROM accounts",
            tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
            resultColumns = listOf(ResultColumn("id", "serial")), dependenciesComplete = true)
        val contract = DatabaseContract(applicationVersion = "jdbc-serial-alias", queries = listOf(query),
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit integer SELECT contract")))

        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertEquals(Verdict.SAFE, requireNotNull(report.baseline).verdict, report.toString())
        assertEquals(1, report.migrations.single().replannedQueries, "The regression must exercise a fresh result description")
        assertNotEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.none {
            it.ruleId == "POSTGRES_SQL_REJECTED" || it.ruleId == "RESULT_SIGNATURE_CHANGED"
        }, report.toString())
    }
}
