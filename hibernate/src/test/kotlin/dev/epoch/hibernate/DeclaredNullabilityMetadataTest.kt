package dev.epoch.hibernate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.TypePath
import org.objectweb.asm.TypeReference
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeclaredNullabilityMetadataTest {
    @TempDir lateinit var directory: Path

    @Test fun `Java annotations describe only the mapped root type and conflicting facts stay unknown`() {
        val binaryName = "dev.epoch.generated.NullabilityAnnotations"
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null)
        fun field(name: String, descriptor: String = "Ljava/lang/String;", signature: String? = null,
                  configure: (org.objectweb.asm.FieldVisitor) -> Unit = {}) {
            writer.visitField(Opcodes.ACC_PUBLIC, name, descriptor, signature, null).also(configure).visitEnd()
        }
        field("conflicting") {
            it.visitAnnotation("Lorg/jetbrains/annotations/NotNull;", false).visitEnd()
            it.visitAnnotation("Lorg/jetbrains/annotations/Nullable;", false).visitEnd()
        }
        field("rootRequired") {
            it.visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value, null,
                "Lorg/jspecify/annotations/NonNull;", false).visitEnd()
        }
        field("elementRequired", "Ljava/util/List;", "Ljava/util/List<Ljava/lang/String;>;") {
            it.visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.FIELD).value, TypePath.fromString("0;"),
                "Lorg/jspecify/annotations/NonNull;", false).visitEnd()
        }
        for (whenValue in listOf("ALWAYS", "MAYBE", "NEVER", "UNKNOWN")) field("when$whenValue") {
            it.visitAnnotation("Ljavax/annotation/Nonnull;", false).apply {
                visitEnum("when", "Ljavax/annotation/meta/When;", whenValue)
                visitEnd()
            }
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "getOptional", "()Ljava/lang/String;", null, null).apply {
            visitTypeAnnotation(TypeReference.newTypeReference(TypeReference.METHOD_RETURN).value, null,
                "Lorg/jspecify/annotations/Nullable;", false).visitEnd()
            visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 1); visitEnd()
        }
        writer.visitEnd()
        val bytes = writer.toByteArray()
        val target = directory.resolve(binaryName.replace('.', '/') + ".class")
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = Class.forName(binaryName, false, loader)
            assertNull(DeclaredNullability.of(type.getField("conflicting")))
            assertEquals(true, DeclaredNullability.of(type.getField("rootRequired")))
            assertNull(DeclaredNullability.of(type.getField("elementRequired")))
            assertEquals(true, DeclaredNullability.of(type.getField("whenALWAYS")))
            assertEquals(false, DeclaredNullability.of(type.getField("whenMAYBE")))
            assertEquals(false, DeclaredNullability.of(type.getField("whenNEVER")))
            assertNull(DeclaredNullability.of(type.getField("whenUNKNOWN")))
            assertEquals(false, DeclaredNullability.of(type.getMethod("getOptional")))
        }
    }

    @Test fun `unresolved Kotlin type parameters do not become concrete non-null column facts`() {
        assertNull(DeclaredNullability.of(GenericDeclaration::class.java.getDeclaredField("value")))
        assertEquals(false, DeclaredNullability.of(GenericDeclaration::class.java.getDeclaredField("optional")))
    }

    @Test fun `Java declaration bytes come from the defining artifact despite parent-first resources`() {
        val binaryName = "dev.epoch.generated.ShadowedDeclaration"
        val resource = binaryName.replace('.', '/') + ".class"
        fun artifact(name: String, annotation: String): Path {
            val root = directory.resolve(name)
            val writer = ClassWriter(0)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null)
            writer.visitField(Opcodes.ACC_PUBLIC, "value", "Ljava/lang/String;", null, null).apply {
                visitAnnotation(annotation, false).visitEnd()
                visitEnd()
            }
            writer.visitEnd()
            Files.createDirectories(root.resolve(resource).parent)
            Files.write(root.resolve(resource), writer.toByteArray())
            return root
        }
        val parentRoot = artifact("parent", "Lorg/jetbrains/annotations/Nullable;")
        val childRoot = artifact("child", "Lorg/jetbrains/annotations/NotNull;")
        URLClassLoader(arrayOf(parentRoot.toUri().toURL()), javaClass.classLoader).use { parent ->
            object : URLClassLoader(arrayOf(childRoot.toUri().toURL()), parent) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name != binaryName) return super.loadClass(name, resolve)
                    return synchronized(getClassLoadingLock(name)) {
                        (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    }
                }
            }.use { child ->
                assertEquals(parentRoot.resolve(resource).toUri().toURL(), child.getResource(resource))
                assertEquals(false, DeclaredNullability.of(parent.loadClass(binaryName).getField("value")))
                assertEquals(true, DeclaredNullability.of(child.loadClass(binaryName).getField("value")))
            }
        }
    }
}

private class GenericDeclaration<T>(var value: T, var optional: String?)
