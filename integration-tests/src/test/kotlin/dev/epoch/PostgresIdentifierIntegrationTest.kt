package dev.epoch

import dev.epoch.core.Operation
import dev.epoch.core.Verdict
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PostgresIdentifierIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `67 character Hibernate table name matches the PostgreSQL truncated catalog name`() {
        val tableName = LongTableNameAccount::class.java.getAnnotation(Table::class.java).name
        assertEquals(67, tableName.length)
        assertEquals(67, tableName.toByteArray(Charsets.UTF_8).size)
        val physicalName = tableName.take(63)
        val contract = HibernateExtractor().extract(ExtractionRequest(
            discoverRepositories = false,
            entityClasses = listOf(LongTableNameAccount::class.java.name),
            applicationVersion = "postgres-identifier-regression",
        ))
        assertTrue(contract.coverage.complete, contract.toString())
        assertEquals("public.$physicalName", contract.entities.single().table)
        assertEquals(Operation.entries.toSet(), contract.queries.map { it.operation }.toSet())
        assertTrue(contract.queries.all { it.sql.contains(tableName) }, contract.toString())
        assertTrue(contract.queries.all { it.tables == listOf("public.$physicalName") }, contract.toString())

        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE $tableName (id bigint PRIMARY KEY, label varchar(255));
            DO $$
            BEGIN
                IF NOT EXISTS (
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname::text = '$physicalName'
                        AND octet_length(c.relname::text) = 63
                ) THEN
                    RAISE EXCEPTION 'Expected PostgreSQL to truncate the table name to 63 bytes';
                END IF;
            END;
            $$;
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__metadata.sql").writeText("ALTER TABLE $tableName ADD COLUMN metadata text;")

        val report = PostgresVerifier().verify(contract, baseline, pending)
        val health = requireNotNull(report.baseline)
        assertTrue(health.findings.none { it.ruleId == "INVALID_BASELINE_TABLE" }, report.toString())
        assertEquals(Verdict.SAFE, health.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.migrations.single().verdict, report.toString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }
}

@Entity(name = "LongTableNameAccount")
@Table(name = "abcdefghijklmnopqrstuvwxyz_abcdefghijklmnopqrstuvwxyz_abcdefghijklm")
open class LongTableNameAccount {
    @field:Id open var id: Long = 0
    @field:Column(name = "label") open var label: String? = null
}
