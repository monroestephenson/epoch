package dev.epoch.cli

import dev.epoch.cli.descriptorfixture.mainunit.MainUnitEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ConfigurationDescriptorsTest {
    @TempDir lateinit var directory: Path

    private val primaryPackage = MainUnitEntity::class.java.packageName
    private val classpath get() = listOf(Path.of(MainUnitEntity::class.java.protectionDomain.codeSource.location.toURI()).toString())
    private fun options() = DescriptorOptions(classpath = classpath)
    private fun properties(text: String): Path = directory.resolve("application.properties").also { it.writeText(text.trimIndent()) }
    private fun xml(body: String, namespace: String = "https://jakarta.ee/xml/ns/persistence", version: String = "3.2"): Path =
        directory.resolve("persistence.xml").also { it.writeText("""
            <persistence xmlns="$namespace" version="$version">$body</persistence>
        """.trimIndent()) }
    private fun unit(extra: String = "", exclude: String = "true") = """
        <persistence-unit name="main">
          <class>${MainUnitEntity::class.java.name}</class>
          <exclude-unlisted-classes>$exclude</exclude-unlisted-classes>
          $extra
        </persistence-unit>
    """.trimIndent()

    @Test fun `selected profile overrides base values and excludes other profile units without implicit inheritance`() {
        val source = properties("""
            quarkus.hibernate-orm.packages=$primaryPackage
            quarkus.hibernate-orm.database.default-schema=base_schema
            %prod.quarkus.hibernate-orm.database.default-schema=prod_schema
            %prod.quarkus.hibernate-orm."audit.history".packages=example.audit
            %dev.quarkus.hibernate-orm."development".packages=example.development
            quarkus.datasource.password=${'$'}{UNAVAILABLE_PRODUCTION_PASSWORD}
        """)
        assertThrows(IllegalArgumentException::class.java) { ConfigurationDescriptors.quarkusProperties(source, options()) }
        val generated = ConfigurationDescriptors.quarkusProperties(source, options().copy(profile = "prod"))
        assertEquals(listOf("audit.history", "default"), generated.units.map { it.name })
        val primary = generated.units.single { it.name == "default" }
        val audit = generated.units.single { it.name == "audit.history" }
        assertEquals("prod_schema", primary.request.hibernateProperties["hibernate.default_schema"])
        assertFalse("hibernate.default_schema" in audit.request.hibernateProperties,
            "Default persistence-unit mapping settings must not be copied into every named unit")
        assertEquals(listOf("example.audit"), audit.request.scanPackages)
        assertTrue(generated.units.all { it.configurationComplete })
    }

    @Test fun `global enablement does not invent a default unit for two named persistence units`() {
        val generated = ConfigurationDescriptors.quarkusProperties(properties("""
            quarkus.hibernate-orm.enabled=false
            %prod.quarkus.hibernate-orm.enabled=${'$'}{orm.enabled:true}
            quarkus.hibernate-orm."main".packages=$primaryPackage
            quarkus.hibernate-orm."audit".packages=example.audit
        """), options().copy(profile = "prod"))
        assertEquals(listOf("audit", "main"), generated.units.map { it.name })
        assertTrue(generated.units.all { it.configurationComplete })
        assertTrue(generated.units.all { it.request.hibernateProperties.isEmpty() })
        assertTrue(generated.diagnostics.isEmpty())
    }

    @Test fun `disabled global ORM gives an explicit clue and invalid or duplicate enablement fails`() {
        val source = properties("""
            quarkus.hibernate-orm.enabled=true
            %prod.quarkus.hibernate-orm.enabled=false
            quarkus.hibernate-orm."main".packages=$primaryPackage
            quarkus.hibernate-orm."audit".packages=example.audit
        """)
        val disabled = assertThrows(IllegalStateException::class.java) {
            ConfigurationDescriptors.quarkusProperties(source,
                options().copy(profile = "prod", selectedUnits = setOf("main")))
        }
        assertTrue(disabled.message.orEmpty().contains("disabled by quarkus.hibernate-orm.enabled"))
        assertFalse(disabled.message.orEmpty().contains("requires explicit packages"))
        val marker = "not-a-boolean-do-not-print-this-value"
        for (setting in listOf("quarkus.hibernate-orm.enabled=$marker",
            "quarkus.hibernate-orm.enabled=true\nquarkus.hibernate-orm.enabled=false")) {
            val failure = assertThrows(Exception::class.java) {
                ConfigurationDescriptors.quarkusProperties(properties("$setting\nquarkus.hibernate-orm.packages=$primaryPackage"), options())
            }
            assertFalse(failure.stackTraceToString().contains(marker))
        }
    }

    @Test fun `expansion limits stop before later substitutions and bound recursive intermediates`() {
        val key = "quarkus.hibernate-orm.database.default-schema"
        // Checking the failure reason proves the resolver stops at the second chunk rather than
        // allocating the entire replacement and only rejecting it after subsequent expressions.
        val tooLarge = "${'$'}{chunk}${'$'}{chunk}${'$'}{must.not.resolve}"
        val inputs = listOf(
            "$key=$tooLarge\nchunk=${"x".repeat(32769)}",
            "$key=${'$'}{intermediate}\nintermediate=$tooLarge\nchunk=${"x".repeat(32769)}",
            "$key=${"x".repeat(65537)}${'$'}{must.not.resolve}",
            "$key=${'$'}{intermediate}\nintermediate=${"x".repeat(65537)}${'$'}{must.not.resolve}",
            "$key=${'$'}{chunk}xx\nchunk=${"x".repeat(65535)}",
        )
        for (settings in inputs) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ConfigurationDescriptors.quarkusProperties(properties("quarkus.hibernate-orm.packages=$primaryPackage\n$settings"), options())
            }
            assertEquals("Expanded mapping setting is too large: $key", failure.message)
        }
        val boundary = ConfigurationDescriptors.quarkusProperties(properties("""
            quarkus.hibernate-orm.packages=$primaryPackage
            $key=${'$'}{chunk}${'$'}{chunk}
            chunk=${"x".repeat(32768)}
        """), options())
        assertEquals(65536, boundary.units.single().request.hibernateProperties.getValue("hibernate.default_schema").length)
    }

    @Test fun `properties continuation escapes and explicit substitutions preserve the intended package scope`() {
        val source = properties("""
            base.package=example.base
            selected.package=example.original
            quarkus.hibernate-orm.packages=${'$'}{base.package},\
              ${'$'}{selected.package}, ${'$'}{fallback.package:example.fallback}
            quarkus.hibernate-orm.database.default-schema=tenant\u005fa
        """)
        val generated = ConfigurationDescriptors.quarkusProperties(source,
            options().copy(definitions = mapOf("selected.package" to "example.explicit")))
        val request = generated.units.single().request
        assertEquals(listOf("example.base", "example.explicit", "example.fallback"), request.scanPackages)
        assertEquals("tenant_a", request.hibernateProperties["hibernate.default_schema"])
    }

    @Test fun `explicit substitutions do not override a literal mapping setting`() {
        val generated = ConfigurationDescriptors.quarkusProperties(properties("quarkus.hibernate-orm.packages=$primaryPackage"),
            options().copy(definitions = mapOf("quarkus.hibernate-orm.packages" to "example.not-the-configured-scope")))
        assertEquals(listOf(primaryPackage), generated.units.single().request.scanPackages)
    }

    @Test fun `ambiguous active keys profile inheritance and unsafe expressions fail without exposing values`() {
        val marker = "must-not-expose-production-secret"
        val invalid = listOf(
            "quarkus.hibernate-orm.packages=example.one\nquarkus.hibernate-orm.packages=example.two",
            "quarkus.hibernate-orm.audit.packages=example.one\nquarkus.hibernate-orm.\"audit\".packages=example.two",
            "quarkus.hibernate-orm.packages=${'$'}{missing.package}",
            "quarkus.hibernate-orm.packages=${'$'}{a}\na=${'$'}{b}\nb=${'$'}{a}",
            "quarkus.hibernate-orm.packages=${'$'}{quarkus.datasource.password}\nquarkus.datasource.password=$marker",
            "quarkus.hibernate-orm.packages=${'$'}{aes-gcm::$marker}",
            "quarkus.hibernate-orm.packages=${'$'}{prefix.${'$'}{nested}}\nnested=$marker",
            "quarkus.hibernate-orm.packages=example.one\nquarkus.config.profile.parent=parent",
            "quarkus.hibernate-orm.packages=example.one\nquarkus.config.locations=https://$marker/config",
            "%prod,common.quarkus.hibernate-orm.packages=example.one",
        )
        for (text in invalid) {
            val failure = assertThrows(Exception::class.java) {
                ConfigurationDescriptors.quarkusProperties(properties(text), options().copy(profile = "prod"))
            }
            assertFalse(failure.stackTraceToString().contains(marker), failure.javaClass.name)
        }
    }

    @Test fun `duplicate inactive unrelated keys do not override or block the selected unit`() {
        val generated = ConfigurationDescriptors.quarkusProperties(properties("""
            quarkus.hibernate-orm.packages=$primaryPackage
            %dev.quarkus.hibernate-orm.packages=example.first
            %dev.quarkus.hibernate-orm.packages=example.second
            unrelated.password=secret-a
            unrelated.password=secret-b
        """), options().copy(profile = "prod"))
        assertEquals(listOf(primaryPackage), generated.units.single().request.scanPackages)
    }

    @Test fun `explicit unit selection does not pretend an unscoped other unit was reconstructed`() {
        val source = properties("""
            quarkus.hibernate-orm.packages=$primaryPackage
            quarkus.hibernate-orm."unscoped".datasource=production-audit
        """)
        assertThrows(Exception::class.java) { ConfigurationDescriptors.quarkusProperties(source, options()) }
        val generated = ConfigurationDescriptors.quarkusProperties(source, options().copy(selectedUnits = setOf("default")))
        assertEquals(listOf("default"), generated.units.map { it.name })
        assertThrows(IllegalArgumentException::class.java) {
            ConfigurationDescriptors.quarkusProperties(source, options().copy(selectedUnits = setOf("missing")))
        }
    }

    @Test fun `unknown mapping settings stay unresolved while datasource secrets are neither evaluated nor copied`() {
        val secret = "never-copy-production-password"
        val databaseUrl = "jdbc:postgresql://production.invalid/private"
        val generated = ConfigurationDescriptors.quarkusProperties(properties("""
            quarkus.hibernate-orm.packages=$primaryPackage
            quarkus.hibernate-orm.mapping-files=custom-mapping.xml
            quarkus.hibernate-orm.future-mapping-mode=must-not-copy-unknown-mode-value
            quarkus.datasource.jdbc.url=$databaseUrl
            quarkus.datasource.username=production-user
            quarkus.datasource.password=$secret
            quarkus.datasource.credentials-provider=${'$'}{unknown.provider}
        """), options())
        val unit = generated.units.single()
        assertFalse(unit.configurationComplete)
        assertTrue(unit.request.unresolvedDynamicQueries.keys.any { "mapping-files" in it })
        assertTrue(unit.request.unresolvedDynamicQueries.keys.any { "future-mapping-mode" in it })
        val serialized = json.writeValueAsString(generated)
        for (value in listOf(secret, databaseUrl, "production-user", "custom-mapping.xml", "must-not-copy-unknown-mode-value"))
            assertFalse(serialized.contains(value), "Configuration values must not appear in diagnostic output")
        assertTrue(unit.request.hibernateProperties.keys.none { "connection" in it || "password" in it || "url" in it })
    }

    @Test fun `XML unlisted discovery mapping files and archive references remain explicit unresolved boundaries`() {
        val generated = ConfigurationDescriptors.persistenceXml(xml(unit("""
            <mapping-file>https://must-not-open.invalid/private-mapping.xml</mapping-file>
            <jar-file>file:///must-not-open/private-model.jar</jar-file>
            <properties><property name="hibernate.future_mapping_mode" value="unknown-private-value"/></properties>
        """.trimIndent(), exclude = "false")), options())
        val unit = generated.units.single()
        assertFalse(unit.configurationComplete)
        for (key in listOf("exclude-unlisted-classes", "mapping-file", "jar-file", "hibernate.future_mapping_mode"))
            assertTrue(unit.request.unresolvedDynamicQueries.keys.any { key in it }, unit.toString())
        val serialized = json.writeValueAsString(generated)
        assertFalse(serialized.contains("must-not-open"), serialized)
        assertFalse(serialized.contains("unknown-private-value"), serialized)
    }

    @Test fun `XML refuses unsupported providers duplicate definitions and unknown namespace inventory`() {
        val cases = listOf(
            unit("<provider>example.OtherProvider</provider>"),
            unit() + unit(),
            unit("""<properties><property name="hibernate.default_schema" value="one"/>
                <property name="hibernate.default_schema" value="two"/></properties>"""),
            "<persistence-unit name=\"empty\"><exclude-unlisted-classes>true</exclude-unlisted-classes></persistence-unit>",
            unit("<exclude-unlisted-classes>false</exclude-unlisted-classes>"),
        )
        for (body in cases) assertThrows(Exception::class.java) { ConfigurationDescriptors.persistenceXml(xml(body), options()) }
        assertThrows(Exception::class.java) {
            ConfigurationDescriptors.persistenceXml(xml(unit(), namespace = "https://unknown.invalid/persistence"), options())
        }
        assertThrows(Exception::class.java) { ConfigurationDescriptors.persistenceXml(xml(unit()), options().copy(profile = "prod")) }
    }

    @Test fun `DOCTYPE external entities and XInclude are rejected without disclosure or parser stderr`() {
        val marker = "sentinel-private-xml-value"
        val external = directory.resolve("$marker.xml").also { it.writeText(marker) }
        val documents = listOf(
            """<!DOCTYPE persistence [<!ENTITY secret "$marker">]>
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2"><persistence-unit name="&secret;"/></persistence>""",
            """<!DOCTYPE persistence [<!ENTITY secret SYSTEM "${external.toUri()}">]>
                <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2"><persistence-unit name="main"><class>&secret;</class></persistence-unit></persistence>""",
            """<persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2" xmlns:xi="http://www.w3.org/2001/XInclude">
                <xi:include href="${external.toUri()}" parse="text"/>${unit()}</persistence>""",
        )
        val errors = ByteArrayOutputStream()
        val previous = System.err
        try {
            System.setErr(PrintStream(errors))
            for (document in documents) {
                val source = directory.resolve("persistence.xml").also { it.writeText(document) }
                val failure = assertThrows(Exception::class.java) { ConfigurationDescriptors.persistenceXml(source, options()) }
                assertFalse(failure.stackTraceToString().contains(marker), failure.javaClass.name)
            }
        } finally { System.setErr(previous) }
        assertEquals("", errors.toString(), "The XML parser must not dump untrusted source fragments to stderr")
        assertEquals(marker, Files.readString(external))
    }

    @Test fun `default XML mapping resources are detected in directories and jars without reading their contents`() {
        val marker = "not-an-XML-parser-input-secret"
        val classes = directory.resolve("compiled").createDirectories()
        classes.resolve("META-INF").createDirectories().resolve("orm.xml").writeText(marker)
        val jar = directory.resolve("mappings.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("META-INF/orm.xml"))
            output.write(marker.toByteArray())
            output.closeEntry()
        }
        for (extra in listOf(classes, jar)) {
            val generated = ConfigurationDescriptors.persistenceXml(xml(unit()), options().copy(classpath = classpath + extra.toString()))
            assertFalse(generated.units.single().configurationComplete)
            assertTrue(generated.units.single().request.unresolvedDynamicQueries.any { "orm.xml" in it.key || "orm.xml" in it.value })
            assertFalse(json.writeValueAsString(generated).contains(marker))
        }
    }

    @Test fun `classpath omissions are diagnosed and invalid archives fail without dropping the usable scope`() {
        val missing = directory.resolve("missing-classes")
        val generated = ConfigurationDescriptors.quarkusProperties(properties("quarkus.hibernate-orm.packages=$primaryPackage"),
            options().copy(classpath = listOf(missing.toString()) + classpath))
        assertEquals(classpath, generated.units.single().request.classpath)
        assertTrue(generated.diagnostics.isNotEmpty())
        assertThrows(Exception::class.java) {
            ConfigurationDescriptors.quarkusProperties(properties("quarkus.hibernate-orm.packages=$primaryPackage"),
                options().copy(classpath = listOf(missing.toString())))
        }
        val invalid = directory.resolve("invalid.jar").also { it.writeText("not an archive") }
        assertThrows(Exception::class.java) {
            ConfigurationDescriptors.quarkusProperties(properties("quarkus.hibernate-orm.packages=$primaryPackage"),
                options().copy(classpath = classpath + invalid.toString()))
        }
    }
}
