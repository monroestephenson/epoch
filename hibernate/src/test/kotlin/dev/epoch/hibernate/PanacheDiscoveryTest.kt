package dev.epoch.hibernate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PanacheDiscoveryTest {
    @TempDir lateinit var directory: Path

    @Test fun `Panache sort and query modifiers cannot claim the unmodified query as verified`() {
        val owner = "dev/epoch/panachefixture/ModifiedCustomerRepository"
        val framework = "io/quarkus/hibernate/orm/panache/PanacheRepository"
        val query = "io/quarkus/hibernate/orm/panache/PanacheQuery"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner,
            "Ljava/lang/Object;L$framework<Ldev/epoch/repositoryfixture/RepositoryCustomer;>;", "java/lang/Object", arrayOf(framework))
        val sorted = writer.visitMethod(Opcodes.ACC_PUBLIC, "sorted", "(Lio/quarkus/panache/common/Sort;)V", null, null)
        sorted.visitCode()
        sorted.visitVarInsn(Opcodes.ALOAD, 0)
        sorted.visitLdcInsn("email = ?1")
        sorted.visitVarInsn(Opcodes.ALOAD, 1)
        sorted.visitInsn(Opcodes.ICONST_0)
        sorted.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
        sorted.visitMethodInsn(Opcodes.INVOKEINTERFACE, framework, "list",
            "(Ljava/lang/String;Lio/quarkus/panache/common/Sort;[Ljava/lang/Object;)Ljava/util/List;", true)
        sorted.visitInsn(Opcodes.POP)
        sorted.visitInsn(Opcodes.RETURN)
        sorted.visitMaxs(0, 0)
        sorted.visitEnd()
        for ((name, argument) in listOf("project" to "java/lang/Class", "page" to "io/quarkus/panache/common/Page",
            "withLock" to "jakarta/persistence/LockModeType")) {
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "(L$argument;)V", null, null)
            method.visitCode()
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitLdcInsn("email = ?1")
            method.visitInsn(Opcodes.ICONST_0)
            method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, framework, "find", "(Ljava/lang/String;[Ljava/lang/Object;)L$query;", true)
            method.visitVarInsn(Opcodes.ALOAD, 1)
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, query, name, "(L$argument;)L$query;", true)
            method.visitInsn(Opcodes.POP)
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        val output = directory.resolve("$owner.class")
        Files.createDirectories(output.parent)
        Files.write(output, writer.toByteArray())
        val runtime = requireNotNull(System.getProperty("epoch.panache.classpath")).split(File.pathSeparator)
        val result = URLClassLoader((runtime.map { Path.of(it).toUri().toURL() } + directory.toUri().toURL()).toTypedArray(), javaClass.classLoader).use { loader ->
            RepositoryDiscovery.discover(runtime + directory.toString(), listOf("dev.epoch.panachefixture"), loader)
        }
        assertTrue(result.hqlQueries.isEmpty(), result.toString())
        for (name in listOf("sorted", "project", "page", "withLock")) {
            val paths = result.accessPaths.filter { it.method.startsWith("$name(") }
            assertTrue(paths.isNotEmpty(), result.toString())
            assertTrue(paths.all { it.status == dev.epoch.core.AccessPathStatus.UNRESOLVED }, paths.toString())
        }
        assertEquals(7, result.unresolved.size, result.toString())
    }

    @Test fun `Panache shorthand is expanded by the actual framework helper for each supported operation`() {
        // Only the repository bytecode fixture is synthetic; expansion runs Quarkus's published helper.
        val owner = "dev/epoch/panachefixture/CustomerPanacheRepository"
        val framework = "io/quarkus/hibernate/orm/panache/PanacheRepository"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner,
            "Ljava/lang/Object;L$framework<Ldev/epoch/repositoryfixture/RepositoryCustomer;>;", "java/lang/Object", arrayOf(framework))
        for ((name, text) in listOf("list" to "email = ?1", "count" to "email = ?1", "delete" to "email = ?1", "update" to "email = ?1")) {
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "invoke${name.replaceFirstChar(Char::uppercaseChar)}", "()V", null, null)
            method.visitCode()
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.visitLdcInsn(text)
            method.visitInsn(Opcodes.ICONST_1)
            method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
            method.visitInsn(Opcodes.DUP)
            method.visitInsn(Opcodes.ICONST_0)
            method.visitLdcInsn("example@example.test")
            method.visitInsn(Opcodes.AASTORE)
            val returns = when (name) { "list" -> "Ljava/util/List;"; "update" -> "I"; else -> "J" }
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, framework, name, "(Ljava/lang/String;[Ljava/lang/Object;)$returns", true)
            method.visitInsn(if (returns == "J") Opcodes.POP2 else Opcodes.POP)
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        val unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC, "invokeUnrelatedHelper", "()V", null, null)
        unrelated.visitCode()
        unrelated.visitLdcInsn("not a database query")
        unrelated.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/epoch/panachefixture/UnrelatedHelper", "list", "(Ljava/lang/String;)Ljava/util/List;", false)
        unrelated.visitInsn(Opcodes.POP)
        unrelated.visitInsn(Opcodes.RETURN)
        unrelated.visitMaxs(0, 0)
        unrelated.visitEnd()
        writer.visitEnd()
        val output = directory.resolve("$owner.class")
        Files.createDirectories(output.parent)
        Files.write(output, writer.toByteArray())
        // The helper really exists and has no persistence ancestry; a missing owner
        // cannot establish that an otherwise unrecognized API call is unrelated.
        val helper = ClassWriter(ClassWriter.COMPUTE_MAXS).apply {
            visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "dev/epoch/panachefixture/UnrelatedHelper", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "list", "(Ljava/lang/String;)Ljava/util/List;", null, null).apply {
                visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(0, 0); visitEnd()
            }
            visitEnd()
        }
        Files.write(output.parent.resolve("UnrelatedHelper.class"), helper.toByteArray())
        val frameworkClasspath = requireNotNull(System.getProperty("epoch.panache.classpath")) { "Panache helper test runtime must be configured" }
        val urls = frameworkClasspath.split(File.pathSeparator).map { Path.of(it).toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            val result = RepositoryDiscovery.discover(frameworkClasspath.split(File.pathSeparator) + directory.toString(), listOf("dev.epoch.panachefixture"), loader)
            assertTrue(result.unresolved.isEmpty(), result.toString())
            assertEquals(4, result.hqlQueries.size, result.toString())
            assertTrue(result.hqlQueries.values.all { it.contains("RepositoryCustomer") && it.contains("email = ?1") }, result.toString())
            assertTrue(result.hqlQueries.values.any { it.startsWith("FROM ", ignoreCase = true) })
            assertTrue(result.hqlQueries.values.any { it.startsWith("SELECT COUNT", ignoreCase = true) })
            assertTrue(result.hqlQueries.values.any { it.startsWith("DELETE", ignoreCase = true) })
            assertTrue(result.hqlQueries.values.any { it.startsWith("UPDATE", ignoreCase = true) })
        }
    }
}
