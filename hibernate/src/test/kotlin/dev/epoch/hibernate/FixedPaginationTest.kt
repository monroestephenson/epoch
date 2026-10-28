package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import dev.epoch.paginationfixture.PaginationDao
import dev.epoch.repositoryfixture.RepositoryCustomer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

/** All positive cases pass through actual supplied Hibernate SQL generation, including 7.4.9 isolation. */
class FixedPaginationTest {
    @TempDir lateinit var directory: Path
    private val root get() = Path.of(PaginationDao::class.java.protectionDomain.codeSource.location.toURI())

    @Test fun `constant local pagination becomes extracted API paths and preserves actual execution states`() {
        val discovery = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.paginationfixture"), javaClass.classLoader)
        for (name in listOf("fixed", "sequential", "offsetOnly")) {
            val paths = discovery.accessPaths.filter { it.method.startsWith("$name(") }
            assertTrue(paths.isNotEmpty(), discovery.toString())
            assertTrue(paths.all { it.status == AccessPathStatus.STATIC }, paths.toString())
        }
        assertEquals(1, discovery.hqlQueries.keys.count { it.contains("#fixed(") })
        assertEquals(2, discovery.hqlQueries.keys.count { it.contains("#sequential(") })
    }

    @Test fun `dynamic control flow native lock and escaped query variants stay located and unresolved`() {
        val discovery = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.paginationfixture"), javaClass.classLoader)
        for (name in listOf("dynamic", "conditional", "nativeQuery", "locked", "escaped")) {
            val paths = discovery.accessPaths.filter { it.method.startsWith("$name(") }
            assertTrue(paths.isNotEmpty(), name)
            assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
            assertFalse(discovery.hqlQueries.keys.any { it.contains("#$name(") }, name)
            assertFalse(discovery.nativeQueries.keys.any { it.contains("#$name(") }, name)
        }
    }

    @Test fun `Hibernate 6 and 7 generate limit offset SQL and typed binders from packaged repositories`() {
        val jar = fixtureJar()
        for (runtime in listOf(emptyList(), System.getProperty("epoch.hibernate749.classpath").split(File.pathSeparator))) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(RepositoryCustomer::class.java.name, CollectionRecord::class.java.name),
                classpath = runtime + jar.toString(), repositoryPackages = listOf("dev.epoch.paginationfixture")))
            val fixedPaths = contract.accessPaths.filter { it.method.startsWith("fixed(") }
            assertTrue(fixedPaths.isNotEmpty(), contract.toString())
            assertTrue(fixedPaths.all { it.status == AccessPathStatus.STATIC }, fixedPaths.toString())
            val fixed = contract.queries.single { it.id in fixedPaths.flatMap { path -> path.queryIds } }
            assertTrue(fixed.sql.lowercase().contains("offset ?"), fixed.sql)
            assertTrue(fixed.sql.lowercase().let { "fetch first ?" in it || "fetch next ?" in it || "limit ?" in it }, fixed.sql)
            assertEquals(listOf("VARCHAR", "INTEGER", "INTEGER"), fixed.parameterTypes)
            assertTrue(fixed.dependenciesComplete, fixed.toString())
            val sequential = contract.queries.filter { it.id.contains("#sequential(") }
            assertEquals(2, sequential.size, sequential.toString())
            assertEquals(setOf(0, 1), sequential.map { it.parameterTypes.size }.toSet())
            val fetch = contract.accessPaths.filter { it.method.startsWith("collectionFetch(") }
            assertTrue(fetch.isNotEmpty())
            assertTrue(fetch.all { it.status == AccessPathStatus.UNRESOLVED }, fetch.toString())
            assertTrue(contract.unresolvedQueries.any { (it.id.contains("#collectionFetch(") || it.affectedIds.any { id -> id.contains("#collectionFetch(") }) && it.reason.contains("collection fetch") }, contract.toString())
        }
    }

    @Test fun `zero maximum results never invents a SQL execution for automatic or declared pagination`() {
        val discovery = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.paginationfixture"), javaClass.classLoader)
        val paths = discovery.accessPaths.filter { it.method.startsWith("zeroResults(") }
        assertTrue(paths.isNotEmpty(), discovery.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
        assertTrue(paths.any { it.reason?.contains("maxResults=0") == true }, paths.toString())
        assertFalse(discovery.hqlQueries.keys.any { it.contains("#zeroResults(") }, discovery.hqlQueries.toString())
        val jar = fixtureJar()
        for (runtime in listOf(emptyList(), System.getProperty("epoch.hibernate749.classpath").split(File.pathSeparator))) {
            val contract = HibernateExtractor().extract(ExtractionRequest(
                entityClasses = listOf(RepositoryCustomer::class.java.name),
                classpath = runtime + jar.toString(), discoverRepositories = false,
                hqlQueries = mapOf("zero" to "select c from RepositoryCustomer c", "positive" to "select c from RepositoryCustomer c"),
                hqlQueryPagination = mapOf("zero" to QueryPagination(0, 0), "positive" to QueryPagination(0, 2))))
            assertFalse(contract.queries.any { it.id == "zero" }, contract.queries.toString())
            assertTrue(contract.unresolvedQueries.any { (it.id == "zero" || "zero" in it.affectedIds) && it.reason.contains("maxResults=0") }, contract.unresolvedQueries.toString())
            assertTrue(contract.queries.any { it.id == "positive" }, contract.queries.toString())
        }
    }

    private fun fixtureJar() = directory.resolve("pagination-app.jar").also { path ->
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            listOf(PaginationDao::class.java, RepositoryCustomer::class.java, CollectionRecord::class.java).forEach { type ->
                val entry = type.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(entry).apply { time = 0 })
                type.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
}
