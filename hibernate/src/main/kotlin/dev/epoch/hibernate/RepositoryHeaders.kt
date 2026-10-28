package dev.epoch.hibernate

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile

internal data class HeaderResult(val node: ClassNode?, val complete: Boolean, val reason: String? = null)
internal data class HierarchyResult(val nodes: Map<String, ClassNode>, val gaps: Set<String>) {
    val complete get() = gaps.isEmpty()
}
internal data class MethodDeclaration(val owner: String, val access: Int, val signature: String?)
internal data class MethodResolution(val declarations: Set<MethodDeclaration>, val gaps: Set<String>)
internal data class FieldDeclaration(val owner: String, val signature: String?)

/** Effective definitions, including unselected ancestor JARs. No application initializer is executed. */
internal class RepositoryHeaders(
    private val selected: Map<String, ClassNode>,
    private val classpath: List<Path>,
    private val loader: ClassLoader,
    /** Reuse the actual isolation policy, not a second hand-maintained prefix list. */
    private val parentOwned: (String) -> Boolean,
    private val failure: (String, String, Throwable?) -> Unit,
    private val maxExternalHeaders: Int = 4096,
    private val maxAncestryDepth: Int = 64,
) {
    private val cache = mutableMapOf<String, HeaderResult>()
    val unresolvedOwners = sortedSetOf<String>()
    private val ancestryCache = mutableMapOf<String, HierarchyResult>()

    fun read(name: String): HeaderResult {
        cache[name]?.let { return it }
        val binary = name.replace('/', '.')
        if (!parentOwned(binary)) selected[name]?.let { return HeaderResult(it, true) }
        if (cache.size >= maxExternalHeaders) return missing(name, "External header budget $maxExternalHeaders exceeded")
        return try {
            // ClassBytes uses the defining artifact, including the JVM-selected multi-release entry.
            // This avoids the old child-first-class / parent-first-resource identity mismatch.
            val type = try { Class.forName(binary, false, loader) }
                catch (_: ClassNotFoundException) { null }
                catch (error: LinkageError) {
                    // A missing transitive class must not hide an available ancestor header.
                    // The callback applies the existing fatal policy and records the located linkage gap.
                    failure("repository-header:$binary", "Ancestor cannot link; reading supplied header only", error)
                    null
                }
            val bytes = type?.let { ClassBytes.read(it) ?: it.module.takeIf { module -> module.isNamed }?.getResourceAsStream("$name.class")?.use { stream -> stream.readBytes() } }
                ?: if (!parentOwned(binary)) suppliedBytes(name) else null
            if (bytes == null) missing(name, "Effective ancestor/header is unavailable")
            else HeaderResult(ClassNode().also {
                ClassReader(bytes).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                require(it.name == name) { "Class entry declares ${it.name} instead of $name" }
            }, true).also { cache[name] = it }
        } catch (error: Throwable) {
            // Production must use the existing fatal/cancellation policy through diagnostics.capture.
            failure("repository-header:$binary", "Cannot inspect effective repository header", error)
            missing(name, "Effective header could not be resolved: ${error.javaClass.name}")
        }
    }

    private fun suppliedBytes(name: String): ByteArray? {
        for (root in classpath) {
            if (!Files.exists(root)) continue
            if (Files.isDirectory(root)) {
                val file = root.resolve("$name.class")
                if (Files.isRegularFile(file)) return Files.readAllBytes(file)
            } else if (Files.isRegularFile(root)) {
                JarFile(root.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jar ->
                    val entry = jar.getJarEntry("$name.class")
                    if (entry != null) return jar.getInputStream(entry).use { it.readBytes() }
                }
            }
        }
        return null
    }

    private fun missing(name: String, reason: String): HeaderResult {
        unresolvedOwners += name.replace('/', '.')
        return HeaderResult(null, false, reason).also { cache.putIfAbsent(name, it) }
    }

    fun hierarchy(start: String): HierarchyResult = ancestryCache.getOrPut(start) {
        val found = sortedMapOf<String, ClassNode>()
        val gaps = sortedSetOf<String>()
        val pending = ArrayDeque<Pair<String, List<String>>>()
        pending += start to emptyList()
        while (pending.isNotEmpty()) {
            val (name, path) = pending.removeFirst()
            if (name in path) { gaps += "Cyclic hierarchy at $name"; continue }
            if (path.size >= maxAncestryDepth) { gaps += "Hierarchy depth bound at $name"; continue }
            if (name in found) continue
            val result = read(name)
            val node = result.node
            if (node == null) { gaps += "$name: ${result.reason}"; continue }
            found[name] = node
            (listOfNotNull(node.superName) + node.interfaces.orEmpty()).distinct().forEach { pending += it to (path + name) }
        }
        HierarchyResult(found, gaps)
    }

    /** Nearest class declaration wins; interface declarations require an unambiguous most-specific result. */
    fun declaration(owner: String, name: String, descriptor: String): MethodResolution {
        val graph = hierarchy(owner)
        val gaps = graph.gaps.toMutableSet()
        var current: String? = owner
        val classes = mutableSetOf<String>()
        while (current != null && classes.add(current)) {
            val node = graph.nodes[current] ?: break
            if (node.access and Opcodes.ACC_INTERFACE == 0) {
                node.methods.filter { it.name == name && it.desc == descriptor }.takeIf { it.isNotEmpty() }?.let { methods ->
                    return MethodResolution(methods.map { MethodDeclaration(node.name, it.access, it.signature) }.toSet(), gaps)
                }
            }
            current = node.superName
        }
        val candidates = graph.nodes.values.filter { it.access and Opcodes.ACC_INTERFACE != 0 }.mapNotNull { node ->
            node.methods.firstOrNull { it.name == name && it.desc == descriptor }?.let { MethodDeclaration(node.name, it.access, it.signature) }
        }
        val mostSpecific = candidates.filter { candidate -> candidates.none { other ->
            other.owner != candidate.owner && candidate.owner in hierarchy(other.owner).nodes
        } }.toSet()
        if (mostSpecific.size > 1) gaps += "Conflicting interface declarations for $owner.$name$descriptor"
        if (mostSpecific.isEmpty()) gaps += "No method declaration for $owner.$name$descriptor"
        return MethodResolution(mostSpecific, gaps)
    }

    /** Follow JVM field lookup; lexical ancestor order can choose a hidden field's wrong generic type. */
    fun fieldDeclaration(owner: String, name: String, descriptor: String): FieldDeclaration? {
        val graph = hierarchy(owner)
        if (!graph.complete) return null
        fun resolve(current: String, path: Set<String>): Set<FieldDeclaration> {
            if (current in path) return emptySet()
            val node = graph.nodes[current] ?: return emptySet()
            val own = node.fields.filter { it.name == name && it.desc == descriptor }
            if (own.isNotEmpty()) return own.map { FieldDeclaration(node.name, it.signature) }.toSet()
            val nestedPath = path + current
            val interfaces = node.interfaces.orEmpty().flatMap { resolve(it, nestedPath) }.toSet()
            if (interfaces.isNotEmpty()) return interfaces
            return node.superName?.let { resolve(it, nestedPath) }.orEmpty()
        }
        return resolve(owner, emptySet()).singleOrNull()
    }
}
