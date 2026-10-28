package dev.epoch

import dev.epoch.core.AccessPathStatus
import dev.epoch.core.ColumnReference
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Verdict
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Application fixtures are Epoch-owned, independently packaged Kotlin modules, never a user repo. */
class Hibernate7JarDiscoveryTest {
    @TempDir lateinit var directory: Path

    private fun property(name: String) = requireNotNull(System.getProperty(name)) { "Missing fixture property $name" }
        .split(File.pathSeparator).filter(String::isNotBlank)

    private fun extract(applicationAsm: Boolean): DatabaseContract {
        val jars = property("epoch.hibernate7.jar.fixtures")
        assertEquals(2, jars.size, "Entity and repository must be separate application JARs")
        assertTrue(jars.all { Files.isRegularFile(Path.of(it)) && it.endsWith(".jar") })
        val contents = jars.map { path -> JarFile(path).use { jar -> jar.entries().asSequence().map { it.name }.toSet() } }
        assertEquals(1, contents.count { "dev/epoch/jarfixture/entity/JarCustomer.class" in it })
        assertEquals(1, contents.count { "dev/epoch/jarfixture/repository/JarCustomerDao.class" in it })
        assertTrue(contents.none { "dev/epoch/jarfixture/entity/JarCustomer.class" in it &&
            "dev/epoch/jarfixture/repository/JarCustomerDao.class" in it })
        val runtime = property("epoch.hibernate749.classpath")
        assertTrue(runtime.any { it.endsWith("hibernate-core-7.4.9.Final.jar") })
        val asm = if (applicationAsm) property("epoch.application.asm.core") else emptyList()
        if (applicationAsm) {
            assertEquals(1, asm.size)
            assertTrue(asm.single().endsWith("asm-9.7.1.jar"))
        }
        val classpath = jars + runtime + asm
        assertTrue(classpath.none { Path.of(it).fileName.toString().startsWith("asm-tree-") ||
            Path.of(it).fileName.toString().startsWith("asm-analysis-") })
        val result = HibernateExtractor().extract(ExtractionRequest(
            applicationVersion = "hibernate7-jar-fixture",
            classpath = classpath,
            scanPackages = listOf("dev.epoch.jarfixture.entity"),
            repositoryPackages = listOf("dev.epoch.jarfixture"),
        ))
        assertTrue(result.coverage.notes.any { it.contains("Hibernate runtime 7.4.9.Final") }, result.toString())
        assertEquals("6.6.13.Final", org.hibernate.Version.getVersionString(), "Application Hibernate must remain isolated")
        return result
    }

    private fun assertDiscovery(contract: DatabaseContract): String {
        assertFalse(contract.unresolvedQueries.any { it.reason.contains("Cannot inspect compiled class") },
            contract.unresolvedQueries.toString())
        for (method in listOf("byEmail", "legacyNote")) {
            val path = contract.accessPaths.single { it.method.startsWith("$method(") }
            assertEquals(AccessPathStatus.STATIC, path.status, path.toString())
            assertTrue(requireNotNull(path.line) > 0)
            val query = contract.queries.single { it.id == path.queryIds.single() }
            assertTrue(query.dependenciesComplete, query.toString())
            assertEquals(listOf("public.jar_customers"), query.tables)
        }
        val dynamic = contract.accessPaths.single { it.method.startsWith("dynamic(") }
        assertEquals(AccessPathStatus.UNRESOLVED, dynamic.status)
        assertEquals("dev.epoch.jarfixture.repository.JarCustomerDao", dynamic.className)
        assertTrue(requireNotNull(dynamic.line) > 0)
        assertTrue(contract.unresolvedQueries.any { it.id == dynamic.id })
        assertFalse(contract.coverage.complete)
        val native = contract.queries.single { it.id.contains("#legacyNote(") }
        assertTrue(ColumnReference("public.jar_customers", "legacy_note") in native.columns)
        assertFalse(contract.entities.flatMap { it.columns }.any { it.name == "legacy_note" })
        return native.id
    }

    @Test fun `Hibernate 7 repository and entity JARs are discovered with tool-owned ASM`() {
        assertDiscovery(extract(applicationAsm = false))
    }

    @Test fun `application ASM core cannot split discovery and inline-only rename still breaks under Hibernate 7`() {
        val contract = extract(applicationAsm = true)
        val nativeId = assertDiscovery(contract)
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(
            "CREATE TABLE jar_customers (id bigint PRIMARY KEY, email varchar(255), legacy_note text);",
        )
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__rename_inline_only.sql").writeText(
            "ALTER TABLE jar_customers RENAME COLUMN legacy_note TO renamed_note;",
        )
        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertTrue(report.baseline!!.findings.none { finding ->
            finding.ruleId.startsWith("INVALID_BASELINE_") || finding.ruleId == "BASELINE_SQL_UNVERIFIED"
        }, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { finding ->
            finding.ruleId == "POSTGRES_SQL_REJECTED" && finding.verdict == Verdict.BREAKING &&
                nativeId in finding.affectedContractIds && "42703" in finding.evidence
        }, report.toString())
    }
}
