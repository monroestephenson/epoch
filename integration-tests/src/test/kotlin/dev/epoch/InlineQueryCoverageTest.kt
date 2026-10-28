package dev.epoch

import dev.epoch.core.AccessPathStatus
import dev.epoch.core.ColumnReference
import dev.epoch.core.DatabaseApiRole
import dev.epoch.core.Verdict
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.inlinefixture.InlineAccount
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class InlineQueryCoverageTest {
    @TempDir lateinit var directory: Path

    @Test fun `ORDER BY and RETURNING dependencies invalidate only their affected native plans`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(InlineAccount::class.java.name), discoverRepositories = false,
            nativeQueries = mapOf("native.order" to "select id from public.inline_accounts order by legacy_note",
                "native.returning" to "update public.inline_accounts set status = ? returning legacy_note"),
            nativeQueryParameterTypes = mapOf("native.returning" to listOf("text"))))
        val affected = contract.queries.filter { it.id.startsWith("native.") }
        assertEquals(2, affected.size)
        assertTrue(affected.all { it.dependenciesComplete && ColumnReference("public.inline_accounts", "legacy_note") in it.columns }, affected.toString())
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE inline_accounts (id bigint PRIMARY KEY, status varchar(255), legacy_note text);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__drop_order_and_returning_column.sql").writeText("ALTER TABLE inline_accounts DROP COLUMN legacy_note;")
        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertEquals(Verdict.SAFE, report.baseline?.verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertEquals(2, report.migrations.single().replannedQueries)
        for (id in affected.map { it.id }) assertTrue(report.migrations.single().findings.any {
            it.ruleId == "POSTGRES_SQL_REJECTED" && id in it.affectedContractIds
        }, report.toString())
    }

    @Test fun `compiled Kotlin query sources have complete dependencies and inline-only rename breaks real SQL`() {
        val classes = Path.of(InlineAccount::class.java.protectionDomain.codeSource.location.toURI())
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(InlineAccount::class.java.name),
            classpath = listOf(classes.toString()),
            repositoryPackages = listOf("dev.epoch.inlinefixture"),
            applicationVersion = "inline-query-fixture",
        ))
        val fixedMethods = mapOf("inlineHql" to "JPA_HQL", "inlineNative" to "JPA_NATIVE",
            "annotationDeclared" to "JPA_NAMED", "multiline" to "JPA_HQL", "marginLiteral" to "JPA_HQL",
            "defaultMarginLiteral" to "JPA_HQL", "shorthand" to "PANACHE")
        val inventory = requireNotNull(contract.apiInventory)
        val verifiedIds = fixedMethods.flatMap { (method, mechanism) ->
            val paths = contract.accessPaths.filter { it.method.startsWith("$method(") }
            val path = paths.single { it.mechanism == mechanism }
            assertEquals(if (mechanism == "JPA_NAMED") AccessPathStatus.DECLARED else AccessPathStatus.STATIC,
                path.status, path.toString())
            assertTrue(path.queryIds.isNotEmpty(), path.toString())
            assertTrue(paths.all { requireNotNull(it.line) > 0 && it.queryIds == path.queryIds }, paths.toString())
            if (mechanism == "PANACHE") {
                assertEquals(1, paths.size, paths.toString())
            } else {
                // One fixed producer plus its execution; scalar bindings belong to the API inventory.
                assertEquals(2, paths.size, paths.toString())
                val terminal = paths.single { it.mechanism == "QUERY_TERMINAL" }
                assertEquals(AccessPathStatus.STATIC, terminal.status, terminal.toString())
                val binding = inventory.invocations.single {
                    it.method.startsWith("$method(") && it.role == DatabaseApiRole.BINDING
                }
                assertEquals("setParameter", binding.name, binding.toString())
                assertFalse(binding.queryCapable, binding.toString())
                assertTrue(binding.accessPathIds.isEmpty(), binding.toString())
                assertNull(binding.unresolvedReason, binding.toString())
            }
            path.queryIds.forEach { id ->
                val query = contract.queries.single { it.id == id }
                assertTrue(query.dependenciesComplete, query.toString())
                assertEquals(listOf("public.inline_accounts"), query.tables, query.toString())
            }
            path.queryIds
        }.toSet()
        val inlineNative = contract.queries.single { it.id.contains("#inlineNative(") }
        assertTrue(ColumnReference("public.inline_accounts", "legacy_note") in inlineNative.columns)
        assertFalse(contract.entities.flatMap { it.columns }.any { it.name == "legacy_note" })
        val dynamicPaths = contract.accessPaths.filter { it.method.startsWith("dynamic(") }
        val dynamic = dynamicPaths.single { it.mechanism == "JPA_HQL" }
        assertEquals(setOf("JPA_HQL", "QUERY_TERMINAL"), dynamicPaths.map { it.mechanism }.toSet())
        assertEquals(2, dynamicPaths.size, dynamicPaths.toString())
        assertTrue(dynamicPaths.all { it.status == AccessPathStatus.UNRESOLVED && it.queryIds.isEmpty() },
            dynamicPaths.toString())
        assertEquals("dev.epoch.inlinefixture.InlineAccountDao", dynamic.className)
        assertTrue(requireNotNull(dynamic.line) > 0, dynamic.toString())
        assertTrue(contract.unresolvedQueries.any { it.id == dynamic.id }, contract.toString())
        assertFalse(contract.coverage.complete, "The unresolved dynamic query must keep the inventory incomplete")

        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(
            "CREATE TABLE inline_accounts (id bigint PRIMARY KEY, status varchar(255), legacy_note text);"
        )
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__rename_inline_only_column.sql").writeText(
            "ALTER TABLE inline_accounts RENAME COLUMN legacy_note TO renamed_note;"
        )
        val report = PostgresVerifier().verify(contract, baseline, pending)
        val invalidFixedQueries = report.baseline?.findings.orEmpty().filter { finding ->
            (finding.ruleId == "BASELINE_SQL_UNVERIFIED" || finding.ruleId.startsWith("INVALID_BASELINE_")) &&
                finding.affectedContractIds.any { it in verifiedIds }
        }
        assertTrue(invalidFixedQueries.isEmpty(), invalidFixedQueries.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { finding ->
            finding.ruleId == "POSTGRES_SQL_REJECTED" && finding.verdict == Verdict.BREAKING &&
                inlineNative.id in finding.affectedContractIds && "42703" in finding.evidence
        }, report.toString())
    }
}
