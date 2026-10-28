package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

/** Measure actual planner work with realistic unrelated catalog behavior, without timing assertions. */
class BehaviorScopeScaleTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(600)
    fun `ten thousand queries ignore unused behavior and warm cache preserves scoped planning counts`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val pending = directory.resolve("pending").createDirectories()
        val tables = (0 until 100).map { "noise_table_${it.toString().padStart(3, '0')}" }
        baseline.resolve("V1__baseline.sql").writeText(buildString {
            tables.forEach { table ->
                appendLine("CREATE TABLE $table (id bigint PRIMARY KEY, label varchar(255), " +
                    "CONSTRAINT ${table}_positive CHECK (id >= 0), " +
                    "CONSTRAINT ${table}_bounded CHECK (id < 1000000000));")
            }
            (0 until 10).forEach { index ->
                appendLine("CREATE FUNCTION unused_sql_$index() RETURNS bigint LANGUAGE sql IMMUTABLE AS 'SELECT 1::bigint';")
                appendLine("CREATE FUNCTION unused_dynamic_$index() RETURNS bigint LANGUAGE plpgsql STABLE AS " +
                    "'DECLARE total bigint; BEGIN EXECUTE ''SELECT count(*) FROM ${tables[index]}'' INTO total; RETURN total; END';")
            }
            (0 until 20).forEach { index ->
                // Project only id so widening label is legal PostgreSQL DDL despite the unused view.
                appendLine("CREATE VIEW unused_view_$index AS SELECT id FROM ${tables[index]};")
            }
            appendLine("CREATE VIEW unused_nested AS SELECT id FROM unused_view_0;")
        })
        pending.resolve("V2__no_catalog_change.sql").writeText(
            "ALTER TABLE ${tables[99]} ADD COLUMN IF NOT EXISTS id bigint;"
        )
        pending.resolve("V3__widen.sql").writeText("ALTER TABLE ${tables[0]} ALTER COLUMN label TYPE varchar(256);")
        pending.resolve("V4__transparent_rename.sql").writeText("""
            ALTER TABLE ${tables[1]} RENAME TO ${tables[1]}_v2;
            CREATE VIEW ${tables[1]} AS SELECT * FROM ${tables[1]}_v2;
        """.trimIndent())
        pending.resolve("V5__nullable_expansion.sql").writeText("ALTER TABLE ${tables[2]} ADD COLUMN metadata text;")
        val queries = tables.flatMap { table -> (0 until 100).map { ordinal ->
            QueryContract("$table.read.$ordinal", Operation.SELECT,
                "SELECT label FROM $table /* distinct template $ordinal */",
                tables = listOf("public.$table"), columns = listOf(ColumnReference("public.$table", "label")),
                dependenciesComplete = true)
        } }
        val contract = DatabaseContract(applicationVersion = "behavior-scope-scale", queries = queries,
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL templates")))
        val options = VerificationOptions(baselineCache = directory.resolve("cache"), planningParallelism = 4,
            migrationTimeoutSeconds = 60, globalTimeoutSeconds = 270)
        fun verify(): CompatibilityReport = PostgresVerifier().verify(contract, baseline, pending, options).also { report ->
            assertTrue(report.complete)
            assertEquals(Verdict.SAFE, report.verdict, report.toString())
            assertEquals(Verdict.SAFE, requireNotNull(report.baseline).verdict, report.toString())
            assertEquals(listOf(0, 100, 100, 0), report.migrations.map { it.replannedQueries }, report.toString())
            assertEquals(listOf(10_000, 9_900, 9_900, 10_000), report.migrations.map { it.reusedQueries }, report.toString())
            assertTrue(report.migrations.all { it.verdict == Verdict.SAFE }, report.toString())
            val unchanged = report.migrations.first()
            assertEquals(false, jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(unchanged)
                .get("catalogChanged")?.booleanValue(), "A supported no-op must say that the inspected catalog did not change")
            assertTrue(unchanged.findings.none { it.verdict == Verdict.SAFE }, unchanged.toString())
            assertTrue(report.migrations[2].findings.any { it.ruleId == "TRANSPARENT_COMPATIBILITY_VIEW" &&
                it.objectName == "public.${tables[1]}" }, report.toString())
            assertTrue((requireNotNull(report.baseline).findings + report.migrations.flatMap { it.findings }).none {
                it.ruleId in setOf("EXISTING_DATABASE_BEHAVIOR", "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN",
                    "RELATION_RENAME_EXPRESSION_UNPROVEN")
            }, report.toString())
        }

        val coldReport = verify()
        val cold = requireNotNull(coldReport.baseline)
        assertEquals("MISS", cold.planCacheStatus, cold.toString())
        assertEquals(10_000, cold.plannedQueries)
        assertEquals(0, cold.reusedQueries)
        assertEquals(4, cold.planningParallelism)

        val warmReport = verify()
        val warm = requireNotNull(warmReport.baseline)
        assertEquals("HIT", warm.cacheStatus, warm.toString())
        assertEquals("HIT", warm.planCacheStatus, warm.toString())
        assertEquals(cold.cacheKey, warm.cacheKey)
        assertEquals(cold.planCacheKey, warm.planCacheKey)
        assertEquals(cold.fingerprint, warm.fingerprint)
        assertEquals(0, warm.plannedQueries)
        assertEquals(10_000, warm.reusedQueries)
        assertEquals(coldReport.migrations.map { it.findings }, warmReport.migrations.map { it.findings },
            "Fresh cluster OIDs and cache restoration must not change compatibility evidence")
    }
}
