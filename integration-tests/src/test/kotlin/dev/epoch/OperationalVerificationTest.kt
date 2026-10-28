package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationException
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Operational facts come from the actual Flyway session and remain separate from compatibility. */
class OperationalVerificationTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()
    private var scenario = 0

    private data class Step(val sql: String, val transactional: Boolean = true)

    private fun read(table: String = "control") = DatabaseContract(
        applicationVersion = "operational-proof",
        queries = listOf(QueryContract("$table.read", Operation.SELECT, "SELECT id FROM $table",
            tables = listOf("public.$table"), columns = listOf(ColumnReference("public.$table", "id")),
            dependenciesComplete = true)),
        coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite operational fixture SQL")),
    )

    private fun verify(baselineSql: String, vararg steps: Step, contract: DatabaseContract = read(),
                       options: VerificationOptions = VerificationOptions()): CompatibilityReport {
        val root = directory.resolve("scenario-${scenario++}")
        val baseline = root.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE control (id bigint PRIMARY KEY);\n$baselineSql")
        val pending = root.resolve("pending").createDirectories()
        steps.forEachIndexed { index, step ->
            val filename = "V${index + 2}__step.sql"
            pending.resolve(filename).writeText(step.sql)
            if (!step.transactional) pending.resolve("$filename.conf").writeText("executeInTransaction=false\n")
        }
        return PostgresVerifier().verify(contract, baseline, pending, options)
    }

    private fun assessment(report: CompatibilityReport, index: Int = 0): OperationalAssessment =
        requireNotNull(report.migrations[index].operational) { "Missing measured assessment: $report" }

    private fun assertRule(statement: OperationalStatementReport, rule: String, verdict: OperationalVerdict) {
        assertEquals(verdict, statement.verdict, statement.toString())
        assertTrue(statement.findings.any { it.ruleId == rule && it.verdict == verdict }, statement.toString())
    }

    @Test fun `actual statement snapshots retain locks rewrites scans and timeout provenance without mutating checkpoints`() {
        val progress = mutableListOf<CompatibilityReport>()
        val report = verify("""
            CREATE TABLE accounts (id bigint PRIMARY KEY, indexed integer, payload integer, label text);
            INSERT INTO accounts VALUES (1, 1, 1, 'present');
            CREATE TABLE events (id bigint PRIMARY KEY, account_id bigint);
            INSERT INTO events VALUES (1, 1);
        """.trimIndent(), Step("""
            SET LOCAL lock_timeout = '750ms';
            CREATE INDEX accounts_lookup ON accounts(indexed);
            ALTER TABLE accounts ALTER COLUMN payload TYPE bigint;
            ALTER TABLE accounts ALTER COLUMN label SET NOT NULL;
            ALTER TABLE accounts ADD CONSTRAINT accounts_positive CHECK (indexed >= 0);
            ALTER TABLE events ADD CONSTRAINT events_account FOREIGN KEY(account_id) REFERENCES accounts(id);
        """.trimIndent()), Step("""
            SET LOCAL lock_timeout = '750ms';
            ALTER TABLE accounts ALTER COLUMN label SET NOT NULL;
            ALTER TABLE accounts ADD COLUMN after_reset text;
        """.trimIndent()), Step("""
            SET LOCAL lock_timeout = 0;
            ALTER TABLE accounts ADD COLUMN no_timeout text;
        """.trimIndent()), options = VerificationOptions(progress = { direction, snapshot ->
            if (direction == "old-on-new") progress += snapshot
        }))
        assertEquals(OperationalVerdict.RISKY, report.operationalVerdict, report.toString())
        val first = assessment(report)
        assertEquals(6, first.statements.size, first.toString())
        assertEquals((1..6).toList(), first.statements.map { it.ordinal })
        assertTrue(first.statements.all { !it.autoCommit }, first.toString())
        assertTrue(first.statements[0].sql.trimStart().startsWith("SET LOCAL"), first.toString())
        assertEquals(30_000L, first.statements[0].lockTimeoutBefore.milliseconds)
        assertEquals(OperationalTimeoutOrigin.VERIFIER, first.statements[0].lockTimeoutBefore.origin)
        for (statement in first.statements.drop(1)) {
            assertEquals(750L, statement.lockTimeoutBefore.milliseconds, statement.toString())
            assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, statement.lockTimeoutBefore.origin, statement.toString())
            assertTrue(statement.lockTimeoutBefore.postgresSource.isNotBlank())
        }
        assertRule(first.statements[1], "OP_INDEX_BUILD_BLOCKS_WRITES", OperationalVerdict.RISKY)
        assertTrue(first.statements[1].heldLocksAfter.any { it.relation == "public.accounts" &&
            it.relationKind == "table" && it.mode == "ShareLock" }, first.statements[1].toString())
        assertRule(first.statements[2], "OP_TABLE_REWRITE", OperationalVerdict.RISKY)
        assertTrue(first.statements[2].rewrittenTables.any { it.table.name == "public.accounts" &&
            it.table.preExisting == true && it.table.baselineName == "public.accounts" }, first.statements[2].toString())
        assertRule(first.statements[3], "OP_NOT_NULL_SCAN", OperationalVerdict.RISKY)
        assertRule(first.statements[4], "OP_CONSTRAINT_VALIDATION_BLOCKS_WRITES", OperationalVerdict.RISKY)
        assertRule(first.statements[5], "OP_CONSTRAINT_VALIDATION_BLOCKS_WRITES", OperationalVerdict.RISKY)

        val next = assessment(report, 1)
        assertEquals(listOf(1, 2, 3), next.statements.map { it.ordinal })
        assertEquals(OperationalVerdict.SAFE, next.verdict, next.toString())
        assertTrue(next.statements[1].findings.any { it.ruleId in setOf("OP_VALIDATED_NOT_NULL", "OP_METADATA_ONLY") }, next.toString())
        assertEquals(30_000L, next.statements.first().lockTimeoutBefore.milliseconds)
        assertEquals(OperationalTimeoutOrigin.VERIFIER, next.statements.first().lockTimeoutBefore.origin)
        assertTrue(next.statements.drop(1).all { it.lockTimeoutBefore.milliseconds == 750L &&
            it.lockTimeoutBefore.origin == OperationalTimeoutOrigin.MIGRATION_LOCAL }, next.toString())
        val disabled = assessment(report, 2).statements[1]
        assertEquals(0L, disabled.lockTimeoutBefore.milliseconds)
        assertEquals(OperationalTimeoutOrigin.MIGRATION_LOCAL, disabled.lockTimeoutBefore.origin)

        val earlier = progress.first { it.migrations.size == 1 }
        assertFalse(earlier.complete)
        assertEquals(first, earlier.migrations.single().operational, "Later callback resets must not mutate an already published assessment.")
    }

    @Test @Timeout(90)
    fun `CIC is operationally SAFE while an ordinary autocommit index remains RISKY with compatible SQL`() {
        val report = verify("CREATE TABLE accounts (id bigint PRIMARY KEY);",
            Step("CREATE INDEX CONCURRENTLY accounts_cic ON accounts(id);", transactional = false),
            Step("CREATE INDEX accounts_plain ON accounts(id);", transactional = false), contract = read("accounts"),
            options = VerificationOptions(migrationTimeoutSeconds = 20, globalTimeoutSeconds = 60))
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        val concurrent = assessment(report).statements.single()
        assertTrue(concurrent.autoCommit, concurrent.toString())
        assertRule(concurrent, "OP_ONLINE_INDEX_BUILD", OperationalVerdict.SAFE)
        val ordinary = assessment(report, 1).statements.single()
        assertTrue(ordinary.autoCommit, ordinary.toString())
        assertRule(ordinary, "OP_INDEX_BUILD_BLOCKS_WRITES", OperationalVerdict.RISKY)
        assertEquals(OperationalVerdict.RISKY, report.operationalVerdict)
    }

    @Test fun `non-null validation is SAFE across released transactions but RISKY under a retained exclusive lock`() {
        val baseline = "CREATE TABLE accounts (id bigint PRIMARY KEY, label text); INSERT INTO accounts VALUES (1, 'present');"
        val add = "ALTER TABLE accounts ADD CONSTRAINT label_nn CHECK (label IS NOT NULL) NOT VALID;"
        val validate = "ALTER TABLE accounts VALIDATE CONSTRAINT label_nn;"
        val notNull = "ALTER TABLE accounts ALTER COLUMN label SET NOT NULL;"
        val timeout = "SET LOCAL lock_timeout='750ms';"
        val split = verify(baseline, Step("$timeout\n$add"), Step("$timeout\n$validate"), Step("$timeout\n$notNull"))
        assertEquals(OperationalVerdict.SAFE, split.operationalVerdict, split.toString())
        assertRule(assessment(split).statements.last(), "OP_DEFERRED_CONSTRAINT_VALIDATION", OperationalVerdict.SAFE)
        val validation = assessment(split, 1).statements.last()
        assertRule(validation, "OP_NONBLOCKING_VALIDATION", OperationalVerdict.SAFE)
        assertTrue(validation.heldLocksBefore.none { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, validation.toString())
        assertRule(assessment(split, 2).statements.last(), "OP_VALIDATED_NOT_NULL", OperationalVerdict.SAFE)

        val grouped = verify(baseline, Step("$timeout\n$add\n$validate\n$notNull"))
        val retained = assessment(grouped).statements[2]
        assertRule(retained, "OP_VALIDATION_UNDER_RETAINED_LOCK", OperationalVerdict.RISKY)
        assertTrue(retained.heldLocksBefore.any { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, retained.toString())
        assertTrue(retained.heldLocksAfter.any { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, retained.toString())
        assertTrue(retained.newlyObservedLocks.none { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, retained.toString())
    }

    @Test fun `supported operations on new pending tables keep their new identity across migrations`() {
        val report = verify("", Step("CREATE TABLE fresh_table (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, payload integer);"),
            Step("ALTER TABLE fresh_table ALTER COLUMN payload TYPE bigint; CREATE INDEX fresh_payload ON fresh_table(payload);"))
        assertEquals(OperationalVerdict.SAFE, report.operationalVerdict, report.toString())
        val later = assessment(report, 1)
        assertTrue(later.statements.flatMap { it.rewrittenTables }.any { it.table.name == "public.fresh_table" &&
            it.table.preExisting == false && it.table.baselineName == null }, later.toString())
        assertTrue(later.findings.any { it.ruleId == "OP_NEW_RELATION_OPERATION" }, later.toString())
    }

    @Test fun `compound ALTER validation cannot ignore the exclusive lock acquired by another clause`() {
        val report = verify("""
            CREATE TABLE accounts (id bigint PRIMARY KEY, payload integer);
            INSERT INTO accounts VALUES (1, 1);
            ALTER TABLE accounts ADD CONSTRAINT payload_nonnegative CHECK (payload >= 0) NOT VALID;
        """.trimIndent(), Step("""
            ALTER TABLE accounts ALTER COLUMN payload SET DEFAULT 0, VALIDATE CONSTRAINT payload_nonnegative;
        """.trimIndent()))
        val statement = assessment(report).statements.single()
        assertTrue(statement.heldLocksBefore.none { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, statement.toString())
        assertTrue(statement.heldLocksAfter.any { it.relation == "public.accounts" && it.mode == "AccessExclusiveLock" }, statement.toString())
        assertRule(statement, "OP_VALIDATION_UNDER_COMPOUND_LOCK", OperationalVerdict.RISKY)
    }

    @Test fun `new tables do not hide custom type input or implicit assignment cast behavior`() {
        val report = verify("""
            CREATE DOMAIN custom_input AS integer CHECK (VALUE >= 0);
            CREATE FUNCTION pg_catalog.convert_timestamp(integer) RETURNS timestamp LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                LOCK TABLE control IN ACCESS EXCLUSIVE MODE;
                RETURN timestamp '2000-01-01' + make_interval(secs => ${'$'}1);
            END ${'$'}${'$'};
            CREATE CAST (integer AS timestamp) WITH FUNCTION pg_catalog.convert_timestamp(integer) AS ASSIGNMENT;
            CREATE FUNCTION pg_catalog.epoch_int_cmp(integer, integer) RETURNS integer LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                LOCK TABLE control IN ACCESS EXCLUSIVE MODE;
                RETURN CASE WHEN ${'$'}1 < ${'$'}2 THEN -1 WHEN ${'$'}1 > ${'$'}2 THEN 1 ELSE 0 END;
            END ${'$'}${'$'};
            CREATE OPERATOR CLASS pg_catalog.epoch_int_ops FOR TYPE integer USING btree AS
                OPERATOR 1 < (integer, integer), OPERATOR 2 <= (integer, integer),
                OPERATOR 3 = (integer, integer), OPERATOR 4 >= (integer, integer),
                OPERATOR 5 > (integer, integer), FUNCTION 1 pg_catalog.epoch_int_cmp(integer, integer);
        """.trimIndent(), Step("CREATE TABLE custom_table (value custom_input DEFAULT 1);"),
            Step("CREATE TABLE fresh_cast (value integer); INSERT INTO fresh_cast VALUES (1);"),
            Step("ALTER TABLE fresh_cast ALTER COLUMN value TYPE timestamp;"),
            Step("CREATE TABLE fresh_index (value integer); INSERT INTO fresh_index VALUES (1),(2); CREATE INDEX custom_ops ON fresh_index(value pg_catalog.epoch_int_ops);"))
        assertEquals(OperationalVerdict.UNKNOWN, assessment(report).verdict, report.toString())
        val cast = assessment(report, 2).statements.single()
        assertEquals(OperationalVerdict.RISKY, cast.verdict, cast.toString())
        assertTrue(cast.heldLocksAfter.any { it.relation == "public.control" && it.mode == "AccessExclusiveLock" }, cast.toString())
        assertTrue(cast.findings.any { it.ruleId == "OP_STATEMENT_UNSUPPORTED" }, cast.toString())
        assertTrue(cast.findings.any { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }, cast.toString())
        val index = assessment(report, 3).statements.last()
        assertEquals(OperationalVerdict.RISKY, index.verdict, index.toString())
        assertTrue(index.heldLocksAfter.any { it.relation == "public.control" && it.mode == "AccessExclusiveLock" }, index.toString())
        assertTrue(index.findings.any { it.verdict == OperationalVerdict.UNKNOWN }, index.toString())
        assertTrue(index.findings.any { it.ruleId == "OP_WRITE_BLOCKING_LOCK" }, index.toString())
    }

    @Test fun `new child FK validation cannot hide a retained write-blocking lock on an old parent`() {
        val report = verify("CREATE TABLE old_parent (id bigint PRIMARY KEY); INSERT INTO old_parent VALUES (1);", Step("""
            ALTER TABLE old_parent ADD COLUMN metadata text;
            CREATE TABLE fresh_child (id bigint PRIMARY KEY, parent_id bigint);
            ALTER TABLE fresh_child ADD CONSTRAINT fresh_parent FOREIGN KEY(parent_id) REFERENCES old_parent(id);
        """.trimIndent()))
        val foreignKey = assessment(report).statements[2]
        assertEquals(OperationalVerdict.RISKY, foreignKey.verdict, foreignKey.toString())
        assertTrue(foreignKey.heldLocksBefore.any { it.relation == "public.old_parent" && it.mode == "AccessExclusiveLock" &&
            it.table?.preExisting == true }, foreignKey.toString())
        assertTrue(foreignKey.findings.any { it.verdict == OperationalVerdict.RISKY &&
            (it.objectName == "public.old_parent" || "public.old_parent" in it.evidence) }, foreignKey.toString())
    }

    @Test fun `supplied sizes grade a renamed old table without downgrading risk and measurements are reproducible`() {
        val baseline = """
            CREATE SCHEMA "OpsSchema";
            CREATE TABLE "OpsSchema"."OldPayload" (id bigint PRIMARY KEY, payload integer);
            INSERT INTO "OpsSchema"."OldPayload" VALUES (1, 1);
        """.trimIndent()
        val change = Step("""
            ALTER TABLE "OpsSchema"."OldPayload" RENAME TO "RenamedPayload";
            ALTER TABLE "OpsSchema"."RenamedPayload" ALTER COLUMN payload TYPE bigint;
        """.trimIndent())
        fun sizes(name: String, rows: Long, bytes: Long): TableSizes {
            val file = directory.resolve("$name-sizes.json")
            json.writeValue(file.toFile(), TableSizes(tables = mapOf("OpsSchema.OldPayload" to TableSize(rows, bytes))))
            return json.readValue(file.toFile())
        }
        val small = sizes("small", 100, 1_048_576)
        val large = sizes("large", 2_000_000, 2_147_483_648)
        val missing = verify(baseline, change)
        val smallReport = verify(baseline, change, options = VerificationOptions(tableSizes = small))
        val largeReport = verify(baseline, change, options = VerificationOptions(tableSizes = large))
        val repeated = verify(baseline, change, options = VerificationOptions(tableSizes = small))
        for ((report, expected) in listOf(missing to OperationalSeverity.HIGH,
            smallReport to OperationalSeverity.LOW, largeReport to OperationalSeverity.HIGH)) {
            assertEquals(OperationalVerdict.RISKY, report.operationalVerdict, report.toString())
            val rewrite = assessment(report).statements[1]
            assertTrue(rewrite.rewrittenTables.any { it.table.name == "OpsSchema.RenamedPayload" &&
                it.table.preExisting == true && it.table.baselineName == "OpsSchema.OldPayload" }, rewrite.toString())
            val findings = rewrite.findings.filter { it.ruleId == "OP_TABLE_REWRITE" }
            assertTrue(findings.isNotEmpty(), rewrite.toString())
            assertTrue(findings.all { it.verdict == OperationalVerdict.RISKY && it.severity == expected }, rewrite.toString())
        }
        val firstJson = json.valueToTree<JsonNode>(assessment(smallReport))
        val againJson = json.valueToTree<JsonNode>(assessment(repeated))
        assertEquals(firstJson, againJson, "Fresh databases must retain identical portable operational evidence.")
        assertNoPhysicalIdentifiers(firstJson)
    }

    @Test fun `Flyway preserves a dollar-quoted procedural statement as one unsupported operation`() {
        val report = verify("CREATE TABLE accounts (id bigint PRIMARY KEY);", Step("""
            DO ${'$'}${'$'} BEGIN PERFORM 1; PERFORM 2; END ${'$'}${'$'};
            SET LOCAL lock_timeout = '750ms';
            ALTER TABLE accounts ADD COLUMN metadata text;
        """.trimIndent()))
        val measured = assessment(report)
        assertEquals(listOf(1, 2, 3), measured.statements.map { it.ordinal }, measured.toString())
        val procedural = measured.statements.first()
        assertTrue(procedural.sql.trimStart().startsWith("DO "), procedural.toString())
        assertTrue("PERFORM 1; PERFORM 2;" in procedural.sql, procedural.toString())
        assertRule(procedural, "OP_STATEMENT_UNSUPPORTED", OperationalVerdict.UNKNOWN)
        assertEquals(OperationalVerdict.UNKNOWN, report.operationalVerdict)
    }

    @Test fun `event trigger command tags distinguish applicable callbacks from unrelated DDL`() {
        val report = verify("""
            CREATE TABLE accounts (id bigint PRIMARY KEY);
            CREATE FUNCTION observe_alter() RETURNS event_trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RETURN; END ${'$'}${'$'};
            CREATE EVENT TRIGGER on_alter ON ddl_command_start WHEN TAG IN ('ALTER TABLE') EXECUTE FUNCTION observe_alter();
        """.trimIndent(), Step("CREATE TABLE fresh_table (id bigint PRIMARY KEY);"),
            Step("ALTER TABLE accounts ADD COLUMN metadata text;"))
        assertEquals(OperationalVerdict.SAFE, assessment(report).verdict, report.toString())
        assertTrue(assessment(report).findings.none { it.ruleId == "OP_DATABASE_CALLBACK_UNPROVEN" }, report.toString())
        val callback = assessment(report, 1).statements.single()
        assertEquals(OperationalVerdict.RISKY, callback.verdict, callback.toString())
        assertTrue(callback.findings.any { it.ruleId == "OP_DATABASE_CALLBACK_UNPROVEN" && it.verdict == OperationalVerdict.UNKNOWN })
        assertTrue(callback.findings.any { it.ruleId == "OP_WRITE_BLOCKING_LOCK" && it.verdict == OperationalVerdict.RISKY })
    }

    @Test @Timeout(90)
    fun `a timed out run retains immutable operational evidence for its completed migration`() {
        val progress = mutableListOf<CompatibilityReport>()
        val failure = assertThrows(VerificationException::class.java) {
            verify("CREATE TABLE accounts (id bigint PRIMARY KEY);", Step("CREATE INDEX accounts_lookup ON accounts(id);"),
                Step("SELECT pg_sleep(5);"), options = VerificationOptions(migrationTimeoutSeconds = 2, globalTimeoutSeconds = 60,
                    progress = { direction, snapshot -> if (direction == "old-on-new") progress += snapshot }))
        }
        assertEquals("MIGRATION_TIMEOUT", failure.stage, failure.toString())
        val retained = progress.last()
        assertFalse(retained.complete)
        assertEquals(listOf("2"), retained.migrations.map { it.migration.version }, retained.toString())
        val first = assessment(retained).statements.single()
        assertRule(first, "OP_INDEX_BUILD_BLOCKS_WRITES", OperationalVerdict.RISKY)
        assertEquals(OperationalVerdict.RISKY, retained.operationalVerdict, retained.toString())
    }

    private fun assertNoPhysicalIdentifiers(node: JsonNode) {
        if (node.isObject) {
            val fields = node.fields()
            while (fields.hasNext()) {
                val (name, value) = fields.next()
                assertFalse(name.lowercase() in setOf("oid", "relfilenode", "filenode", "pid", "timestamp", "duration", "elapsed"), node.toPrettyString())
                assertNoPhysicalIdentifiers(value)
            }
        } else if (node.isArray) node.forEach(::assertNoPhysicalIdentifiers)
    }
}
