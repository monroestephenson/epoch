package dev.epoch.cli

import dev.epoch.hibernate.ExtractionRequest
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

enum class DescriptorFormat { QUARKUS_PROPERTIES, PERSISTENCE_XML }

data class DescriptorOptions(
    val classpath: List<String>,
    val applicationVersion: String = "unspecified",
    val profile: String? = null,
    val definitions: Map<String, String> = emptyMap(),
    val repositoryPackages: List<String> = emptyList(),
    val selectedUnits: Set<String> = emptySet(),
)

data class DescriptorDiagnostic(val unit: String?, val key: String, val message: String)
data class UnitDescriptor(val name: String, val request: ExtractionRequest, val configurationComplete: Boolean)
data class DescriptorGeneration(
    val format: DescriptorFormat,
    val profile: String?,
    val units: List<UnitDescriptor>,
    val diagnostics: List<DescriptorDiagnostic> = emptyList(),
)

/** Reads a bounded configuration subset without starting the application or copying connection settings. */
object ConfigurationDescriptors {
    private const val ORM = "quarkus.hibernate-orm."
    private const val MAX_EXPANSION = 65536
    private val binaryName = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
    private val profileName = Regex("[A-Za-z0-9_-]+")
    private val placeholderKey = Regex("[A-Za-z0-9_.-]+")
    private val secretKey = Regex("(?:^|[._-])(?:password|username|user|secret|token|credential|credentials|url|datasource|connection)(?:[._-]|$)", RegexOption.IGNORE_CASE)
    private val safeHibernate = setOf(
        "hibernate.default_schema", "hibernate.default_catalog", "hibernate.physical_naming_strategy", "hibernate.implicit_naming_strategy",
        "hibernate.type.preferred_uuid_jdbc_type", "hibernate.type.preferred_boolean_jdbc_type",
        "hibernate.type.preferred_duration_jdbc_type", "hibernate.type.preferred_instant_jdbc_type",
        "hibernate.timezone.default_storage", "hibernate.jdbc.time_zone", "hibernate.jdbc.batch_size",
        "hibernate.jdbc.fetch_size", "hibernate.default_batch_fetch_size", "hibernate.query.in_clause_parameter_padding",
        "hibernate.order_inserts", "hibernate.order_updates", "hibernate.query.startup_check",
    )
    private val quarkusProperties = mapOf(
        "database.default-schema" to "hibernate.default_schema", "database.default-catalog" to "hibernate.default_catalog",
        "physical-naming-strategy" to "hibernate.physical_naming_strategy", "implicit-naming-strategy" to "hibernate.implicit_naming_strategy",
        "jdbc.statement-batch-size" to "hibernate.jdbc.batch_size", "jdbc.statement-fetch-size" to "hibernate.jdbc.fetch_size",
        "fetch.batch-size" to "hibernate.default_batch_fetch_size", "query.in-clause-parameter-padding" to "hibernate.query.in_clause_parameter_padding",
    )
    private val defaultRoots = setOf("packages", "physical-naming-strategy", "implicit-naming-strategy", "database", "jdbc", "fetch", "query",
        "log", "statistics", "metrics", "cache", "second-level-caching-enabled", "schema-management", "unsupported-properties", "mapping-files",
        "dialect", "active", "enabled", "datasource", "validate-in-dev-mode", "multitenant", "request-scoped", "flush", "persistence-xml")

