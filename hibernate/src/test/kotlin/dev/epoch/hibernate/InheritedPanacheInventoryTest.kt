package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.AccessPathStatus
import dev.epoch.repositoryfixture.RepositoryCustomer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Public scanner/extractor APIs; independent JARs use the actual published Panache API/helper runtime. */
class InheritedPanacheInventoryTest {
    @TempDir lateinit var directory: Path
    private val repository = "io/quarkus/hibernate/orm/panache/PanacheRepository"
    private val query = "io/quarkus/hibernate/orm/panache/PanacheQuery"
    private val findDescriptor get() = "(Ljava/lang/String;[Ljava/lang/Object;)L$query;"
    private val entity = RepositoryCustomer::class.java.name.replace('.', '/')

    @Test fun `unselected generic base JAR resolves inherited find and links direct local finite terminals`() {
        val files = fixture()
        withLoader(files) { loader ->
            val result = RepositoryDiscovery.discover(framework() + files.map(Path::toString), listOf("selected"), loader)
            for (method in listOf("direct", "local")) {
                val paths = result.accessPaths.filter { it.method.startsWith("$method(") }
                assertEquals(2, paths.size, result.toString())
                assertTrue(paths.all { it.status == AccessPathStatus.STATIC }, paths.toString())
                assertEquals(paths[0].queryIds, paths[1].queryIds)
                assertTrue(result.hqlQueries.getValue(paths[0].queryIds.single()).contains("RepositoryCustomer"))
            }
            val direct = result.accessPaths.filter { it.method.startsWith("direct(") }
            assertEquals(listOf(":1", ":2"), direct.map { ":" + it.id.substringAfterLast(':') })
            val finite = result.accessPaths.filter { it.method.startsWith("finite(") }
            assertEquals(2, finite.size)
            assertTrue(finite.all { it.status == AccessPathStatus.STATIC && it.queryIds.size == 2 }, finite.toString())
            assertEquals(finite[0].queryIds, finite[1].queryIds)
            val inventory = json(result).path("apiInventory")
            assertFalse(inventory.isMissingNode, "The scanner must export its raw API inventory")
            val directCalls = inventory.path("invocations").filter { it.path("method").asText().startsWith("direct(") }
            assertEquals(listOf(1, 2), directCalls.map { it.path("invocationIndex").asInt() })
            assertEquals(listOf("FACTORY", "EXECUTION"), directCalls.map { it.path("role").asText() })
            assertTrue(inventory.path("completeWithinDeclaredScope").asBoolean(), inventory.toString())
            assertTrue(inventory.path("scannedClasses").none { it.asText().startsWith("outside.") })
        }
    }

    @Test fun `discarded modifier result taints only its origin and custom base override stays unresolved`() {
        val files = fixture()
        withLoader(files) { loader ->
            val result = RepositoryDiscovery.discover(framework() + files.map(Path::toString), listOf("selected"), loader)
            val modified = result.accessPaths.filter { it.method.startsWith("independent(") }
            assertEquals(3, modified.count { it.status == AccessPathStatus.UNRESOLVED }, modified.toString())
            val ordinary = modified.filter { it.status == AccessPathStatus.STATIC }
            assertEquals(2, ordinary.size, modified.toString())
            assertEquals(ordinary[0].queryIds, ordinary[1].queryIds)
            assertEquals(1, result.hqlQueries.keys.count { it.contains("#independent(") })
            val custom = result.accessPaths.filter { it.className == "selected.CustomRepo" }
            assertTrue(custom.isNotEmpty())
            assertTrue(custom.all { it.status == AccessPathStatus.UNRESOLVED }, custom.toString())
            assertFalse(result.hqlQueries.keys.any { it.startsWith("selected.CustomRepo#") })
        }
    }

