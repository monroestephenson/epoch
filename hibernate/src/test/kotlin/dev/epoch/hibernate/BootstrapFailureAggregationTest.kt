package dev.epoch.hibernate

import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BootstrapFailureAggregationTest {
    @TempDir lateinit var directory: Path

    @Test fun `one actual Hibernate bootstrap failure retains every affected entity without duplicating its cause`() {
        val names = (0 until 75).map { "bootstrapfailure.Entity$it" }
        names.forEach { name -> writeEntity(name, names.first()) }
        val request = ExtractionRequest(entityClasses = names, classpath = listOf(directory.toString()),
            discoverRepositories = false, keepGoing = true,
            physicalNamingStrategy = FailingBootstrapNamingStrategy::class.java.name)
        val contract = HibernateExtractor().extract(request)
        assertTrue(contract.entities.isEmpty())
        assertTrue(contract.queries.isEmpty())
        assertFalse(contract.coverage.complete)
        assertEquals(1, contract.unresolvedQueries.size, contract.unresolvedQueries.toString())
        val diagnostic = contract.unresolvedQueries.single()
        assertEquals(names.sorted(), diagnostic.affectedIds)
        assertTrue(diagnostic.reason.contains("IllegalStateException: fixture naming strategy bootstrap root"), diagnostic.reason)
        assertEquals(contract, HibernateExtractor().extract(request.copy(entityClasses = names.reversed())),
            "The same actual failure must have deterministic cause and affected-source ordering")
    }

    @Test fun `a common absent class dependency retains every entity in one loading diagnostic`() {
        val names = (0 until 4).map { "bootstrapfailure.MissingDependency$it" }
        names.forEach { writeEntity(it, names.first(), "missingdependency/UnavailableBase") }
        val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = names,
            classpath = listOf(directory.toString()), keepGoing = true, discoverRepositories = false))
        val diagnostic = contract.unresolvedQueries.single()
        assertEquals(names.sorted(), diagnostic.affectedIds)
        assertTrue(diagnostic.reason.contains("NoClassDefFoundError: missingdependency/UnavailableBase"), diagnostic.reason)
        assertTrue(diagnostic.reason.contains("ClassNotFoundException: missingdependency.UnavailableBase"), diagnostic.reason)
        assertFalse(contract.coverage.complete)
    }

    private fun writeEntity(name: String, peer: String, superclass: String = "java/lang/Object") {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, superclass, null)
            visitAnnotation("Ljakarta/persistence/Entity;", true).visitEnd()
            visitField(Opcodes.ACC_PUBLIC, "id", "J", null, null).apply {
                visitAnnotation("Ljakarta/persistence/Id;", true).visitEnd()
                visitEnd()
            }
            visitField(Opcodes.ACC_PUBLIC, "peer", "L${peer.replace('.', '/')};", null, null).apply {
                visitAnnotation("Ljakarta/persistence/ManyToOne;", true).visitEnd()
                visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val path = directory.resolve(name.replace('.', '/') + ".class")
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
    }
}

class FailingBootstrapNamingStrategy : PhysicalNamingStrategyStandardImpl() {
    init { throw IllegalStateException("fixture naming strategy bootstrap root") }
}
