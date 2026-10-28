package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import dev.epoch.core.ColumnReference
import dev.epoch.core.Operation
import dev.epoch.hibernate.queryisolationfixture.QueryIsolationRecord
import dev.epoch.hibernate.queryisolationfixture.QueryIsolationRepository
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryTranslationIsolationTest {
    private fun runtimes(): List<Pair<String, List<String>>> {
        val fixtures = Path.of(QueryIsolationRecord::class.java.protectionDomain.codeSource.location.toURI()).toString()
        return listOf("6.6" to listOf(fixtures), "7.4.9" to
            (System.getProperty("epoch.hibernate749.classpath").split(File.pathSeparator) + fixtures))
    }

    @TestFactory fun `selection aliases and positions do not recurse through the SQL AST walker`() = runtimes().map { (version, classpath) ->
        DynamicTest.dynamicTest("selection references with Hibernate $version") {
            val definitions = sortedMapOf(
                "aliasOrder" to "select r.label as caption from QueryIsolationRecord r order by caption",
                "aliasGroup" to "select r.label as caption from QueryIsolationRecord r group by caption order by caption",
                "ordinalOrder" to "select r.label from QueryIsolationRecord r order by 1",
            )
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(QueryIsolationRecord::class.java.name), classpath = classpath,
                hqlQueries = definitions, discoverRepositories = false, keepGoing = true,
            ))
            definitions.keys.forEach { id ->
                val query = contract.queries.single { it.id == id }
                assertEquals(Operation.SELECT, query.operation)
                assertTrue(query.dependenciesComplete, "$id: ${contract.unresolvedQueries}")
                assertEquals(listOf("public.query_isolation_records"), query.tables)
                assertTrue(ColumnReference("public.query_isolation_records", "label") in query.columns, query.toString())
                assertFalse(contract.unresolvedQueries.any { it.id == id }, contract.unresolvedQueries.toString())
            }
            assertTrue(contract.coverage.complete, contract.unresolvedQueries.toString())
        }
    }

    @TestFactory fun `one malformed repository query preserves healthy SQL and the failed call site`() = runtimes().map { (version, classpath) ->
        DynamicTest.dynamicTest("query-local failure with Hibernate $version") {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(QueryIsolationRecord::class.java.name), classpath = classpath,
                repositoryPackages = listOf(QueryIsolationRepository::class.java.packageName),
                keepGoing = true,
            ))
            val failedPaths = contract.accessPaths.filter { it.method.startsWith("malformed(") }
            val healthyPaths = contract.accessPaths.filter { it.method.startsWith("healthy(") }
            val failed = failedPaths.single { it.mechanism == "JPA_HQL" }
            val healthy = healthyPaths.single { it.mechanism == "JPA_HQL" }
            assertTrue(failedPaths.all { it.status == AccessPathStatus.UNRESOLVED }, failedPaths.toString())
            assertTrue(healthyPaths.all { it.status == AccessPathStatus.STATIC && it.queryIds == healthy.queryIds }, healthyPaths.toString())
            assertEquals(AccessPathStatus.UNRESOLVED, failed.status, failed.toString())
            assertEquals(QueryIsolationRepository::class.java.name, failed.className)
            assertTrue(failed.line != null && failed.line!! > 0, failed.toString())
            assertTrue(contract.unresolvedQueries.any { (it.id == failed.id || failed.id in it.affectedIds) && it.reason.contains("labelThatDoesNotExist") },
                contract.unresolvedQueries.toString())
            assertEquals(AccessPathStatus.STATIC, healthy.status, healthy.toString())
            assertTrue(healthy.queryIds.all { id -> contract.queries.any { it.id == id && it.dependenciesComplete } }, contract.toString())
            assertTrue(contract.queries.any { it.operation == Operation.INSERT }, contract.toString())
            assertFalse(contract.coverage.complete)
            assertTrue(failed.queryIds.none { id -> contract.queries.any { it.id == id } }, contract.toString())
        }
    }
}