    @Test fun `unsupported count terminal does not publish its unused base select as an executed variant`() {
        val files = fixture()
        withLoader(files) { loader ->
            val result = RepositoryDiscovery.discover(framework() + files.map(Path::toString), listOf("selected"), loader)
            val paths = result.accessPaths.filter { it.method.startsWith("countOnly(") }
            assertEquals(2, paths.size, result.toString())
            assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
            assertTrue(paths.any { it.reason?.contains("distinct result/SQL variant") == true }, paths.toString())
            assertFalse(result.hqlQueries.keys.any { it.contains("#countOnly(") }, result.hqlQueries.toString())
            val direct = result.accessPaths.filter { it.method.startsWith("direct(") }
            assertTrue(direct.all { it.status == AccessPathStatus.STATIC }, direct.toString())
        }
    }

    @Test fun `unavailable external owner retains located gaps and an unknown denominator extent`() {
        val owner = "selected/MissingRepo"
        val writer = owner(owner, "outside/Missing", "Ljava/lang/Object;Loutside/Missing;")
        method(writer, "run") { factory(this, "outside/Missing"); terminal(this) }
        writer.visitEnd()
        val jar = jar("missing.jar", mapOf("$owner.class" to writer.toByteArray()))
        withLoader(listOf(jar)) { loader ->
            val result = RepositoryDiscovery.discover(framework() + jar.toString(), listOf("selected"), loader)
            val inventory = json(result).path("apiInventory")
            assertFalse(inventory.isMissingNode)
            assertFalse(inventory.path("completeWithinDeclaredScope").asBoolean())
            assertTrue(inventory.path("ownerGaps").any { gap -> gap.path("callSiteIds").any { it.asText().contains("selected.MissingRepo#run()V:0") } }, inventory.toString())
            assertTrue(result.unresolved.isNotEmpty(), result.toString())
        }
    }

    @Test fun `actual Hibernate 7 runtime translates fixed inherited Panache origins from module JARs`() {
        val files = fixture()
        val hibernate = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        val request = ExtractionRequest(entityClasses = listOf(RepositoryCustomer::class.java.name),
            classpath = hibernate + requireNotNull(System.getProperty("epoch.panache749.classpath")).split(File.pathSeparator) + files.map(Path::toString), repositoryPackages = listOf("selected"))
        val contract = HibernateExtractor().extract(request)
        val direct = contract.accessPaths.filter { it.className == "selected.CustomerRepo" && it.method.startsWith("direct(") }
        assertEquals(2, direct.size, contract.toString())
        assertTrue(direct.all { it.status == AccessPathStatus.STATIC }, contract.unresolvedQueries.toString())
        assertEquals(direct[0].queryIds, direct[1].queryIds)
        assertTrue(contract.queries.any { it.id in direct[0].queryIds && "repository_customer" in it.sql && it.dependenciesComplete })
        assertTrue(contract.coverage.notes.any { "Hibernate runtime 7.4.9.Final" in it })
        assertFalse(json(contract).path("apiInventory").isMissingNode)
    }

