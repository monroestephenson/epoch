package dev.epoch

import dev.epoch.core.ColumnReference
import dev.epoch.core.Coverage
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import dev.epoch.core.Verdict
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Count actual PostgreSQL planning work instead of asserting machine-dependent timing. */
class VerificationScaleTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(600)
    fun `ten thousand statements over one hundred migrations only replan changed table dependencies`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val pending = directory.resolve("pending").createDirectories()
        val tables = (0 until 100).map { "scale_table_${it.toString().padStart(3, '0')}" }
        baseline.resolve("V1__baseline.sql").writeText(tables.joinToString("\n") {
            "CREATE TABLE $it (id bigint PRIMARY KEY, label varchar(255));"
        })
        val queries = tables.flatMap { table ->
            (0 until 100).map { ordinal ->
                QueryContract("$table.read.$ordinal", Operation.SELECT,
                    "SELECT label FROM $table /* distinct template $ordinal */",
                    tables = listOf("public.$table"),
                    columns = listOf(ColumnReference("public.$table", "label")), dependenciesComplete = true)
            }
        }
        tables.forEachIndexed { index, table ->
            pending.resolve("V${index + 2}__widen_${table}.sql").writeText(
                "ALTER TABLE $table ALTER COLUMN label TYPE varchar(256);"
            )
        }
        val contract = DatabaseContract(applicationVersion = "scale-fixture", queries = queries,
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL templates")))
        data class Checkpoint(val direction: String, val completedSteps: Int, val complete: Boolean)
        val checkpoints = mutableListOf<Checkpoint>()
        val report = PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(
            planningParallelism = 4, migrationTimeoutSeconds = 60, globalTimeoutSeconds = 540,
            progress = { direction, snapshot ->
                checkpoints += Checkpoint(direction, snapshot.migrations.size, snapshot.complete)
            },
        ))

        val health = requireNotNull(report.baseline)
        assertEquals(10_000, health.plannedQueries)
        assertEquals(0, health.reusedQueries)
        assertEquals(4, health.planningParallelism)
        assertEquals(100, report.migrations.size)
        report.migrations.forEach { step ->
            assertEquals(100, step.replannedQueries, step.migration.filename)
            assertEquals(9_900, step.reusedQueries, step.migration.filename)
            assertEquals(1, step.planningParallelism,
                "Transactional DDL must be checked on its own connection before commit")
            assertEquals(Verdict.SAFE, step.verdict, step.findings.toString())
        }
        assertEquals(10_000, report.migrations.sumOf { requireNotNull(it.replannedQueries) },
            "The pending window must perform 10,000 checks, not steps × 10,000 checks")
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.complete)
        val partial = checkpoints.filterNot { it.complete }
        assertEquals((0..100).toList(), partial.map { it.completedSteps },
            "Baseline and every completed migration must produce an incomplete checkpoint")
        assertTrue(partial.all { it.direction == "old-on-new" }, checkpoints.toString())
    }

    @Test @Timeout(120)
    fun `pooled committed checks and serialized transactional checks observe the correct schema`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id bigint PRIMARY KEY, label varchar(255));")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__committed_widen.sql").writeText("ALTER TABLE accounts ALTER COLUMN label TYPE varchar(256);")
        pending.resolve("V2__committed_widen.sql.conf").writeText("executeInTransaction=false\n")
        pending.resolve("V3__transactional_rename.sql").writeText("ALTER TABLE accounts RENAME COLUMN label TO renamed_label;")
        val queries = (0 until 4).map { index ->
            QueryContract("Account.read.$index", Operation.SELECT, "SELECT label FROM accounts /* template $index */",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "label")),
                dependenciesComplete = true)
        }
        val contract = DatabaseContract(applicationVersion = "transaction-visibility", queries = queries,
            coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(planningParallelism = 4, migrationTimeoutSeconds = 15, globalTimeoutSeconds = 90))
        val committed = report.migrations[0]
        assertEquals(Verdict.SAFE, committed.verdict, committed.toString())
        assertEquals(4, committed.replannedQueries)
        assertEquals(4, committed.planningParallelism)
        val transactional = report.migrations[1]
        assertEquals(Verdict.BREAKING, transactional.verdict, transactional.toString())
        assertEquals(4, transactional.replannedQueries)
        assertEquals(1, transactional.planningParallelism)
        val rejected = transactional.findings.filter { it.ruleId == "POSTGRES_SQL_REJECTED" }
        assertEquals(queries.map { it.id }.toSet(), rejected.flatMap { it.affectedContractIds }.toSet())
        assertTrue(rejected.all { it.verdict == Verdict.BREAKING && "42703" in it.evidence }, rejected.toString())
    }
}
