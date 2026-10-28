package dev.epoch.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Callable

data class DescriptorManifestUnit(val name: String, val descriptor: String, val configurationComplete: Boolean, val unresolvedSettings: Int)
data class DescriptorManifest(
    val schemaVersion: Int = 1,
    val format: DescriptorFormat,
    val profile: String?,
    val units: List<DescriptorManifestUnit>,
    val diagnostics: List<DescriptorDiagnostic>,
    val limitations: List<String> = listOf(
        "Configuration completeness describes only the supported mapping configuration subset; it does not establish query coverage.",
        "Persistence-unit configuration does not establish repository ownership or shared physical database topology.",
    ),
)

@Command(name = "descriptors", mixinStandardHelpOptions = true,
    description = ["Generate one extraction descriptor per persistence unit from supported Quarkus properties or persistence.xml.",
        "Reads mapping configuration without booting the application; connection credentials and datasource URLs are never copied."])
class Descriptors : Callable<Int> {
    @Option(names = ["--quarkus-properties"], description = ["UTF-8 application.properties input; choose exactly one source"])
    var quarkusProperties: Path? = null
    @Option(names = ["--persistence-xml"], description = ["Standard persistence.xml with explicit entity classes; choose exactly one source"])
    var persistenceXml: Path? = null
    @Option(names = ["--classpath"], required = true, description = ["Compiled application directory or dependency JAR; repeat; paths are relative to the invocation directory"])
    var classpath: List<Path> = emptyList()
    @Option(names = ["--profile"], description = ["One explicit Quarkus profile; required when ORM settings have profile-specific variants"])
    var profile: String? = null
    @Option(names = ["--define"], description = ["Explicit non-secret KEY=VALUE substitution; repeat; environment variables are not read"])
    var definitions: List<String> = emptyList()
    @Option(names = ["--repository-package"], description = ["Repository/DAO package selector independent of entity packages; repeat"])
    var repositoryPackages: List<String> = emptyList()
    @Option(names = ["--unit"], description = ["Declared persistence-unit name to include; repeat; default includes every configured unit"])
    var units: List<String> = emptyList()
    @Option(names = ["--application-version"], defaultValue = "unspecified", description = ["Application version recorded in every generated descriptor"])
    var applicationVersion = "unspecified"
    @Option(names = ["--output-directory"], required = true, description = ["New directory for the complete descriptor set and manifest; existing destinations are never overwritten"])
    lateinit var outputDirectory: Path

    override fun call(): Int {
        require((quarkusProperties == null) != (persistenceXml == null)) { "Choose exactly one of --quarkus-properties and --persistence-xml." }
        val supplied = sortedMapOf<String, String>()
        definitions.forEach { definition ->
            val split = definition.indexOf('=')
            require(split > 0) { "Each --define must use non-secret KEY=VALUE syntax." }
            val key = definition.substring(0, split)
            require(supplied.put(key, definition.substring(split + 1)) == null) { "Duplicate --define key: $key" }
        }
        val options = DescriptorOptions(classpath.map { it.toAbsolutePath().normalize().toString() }, applicationVersion,
            profile, supplied, repositoryPackages, units.toSet())
        val generation = quarkusProperties?.let { ConfigurationDescriptors.quarkusProperties(it, options) }
            ?: ConfigurationDescriptors.persistenceXml(requireNotNull(persistenceXml), options)
        val manifest = publishDescriptors(generation, outputDirectory)
        println("Generated ${manifest.units.size} persistence-unit descriptors in $outputDirectory")
        manifest.units.forEach { println("  ${it.name}: ${it.descriptor}; configuration ${if (it.configurationComplete) "supported" else "incomplete"}") }
        manifest.diagnostics.forEach { println("  ${it.unit ?: "inputs"} / ${it.key}: ${it.message}") }
        return 0
    }
}

/** Validate the whole generation before publishing a staged directory; no partial manifest is exposed. */
fun publishDescriptors(generation: DescriptorGeneration, outputDirectory: Path): DescriptorManifest {
    require(generation.units.isNotEmpty()) { "No persistence-unit descriptors were generated." }
    require(generation.units.map { it.name }.distinct().size == generation.units.size) { "Duplicate generated persistence-unit names." }
    val destination = outputDirectory.toAbsolutePath().normalize()
    require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Descriptor output directory already exists; choose a new directory." }
    val entries = generation.units.sortedBy { it.name }.mapIndexed { index, unit ->
        require(unit.name.isNotBlank()) { "Generated persistence-unit name is empty." }
        val slug = unit.name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "unit" }
        val digest = MessageDigest.getInstance("SHA-256").digest(unit.name.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        val filename = "${(index + 1).toString().padStart(3, '0')}-$slug-$digest.json"
        unit to DescriptorManifestUnit(unit.name, filename, unit.configurationComplete, unit.request.unresolvedDynamicQueries.size)
    }
    require(entries.map { it.second.descriptor }.distinct().size == entries.size) { "Generated descriptor filenames collide." }
    val manifest = DescriptorManifest(format = generation.format, profile = generation.profile, units = entries.map { it.second },
        diagnostics = generation.diagnostics.sortedWith(compareBy({ it.unit ?: "" }, { it.key }, { it.message })))
    val parent = requireNotNull(destination.parent) { "Descriptor output directory must have a parent." }
    Files.createDirectories(parent)
    val stage = Files.createTempDirectory(parent, ".epoch-descriptors-")
    try {
        entries.forEach { (unit, entry) -> json.writeValue(stage.resolve(entry.descriptor).toFile(), unit.request) }
        json.writeValue(stage.resolve("manifest.json").toFile(), manifest)
        // The sibling staging directory keeps this on one filesystem. No REPLACE_EXISTING
        // is requested, so an existing user destination remains untouched.
        Files.move(stage, destination)
        return manifest
    } finally {
        if (Files.exists(stage)) Files.walk(stage).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