    fun quarkusProperties(source: Path, options: DescriptorOptions): DescriptorGeneration {
        val context = Context(options)
        val raw = readProperties(source)
        val selectedProfile = options.profile
        require(selectedProfile == null || profileName.matches(selectedProfile)) { "Select one simple profile name." }
        for (key in raw.keys.filter { it.startsWith('%') }) {
            val split = key.indexOf('.')
            if (split <= 1) {
                require(!key.contains(ORM)) { "Malformed ORM profile property key." }
                continue
            }
            if (key.substring(split + 1).startsWith(ORM)) {
                require(profileName.matches(key.substring(1, split))) { "Profile groups are unsupported for ORM configuration." }
                require(selectedProfile != null) { "Profile-dependent ORM configuration requires an explicit --profile." }
            }
        }
        val active = raw.filterKeys { !it.startsWith('%') }.toMutableMap()
        selectedProfile?.let { profile -> raw.filterKeys { it.startsWith("%$profile.") }.forEach { (key, value) ->
            active[key.removePrefix("%$profile.")] = value
        } }
        for (key in listOf("quarkus.config.locations", "quarkus.config.profile.parent", "smallrye.config.profile.parent")) {
            require(key !in active) { "Unsupported configuration inventory setting: $key" }
        }
        val resolver = Resolver(active, options.definitions)
        // Build-time enablement applies to all units; it is not a default-unit mapping setting.
        val enabledKey = "${ORM}enabled"
        if (enabledKey in active) {
            require(active.getValue(enabledKey).size == 1) { "Duplicate active property: $enabledKey" }
            when (resolver.value(enabledKey).lowercase(java.util.Locale.ROOT)) {
                "true" -> Unit
                "false" -> error("Hibernate ORM is disabled by $enabledKey; no persistence-unit descriptors can be generated.")
                else -> error("Expected a boolean global ORM control: $enabledKey")
            }
        }
        val grouped = sortedMapOf<String, MutableMap<String, String>>()
        active.keys.filter { it.startsWith(ORM) && it != enabledKey }.sorted().forEach { key ->
            require(active.getValue(key).size == 1) { "Duplicate active property: $key" }
            val (unit, setting) = unitSetting(key.removePrefix(ORM))
            require(grouped.getOrPut(unit) { sortedMapOf() }.put(setting, key) == null) {
                "Duplicate effective ORM setting in unit '$unit': $setting"
            }
        }
        // Global runtime controls do not themselves declare a default persistence unit.
        if (grouped["default"]?.keys?.all(::ignoredQuarkus) == true) grouped.remove("default")
        val names = selectUnits(grouped.keys, options.selectedUnits)
        val units = names.map { name ->
            val settings = grouped.getValue(name)
            val packagesKey = settings["packages"] ?: error("Persistence unit '$name' requires explicit packages.")
            val packages = packages(resolver.value(packagesKey), packagesKey)
            val mapped = sortedMapOf<String, String>()
            val unresolved = sortedMapOf<String, String>()
            settings.forEach { (setting, key) ->
                when {
                    setting == "packages" -> Unit
                    setting in quarkusProperties -> mapped[quarkusProperties.getValue(setting)] = resolver.value(key)
                    ignoredQuarkus(setting) -> Unit
                    setting.startsWith("unsupported-properties.") -> {
                        val hibernateKey = setting.removePrefix("unsupported-properties.").removeSurrounding("\"")
                        when {
                            ignoredHibernate(hibernateKey) -> Unit
                            hibernateKey in safeHibernate -> mapped[hibernateKey] = resolver.value(key)
                            else -> context.unsupported(name, key, "Unsupported Hibernate mapping or bootstrap setting.", unresolved)
                        }
                    }
                    else -> context.unsupported(name, key, "ORM configuration is outside the supported mapping subset.", unresolved)
                }
            }
            context.unit(name, emptyList(), packages, mapped, unresolved)
        }
        return context.result(DescriptorFormat.QUARKUS_PROPERTIES, units)
    }

