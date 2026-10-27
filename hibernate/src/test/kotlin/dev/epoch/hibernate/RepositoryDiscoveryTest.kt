package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import dev.epoch.repositoryfixture.RepositoryCustomer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.jar.Attributes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import java.net.URLClassLoader
import kotlin.test.*

class RepositoryDiscoveryTest {
    @TempDir lateinit var directory: Path
    private val root get() = Path.of(RepositoryCustomer::class.java.protectionDomain.codeSource.location.toURI())
    private fun discover() = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.repositoryfixture"), javaClass.classLoader)

    @Test fun `constant arguments are discovered from frames without initializing DAO classes`() {
        val result = discover()
        assertTrue(result.hqlQueries.any { it.key.contains("#fixed(") && it.value.contains("c.email = :email") })
        assertTrue(result.nativeQueries.any { it.key.contains("#nativeColumn(") && it.value.contains("legacy_email") })
        assertTrue(result.nativeQueries.any { it.key.contains("#jdbc(") && it.value.startsWith("delete") })
        assertFalse(result.unresolved.any { it.id.contains("#jdbc(") }, result.toString())
        assertTrue(result.accessPaths.count { it.id.contains("#jdbc(") } >= 2)
        assertTrue(result.accessPaths.filter { it.id.contains("#fixed(") }.all { it.line != null && it.status == AccessPathStatus.STATIC })
    }

    @Test fun `equal and finite different constants survive merges but unused literals never replace dynamic arguments`() {
        val result = discover()
        assertTrue(result.hqlQueries.keys.any { it.contains("#sameBranches(") })
        val variants = result.hqlQueries.filterKeys { it.contains("#differentBranches(") }
        assertEquals(2, variants.size, result.toString())
        assertTrue(variants.keys.all { it.contains(".variant.") })
        assertEquals(variants.keys.sorted(), result.accessPaths.single { it.method.startsWith("differentBranches(") }.queryIds)
        assertFalse(result.unresolved.any { it.id.contains("#differentBranches(") })
        assertTrue(result.unresolved.any { it.id.contains("#dynamic(") })
        assertFalse(result.hqlQueries.keys.any { it.contains("#dynamic(") })
    }

    @Test fun `constant invokedynamic concatenation and boolean char conversions are resolved correctly`() {
        val result = discover()
        assertEquals("select c from RepositoryCustomer c", result.hqlQueries.entries.single { it.key.contains("#concatenation(") }.value)
        assertEquals("select true, 'A'", result.nativeQueries.entries.single { it.key.contains("#valueOf(") }.value)
    }

    @Test fun `one finite concat operand is enumerated but correlated operands cannot fabricate query combinations`() {
        val result = discover()
        assertEquals(setOf("select id from repository_customer", "select email from repository_customer"),
            result.nativeQueries.filterKeys { it.contains("#finiteSuffix(") }.values.toSet())
        assertTrue(result.nativeQueries.keys.none { it.contains("#correlatedFragments(") })
        assertTrue(result.unresolved.single { it.id.contains("#correlatedFragments(") }.reason.contains("Cartesian product"), result.toString())
    }

    @Test fun `builders procedure calls and query modifiers remain individually unresolved`() {
        val result = discover()
        for (method in listOf("criteria", "callable", "pagination", "externalPrepared")) {
            assertTrue(result.accessPaths.any { it.method.startsWith("$method(") && it.status == AccessPathStatus.UNRESOLVED }, method)
        }
        assertFalse(result.nativeQueries.keys.any { it.contains("#callable(") })
        assertTrue(result.accessPaths.any { it.method.startsWith("named(") && it.status == AccessPathStatus.DECLARED && it.queryIds == listOf("RepositoryCustomer.named") })
    }

