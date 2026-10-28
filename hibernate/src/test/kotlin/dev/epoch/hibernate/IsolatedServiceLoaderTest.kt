package dev.epoch.hibernate

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Separate packaged service APIs reproduce mixed-version Jackson discovery without relying on Jackson names. */
class IsolatedServiceLoaderTest {
    @TempDir lateinit var directory: Path

    @Test fun `application service API does not discover providers belonging to the parent runtime`() {
        val api = "fixture.spi.Module"
        val parentProvider = "fixture.parent.ParentModule"
        val appProvider = "fixture.application.ApplicationModule"
        val parentJar = jar("parent.jar", mapOf(
            entry(api) to serviceInterface(api), entry(parentProvider) to provider(parentProvider, api),
            "META-INF/services/$api" to "$parentProvider\n".toByteArray(),
        ))
        val appJar = jar("application.jar", mapOf(
            entry(api) to serviceInterface(api), entry(appProvider) to provider(appProvider, api),
            "META-INF/services/$api" to "$appProvider\n".toByteArray(),
        ))
        URLClassLoader(arrayOf(parentJar.toUri().toURL()), javaClass.classLoader).use { parent ->
            isolated(arrayOf(appJar.toUri().toURL()), parent).use { app ->
                val childApi = app.loadClass(api)
                assertNotSame(parent.loadClass(api), childApi)
                val services = ServiceLoader.load(childApi, app).toList()
                assertEquals(listOf(appProvider), services.map { it.javaClass.name })
                assertTrue(services.all(childApi::isInstance))
                assertEquals(listOf(appJar.toUri().toURL().toString()), app.getResources("META-INF/services/$api")
                    .asSequence().map { it.toString().substringAfter("jar:").substringBefore("!/") }.toList())
                assertEquals(app.getResources("META-INF/services/$api").nextElement(), app.getResource("META-INF/services/$api"))
            }
        }
    }

    @Test fun `a service API supplied only by the parent still discovers compatible application providers`() {
        val api = "fixture.shared.Service"
        val parentProvider = "fixture.shared.ParentService"
        val appProvider = "fixture.application.AppService"
        val parentJar = jar("shared-parent.jar", mapOf(entry(api) to serviceInterface(api),
            entry(parentProvider) to provider(parentProvider, api), "META-INF/services/$api" to "$parentProvider\n".toByteArray()))
        val appJar = jar("shared-app.jar", mapOf(entry(appProvider) to provider(appProvider, api),
            "META-INF/services/$api" to "$appProvider\n".toByteArray()))
        URLClassLoader(arrayOf(parentJar.toUri().toURL()), javaClass.classLoader).use { parent ->
            isolated(arrayOf(appJar.toUri().toURL()), parent).use { app ->
                val sharedApi = app.loadClass(api)
                assertSame(parent.loadClass(api), sharedApi)
                val services = ServiceLoader.load(sharedApi, app).toList()
                assertEquals(setOf(parentProvider, appProvider), services.map { it.javaClass.name }.toSet())
                assertTrue(services.all(sharedApi::isInstance))
            }
        }
    }

    private fun isolated(urls: Array<URL>, parent: ClassLoader): URLClassLoader {
        val type = Class.forName("${IsolatedHibernateRuntime::class.java.name}\$ApplicationLoader")
        val constructor = type.getDeclaredConstructor(Array<URL>::class.java, ClassLoader::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(urls, parent) as URLClassLoader
    }

    private fun jar(name: String, entries: Map<String, ByteArray>): Path = directory.resolve(name).also { path ->
        JarOutputStream(Files.newOutputStream(path)).use { output -> entries.forEach { (entry, bytes) ->
            output.putNextEntry(JarEntry(entry).apply { time = 0 })
            output.write(bytes); output.closeEntry()
        } }
    }
    private fun entry(name: String) = name.replace('.', '/') + ".class"
    private fun serviceInterface(name: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
            name.replace('.', '/'), null, "java/lang/Object", null)
        visitEnd()
    }.toByteArray()
    private fun provider(name: String, api: String): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", arrayOf(api.replace('.', '/')))
        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN); visitMaxs(1, 1); visitEnd()
        }
        visitEnd()
    }.toByteArray()
}