    fun persistenceXml(source: Path, options: DescriptorOptions): DescriptorGeneration {
        require(options.profile == null) { "--profile applies only to Quarkus properties." }
        val context = Context(options)
        val root = xmlRoot(source)
        require(root.localName == "persistence") { "Expected a persistence XML document." }
        val versions = when (root.namespaceURI) {
            "https://jakarta.ee/xml/ns/persistence" -> setOf("3.0", "3.1", "3.2")
            "http://xmlns.jcp.org/xml/ns/persistence" -> setOf("2.1", "2.2")
            "http://java.sun.com/xml/ns/persistence" -> setOf("1.0", "2.0")
            else -> error("Unsupported persistence XML namespace.")
        }
        require(root.getAttribute("version") in versions) { "Unsupported persistence XML version." }
        val declared = sortedMapOf<String, Element>()
        children(root).forEach { unit ->
            require(unit.localName == "persistence-unit") { "Unsupported persistence XML root element." }
            val name = unitName(unit.getAttribute("name"))
            require(declared.put(name, unit) == null) { "Duplicate persistence unit name: $name" }
        }
        val units = selectUnits(declared.keys, options.selectedUnits).map { name ->
            val elements = children(declared.getValue(name)).groupBy { it.localName }
            val unresolved = sortedMapOf<String, String>()
            fun scalar(key: String): String? {
                val values = elements[key].orEmpty()
                require(values.size <= 1) { "Repeated persistence XML setting in unit '$name': $key" }
                require(values.all { children(it).isEmpty() }) { "Nested XML scalar setting in unit '$name': $key" }
                return values.singleOrNull()?.textContent?.trim()
            }
            require(scalar("provider").let { it == null || it == "org.hibernate.jpa.HibernatePersistenceProvider" }) {
                "Persistence unit '$name' uses an unsupported provider."
            }
            val entities = elements["class"].orEmpty().map { element ->
                require(children(element).isEmpty()) { "Nested XML class entry in persistence unit '$name'." }
                element.textContent.trim().also { require(binaryName.matches(it)) { "Invalid class name in persistence unit '$name'." } }
            }.distinct().sorted()
            require(entities.isNotEmpty()) { "Persistence unit '$name' requires explicit annotated class entries." }
            val exclude = scalar("exclude-unlisted-classes")
            require(exclude == null || exclude in setOf("", "true", "false")) { "Invalid exclude-unlisted-classes setting in unit '$name'." }
            if (exclude != "true" && exclude != "") context.unsupported(name, "exclude-unlisted-classes",
                "Archive-root discovery is not reconstructed; explicit class entries are not an exhaustive inventory.", unresolved)
            val properties = sortedMapOf<String, List<String>>()
            require(elements["properties"].orEmpty().size <= 1) { "Repeated properties element in persistence unit '$name'." }
            elements["properties"].orEmpty().flatMap(::children).forEach { property ->
                require(property.localName == "property") { "Unsupported persistence XML property element." }
                require(children(property).isEmpty()) { "Nested XML property entry in persistence unit '$name'." }
                val key = property.getAttribute("name")
                require(key.isNotBlank()) { "Empty property name in persistence unit '$name'." }
                require(properties.put(key, listOf(property.getAttribute("value"))) == null) { "Duplicate property in unit '$name': $key" }
            }
            val resolver = Resolver(properties, options.definitions)
            val mapped = sortedMapOf<String, String>()
            properties.keys.forEach { key ->
                when {
                    ignoredHibernate(key) -> Unit
                    key in safeHibernate -> mapped[key] = resolver.value(key)
                    else -> context.unsupported(name, key, "Persistence property is outside the supported mapping subset.", unresolved)
                }
            }
            val known = setOf("provider", "class", "exclude-unlisted-classes", "properties", "description", "jta-data-source",
                "non-jta-data-source", "validation-mode", "shared-cache-mode")
            elements.keys.filter { it !in known }.sorted().forEach { key ->
                context.unsupported(name, key, "XML mappings, referenced archives or other provider metadata are not reconstructed.", unresolved)
            }
            context.unit(name, entities, emptyList(), mapped, unresolved)
        }
        return context.result(DescriptorFormat.PERSISTENCE_XML, units)
    }

