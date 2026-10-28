package dev.epoch.hibernate

import dev.epoch.core.DatabaseContract
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** Runs the API-compatible, tested adapter against the application's own Hibernate and JPA runtime. */
internal object IsolatedHibernateRuntime {
    val testedVersions = setOf("6.6.13.Final", "7.0.10.Final", "7.1.4.Final", "7.4.9.Final")

    fun extractIfRequired(request: ExtractionRequest): DatabaseContract? {
        // Request/model classes deliberately cross the loader boundary; Hibernate classes never do.
        if (HibernateExtractor::class.java.classLoader !== ExtractionRequest::class.java.classLoader) return null
        val supplied = ClasspathInspection.hibernateVersions(request.classpath)
        if (supplied.isEmpty()) return null
        require(supplied.values.distinct().size == 1) { "Classpath contains conflicting Hibernate runtimes: $supplied" }
        val version = supplied.values.singleOrNull() ?: supplied.values.first()
        require(version.startsWith("6.6.") || version.startsWith("7.")) {
            "Hibernate version mismatch: application supplies $version at ${supplied.keys.joinToString()}; " +
                "adapters target Hibernate 6.6 and 7.x (regression runtimes: ${testedVersions.sorted().joinToString()}). Supply a supported application runtime; extraction never silently replaces it."
        }
        val urls = (request.classpath.filter { Files.exists(Path.of(it)) }.map { Path.of(it).toUri().toURL() } +
            HibernateExtractor::class.java.protectionDomain.codeSource.location).distinct()
        ApplicationLoader(urls.toTypedArray(), HibernateExtractor::class.java.classLoader).use { loader ->
            val previous = Thread.currentThread().contextClassLoader
            try {
                Thread.currentThread().contextClassLoader = loader
                val actualVersion = loader.loadClass("org.hibernate.Version").getMethod("getVersionString").invoke(null)
                require(actualVersion == version) { "Hibernate version mismatch in ${supplied.keys.joinToString()}: artifact declares $version but runtime identifies itself as $actualVersion" }
                val worker = loader.loadClass(HibernateExtractor::class.java.name)
                return worker.getMethod("extract", ExtractionRequest::class.java)
                    .invoke(worker.getConstructor().newInstance(), request) as DatabaseContract
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            } catch (failure: LinkageError) {
                throw IllegalArgumentException("Cannot link supplied Hibernate $version at ${supplied.keys.joinToString()}: " +
                    "the application runtime is incomplete or its SPI is incompatible. Include its full dependency classpath; " +
                    "bundled runtime ${org.hibernate.Version.getVersionString()} was not substituted.", failure)
            } finally { Thread.currentThread().contextClassLoader = previous }
        }
    }

    private class ApplicationLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return@synchronized it }
            val shared = name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.") ||
                name.startsWith("kotlin.") || name.startsWith("dev.epoch.core.") || name.startsWith("org.slf4j.") ||
                // The adapter owns ASM as a complete family. An app may supply only asm-core;
                // mixing its ClassVisitor with the parent's asm-tree ClassNode breaks every scan.
                name.startsWith("org.objectweb.asm.") ||
                name == ExtractionRequest::class.java.name || name == EntityOverride::class.java.name
            val type = if (shared) super.loadClass(name, false) else try { findClass(name) }
                catch (missing: ClassNotFoundException) {
                    // Never mix classes from bundled Hibernate/JPA into an incomplete application runtime.
                    if (name.startsWith("org.hibernate.") || name.startsWith("jakarta.persistence.")) throw missing
                    super.loadClass(name, false)
                }
            if (resolve) resolveClass(type)
            type
        }
    }
}
