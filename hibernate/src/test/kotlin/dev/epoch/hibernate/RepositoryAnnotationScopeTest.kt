package dev.epoch.hibernate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Same missing RESTEasy annotation shape as the published Keycloak services JAR. */
class RepositoryAnnotationScopeTest {
    @TempDir lateinit var directory: Path

    @Test fun `missing HTTP resource annotation does not invent a repository query`() {
        writeInterface("HttpResource", repository = false)
        val discovered = discover()
        assertTrue(discovered.accessPaths.isEmpty(), discovered.toString())
        assertTrue(discovered.unresolved.isEmpty(), discovered.toString())
        assertTrue(discovered.hqlQueries.isEmpty())
    }

    @Test fun `unreadable annotation on a known Spring repository still blocks fabricated derivation`() {
        writeInterface("SpringRepository", repository = true)
        val discovered = discover()
        assertEquals(1, discovered.accessPaths.size, discovered.toString())
        assertEquals("SPRING_QUERY_EXTENSION", discovered.accessPaths.single().mechanism)
        assertEquals(1, discovered.unresolved.size, discovered.toString())
        assertTrue(discovered.hqlQueries.isEmpty())
    }

    @Test fun `readable composed query annotation stays unresolved even without a Spring repository parent`() {
        writeInterface("ComposedQueryDao", repository = false,
            annotation = "Ldev/epoch/externalqueryfixture/ExternalQuery;")
        val discovered = discover()
        assertEquals(1, discovered.accessPaths.size, discovered.toString())
        assertEquals("SPRING_QUERY_EXTENSION", discovered.accessPaths.single().mechanism)
        assertEquals(1, discovered.unresolved.size, discovered.toString())
        assertTrue(discovered.hqlQueries.isEmpty())
    }

    private fun discover() = RepositoryDiscovery.discover(listOf(directory.toString()),
        listOf("annotationscope"), javaClass.classLoader)

    private fun writeInterface(name: String, repository: Boolean,
                               annotation: String = "Lorg/jboss/resteasy/reactive/NoCache;") {
        val owner = "annotationscope/$name"
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                owner, null, "java/lang/Object",
                if (repository) arrayOf("org/springframework/data/repository/Repository") else null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "findById", "(Ljava/lang/String;)Ljava/lang/Object;", null, null).apply {
                // Annotation header deliberately absent, just as in the public runtime classpath.
                visitAnnotation(annotation, true).visitEnd()
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
        val file = directory.resolve("$owner.class")
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }
}
