package dev.epoch

import dev.epoch.core.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.repositoryfixture.RepositoryAccount
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class RepositoryDiscoveryTest {
    @TempDir lateinit var directory: Path

    @Test fun `discovered native unmapped column is checked and bulk HQL delete is retained`() {
        val classes = Path.of(RepositoryAccount::class.java.protectionDomain.codeSource.location.toURI())
        val contract = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = listOf(RepositoryAccount::class.java.name),
            classpath = listOf(classes.toString()), repositoryPackages = listOf("dev.epoch.repositoryfixture"),
            applicationVersion = "repository-fixture",
        ))
        val native = contract.queries.firstOrNull { it.sql.contains("legacy_column", ignoreCase = true) }
        assertNotNull(native, contract.toString())
        assertFalse(contract.entities.flatMap { it.columns }.any { it.name == "legacy_column" }, contract.toString())
        assertTrue(contract.queries.any { it.operation == Operation.DELETE && it.id.contains("bulkDelete") }, contract.toString())

        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id bigint PRIMARY KEY, label varchar(255), legacy_column text);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__drop_legacy.sql").writeText("ALTER TABLE accounts DROP COLUMN legacy_column;")
        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertTrue(report.baseline?.findings.orEmpty().none {
            it.ruleId == "BASELINE_SQL_UNVERIFIED" && native!!.id in it.affectedContractIds
        }, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.flatMap { it.findings }.any {
            it.ruleId == "POSTGRES_SQL_REJECTED" && it.verdict == Verdict.BREAKING && native!!.id in it.affectedContractIds
        }, report.toString())
    }
}
