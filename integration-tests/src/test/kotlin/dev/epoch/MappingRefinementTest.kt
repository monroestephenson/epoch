package dev.epoch

import dev.epoch.core.*
import dev.epoch.fixtures.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class MappingRefinementTest {
    @TempDir lateinit var directory: Path

    private fun extract(vararg entities: Class<*>) = HibernateExtractor().extract(ExtractionRequest(
        discoverRepositories = false,
        entityClasses = entities.map { it.name }, applicationVersion = "physical-mapping-regressions",
    ))

    private fun verify(contract: DatabaseContract, baselineSql: String, change: String): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__change.sql").writeText(change)
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    @Test fun `JOINED inheritance attributes parent columns to their actual physical table`() {
        val contract = extract(JoinedPerson::class.java, JoinedEmployee::class.java)
        assertFalse(contract.queries.flatMap { it.columns }.any {
            it.table == "public.employees" && it.column == "display_name"
        }, contract.toString())
        val report = verify(contract, """
            CREATE TABLE people (id bigint PRIMARY KEY, display_name varchar(255));
            CREATE TABLE employees (id bigint PRIMARY KEY REFERENCES people(id), department varchar(255));
        """.trimIndent(), "ALTER TABLE employees ADD COLUMN metadata text;")
        assertTrue(report.baseline?.findings.orEmpty().none { it.ruleId == "INVALID_BASELINE_COLUMN" }, report.toString())
        assertNotEquals(Verdict.BREAKING, report.verdict, report.toString())
    }

    @Test fun `secondary table dependencies retain actual alias and key columns`() {
        val contract = extract(SecondaryAccount::class.java)
        assertFalse(contract.queries.flatMap { it.columns }.any {
            it.table == "public.accounts" && it.column == "biography"
        }, contract.toString())
        val report = verify(contract, """
            CREATE TABLE accounts (id bigint PRIMARY KEY, display_name varchar(255));
            CREATE TABLE account_profiles (account_id bigint PRIMARY KEY REFERENCES accounts(id), biography varchar(255));
        """.trimIndent(), "ALTER TABLE accounts ADD COLUMN metadata text;")
        assertTrue(report.baseline?.findings.orEmpty().none { it.ruleId == "INVALID_BASELINE_COLUMN" }, report.toString())
        assertNotEquals(Verdict.BREAKING, report.verdict, report.toString())
    }

    @Test fun `element collection table removal breaks extracted collection CRUD`() {
        val contract = extract(TaggedAccount::class.java)
        assertTrue(contract.queries.any {
            it.targetTable == "public.account_tags" && it.operation == Operation.INSERT && it.dependenciesComplete
        }, contract.toString())
        val report = verify(contract, """
            CREATE TABLE accounts (id bigint PRIMARY KEY);
            CREATE TABLE account_tags (account_id bigint REFERENCES accounts(id), tag varchar(255) NOT NULL);
        """.trimIndent(), "DROP TABLE account_tags;")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.verdict == Verdict.BREAKING && it.affectedContractIds.any { id -> id.contains("tags") }
        }, report.toString())
    }

    @Test fun `secondary table key is explicitly supplied rather than dependent on its default`() {
        val contract = extract(SecondaryAccount::class.java)
        val key = contract.entities.single { it.table == "public.account_profiles" }.columns.single { it.name == "account_id" }
        assertTrue(key.insertable && !key.generated, contract.toString())
        val report = verify(contract, """
            CREATE TABLE accounts (id bigint PRIMARY KEY, display_name varchar(255));
            CREATE TABLE account_profiles (account_id bigint DEFAULT 0 PRIMARY KEY REFERENCES accounts(id), biography varchar(255));
        """.trimIndent(), "ALTER TABLE account_profiles ALTER COLUMN account_id DROP DEFAULT;")
        assertNotEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.none { it.ruleId == "REQUIRED_GENERATION_REMOVED" }, report.toString())
    }

    @Test fun `many-to-many join table rename breaks extracted collection CRUD`() {
        val contract = extract(Team::class.java, Member::class.java)
        assertTrue(contract.queries.any {
            it.targetTable == "public.team_members" && it.operation == Operation.INSERT && it.dependenciesComplete
        }, contract.toString())
        val report = verify(contract, """
            CREATE TABLE teams (id bigint PRIMARY KEY);
            CREATE TABLE members (id bigint PRIMARY KEY);
            CREATE TABLE team_members (team_id bigint REFERENCES teams(id), member_id bigint REFERENCES members(id));
        """.trimIndent(), "ALTER TABLE team_members RENAME TO organization_members;")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
    }

    @Test fun `identity identifier is an established catalog dependency for ordinary CRUD`() {
        val contract = extract(IdentityAccount::class.java)
        assertTrue(contract.coverage.complete, contract.toString())
        val report = verify(contract, """
            CREATE TABLE accounts (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label varchar(255));
        """.trimIndent(), "ALTER TABLE accounts ADD COLUMN metadata text;")
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }
}
