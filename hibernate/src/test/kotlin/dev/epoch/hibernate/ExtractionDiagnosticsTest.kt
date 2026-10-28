package dev.epoch.hibernate

import dev.epoch.core.QueryContract
import dev.epoch.core.UnresolvedQuery
import dev.epoch.core.AccessPathStatus
import dev.epoch.hibernate.diagnosticvariantfixture.VariantDiagnosticsRepository
import dev.epoch.hibernate.queryisolationfixture.QueryIsolationRecord
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

class ExtractionDiagnosticsTest {
    @Test fun `query failures share one complete source inventory and preserve unrelated healthy SQL`() {
        val diagnostics = ExtractionDiagnostics()
        val unresolved = mutableListOf<UnresolvedQuery>()
        val queries = mutableListOf<QueryContract>()
        repeat(200) { index ->
            QueryExtraction.record("query.$index", "HQL translation", queries, unresolved, diagnostics) {
                throw IllegalStateException("same wrapper", IllegalArgumentException("same root cause"))
            }
        }
        val group = diagnostics.unresolved().single()
        assertEquals((0 until 200).map { "query.$it" }.sorted(), group.affectedIds)
        assertTrue(group.reason.contains("IllegalStateException: same wrapper; caused by IllegalArgumentException: same root cause"))
        assertTrue(unresolved.isEmpty(), "Repeated evidence should be materialized once, not retained in 200 rows")
        QueryExtraction.record("healthy", "HQL translation", queries, unresolved, diagnostics) {
            ExtractedSql(QueryContract("healthy", dev.epoch.core.Operation.SELECT, "select 1"))
        }
        assertEquals(listOf("healthy"), queries.map { it.id })
    }

    @Test fun `grouping uses full normalized cause content rather than clipped display or exception class`() {
        val diagnostics = ExtractionDiagnostics()
        val prefix = "x".repeat(600)
        diagnostics.capture("one", "phase", IllegalArgumentException(prefix + "one"))
        diagnostics.capture("two", "phase", IllegalArgumentException(prefix + "two"))
        diagnostics.capture("three", "another phase", IllegalArgumentException(prefix + "one"))
        assertEquals(3, diagnostics.unresolved().size)
        assertEquals(setOf("one", "two", "three"), diagnostics.unresolved().map { it.id }.toSet())
    }

    @Test fun `grouping never recovers cancellation or resource failures`() {
        val diagnostics = ExtractionDiagnostics()
        assertFailsWith<CancellationException> { diagnostics.capture("cancelled", "phase", CancellationException("stop")) }
        assertFailsWith<OutOfMemoryError> { diagnostics.capture("memory", "phase", OutOfMemoryError("stop")) }
        assertTrue(diagnostics.unresolved().isEmpty())
    }

    @Test fun `partially translated finite paths link to the shared failure without duplicating it`() {
        val root = Path.of(VariantDiagnosticsRepository::class.java.protectionDomain.codeSource.location.toURI())
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(QueryIsolationRecord::class.java.name), classpath = listOf(root.toString()),
            repositoryPackages = listOf(VariantDiagnosticsRepository::class.java.packageName)))
        val paths = contract.accessPaths
        assertEquals(4, paths.size, contract.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED && it.queryIds.size == 2 }, paths.toString())
        val group = contract.unresolvedQueries.single()
        assertTrue(group.affectedIds.containsAll(paths.map { it.id }), group.toString())
        assertEquals(6, group.affectedIds.size, "Two failed variants and their four factory/execution paths share one cause")
        assertTrue(paths.all { it.reason == "Extraction diagnostic ${group.id}" }, paths.toString())
        assertEquals(2, contract.queries.count { query -> paths.any { query.id in it.queryIds } })
        assertFalse(contract.coverage.complete)
    }
}
