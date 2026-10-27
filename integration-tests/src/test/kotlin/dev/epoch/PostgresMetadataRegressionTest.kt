package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Exercises PostgreSQL's catalog ownership and actual prepared-statement type inference. */
class PostgresMetadataRegressionTest {
    @TempDir lateinit var directory: Path

    private fun verify(baselineSql: String, migration: String, contract: DatabaseContract): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__change.sql").writeText(migration)
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    private fun readContract(vararg queries: QueryContract) = DatabaseContract(
        applicationVersion = "metadata-regression", queries = queries.toList(),
        coverage = Coverage(complete = true, mechanisms = listOf("Explicit fixed SQL test contract")),
    )

    private fun select(id: String, sql: String, types: List<String>, columns: List<String>) = QueryContract(
        id, Operation.SELECT, sql, tables = listOf("public.typed"),
        columns = columns.map { ColumnReference("public.typed", it) }, parameterTypes = types,
        dependenciesComplete = true,
    )

    @Test fun `extension owned functions do not blanket mark ordinary schema changes unknown`() {
        val report = verify(
            "CREATE EXTENSION pgcrypto; CREATE EXTENSION pg_trgm; CREATE TABLE typed (id bigint PRIMARY KEY);",
            "ALTER TABLE typed ADD COLUMN metadata text;",
            readContract(select("typed.find", "SELECT id FROM typed WHERE id = ?", listOf("bigint"), listOf("id"))),
        )
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }, report.toString())
    }

    @Test fun `user function calling extension remains a custom behavior`() {
        val report = verify(
            """
            CREATE EXTENSION pgcrypto;
            CREATE TABLE typed (id bigint PRIMARY KEY);
            CREATE FUNCTION public.user_hash(value text) RETURNS bytea LANGUAGE sql IMMUTABLE
              AS 'SELECT public.digest(value, ''sha256'')';
            """.trimIndent(),
            "ALTER TABLE typed ADD COLUMN metadata text;",
            readContract(select("typed.find", "SELECT id FROM typed WHERE id = ?", listOf("bigint"), listOf("id"))),
        )
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.baseline?.findings.orEmpty().any {
            it.ruleId == "EXISTING_DATABASE_BEHAVIOR" && "user_hash" in it.evidence && it.migration == null
        }, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }, report.toString())
        assertEquals(Verdict.SAFE, report.migrations.single().verdict, report.toString())
    }

    @Test fun `JDBC double tinyint and OTHER UUID JSON types resolve against PostgreSQL`() {
        val report = verify(
            "CREATE TABLE typed (id bigint PRIMARY KEY, score double precision, rank smallint, token uuid, payload jsonb);",
            "ALTER TABLE typed ADD COLUMN metadata text;",
            readContract(select("typed.find", "SELECT id FROM typed WHERE score = ? AND rank = ? AND token = ? AND payload = ?",
                listOf("DOUBLE", "TINYINT", "OTHER", "OTHER"), listOf("id", "score", "rank", "token", "payload"))),
        )
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
    }

    @Test fun `OTHER custom enum type is inferred from its actual column`() {
        val report = verify(
            "CREATE TYPE public.state AS ENUM ('ready', 'done'); CREATE TABLE typed (id bigint PRIMARY KEY, state public.state);",
            "CREATE INDEX typed_id_lookup ON typed(id);",
            readContract(select("typed.find", "SELECT id FROM typed WHERE state = ?", listOf("OTHER"), listOf("id", "state"))),
        )
        // Index semantics are conservatively UNKNOWN; SQL must still validate with the custom enum type.
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
        assertTrue(report.baseline?.findings.orEmpty().none { it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
    }

    @Test fun `OTHER parameter type is frozen to old UUID after target becomes text`() {
        val report = verify(
            "CREATE TABLE typed (id bigint PRIMARY KEY, token uuid);",
            "ALTER TABLE typed ALTER COLUMN token TYPE text USING token::text;",
            readContract(select("typed.find", "SELECT id FROM typed WHERE token = ?", listOf("OTHER"), listOf("id", "token"))),
        )
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "POSTGRES_SQL_REJECTED" && it.verdict == Verdict.BREAKING && "42883" in it.evidence
        }, report.toString())
    }

    @Test fun `unconstrained OTHER parameter stays unknown rather than using PostgreSQL text default`() {
        val query = QueryContract("untyped", Operation.SELECT, "SELECT ?", parameterTypes = listOf("OTHER"), dependenciesComplete = true)
        val report = verify("CREATE TABLE typed(id bigint);", "ALTER TABLE typed ADD COLUMN metadata text;", readContract(query))
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.baseline?.findings.orEmpty().any {
            it.ruleId == "BASELINE_SQL_UNVERIFIED" && "ambiguous" in it.evidence && it.migration == null
        }, report.toString())
    }
}
