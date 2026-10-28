package dev.epoch

import dev.epoch.core.*
import dev.epoch.datadifffixture.*
import dev.epoch.hibernate.EntityOverride
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class DataCompatibilityMultiUnitTest {
    @TempDir lateinit var directory: Path

    @Test fun `multiple units preserve every nested reader hierarchy and discriminator writer link`() {
        fun extract(vararg classes: Class<*>, root: Class<*>? = null) = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = classes.map { it.name }, discoverRepositories = false,
            entityOverrides = root?.let { mapOf(it.name to EntityOverride(operations = setOf(Operation.SELECT))) }.orEmpty(),
        ))
        val old = listOf(extract(DataOldName::class.java),
            extract(DataOldCreature::class.java, DataOldDog::class.java, root = DataOldCreature::class.java))
        val new = listOf(extract(DataNewName::class.java),
            extract(DataNewCreature::class.java, DataNewDog::class.java, DataNewCat::class.java, root = DataNewCreature::class.java))
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE data_name (id bigint PRIMARY KEY, state varchar(255));
            CREATE TABLE data_creatures (id bigint PRIMARY KEY, dtype varchar(31) NOT NULL, label varchar(255));
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        val checkpoints = mutableListOf<Pair<String, CompatibilityReport>>()
        val report = PostgresVerifier().verifyBidirectional(old, new, baseline, pending,
            VerificationOptions(progress = { direction, result -> checkpoints += direction to result }))
        val data = requireNotNull(report.dataCompatibility)
        assertEquals(Verdict.BREAKING, data.verdict, data.toString())
        val findings = data.migrations.flatMap { it.findings }
        for ((rule, unit, reader, writer) in listOf(
            listOf("DATA_ENUM_VALUE_UNREADABLE", "contract[1]/", DataOldName::class.java.name, DataNewName::class.java.name),
            listOf("DATA_DISCRIMINATOR_UNREADABLE", "contract[2]/", DataOldCreature::class.java.name, DataNewCat::class.java.name),
        )) {
            val ids = findings.filter { it.ruleId == rule }.flatMap { it.affectedContractIds }
            assertTrue(ids.any { unit in it && reader in it }, "$rule reader: $ids\n$data")
            assertTrue(ids.any { unit in it && writer in it }, "$rule writer: $ids\n$data")
        }
        assertTrue(checkpoints.any { it.first == "new-writes-old-reads" }, checkpoints.toString())
        assertTrue(checkpoints.all { !it.second.complete }, "Progress is retained evidence, never a final completion claim")
        assertTrue(data.complete)
        assertNull(data.baseline, "Data regressions must not be attributed to schema baseline health")
    }
}
