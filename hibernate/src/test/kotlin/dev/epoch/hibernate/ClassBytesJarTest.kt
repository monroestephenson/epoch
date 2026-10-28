package dev.epoch.hibernate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ClassBytesJarTest {
    @TempDir lateinit var directory: Path

    @Test fun `class bytes select the same multi-release JAR variant as the loaded class`() {
        val binaryName = "dev.epoch.generated.MultiReleaseConverter"
        val entryName = binaryName.replace('.', '/') + ".class"
        val baseBytes = markedClass(binaryName, "base")
        val runtimeBytes = markedClass(binaryName, "runtime")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MULTI_RELEASE] = "true"
        }
        val jarPath = directory.resolve("converter.jar")
        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jar ->
            fun add(name: String, bytes: ByteArray) {
                jar.putNextEntry(JarEntry(name).apply { time = 0 })
                jar.write(bytes)
                jar.closeEntry()
            }
            add(entryName, baseBytes)
            add("META-INF/versions/${Runtime.version().feature()}/$entryName", runtimeBytes)
        }

        URLClassLoader(arrayOf(jarPath.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = Class.forName(binaryName, false, loader)
            assertEquals("runtime", type.getField("MARKER").get(null),
                "The JVM must load the versioned entry, so reading the base entry cannot satisfy this test")
            val actual = assertNotNull(ClassBytes.read(type))
            assertContentEquals(runtimeBytes, actual)
            assertFalse(baseBytes.contentEquals(actual))
        }
    }

    private fun markedClass(binaryName: String, marker: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null)
        visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            "MARKER", "Ljava/lang/String;", null, marker).visitEnd()
        visitEnd()
    }.toByteArray()
}