    private fun fixture(): List<Path> {
        val baseName = "outside/ReorderedBase"
        val base = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, baseName,
                "<A:Ljava/lang/Object;T:Ljava/lang/Object;>Ljava/lang/Object;L$repository<TT;>;", "java/lang/Object", arrayOf(repository))
            visitEnd()
        }.toByteArray()
        val customName = "outside/CustomBase"
        val custom = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT, customName,
                "Ljava/lang/Object;L$repository<L$entity;>;", "java/lang/Object", arrayOf(repository))
            visitMethod(Opcodes.ACC_PUBLIC, "find", findDescriptor, null, null).apply {
                visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
            }; visitEnd()
        }.toByteArray()
        val repoName = "selected/CustomerRepo"
        val repo = owner(repoName, baseName, "Ljava/lang/Object;L$baseName<Loutside/AuditEntity;L$entity;>;")
        method(repo, "direct") {
            visitLdcInsn("helper"); visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false); visitInsn(Opcodes.POP)
            factory(this, baseName); terminal(this)
        }
        method(repo, "local") { factory(this, baseName); visitVarInsn(Opcodes.ASTORE, 1); visitVarInsn(Opcodes.ALOAD, 1); terminal(this) }
        method(repo, "finite", "(Z)V") {
            visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ILOAD, 1)
            val other = Label(); val joined = Label()
            visitJumpInsn(Opcodes.IFEQ, other); visitLdcInsn("email = ?1"); visitJumpInsn(Opcodes.GOTO, joined)
            visitLabel(other); visitLdcInsn("email <> ?1"); visitLabel(joined)
            emptyArguments(this); visitMethodInsn(Opcodes.INVOKEINTERFACE, baseName, "find", findDescriptor, true); terminal(this)
        }
        method(repo, "countOnly") {
            factory(this, baseName)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, query, "count", "()J", true); visitInsn(Opcodes.POP2)
        }
        method(repo, "independent") {
            factory(this, baseName); visitVarInsn(Opcodes.ASTORE, 1)
            visitVarInsn(Opcodes.ALOAD, 1); visitInsn(Opcodes.ICONST_0); visitIntInsn(Opcodes.BIPUSH, 10)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, query, "page", "(II)L$query;", true); visitInsn(Opcodes.POP)
            visitVarInsn(Opcodes.ALOAD, 1); terminal(this)
            factory(this, baseName); terminal(this)
        }
        repo.visitEnd()
        val override = owner("selected/CustomRepo", customName, "Ljava/lang/Object;L$customName;")
        method(override, "run") { factory(this, customName); terminal(this) }; override.visitEnd()
        val entityBytes = requireNotNull(RepositoryCustomer::class.java.getResourceAsStream("RepositoryCustomer.class")).use { it.readBytes() }
        val audit = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "outside/AuditEntity", null, "java/lang/Object", null)
            visitAnnotation("Ljakarta/persistence/Entity;", true).visitEnd()
            visitField(Opcodes.ACC_PUBLIC, "id", "Ljava/lang/Long;", null, null).apply { visitAnnotation("Ljakarta/persistence/Id;", true).visitEnd(); visitEnd() }
            visitEnd()
        }.toByteArray()
        return listOf(jar("entity.jar", mapOf("$entity.class" to entityBytes, "outside/AuditEntity.class" to audit)), jar("bases.jar", mapOf("$baseName.class" to base, "$customName.class" to custom)),
            jar("repositories.jar", mapOf("$repoName.class" to repo.toByteArray(), "selected/CustomRepo.class" to override.toByteArray())))
    }
    private fun owner(name: String, parent: String, signature: String) = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, signature, "java/lang/Object", arrayOf(parent))
    }
    private fun method(writer: ClassWriter, name: String, descriptor: String = "()V", body: MethodVisitor.() -> Unit) {
        writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply { visitCode(); body(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd() }
    }
    private fun emptyArguments(method: MethodVisitor) {
        method.visitInsn(Opcodes.ICONST_1); method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        method.visitInsn(Opcodes.DUP); method.visitInsn(Opcodes.ICONST_0); method.visitLdcInsn("customer@example.test"); method.visitInsn(Opcodes.AASTORE)
    }
    private fun factory(method: MethodVisitor, owner: String) {
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitLdcInsn("email = ?1"); emptyArguments(method)
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, "find", findDescriptor, true)
    }
    private fun terminal(method: MethodVisitor) { method.visitMethodInsn(Opcodes.INVOKEINTERFACE, query, "list", "()Ljava/util/List;", true); method.visitInsn(Opcodes.POP) }
    private fun jar(name: String, entries: Map<String, ByteArray>): Path = directory.resolve(name).also { path ->
        JarOutputStream(Files.newOutputStream(path)).use { jar -> entries.toSortedMap().forEach { (name, bytes) ->
            jar.putNextEntry(JarEntry(name).apply { time = 0 }); jar.write(bytes); jar.closeEntry()
        } }
    }
    private fun framework() = requireNotNull(System.getProperty("epoch.panache.classpath")).split(File.pathSeparator)
    private fun <T> withLoader(paths: List<Path>, block: (ClassLoader) -> T): T = URLClassLoader(
        (framework().map { Path.of(it) } + paths).map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader).use(block)
    private fun json(value: Any): JsonNode = jacksonObjectMapper().valueToTree(value)
}