    private class Context(private val options: DescriptorOptions) {
        val diagnostics = mutableListOf<DescriptorDiagnostic>()
        private var implicitXml = false
        private val classpath = options.classpath.mapIndexedNotNull { index, entry ->
            val path = Path.of(entry).toAbsolutePath().normalize()
            when {
                !Files.exists(path) -> { diagnostics += DescriptorDiagnostic(null, "classpath[$index]", "Missing compiled root was skipped."); null }
                Files.isDirectory(path) -> { implicitXml = implicitXml || Files.isRegularFile(path.resolve("META-INF/orm.xml")); path.toString() }
                Files.isRegularFile(path) -> {
                    try { ZipFile(path.toFile()).use { implicitXml = implicitXml || it.getEntry("META-INF/orm.xml") != null } }
                    catch (_: Exception) { error("Classpath entry $index is not a readable archive.") }
                    path.toString()
                }
                else -> error("Classpath entry $index is not a compiled directory or archive.")
            }
        }.distinct()
        init {
            require(classpath.isNotEmpty()) { "At least one usable compiled classpath root is required." }
            require(options.repositoryPackages.all(binaryName::matches)) { "Invalid repository package selector." }
            options.definitions.keys.forEach { require(placeholderKey.matches(it) && !sensitive(it)) { "Unsupported substitution key." } }
        }
        fun unsupported(unit: String, key: String, reason: String, unresolved: MutableMap<String, String>) {
            unresolved["configuration/$unit/$key"] = reason
            diagnostics += DescriptorDiagnostic(unit, key, reason)
        }
        fun unit(name: String, entities: List<String>, packages: List<String>, mapped: Map<String, String>, unresolved: MutableMap<String, String>): UnitDescriptor {
            if (implicitXml) unsupported(name, "META-INF/orm.xml", "A supplied compiled root contains default ORM XML; annotated extraction does not apply it.", unresolved)
            val physical = mapped["hibernate.physical_naming_strategy"]
            val implicit = mapped["hibernate.implicit_naming_strategy"]
            require(listOfNotNull(physical, implicit).all(binaryName::matches)) { "Invalid naming strategy class in persistence unit '$name'." }
            require(mapped.values.none { it.isBlank() }) { "Empty mapping setting in persistence unit '$name'." }
            return UnitDescriptor(name, ExtractionRequest(entityClasses = entities, classpath = classpath,
                applicationVersion = options.applicationVersion, scanPackages = packages,
                physicalNamingStrategy = physical, implicitNamingStrategy = implicit,
                hibernateProperties = mapped.filterKeys { it !in setOf("hibernate.physical_naming_strategy", "hibernate.implicit_naming_strategy") }.toSortedMap(),
                unresolvedDynamicQueries = unresolved.toSortedMap(), repositoryPackages = options.repositoryPackages.distinct().sorted()), unresolved.isEmpty())
        }
        fun result(format: DescriptorFormat, units: List<UnitDescriptor>) = DescriptorGeneration(format, options.profile, units,
            diagnostics.distinct().sortedWith(compareBy({ it.unit ?: "" }, { it.key }, { it.message })))
    }

    private class Resolver(private val properties: Map<String, List<String>>, private val definitions: Map<String, String>) {
        fun value(key: String): String = resolve(key, key, linkedSetOf(), useDefinition = false)
        private fun resolve(key: String, requested: String, chain: Set<String>, useDefinition: Boolean = true): String {
            require(key !in chain && chain.size < 32) { "Cyclic or excessive substitution in mapping setting: $requested" }
            val entries = properties[key]
            require(entries == null || entries.size == 1) { "Duplicate referenced property in mapping setting: $requested" }
            val input = definitions[key].takeIf { useDefinition } ?: entries?.singleOrNull() ?: error("Unresolved mapping setting: $requested")
            require(input.length <= MAX_EXPANSION) { "Expanded mapping setting is too large: $requested" }
            require(!input.contains("::") && !Regex("\\$\\{[^}]*\\$\\{").containsMatchIn(input)) { "Unsupported expression in mapping setting: $requested" }
            val pattern = Regex("\\$\\{([^{}]+)}")
            val output = StringBuilder(input.length)
            fun appendBounded(value: String, start: Int = 0, end: Int = value.length) {
                require(end - start <= MAX_EXPANSION - output.length) { "Expanded mapping setting is too large: $requested" }
                output.append(value, start, end)
            }
            var position = 0
            for (match in pattern.findAll(input)) {
                appendBounded(input, position, match.range.first)
                val parts = match.groupValues[1].split(':', limit = 2)
                val reference = parts[0]
                require(placeholderKey.matches(reference) && !sensitive(reference) && reference !in setOf("env", "sys") &&
                    !reference.startsWith("env.") && !reference.startsWith("sys.")) { "Unsupported or sensitive substitution in mapping setting: $requested" }
                val replacement = if (reference in definitions || reference in properties) resolve(reference, requested, chain + key)
                    else parts.getOrNull(1) ?: error("Unresolved substitution in mapping setting: $requested")
                appendBounded(replacement)
                position = match.range.last + 1
            }
            appendBounded(input, position, input.length)
            require(!output.contains("${'$'}{")) { "Unsupported expression in mapping setting: $requested" }
            return output.toString().trim()
        }
    }

