package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.AccessPathStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RepositoryFailureAggregationTest {
    @TempDir lateinit var directory: Path

    @Test fun `repeated actual class failures retain every source with one shared cause`() {
        val packageDir = Files.createDirectories(directory.resolve("failurefixture"))
        val brokenNames = listOf("BrokenOne", "BrokenTwo", "BrokenThree")
        brokenNames.forEach { Files.write(packageDir.resolve("$it.class"), byteArrayOf(0)) }
        val badVersion = ByteArray(16).apply {
            this[0] = 0xca.toByte(); this[1] = 0xfe.toByte(); this[2] = 0xba.toByte(); this[3] = 0xbe.toByte()
            this[6] = 0x7f; this[7] = 0xff.toByte()
        }
        Files.write(packageDir.resolve("UnsupportedVersion.class"), badVersion)
        Files.write(packageDir.resolve("HealthyQueries.class"), healthyClass())
        val discovered = RepositoryDiscovery.discover(listOf(directory.toString()), listOf("failurefixture"), javaClass.classLoader)
        assertEquals(1, discovered.accessPaths.size, discovered.toString())
        assertEquals(AccessPathStatus.STATIC, discovered.accessPaths.single().status)
        assertEquals("select 1", discovered.hqlQueries.values.single())
        val unresolved = jacksonObjectMapper().valueToTree<JsonNode>(discovered).path("unresolved")
        val groups = unresolved.filter { it.path("affectedIds").isArray && !it.path("affectedIds").isEmpty }
        assertEquals(2, groups.size, "Different actual causes must remain separate, repeated causes must be compact: $unresolved")
        val shared = groups.single { it.path("affectedIds").size() == 3 }
        assertEquals(brokenNames.map { "repository-discovery:failurefixture/$it.class" }.sorted(),
            shared.path("affectedIds").map { it.asText() })
        assertTrue(shared.path("reason").asText().contains("ArrayIndexOutOfBoundsException"), shared.toString())
        val version = groups.single { it.path("affectedIds").size() == 1 }
        assertEquals("repository-discovery:failurefixture/UnsupportedVersion.class", version.path("affectedIds").single().asText())
        assertTrue(version.path("reason").asText().contains("Unsupported class file major version"), version.toString())
        assertEquals(2, unresolved.size(), "Do not re-expand grouped failures into duplicate evidence rows")
        val repeated = RepositoryDiscovery.discover(listOf(directory.toString()), listOf("failurefixture"), javaClass.classLoader)
        assertEquals(discovered, repeated, "Grouping and affected source ordering must be deterministic")
    }

    @Test fun `failed method analysis keeps every recognized API site while grouping the shared cause`() {
        val packageDir = Files.createDirectories(directory.resolve("failurefixture"))
        val broken = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "failurefixture/FlowFailures", null, "java/lang/Object", null)
            for (name in listOf("first", "second")) visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                name, "(Ljakarta/persistence/EntityManager;)V", null, null).apply {
                visitCode()
                visitInsn(Opcodes.POP) // Parsed successfully; actual ASM data flow cannot pop an empty stack.
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLdcInsn("select 1")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery",
                    "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        Files.write(packageDir.resolve("FlowFailures.class"), broken)
        Files.write(packageDir.resolve("HealthyQueries.class"), healthyClass())
        val result = RepositoryDiscovery.discover(listOf(directory.toString()), listOf("failurefixture"), javaClass.classLoader)
        val paths = result.accessPaths.filter { it.status == AccessPathStatus.UNRESOLVED }
        assertEquals(2, paths.size)
        assertEquals(1, result.accessPaths.count { it.status == AccessPathStatus.STATIC })
        val shared = result.unresolved.single()
        assertTrue(shared.affectedIds.containsAll(paths.map { it.id }), shared.toString())
        assertTrue(paths.all { it.reason == "Extraction diagnostic ${shared.id}" }, paths.toString())
        assertTrue(shared.reason.contains("AnalyzerException"), shared.toString())
        assertTrue(result.notes.any { "recognized 3 database API call sites" in it }, result.notes.toString())
        val inventory = assertNotNull(result.apiInventory)
        assertFalse(inventory.completeWithinDeclaredScope, inventory.toString())
        assertEquals(2, inventory.failedSources.size, inventory.toString())
        assertEquals(3, inventory.invocations.size, inventory.toString())
    }

    private fun healthyClass(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "failurefixture/HealthyQueries", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "read", "(Ljakarta/persistence/EntityManager;)V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitLdcInsn("select 1")
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery",
                "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(2, 1)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
