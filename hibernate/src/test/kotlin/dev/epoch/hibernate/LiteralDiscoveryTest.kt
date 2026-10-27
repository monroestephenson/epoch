package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import dev.epoch.literalfixture.LiteralDao
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class LiteralDiscoveryTest {
    private fun discover() = RepositoryDiscovery.discover(
        listOf(Path.of(LiteralDao::class.java.protectionDomain.codeSource.location.toURI()).toString()),
        listOf("dev.epoch.literalfixture"), javaClass.classLoader,
    )

    @Test fun `Kotlin literal indentation and explicit or default margins normalize without executing application code`() {
        val result = discover()
        for (name in listOf("indentation", "defaultMargin", "customMargin")) {
            val query = result.hqlQueries.entries.single { it.key.contains("#$name(") }
            assertEquals("select c\nfrom RepositoryCustomer c", query.value, result.toString())
            val path = result.accessPaths.single { it.id == query.key }
            assertEquals(AccessPathStatus.STATIC, path.status)
            assertTrue(requireNotNull(path.line) > 0)
        }
    }

    @Test fun `finite native and prepared statement alternatives retain one stable located call site`() {
        val result = discover()
        for (method in listOf("finiteNative", "finitePrepared")) {
            val queries = result.nativeQueries.filterKeys { it.contains("#$method(") }
            assertEquals(2, queries.size, result.toString())
            val paths = result.accessPaths.filter { it.method.startsWith("$method(") }
            assertTrue(paths.isNotEmpty())
            assertTrue(paths.all { it.status == AccessPathStatus.STATIC && it.queryIds.toSet() == queries.keys && it.line != null }, result.toString())
        }
        assertEquals(result, discover(), "Enumeration and call-site identifiers must be deterministic")
    }

    @Test fun `sixteen alternatives are retained but excessive alternatives and dynamic templates remain unresolved`() {
        val result = discover()
        assertEquals(16, result.nativeQueries.keys.count { it.contains("#bounded(") }, result.toString())
        for (method in listOf("excessive", "dynamicTemplate")) {
            assertTrue(result.nativeQueries.keys.none { it.contains("#$method(") }, result.toString())
            val path = result.accessPaths.single { it.method.startsWith("$method(") }
            assertEquals(AccessPathStatus.UNRESOLVED, path.status)
            assertEquals(LiteralDao::class.java.name, path.className)
            assertTrue(requireNotNull(path.line) > 0)
        }
        assertTrue(result.unresolved.single { it.id.contains("#excessive(") }.reason.contains("16-variant"), result.toString())
    }
}