    @Test fun `selected jar package is scanned and stable call site identifiers match directory scan`() {
        val jar = directory.resolve("application.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(root.resolve("dev/epoch/repositoryfixture")).use { files ->
                files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.sorted().forEach { path ->
                    output.putNextEntry(JarEntry(root.relativize(path).toString().replace('\\', '/')))
                    Files.copy(path, output)
                    output.closeEntry()
                }
            }
        }
        val result = RepositoryDiscovery.discover(listOf(jar.toString()), listOf("dev.epoch.repositoryfixture"), javaClass.classLoader)
        assertEquals(discover().hqlQueries, result.hqlQueries)
        assertEquals(discover().nativeQueries, result.nativeQueries)
        val unscoped = RepositoryDiscovery.discover(listOf(jar.toString()), emptyList(), javaClass.classLoader)
        assertTrue(unscoped.unresolved.any { it.id == "repository-discovery:scope" })
    }

    @Test fun `multi release archive discovery reads the bytecode selected by this JVM`() {
        val name = "dev/epoch/repositoryfixture/CustomerDao.class"
        val original = Files.readAllBytes(root.resolve(name))
        val node = ClassNode()
        ClassReader(original).accept(node, 0)
        node.methods.flatMap { it.instructions.toArray().toList() }.filterIsInstance<LdcInsnNode>()
            .filter { (it.cst as? String)?.contains("legacy_email") == true }
            .forEach { it.cst = (it.cst as String).replace("legacy_email", "runtime_version_column") }
        val writer = ClassWriter(0)
        node.accept(writer)
        val jar = directory.resolve("multi-release.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Multi-Release", "true")
        }
        JarOutputStream(Files.newOutputStream(jar), manifest).use { out ->
            out.putNextEntry(JarEntry(name)); out.write(original); out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/versions/17/$name")); out.write(writer.toByteArray()); out.closeEntry()
        }
        val result = RepositoryDiscovery.discover(listOf(jar.toString()), listOf("dev.epoch.repositoryfixture"), javaClass.classLoader)
        assertTrue(result.nativeQueries.values.any { it.contains("runtime_version_column") }, result.toString())
        assertFalse(result.nativeQueries.values.any { it.contains("legacy_email") }, result.toString())
    }

    @Test fun `extractor includes discovered queries and preserves per method unresolved coverage`() {
        val result = HibernateExtractor().extract(ExtractionRequest(entityClasses = listOf(RepositoryCustomer::class.java.name),
            classpath = listOf(root.toString()), repositoryPackages = listOf("dev.epoch.repositoryfixture")))
        assertTrue(result.queries.any { it.id.contains("#fixed(") && it.sql.contains("repository_customer") }, result.toString())
        assertTrue(result.queries.any { it.id.contains("#nativeColumn(") && it.sql.contains("legacy_email") && !it.sql.contains("?1") }, result.toString())
        assertTrue(result.accessPaths.any { it.id.contains("#dynamic(") && it.status == AccessPathStatus.UNRESOLVED })
        assertFalse(result.coverage.complete)
    }

    @Test fun `Spring annotations and supported PartTree grammar are discovered through actual Spring APIs`() {
        val result = discover()
        assertTrue(result.hqlQueries.any { it.key.contains("#explicit(") && it.value.contains("c.email = :email") })
        assertTrue(result.nativeQueries.any { it.key.contains("#legacy(") && it.value.contains("legacy_email") })
        assertTrue(result.hqlQueries.any { it.key.contains("#findByEmailAndId(") && it.value.contains("e.email = ?1") && it.value.contains("e.id = ?2") }, result.toString())
        assertTrue(result.hqlQueries.keys.any { it.contains("#findById(") }, result.toString())
        assertFalse(result.unresolved.any { it.id.contains("#findById(") }, result.toString())
        assertTrue(result.hqlQueries.any { it.key.endsWith(":countQuery") && it.value.contains("count(c)") })
    }

    @Test fun `Spring null-dependent equality is finitely expanded while projections pagination and entity graphs stay unresolved`() {
        val result = discover()
        val variants = result.hqlQueries.filterKeys { it.contains("#findByEmailAndId(") }.values
        assertEquals(4, variants.size, result.toString())
        assertTrue(variants.any { it.contains("e.email is null") && it.contains("e.id = ?1") }, variants.toString())
        assertTrue(variants.any { it.contains("e.email is null") && it.contains("e.id is null") }, variants.toString())
        assertFalse(result.unresolved.any { it.id.contains("#findByEmailAndId(") }, result.toString())
        assertEquals(4, result.accessPaths.single { it.id.contains("#findByEmailAndId(") }.queryIds.size)
        val negative = result.hqlQueries.filterKeys { it.contains("#findByEmailNot(") }.values
        assertEquals(2, negative.size)
        assertTrue(negative.any { it.contains("e.email is not null") })
        assertTrue(result.unresolved.any { it.id.contains("#findByEmailAndEmailAndEmailAndEmailAndEmail(") && it.reason.contains("16-variant bound") })
        assertTrue(result.unresolved.any { it.id.contains("#findByEmail(") && it.id.endsWith(":projection") }, result.toString())
        assertTrue(result.unresolved.any { it.id.contains("#findByEmailContaining(") && it.reason.contains("Unsupported Spring derived predicate") }, result.toString())
        assertTrue(result.unresolved.any { it.id.contains("#paged(") && it.id.endsWith(":variants") }, result.toString())
        assertTrue(result.unresolved.any { it.id.contains("#graph(") && it.id.endsWith(":variants") }, result.toString())
    }

    @Test fun `real NativeQuery annotation is retained but custom query rewriters never become verified base SQL`() {
        val result = discover()
        assertTrue(result.nativeQueries.any { it.key.contains("#modernNative(") && it.value.contains("modern_native_email") }, result.toString())
        assertTrue(result.nativeQueries.any { it.key.contains("#modernNative(") && it.key.endsWith(":countQuery") }, result.toString())
        for (method in listOf("rewritten", "nativeRewritten")) {
            assertTrue(result.unresolved.any { it.id.contains("#$method(") && it.reason.contains("QueryRewriter") }, result.toString())
            assertFalse((result.hqlQueries.keys + result.nativeQueries.keys).any { it.contains("#$method(") })
        }
    }

    @Test fun `named JPQL and native queries take precedence over otherwise valid derived method names`() {
        val result = discover()
        for (method in listOf("findByEmailIsNotNull", "findByEmailIsNull", "findByEmailNotNull")) {
            assertFalse(result.hqlQueries.keys.any { it.contains("#$method(") }, result.toString())
            val path = result.accessPaths.single { it.method.startsWith("$method(") }
            assertEquals(AccessPathStatus.DECLARED, path.status)
            assertEquals(listOf("RepositoryCustomer.$method"), path.queryIds)
        }
        val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = listOf(RepositoryCustomer::class.java.name),
            classpath = listOf(root.toString()), repositoryPackages = listOf("dev.epoch.repositoryfixture")))
        assertTrue(contract.queries.any { it.id == "RepositoryCustomer.findByEmailIsNull" && it.sql.contains("legacy_named_email") })
        assertFalse(contract.queries.any { it.id.contains("#findByEmailIsNull(") })
    }

    @Test fun `composed query annotations outside scan packages cannot fall through to method derivation`() {
        val result = discover()
        assertTrue(result.unresolved.any { it.id.contains("#findByEmailEquals(") && it.reason.contains("composed") }, result.toString())
        assertFalse(result.hqlQueries.keys.any { it.contains("#findByEmailEquals(") })
    }

    @Test fun `external named query property resource blocks fabricated derived alternative`() {
        val properties = directory.resolve("META-INF/jpa-named-queries.properties")
        Files.createDirectories(properties.parent)
        Files.writeString(properties, "RepositoryCustomer.findById=select c from RepositoryCustomer c where c.id > 900\n")
        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val result = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.repositoryfixture"), loader)
            assertTrue(result.unresolved.any { it.id.contains("#findById(") && it.reason.contains("jpa-named-queries.properties") }, result.toString())
            assertFalse(result.hqlQueries.keys.any { it.contains("#findById(") })
        }
    }
}
