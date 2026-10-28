package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.*
import dev.epoch.postgres.PostgresCatalog
import dev.epoch.postgres.PostgresVerifier
import dev.epoch.postgres.VerificationOptions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BehaviorScopeIntegrationTest {
    @TempDir lateinit var directory: Path

    private fun read(table: String, column: String = "id") = QueryContract("$table.read", Operation.SELECT,
        "SELECT $column FROM $table", tables = listOf("public.$table"),
        columns = listOf(ColumnReference("public.$table", column)), dependenciesComplete = true)

    private fun insert(table: String, id: String = "$table.insert", marker: String? = null): QueryContract {
        val names = if (marker == null) listOf("id") else listOf("id", "marker")
        val columns = names.map { ColumnReference("public.$table", it) }
        val values = if (marker == null) "?" else "?, $marker"
        return QueryContract(id, Operation.INSERT, "INSERT INTO $table (${names.joinToString()}) VALUES ($values)",
            tables = listOf("public.$table"), columns = columns, writeColumns = columns,
            parameterTypes = List(if (marker == "?") 2 else 1) { "bigint" }, targetTable = "public.$table",
            dependenciesComplete = true)
    }

    @Test @Timeout(240)
    fun `opaque defaults affect only consumers and grouped evidence retains every calling ID`() {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(OPAQUE_BASELINE)
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__unrelated.sql").writeText("ALTER TABLE independent ADD COLUMN metadata text;")
        pending.resolve("V3__lookup.sql").writeText("ALTER TABLE lookup_values ADD COLUMN metadata text;")
        val consumers = (0 until 20).map { index ->
            insert("accounts", "accounts.omitted.$index").let { it.copy(sql = it.sql + " /* caller $index */") }
        } + insert("accounts", "accounts.explicit-default", "DEFAULT")
        val unaffected = listOf(insert("accounts", "accounts.bound", "?"), read("accounts"), read("independent"))
        val expectedIds = consumers.map { it.id }.toSet()
        val contract = DatabaseContract(applicationVersion = "opaque-default-callers", queries = consumers + unaffected,
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite SQL templates")))
        val options = VerificationOptions(baselineCache = directory.resolve("cache"), planningParallelism = 2,
            migrationTimeoutSeconds = 30, globalTimeoutSeconds = 100)
        fun verify(): CompatibilityReport = PostgresVerifier().verify(contract, baseline, pending, options).also { report ->
            assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
            assertTrue(report.complete)
            val baselineFindings = requireNotNull(report.baseline).findings.filter { it.ruleId == "EXISTING_DATABASE_BEHAVIOR" }
            assertScopedAndGrouped(baselineFindings, expectedIds)
            assertTrue(baselineFindings.all { it.migration == null }, baselineFindings.toString())
            assertTrue(requireNotNull(report.baseline).findings.none { it.ruleId == "BASELINE_SQL_UNVERIFIED" ||
                it.ruleId.startsWith("INVALID_BASELINE_") }, report.toString())
            report.migrations.forEach { step ->
                assertEquals(consumers.size, step.replannedQueries, step.toString())
                assertEquals(unaffected.size, step.reusedQueries, step.toString())
                val findings = step.findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
                assertScopedAndGrouped(findings, expectedIds)
                assertTrue(findings.all { it.migration == step.migration }, findings.toString())
            }
        }
        val cold = verify()
        val warm = verify()
        val warmHealth = requireNotNull(warm.baseline)
        assertEquals("HIT", warmHealth.cacheStatus, warmHealth.toString())
        assertEquals(consumers.size, warmHealth.plannedQueries, warmHealth.toString())
        assertEquals(unaffected.size, warmHealth.reusedQueries, warmHealth.toString())
        assertEquals(requireNotNull(cold.baseline).fingerprint, warmHealth.fingerprint)
        assertEquals(cold.migrations.map { it.findings }, warm.migrations.map { it.findings })
    }

    private fun assertScopedAndGrouped(findings: List<Finding>, callers: Set<String>) {
        assertTrue(findings.isNotEmpty(), "The actually invoked opaque function must remain UNKNOWN")
        assertEquals(callers, findings.flatMap { it.affectedContractIds }.toSet(), findings.toString())
        assertTrue(findings.all { it.verdict == Verdict.UNKNOWN && it.affectedContractIds.isNotEmpty() }, findings.toString())
        assertTrue(findings.size < callers.size, "One root cause must not produce one row for every caller: $findings")
        assertEquals(findings.size, findings.map { listOf(it.ruleId, it.objectName, it.message, it.evidence) }.distinct().size,
            "Repeated root causes must merge their affected IDs")
    }

    @Test @Timeout(150)
    fun `real catalog edges preserve view column identity defaults FK activation and stable names`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.execute("CREATE ROLE epoch_behavior_reader;")
                    connection.execute(GRAPH_BASELINE)
                    val catalog = PostgresCatalog()
                    val first = catalog.inspect(connection)
                    val graph = requireNotNull(first.behaviorGraph)
                    assertTrue(graph.attachmentInventoryComplete)
                    val syntax = BehaviorQuerySyntax()
                    val scopes = BehaviorScope(first)
                    fun scope(query: QueryContract) = scopes.forQuery(query, syntax.inspect(query))

                    val directView = graph.nodes.getValue("view:public.used_view")
                    assertTrue(directView.references.any { it.name == "public.view_base" && it.column == "label" &&
                        it.kind == BehaviorReferenceKind.COLUMN && it.access == BehaviorAccess.SELECT }, directView.toString())
                    assertTrue(directView.references.none { it.column == "removed" }, directView.toString())
                    val nested = scope(read("nested_view", "label"))
                    assertTrue(nested.semanticNodeIds.containsAll(setOf("view:public.nested_view", "view:public.used_view")), nested.toString())
                    assertTrue(nested.references.any { it.name == "public.view_base" && it.column == "label" }, nested.toString())
                    val base = scope(read("view_base", "label"))
                    assertTrue(base.semanticNodeIds.none { it.startsWith("view:") }, base.toString())
                    val stored = scope(read("stored_view", "label"))
                    assertTrue(stored.semanticNodeIds.none { it == "view:public.used_view" }, stored.toString())
                    assertTrue(stored.references.none { it.name == "public.view_base" }, stored.toString())

                    val defaultRoot = "default:public.accounts.marker"
                    val opaqueFunction = "function:public.opaque_lookup()"
                    listOf(insert("accounts"), insert("accounts", marker = "DEFAULT")).forEach { query ->
                        val reached = scope(query)
                        assertTrue(defaultRoot in reached.semanticNodeIds && opaqueFunction in reached.semanticNodeIds, reached.toString())
                        assertTrue(reached.uncertainties.isNotEmpty(), reached.toString())
                    }
                    listOf(insert("accounts", marker = "?"), read("accounts")).forEach { query ->
                        val reached = scope(query)
                        assertFalse(defaultRoot in reached.semanticNodeIds || opaqueFunction in reached.semanticNodeIds, reached.toString())
                    }
                    val parsed = scope(insert("parsed_accounts"))
                    assertTrue(parsed.semanticNodeIds.containsAll(setOf("default:public.parsed_accounts.marker",
                        "function:public.delegated_value()", "function:public.parsed_value()")), parsed.toString())
                    assertTrue(parsed.references.any { it.name == "public.lookup_values" && it.column == "value" }, parsed.toString())
                    val observer = scope(insert("name_observer"))
                    assertTrue(observer.uncertainties.isNotEmpty(),
                        "Pinned relation-name lookup has hidden dependencies despite a parsed body: $observer")

                    val parentId = ColumnReference("public.parent", "id")
                    val deletion = QueryContract("parent.delete", Operation.DELETE, "DELETE FROM parent WHERE id = ?",
                        tables = listOf("public.parent"), columns = listOf(parentId), parameterTypes = listOf("bigint"),
                        targetTable = "public.parent", dependenciesComplete = true)
                    val childTrigger = "trigger:public.child.child_audit"
                    val cascade = scope(deletion)
                    assertTrue(childTrigger in cascade.semanticNodeIds, cascade.toString())
                    assertTrue(cascade.references.any { it.name == "public.child" && it.access == BehaviorAccess.DELETE }, cascade.toString())
                    assertTrue(cascade.uncertainties.isNotEmpty(), cascade.toString())
                    assertFalse(childTrigger in cascade.planningNodeIds,
                        "PREPARE and EXPLAIN do not execute ordinary row triggers: $cascade")
                    assertFalse(childTrigger in scope(read("parent")).semanticNodeIds)
                    assertFalse(childTrigger in scope(read("independent")).semanticNodeIds)
                    val cast = scope(QueryContract("domain.cast", Operation.SELECT, "SELECT CAST(? AS public.positive_id)",
                        parameterTypes = listOf("bigint"), dependenciesComplete = true))
                    assertTrue(cast.planningMayEvaluate || cast.planningUncertainties.isNotEmpty(),
                        "A table-free custom-domain cast cannot escape local planning guards: $cast")

                    assertTrue(first.behaviors.getValue("policy:public.policy_rows.read_rows").contains("epoch_behavior_reader"),
                        "Policy identities must use role names instead of cluster-local OIDs")
                    connection.execute("DROP SCHEMA public CASCADE; DROP ROLE epoch_behavior_reader; " +
                        "CREATE ROLE epoch_behavior_reader; CREATE SCHEMA public;")
                    connection.execute(GRAPH_BASELINE)
                    val rebuilt = catalog.inspect(connection)
                    assertNotEquals(first.tables.getValue("public.view_base").relationOid,
                        rebuilt.tables.getValue("public.view_base").relationOid)
                    assertEquals(first.behaviors, rebuilt.behaviors)
                    assertEquals(graph, rebuilt.behaviorGraph,
                        "Recreated relations and roles must retain the same stable dependency graph")
                    assertEquals(jacksonObjectMapper().writeValueAsString(graph),
                        jacksonObjectMapper().writeValueAsString(rebuilt.behaviorGraph))

                    // Actual execution shows why supplying a value and omitting it are distinct paths.
                    connection.execute("INSERT INTO accounts(id) VALUES (1);")
                    assertEquals(7L, connection.scalar("SELECT marker FROM accounts WHERE id = 1"))
                    connection.execute("ALTER TABLE lookup_values RENAME COLUMN value TO renamed_value;")
                    connection.execute("INSERT INTO accounts(id, marker) VALUES (2, 99);")
                    listOf("INSERT INTO accounts(id) VALUES (3)", "INSERT INTO accounts(id, marker) VALUES (3, DEFAULT)").forEach { sql ->
                        val failure = assertThrows(SQLException::class.java) { connection.execute(sql) }
                        assertEquals("42703", failure.sqlState, sql)
                    }
                    connection.execute("INSERT INTO parent VALUES (1); INSERT INTO child VALUES (1, 1);")
                    assertEquals(0L, connection.scalar("SELECT count(*) FROM child_deletions"))
                    connection.execute("DELETE FROM parent WHERE id = 1;")
                    assertEquals(1L, connection.scalar("SELECT count(*) FROM child_deletions"))
                }
            }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql); Unit }
    private fun Connection.scalar(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> assertTrue(result.next()); result.getLong(1) }
    }

    companion object {
        private const val OPAQUE_BASELINE = """
            CREATE TABLE lookup_values (id bigint PRIMARY KEY, value bigint);
            INSERT INTO lookup_values VALUES (1, 7);
            CREATE FUNCTION opaque_lookup() RETURNS bigint LANGUAGE plpgsql STABLE AS
              'DECLARE answer bigint; BEGIN EXECUTE ''SELECT value FROM lookup_values WHERE id = 1'' INTO answer; RETURN answer; END';
            CREATE TABLE accounts (id bigint PRIMARY KEY, marker bigint DEFAULT opaque_lookup());
            CREATE TABLE independent (id bigint PRIMARY KEY);
        """
        private const val GRAPH_BASELINE = OPAQUE_BASELINE + """
            CREATE TABLE view_base (id bigint, removed text, label text);
            ALTER TABLE view_base DROP COLUMN removed;
            CREATE VIEW used_view AS SELECT label FROM view_base;
            CREATE VIEW nested_view AS SELECT label FROM used_view;
            CREATE VIEW unused_view AS SELECT id FROM view_base;
            CREATE MATERIALIZED VIEW stored_view AS SELECT label FROM used_view;
            CREATE FUNCTION parsed_value() RETURNS bigint LANGUAGE sql STABLE
              RETURN (SELECT value FROM lookup_values WHERE id = 1);
            CREATE FUNCTION delegated_value() RETURNS bigint LANGUAGE sql STABLE RETURN parsed_value();
            CREATE TABLE parsed_accounts (id bigint PRIMARY KEY, marker bigint DEFAULT delegated_value());
            CREATE TABLE named_accounts (id bigserial PRIMARY KEY);
            CREATE FUNCTION observed_sequence() RETURNS text LANGUAGE sql STABLE
              RETURN pg_get_serial_sequence('named_accounts', 'id');
            CREATE TABLE name_observer (id bigint PRIMARY KEY, marker text DEFAULT observed_sequence());
            CREATE TABLE parent (id bigint PRIMARY KEY);
            CREATE TABLE child (id bigint PRIMARY KEY, parent_id bigint REFERENCES parent(id) ON DELETE CASCADE);
            CREATE TABLE child_deletions (child_id bigint);
            CREATE FUNCTION audit_child_delete() RETURNS trigger LANGUAGE plpgsql AS
              'BEGIN INSERT INTO child_deletions VALUES (OLD.id); RETURN OLD; END';
            CREATE TRIGGER child_audit AFTER DELETE ON child FOR EACH ROW EXECUTE FUNCTION audit_child_delete();
            CREATE DOMAIN positive_id AS bigint CHECK (VALUE >= 0);
            CREATE TABLE policy_rows (id bigint PRIMARY KEY);
            ALTER TABLE policy_rows ENABLE ROW LEVEL SECURITY;
            CREATE POLICY read_rows ON policy_rows FOR SELECT TO epoch_behavior_reader USING (id >= 0);
        """
    }
}
