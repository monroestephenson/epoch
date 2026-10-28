package dev.epoch.hibernate

import dev.epoch.core.AccessPathStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class RepositoryUnknownOriginTest {
    @TempDir lateinit var directory: Path

    @Test fun `zero limit on a known or external query invalidates the known factory only`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Ljakarta/persistence/Query;Z)V", 6) {
                knownOrExternal()
                visitVarInsn(Opcodes.ALOAD, 4); visitInsn(Opcodes.ICONST_0)
                queryCall("setMaxResults", "(I)Ljakarta/persistence/Query;")
                execute()
                healthy(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        assertKnownFactorySuppressed(result, "maxResults=0")
        assertHealthy(result)
    }

    @Test fun `receiver return cast and local copy preserve a known factory through unknown extent`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Ljakarta/persistence/Query;Z)V", 6) {
                knownOrExternal()
                visitVarInsn(Opcodes.ALOAD, 4); visitLdcInsn("email"); visitLdcInsn("value")
                queryCall("setParameter", "(Ljava/lang/String;Ljava/lang/Object;)Ljakarta/persistence/Query;")
                visitTypeInsn(Opcodes.CHECKCAST, "jakarta/persistence/Query")
                visitVarInsn(Opcodes.ASTORE, 4); visitVarInsn(Opcodes.ALOAD, 4)
                visitLdcInsn("application.hint"); visitLdcInsn("value")
                queryCall("setHint", "(Ljava/lang/String;Ljava/lang/Object;)Ljakarta/persistence/Query;")
                execute()
                healthy(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        assertKnownFactorySuppressed(result, "modifier")
        assertHealthy(result)
    }

    @Test fun `returning a merged unknown receiver still marks its known factory escaped`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Ljakarta/persistence/Query;Z)Ljakarta/persistence/Query;", 6) {
                knownOrExternal()
                healthy()
                visitVarInsn(Opcodes.ALOAD, 4)
                visitTypeInsn(Opcodes.CHECKCAST, "jakarta/persistence/Query")
                visitInsn(Opcodes.ARETURN)
            }
        }
        val result = discover()
        assertKnownFactorySuppressed(result, "method return")
        assertHealthy(result)
    }

    @Test fun `an unmodified known query remains extracted but its mixed external execution stays unresolved`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Ljakarta/persistence/Query;Z)V", 5) {
                knownOrExternal()
                visitVarInsn(Opcodes.ALOAD, 4); execute(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        assertEquals(listOf("from Known"), result.hqlQueries.values.toList(), result.toString())
        assertEquals(AccessPathStatus.STATIC, result.accessPaths.single { it.mechanism == "JPA_HQL" }.status)
        val terminal = result.accessPaths.single { it.mechanism == "QUERY_TERMINAL" }
        assertEquals(AccessPathStatus.UNRESOLVED, terminal.status, terminal.toString())
        assertTrue(terminal.reason?.contains("unestablished producer") == true, terminal.toString())
        assertAnalysisSucceeded(result)
    }

    @Test fun `a finite union of two established factories keeps both SQL origins`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Z)V", 4) {
                factory("from First"); visitVarInsn(Opcodes.ASTORE, 2)
                factory("from Second"); visitVarInsn(Opcodes.ASTORE, 3)
                val alternative = Label(); val joined = Label()
                visitVarInsn(Opcodes.ILOAD, 1); visitJumpInsn(Opcodes.IFEQ, alternative)
                visitVarInsn(Opcodes.ALOAD, 2); visitJumpInsn(Opcodes.GOTO, joined)
                visitLabel(alternative); visitVarInsn(Opcodes.ALOAD, 3)
                visitLabel(joined); execute(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        assertEquals(setOf("from First", "from Second"), result.hqlQueries.values.toSet(), result.toString())
        assertEquals(3, result.accessPaths.size, result.toString())
        assertTrue(result.accessPaths.all { it.status == AccessPathStatus.STATIC }, result.toString())
        assertEquals(result.hqlQueries.keys, result.accessPaths.single { it.mechanism == "QUERY_TERMINAL" }.queryIds.toSet())
        assertAnalysisSucceeded(result)
    }

    @Test fun `a NULL alternative contributes no unknown successful SQL producer`() {
        writeRepository {
            method("run", "(Ljakarta/persistence/EntityManager;Z)V", 3) {
                factory("from Known"); visitVarInsn(Opcodes.ASTORE, 2)
                val alternative = Label(); val joined = Label()
                visitVarInsn(Opcodes.ILOAD, 1); visitJumpInsn(Opcodes.IFEQ, alternative)
                visitVarInsn(Opcodes.ALOAD, 2); visitJumpInsn(Opcodes.GOTO, joined)
                visitLabel(alternative); visitInsn(Opcodes.ACONST_NULL)
                visitLabel(joined); execute(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        assertEquals(listOf("from Known"), result.hqlQueries.values.toList(), result.toString())
        assertEquals(2, result.accessPaths.size, result.toString())
        assertTrue(result.accessPaths.all { it.status == AccessPathStatus.STATIC }, result.toString())
        assertAnalysisSucceeded(result)
    }

    @Test fun `origin budget overflow invalidates all factories in that method without losing effect targets`() {
        writeRepository {
            method("overflow", "(Ljakarta/persistence/EntityManager;I)V", 19) {
                (0..16).forEach { index -> factory("from Origin$index"); visitVarInsn(Opcodes.ASTORE, index + 2) }
                val labels = (0..16).map { Label() }; val joined = Label()
                visitVarInsn(Opcodes.ILOAD, 1)
                visitTableSwitchInsn(0, 16, labels.first(), *labels.toTypedArray())
                labels.forEachIndexed { index, label ->
                    visitLabel(label); visitVarInsn(Opcodes.ALOAD, index + 2); visitJumpInsn(Opcodes.GOTO, joined)
                }
                visitLabel(joined); visitInsn(Opcodes.ICONST_0)
                queryCall("setMaxResults", "(I)Ljakarta/persistence/Query;"); execute()
                factory("from SameMethodSibling"); execute(); visitInsn(Opcodes.RETURN)
            }
            method("independent", "(Ljakarta/persistence/EntityManager;)V", 1) {
                factory("from OtherMethod"); execute(); visitInsn(Opcodes.RETURN)
            }
        }
        val result = discover()
        val affected = result.accessPaths.filter { it.method.startsWith("overflow(") }
        val factories = affected.filter { it.mechanism == "JPA_HQL" }
        assertEquals(18, factories.size, result.toString())
        assertTrue(factories.all { it.status == AccessPathStatus.UNRESOLVED && it.reason?.contains("16-origin") == true }, factories.toString())
        assertTrue(affected.all { it.status == AccessPathStatus.UNRESOLVED }, affected.toString())
        assertEquals(listOf("from OtherMethod"), result.hqlQueries.values.toList(), result.toString())
        assertTrue(result.accessPaths.filter { it.method.startsWith("independent(") }.all { it.status == AccessPathStatus.STATIC }, result.toString())
        assertAnalysisSucceeded(result)
    }

    private fun assertKnownFactorySuppressed(result: DiscoveredQueries, reason: String) {
        val firstFactory = result.accessPaths.single { it.id.endsWith(":0") && it.mechanism == "JPA_HQL" }
        assertEquals(AccessPathStatus.UNRESOLVED, firstFactory.status, firstFactory.toString())
        assertTrue(firstFactory.reason?.contains(reason, ignoreCase = true) == true, firstFactory.toString())
        assertFalse("from Known" in result.hqlQueries.values, result.toString())
        assertAnalysisSucceeded(result)
    }

    private fun assertHealthy(result: DiscoveredQueries) {
        val healthyId = result.hqlQueries.entries.single { it.value == "from Healthy" }.key
        val healthy = result.accessPaths.filter { healthyId in it.queryIds }
        assertEquals(2, healthy.size, result.toString())
        assertTrue(healthy.all { it.status == AccessPathStatus.STATIC }, healthy.toString())
        assertEquals(listOf("from Healthy"), result.hqlQueries.values.toList(), result.toString())
    }

    private fun assertAnalysisSucceeded(result: DiscoveredQueries) {
        val inventory = requireNotNull(result.apiInventory)
        assertTrue(inventory.failedSources.isEmpty(), inventory.toString())
        assertTrue(inventory.ownerGaps.isEmpty(), inventory.toString())
    }

    private fun MethodVisitor.knownOrExternal() {
        factory("from Known"); visitVarInsn(Opcodes.ASTORE, 3)
        val alternative = Label(); val joined = Label()
        visitVarInsn(Opcodes.ILOAD, 2); visitJumpInsn(Opcodes.IFEQ, alternative)
        visitVarInsn(Opcodes.ALOAD, 3); visitJumpInsn(Opcodes.GOTO, joined)
        visitLabel(alternative); visitVarInsn(Opcodes.ALOAD, 1)
        visitLabel(joined); visitVarInsn(Opcodes.ASTORE, 4)
    }

    private fun MethodVisitor.factory(hql: String) {
        visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn(hql)
        visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
    }
    private fun MethodVisitor.healthy() { factory("from Healthy"); execute() }
    private fun MethodVisitor.execute() { queryCall("getResultList", "()Ljava/util/List;"); visitInsn(Opcodes.POP) }
    private fun MethodVisitor.queryCall(name: String, descriptor: String) =
        visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/Query", name, descriptor, true)
    private fun ClassWriter.method(name: String, descriptor: String, locals: Int, body: MethodVisitor.() -> Unit) {
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, descriptor, null, null).apply {
            visitCode(); body(); visitMaxs(4, locals); visitEnd()
        }
    }
    private fun writeRepository(body: ClassWriter.() -> Unit) {
        val writer = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/OriginRepository", null, "java/lang/Object", null)
            body(); visitEnd()
        }
        val path = directory.resolve("audit/OriginRepository.class")
        Files.createDirectories(path.parent); Files.write(path, writer.toByteArray())
    }
    private fun discover() = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use {
        RepositoryDiscovery.discover(listOf(directory.toString()), listOf("audit"), it)
    }
}