    private fun readProperties(source: Path): Map<String, List<String>> {
        require(Files.size(source) <= 4 * 1024 * 1024) { "Properties input exceeds the supported size." }
        val values = linkedMapOf<String, MutableList<String>>()
        val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                values.getOrPut(key.toString()) { mutableListOf() } += value.toString()
                return super.put(key, value)
            }
        }
        try { Files.newBufferedReader(source, StandardCharsets.UTF_8).use(properties::load) }
        catch (_: IllegalArgumentException) { error("Malformed properties input.") }
        return values
    }

    private fun unitSetting(suffix: String): Pair<String, String> {
        if (suffix.startsWith('"')) {
            val close = suffix.indexOf('"', 1)
            require(close > 1 && suffix.getOrNull(close + 1) == '.') { "Malformed quoted persistence-unit property key." }
            val unit = unitName(suffix.substring(1, close))
            require(unit != "default") { "The named unit 'default' conflicts with the unnamed unit identifier." }
            return unit to suffix.substring(close + 2)
        }
        val first = suffix.substringBefore('.')
        val rest = suffix.substringAfter('.', "")
        if (first !in defaultRoots && rest.substringBefore('.') in defaultRoots) {
            require(first != "default") { "The named unit 'default' conflicts with the unnamed unit identifier." }
            return unitName(first) to rest
        }
        return "default" to suffix
    }
    private fun unitName(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }) { "Invalid persistence unit name." }
        return value
    }
    private fun selectUnits(available: Set<String>, selected: Set<String>): List<String> {
        require(available.isNotEmpty()) { "No supported persistence-unit configuration was found." }
        require(selected.all { it in available }) { "A selected persistence unit was not declared by this configuration." }
        return (selected.ifEmpty { available }).sorted()
    }
    private fun packages(value: String, key: String): List<String> {
        val packages = value.split(',').map(String::trim)
        require(packages.isNotEmpty() && packages.all(binaryName::matches)) { "Invalid or empty package selector: $key" }
        return packages.distinct().sorted()
    }
    private fun sensitive(key: String): Boolean = key.startsWith("quarkus.datasource.") || secretKey.containsMatchIn(key)
    private fun ignoredQuarkus(key: String): Boolean = key == "datasource" || key == "second-level-caching-enabled" || key == "validate-in-dev-mode" ||
        listOf("log.", "metrics.", "statistics", "cache.", "database.generation", "schema-management.").any(key::startsWith) || sensitive(key)
    private fun ignoredHibernate(key: String): Boolean = sensitive(key) || key.startsWith("hibernate.connection.") || key.startsWith("hibernate.hbm2ddl.") ||
        key.startsWith("jakarta.persistence.jdbc.") || key.startsWith("javax.persistence.jdbc.") || key.contains("schema-generation") ||
        key in setOf("jakarta.persistence.validation.mode", "javax.persistence.validation.mode", "hibernate.show_sql", "hibernate.format_sql", "hibernate.generate_statistics", "hibernate.jdbc.log.errors")

    private fun xmlRoot(source: Path): Element {
        require(Files.size(source) <= 8 * 1024 * 1024) { "Persistence XML exceeds the supported size." }
        try {
            val factory = DocumentBuilderFactory.newDefaultInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External XML resolution is not permitted.") }
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(exception: SAXParseException) = Unit
                override fun error(exception: SAXParseException): Unit = throw SAXException("Invalid persistence XML.")
                override fun fatalError(exception: SAXParseException): Unit = throw SAXException("Invalid persistence XML.")
            })
            val root = Files.newInputStream(source).use { builder.parse(it).documentElement }
            val elements = root.getElementsByTagName("*")
            require((0 until elements.length).all { elements.item(it).namespaceURI == root.namespaceURI }) {
                "Foreign XML elements, including XInclude, are unsupported."
            }
            return root
        } catch (_: Exception) { error("Cannot parse persistence XML safely; malformed XML and DTD/entity input are unsupported.") }
    }
    private fun children(parent: Element): List<Element> = (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }.onEach {
        require(it.namespaceURI == parent.namespaceURI) { "Foreign XML elements, including XInclude, are unsupported." }
    }
}
