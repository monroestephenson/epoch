package dev.epoch

import dev.epoch.core.*
import dev.epoch.hibernate.EntityOverride
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class CteCompatibilityTest {
    @TempDir lateinit var directory: Path

    @Test fun `real extracted native CTE and nested aliases preserve baseline objects and exact SQL failures`() {
        val native = mapOf(
            "native.cte" to "with visible as (select a.id, a.legacy from public.cte_accounts a) select visible.legacy from visible where visible.id = ?",
            "native.derived" to "select x.legacy from (select a.legacy from public.cte_accounts a) x",
            "native.nested_alias" to "select a.id from public.cte_accounts a where exists " +
                "(select 1 from public.cte_badges a where a.account_id = ? and a.legacy is not null)",
        )
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(CteMappedAccount::class.java.name), discoverRepositories = false,
            entityOverrides = mapOf(CteMappedAccount::class.java.name to EntityOverride(operations = setOf(Operation.SELECT))),
            nativeQueries = native, nativeQueryParameterTypes = mapOf("native.cte" to listOf("BIGINT"), "native.nested_alias" to listOf("BIGINT")),
        ))
        val extracted = contract.queries.filter { it.id in native }
        assertEquals(3, extracted.size)
        assertTrue(extracted.single { it.id == "native.cte" }.dependenciesComplete)
        extracted.forEach { query ->
            assertTrue(query.tables.all { it in setOf("public.cte_accounts", "public.cte_badges") }, query.toString())
            assertFalse(query.columns.any { it.table in setOf("public.visible", "public.x", "public.a") }, query.toString())
        }
        val nested = extracted.single { it.id == "native.nested_alias" }
        assertTrue(ColumnReference("public.cte_badges", "legacy") in nested.columns)
        assertFalse(ColumnReference("public.cte_accounts", "legacy") in nested.columns)
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE cte_accounts (id bigint PRIMARY KEY, legacy text);
            CREATE TABLE cte_badges (id bigint PRIMARY KEY, account_id bigint, legacy text);
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__rename_account_legacy.sql").writeText("ALTER TABLE cte_accounts RENAME COLUMN legacy TO current_note;")
        pending.resolve("V3__rename_badge_legacy.sql").writeText("ALTER TABLE cte_badges RENAME COLUMN legacy TO current_note;")
        val report = PostgresVerifier().verify(contract, baseline, pending)
        val health = requireNotNull(report.baseline)
        assertTrue(health.findings.none { it.ruleId.startsWith("INVALID_BASELINE_") || it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
        // Dependency completeness is distinct from behavior-planning support: the current
        // BehaviorQuerySyntax deliberately leaves WITH/nested effects UNKNOWN.
        assertEquals(Verdict.UNKNOWN, health.verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        val first = report.migrations.single { it.migration.version == "2" }
        for (id in listOf("native.cte", "native.derived")) assertTrue(first.findings.any {
            it.ruleId == "POSTGRES_SQL_REJECTED" && id in it.affectedContractIds && "42703" in it.evidence
        }, "$id: $report")
        assertFalse(first.findings.any { it.verdict == Verdict.BREAKING && "native.nested_alias" in it.affectedContractIds }, report.toString())
        val second = report.migrations.single { it.migration.version == "3" }
        assertTrue(second.findings.any { it.ruleId == "POSTGRES_SQL_REJECTED" && "native.nested_alias" in it.affectedContractIds && "42703" in it.evidence }, report.toString())
    }
    @Test fun `relation column aliases prepare at baseline without invented missing columns`() {
        val native = mapOf(
            "native.alias_flat" to "select a.renamed_id from public.cte_accounts a(renamed_id)",
            "native.alias_cte" to "with visible as (select a.renamed_id from public.cte_accounts a(renamed_id)) select visible.renamed_id from visible",
            "native.alias_cte_reference" to "with visible as (select a.id from public.cte_accounts a) select v.renamed_id from visible v(renamed_id)",
        )
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(CteMappedAccount::class.java.name), discoverRepositories = false,
            entityOverrides = mapOf(CteMappedAccount::class.java.name to EntityOverride(operations = setOf(Operation.SELECT))),
            nativeQueries = native,
        ))
        val extracted = contract.queries.filter { it.id in native }
        assertEquals(native.size, extracted.size)
        val baseline = directory.resolve("alias-baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE cte_accounts (id bigint PRIMARY KEY, legacy text);")
        val pending = directory.resolve("alias-pending").createDirectories()
        pending.resolve("V2__unrelated_expansion.sql").writeText("ALTER TABLE cte_accounts ADD COLUMN note text;")
        pending.resolve("V3__drop_actual_relation.sql").writeText("DROP TABLE cte_accounts;")
        val report = PostgresVerifier().verify(contract, baseline, pending)
        val health = requireNotNull(report.baseline)
        assertTrue(health.findings.none { it.ruleId.startsWith("INVALID_BASELINE_") || it.ruleId == "BASELINE_SQL_UNVERIFIED" }, report.toString())
        assertEquals(contract.queries.size, health.plannedQueries, "Every extracted statement must actually be checked by PostgreSQL")
        assertTrue(extracted.all { !it.dependenciesComplete }, extracted.toString())
        assertTrue(extracted.all { query -> query.columns.none { it.column == "renamed_id" } }, extracted.toString())
        val expansion = report.migrations.single { it.migration.version == "2" }
        assertTrue(expansion.findings.none { it.verdict == Verdict.BREAKING }, expansion.toString())
        val drop = report.migrations.single { it.migration.version == "3" }
        for (id in native.keys) assertTrue(drop.findings.any { finding -> finding.ruleId == "POSTGRES_SQL_REJECTED" &&
            id in finding.affectedContractIds && "42P01" in finding.evidence }, "$id: $drop")
    }

}

@Entity
@Table(name = "cte_accounts")
class CteMappedAccount {
    @Id var id: Long? = null
}
