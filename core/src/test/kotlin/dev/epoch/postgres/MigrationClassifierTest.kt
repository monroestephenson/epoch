package dev.epoch.postgres

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MigrationClassifierTest {
    private val migration = MigrationInfo("2", "V2__change.sql")
    private val baseline = SchemaSnapshot(tables = mapOf("public.customer" to TableSchema(columns = mapOf(
        "id" to ColumnSchema("integer", false), "name" to ColumnSchema("character varying(20)", true),
    ))))
    private fun classify(sql: String, after: SchemaSnapshot = baseline) =
        MigrationClassifier().findings(sql, migration, baseline, after)

    @Test fun `net drops are represented by catalog but recreated table data loss is not`() {
        assertTrue(classify("DROP TABLE customer CASCADE", SchemaSnapshot()).isEmpty())
        assertFalse(classify("DROP TABLE customer; CREATE TABLE customer(id integer, name varchar(20));").isEmpty())
    }

    @Test fun `drop and recreate column is not waived by unchanged final catalog`() {
        val after = baseline.copy(tables = mapOf("public.customer" to baseline.tables.getValue("public.customer").copy(
            columns = baseline.tables.getValue("public.customer").columns - "name",
        )))
        assertTrue(classify("ALTER TABLE customer DROP COLUMN name", after).isEmpty())
        assertFalse(classify("ALTER TABLE customer DROP COLUMN name; ALTER TABLE customer ADD COLUMN name varchar(20)").isEmpty())
    }

    @Test fun `column metadata operations and identity type alteration avoid generic noise`() {
        listOf("ALTER COLUMN name SET NOT NULL", "ALTER COLUMN name DROP NOT NULL", "ALTER COLUMN name SET DEFAULT 'initial'",
            "ALTER COLUMN name DROP DEFAULT", "ALTER COLUMN name TYPE varchar(20)", "ALTER COLUMN id DROP IDENTITY IF EXISTS")
            .forEach { change -> assertTrue(classify("ALTER TABLE customer $change").isEmpty(), change) }
    }

    @Test fun `widening is classified but explicit data transforms remain unsupported`() {
        val after = baseline.copy(tables = mapOf("public.customer" to baseline.tables.getValue("public.customer").copy(
            columns = baseline.tables.getValue("public.customer").columns + ("id" to ColumnSchema("bigint", false)),
        )))
        assertTrue(classify("ALTER TABLE customer ALTER COLUMN id TYPE bigint", after).isEmpty())
        assertFalse(classify("ALTER TABLE customer ALTER COLUMN id TYPE bigint USING id + 1", after).isEmpty())
    }

    @Test fun `type ALTER with COLLATE is not a proven no-op or widening`() {
        val unchanged = classify("ALTER TABLE customer ALTER COLUMN name TYPE varchar(20) COLLATE \"C\"")
        assertTrue(unchanged.any { it.ruleId == "UNSUPPORTED_MIGRATION_EFFECT" }, unchanged.toString())
        val after = baseline.copy(tables = mapOf("public.customer" to baseline.tables.getValue("public.customer").copy(
            columns = baseline.tables.getValue("public.customer").columns + ("name" to ColumnSchema("character varying(40)", true)),
        )))
        val widened = classify("ALTER TABLE customer ALTER COLUMN name TYPE varchar(40) COLLATE \"C\"", after)
        assertTrue(widened.any { it.ruleId == "UNSUPPORTED_MIGRATION_EFFECT" }, widened.toString())
    }

    @Test fun `concurrent index grammar does not create unsupported schema finding`() {
        assertTrue(classify("CREATE INDEX CONCURRENTLY idx_customer_name ON customer(name)").isEmpty())
        assertTrue(classify("CREATE UNIQUE INDEX CONCURRENTLY idx_customer_name ON customer(name)").isEmpty())
    }

    @Test fun `procedural and data statements are never hidden behind same catalog`() {
        assertFalse(classify("UPDATE customer SET name = 'x; y';").isEmpty())
        assertFalse(classify("DO ${'$'}${'$'} BEGIN DELETE FROM customer; END ${'$'}${'$'};").isEmpty())
        assertFalse(classify("TRUNCATE customer;").isEmpty())
    }

    @Test fun `enum label operations are classified only with matching catalog evidence`() {
        val before = baseline.copy(enumTypes = mapOf("public.status" to listOf("OLD")))
        val added = before.copy(enumTypes = mapOf("public.status" to listOf("OLD", "NEW")))
        val renamed = before.copy(enumTypes = mapOf("public.status" to listOf("NEW")))
        val classifier = MigrationClassifier()
        assertTrue(classifier.findings("ALTER TYPE status ADD VALUE 'NEW'", migration, before, added).isEmpty())
        assertTrue(classifier.findings("ALTER TYPE status RENAME VALUE 'OLD' TO 'NEW'", migration, before, renamed).isEmpty())
        assertFalse(classifier.findings("ALTER TYPE status ADD VALUE 'NEW'", migration, before, renamed).isEmpty())
    }

    @Test fun `create view is supported only for the catalog proven old relation name`() {
        val classifier = MigrationClassifier()
        val sql = "ALTER TABLE customer RENAME TO customer_archive; CREATE VIEW customer AS SELECT * FROM customer_archive"
        assertTrue(classifier.findings(sql, migration, baseline, baseline, setOf("public.customer")).isEmpty())
        assertTrue(classifier.findings(sql, migration, baseline, baseline).any { it.ruleId == "UNSUPPORTED_MIGRATION_EFFECT" })
        assertTrue(classifier.findings("CREATE VIEW other AS SELECT * FROM customer_archive", migration,
            baseline, baseline, setOf("public.customer")).any { it.ruleId == "UNSUPPORTED_MIGRATION_EFFECT" })
        assertFalse(classifier.findings("CREATE MATERIALIZED VIEW customer AS SELECT * FROM customer_archive", migration,
            baseline, baseline, setOf("public.customer")).isEmpty())
        assertFalse(classifier.findings("CREATE TEMP VIEW customer AS SELECT * FROM customer_archive", migration,
            baseline, baseline, setOf("public.customer")).isEmpty())
    }

    @Test fun `proven compatibility view never waives unrelated data statements`() {
        val findings = MigrationClassifier().findings(
            "ALTER TABLE customer RENAME TO customer_archive; CREATE VIEW customer AS SELECT * FROM customer_archive; UPDATE customer_archive SET name = 'changed'",
            migration, baseline, baseline, setOf("public.customer"))
        assertEquals(1, findings.size)
        assertEquals("UNSUPPORTED_MIGRATION_EFFECT", findings.single().ruleId)
        assertTrue(findings.single().evidence.contains("Update"))
    }

    @Test fun `proven quoted view names retain PostgreSQL identity semantics`() {
        assertTrue(MigrationClassifier().findings("CREATE VIEW \"Customer\" AS SELECT * FROM customer_archive", migration,
            baseline, baseline, setOf("public.Customer")).isEmpty())
        assertFalse(MigrationClassifier().findings("CREATE VIEW \"Customer\" AS SELECT * FROM customer_archive", migration,
            baseline, baseline, setOf("public.customer")).isEmpty())
    }
}
