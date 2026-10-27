package dev.epoch.hibernate

import org.jboss.jandex.DotName
import org.jboss.jandex.Indexer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarFile
import java.util.jar.Manifest

internal object ClasspathInspection {
    private const val HIBERNATE_CLASS = "org/hibernate/Version.class"
    private val versionProperties = listOf(
        "META-INF/maven/org.hibernate.orm/hibernate-core/pom.properties",
        "META-INF/maven/org.hibernate/hibernate-core/pom.properties",
    )

    fun hibernateVersions(classpath: List<String>): Map<String, String> = buildMap {
        for (entry in classpath.distinct().sorted()) {
            val path = Path.of(entry)
            if (!Files.exists(path)) continue
            if (Files.isDirectory(path)) {
                if (!Files.exists(path.resolve(HIBERNATE_CLASS))) continue
                val version = versionProperties.firstNotNullOfOrNull { resource ->
                    path.resolve(resource).takeIf(Files::isRegularFile)?.let { file ->
                        Files.newInputStream(file).use { Properties().apply { load(it) }.getProperty("version") }
                    }
                } ?: path.resolve("META-INF/MANIFEST.MF").takeIf(Files::isRegularFile)?.let { file ->
                    Files.newInputStream(file).use { Manifest(it).mainAttributes.getValue("Implementation-Version") }
                }
                put(entry, version ?: "unidentifiable")
            } else JarFile(path.toFile()).use { jar ->
                if (jar.getJarEntry(HIBERNATE_CLASS) != null) {
                    val version = versionProperties.firstNotNullOfOrNull { resource -> jar.getJarEntry(resource)?.let { e ->
                        jar.getInputStream(e).use { Properties().apply { load(it) }.getProperty("version") }
                    } } ?: jar.manifest?.mainAttributes?.getValue("Implementation-Version")
                    put(entry, version ?: "unidentifiable")
                }
            }
        }
    }

    /** Inside the isolated worker, prove the resolved runtime is exactly the supplied one. */
    fun checkHibernateVersion(classpath: List<String>) {
        val loaded = org.hibernate.Version.getVersionString()
        hibernateVersions(classpath).forEach { (path, version) ->
            require(version == loaded) { "Hibernate version mismatch in $path: application supplies $version; loaded runtime is $loaded" }
        }
    }

    /** Inspect bytecode annotations only: scanning does not load or initialize application classes. */
    fun entities(classpath: List<String>, packages: List<String>): List<String> {
        if (packages.isEmpty()) return emptyList()
        require(classpath.isNotEmpty()) { "scanPackages requires explicit classpath directories or JARs" }
        val prefixes = packages.distinct().sorted().map { name ->
            require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "Invalid scan package: $name" }
            name.replace('.', '/') + "/"
        }
        val indexer = Indexer()
        for (entry in classpath.distinct().sorted()) {
            val path = Path.of(entry)
            if (!Files.exists(path)) continue
            if (Files.isDirectory(path)) {
                val files = sortedSetOf<Path>()
                for (prefix in prefixes) {
                    val directory = path.resolve(prefix)
                    if (Files.isDirectory(directory)) Files.walk(directory).use { paths ->
                        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach(files::add)
                    }
                }
                for (file in files) Files.newInputStream(file).use { indexer.index(it) }
            } else JarFile(path.toFile()).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") && prefixes.any(it.name::startsWith) }
                    .sortedBy { it.name }.forEach { e -> jar.getInputStream(e).use { indexer.index(it) } }
            }
        }
        val entity = DotName.createSimple("jakarta.persistence.Entity")
        return indexer.complete().knownClasses.filter { it.declaredAnnotation(entity) != null }.map { it.name().toString() }.sorted()
    }
}
