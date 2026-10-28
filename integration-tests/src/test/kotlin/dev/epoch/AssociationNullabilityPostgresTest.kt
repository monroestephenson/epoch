package dev.epoch

import dev.epoch.associationfixture.*
import dev.epoch.core.CompatibilityEngine
import dev.epoch.core.MigrationInfo
import dev.epoch.core.Verdict
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AssociationNullabilityPostgresTest {
    @TempDir lateinit var directory: Path

    @Test @Timeout(240)
    fun `actual Hibernate commits prove reference declarations differ from every JDBC input domain`() {
        val classes = AssociationTransactionWitness.entities
        val fixtures = Path.of(AssociationTransactionWitness::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val runtime7 = requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator)
        val runtimes = listOf("6.6.13.Final" to emptyList(), "7.4.9.Final" to runtime7)
        PostgreSQLContainer(DockerImageName.parse("postgres:16.8")).use { database ->
            database.start()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                for ((version, runtime) in runtimes) {
                    connection.createStatement().use { statement ->
                        statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;")
                        statement.execute(AssociationTransactionWitness.schema)
                    }
                    val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = classes.map { it.name },
                        classpath = if (runtime.isEmpty()) emptyList() else runtime + fixtures, discoverRepositories = false))
                    val direct = contract.entities.single { it.table == "public.association_direct_write" }
                        .columns.single { it.name == "parent_id" }
                    assertEquals(true, direct.declaredNonNull, "$version: $direct")
                    assertFalse(direct.inputDomainComplete, "$version: a source reference is not exhaustive lifecycle proof")
                    val natural = contract.entities.single { it.table == "public.association_natural_write" }
                        .columns.single { it.name == "parent_code" }
                    assertNull(natural.declaredNonNull, "$version: a non-null object may have a NULL natural key")
                    val baseline = PostgresCatalog().inspect(connection)
                    val output = invoke(version, runtime, database)
                    assertTrue(output.contains("PERSISTED_TARGET_COMMITTED_BEFORE_AND_AFTER"), output)
                    assertTrue(output.contains("NULL_REFERENCE_COMMITTED_THEN_REJECTED_23502"), output)
                    assertTrue(output.contains("NON_NULL_REFERENCE_WITH_NULL_NATURAL_KEY_COMMITTED_THEN_REJECTED_23502"), output)
                    assertTrue(output.contains("IDENTITY_SELF_REFERENCE_COMMITTED_THEN_REJECTED_23502"), output)
                    val result = CompatibilityEngine().verifyStep(contract, baseline, PostgresCatalog().inspect(connection),
                        MigrationInfo("2", "V2__tighten_association_columns.sql"))
                    assertNotEquals(Verdict.SAFE, result.verdict, result.toString())
                    for (objectName in listOf("public.association_direct_write.parent_id",
                        "public.association_nullable_write.parent_id", "public.association_natural_write.parent_code",
                        "public.association_identity_self_write.parent_id")) {
                        assertTrue(result.findings.any { it.objectName == objectName && it.verdict == Verdict.UNKNOWN },
                            "$version: missing explicitly scoped uncertainty for $objectName: $result")
                    }
                    assertFalse(result.findings.any { it.ruleId == "NON_NULL_INPUT_PRESERVED" &&
                        it.objectName == "public.association_direct_write.parent_id" }, result.toString())
                }
            }
        }
    }

    private fun invoke(version: String, runtime: List<String>, database: PostgreSQLContainer): String {
        val stdout = directory.resolve("$version.stdout")
        val stderr = directory.resolve("$version.stderr")
        val base = requireNotNull(System.getProperty("epoch.cli.test.classpath")).split(File.pathSeparator)
        val classpath = (runtime + base).distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx512m", "-cp", classpath, AssociationTransactionWitness::class.java.name,
            version, database.jdbcUrl, database.username, database.password)
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Association witness timed out: ${Files.readString(stderr)}")
            val diagnostics = Files.readString(stdout) + "\n" + Files.readString(stderr)
            assertEquals(0, process.exitValue(), diagnostics)
            return diagnostics
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
        }
    }
}
