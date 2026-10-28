package dev.epoch.hibernate

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile

/** Read the defining artifact, not a parent-first resource with the same class name. */
internal object ClassBytes {
    fun read(type: Class<*>): ByteArray? = try {
        val location = type.protectionDomain?.codeSource?.location
        if (location == null || location.protocol != "file") null else {
            val source = Path.of(location.toURI())
            val name = type.name.replace('.', '/') + ".class"
            if (Files.isDirectory(source)) {
                source.resolve(name).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
            } else if (Files.isRegularFile(source)) {
                JarFile(source.toFile(), true, ZipFile.OPEN_READ, Runtime.version()).use { jar ->
                    jar.getJarEntry(name)?.let { entry -> jar.getInputStream(entry).use { it.readAllBytes() } }
                }
            } else null
        }
    } catch (_: java.io.IOException) { null }
      catch (_: java.net.URISyntaxException) { null }
      catch (_: SecurityException) { null }
      catch (_: IllegalArgumentException) { null }
}
