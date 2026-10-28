package dev.epoch

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
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class BehaviorCatalogBoundaryTest {
    @TempDir lateinit var directory: Path
    private fun insert(table: String, id: String = "$table.insert") = QueryContract(id, Operation.INSERT,
        "INSERT INTO public.$table (label) VALUES (?)", tables = listOf("public.$table"),
        columns = listOf(ColumnReference("public.$table", "label")), writeColumns = listOf(ColumnReference("public.$table", "label")),
        parameterTypes = listOf("text"), targetTable = "public.$table", dependenciesComplete = true)

    @Test @Timeout(150)
    fun `system dependency nodes stop at catalog boundary while reached user code stays opaque`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { it.execute("""
                        CREATE TABLE lookup_values(id bigint PRIMARY KEY, label text);
                        CREATE FUNCTION nested_lookup() RETURNS text LANGUAGE sql STABLE
                          RETURN (SELECT label FROM lookup_values WHERE id = 1);
                        CREATE FUNCTION delegated_lookup() RETURNS text LANGUAGE sql STABLE RETURN nested_lookup();
                        CREATE FUNCTION pg_catalog.epoch_user_clock() RETURNS timestamptz LANGUAGE plpgsql STABLE AS
                          'BEGIN RETURN now(); END';
                        CREATE TABLE opaque_rows(id bigserial PRIMARY KEY, label text,
                          marker text DEFAULT delegated_lookup(), stamp timestamptz DEFAULT pg_catalog.epoch_user_clock());
                        CREATE TABLE ordinary_rows(id bigserial PRIMARY KEY, label text,
                          created_at timestamptz DEFAULT CURRENT_TIMESTAMP,
                          modified_at timestamp DEFAULT now(), status text DEFAULT 'NEW', CHECK (id >= 0), CHECK (length(label) >= 0));
                        CREATE INDEX ordinary_label_idx ON ordinary_rows(label);
                        CREATE INDEX ordinary_lower_idx ON ordinary_rows(lower(label)) WHERE id >= 0;
                        CREATE FUNCTION pg_catalog.epoch_user_equal(a bigint,b bigint) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS
                          'BEGIN RETURN a=b; END';
                        CREATE OPERATOR public.=== (LEFTARG=bigint, RIGHTARG=bigint, PROCEDURE=pg_catalog.epoch_user_equal);
                        CREATE TABLE operator_rows(id bigint, label text, CHECK(id OPERATOR(public.===) id));
                        CREATE FUNCTION unrelated_function() RETURNS text LANGUAGE plpgsql AS
                          'BEGIN RETURN ''unused''; END';
                    """.trimIndent()) }
                    val snapshot = PostgresCatalog().inspect(connection)
                    val graph = requireNotNull(snapshot.behaviorGraph)
                    assertTrue(graph.nodes.values.none { it.kind == "pg_language" },
                        "SQL and PLpgSQL languages are infrastructure, not caller behavior roots: ${graph.nodes}")
                    assertTrue(graph.nodes.keys.none { it.startsWith("catalog:language:") || it.startsWith("catalog:table:pg_catalog.") })
                    val syntax = BehaviorQuerySyntax()
                    val ordinary = BehaviorScope(snapshot).forQuery(insert("ordinary_rows"), syntax.inspect(insert("ordinary_rows")))
                    assertTrue(ordinary.uncertainties.isEmpty(), ordinary.toString())
                    assertTrue(ordinary.references.any { it.kind == BehaviorReferenceKind.SEQUENCE && it.name == "public.ordinary_rows_id_seq" })
                    val opaque = BehaviorScope(snapshot).forQuery(insert("opaque_rows"), syntax.inspect(insert("opaque_rows")))
                    assertTrue(opaque.semanticNodeIds.any { it.startsWith("function:pg_catalog.epoch_user_clock(") },
                        "A normal-OID user function remains visible even in pg_catalog")
                    assertTrue(opaque.references.any { it.name == "public.lookup_values" && it.column == "label" }, opaque.toString())
                    assertTrue(opaque.uncertainties.any { it.nodeId.startsWith("function:public.nested_lookup(") })
                    assertTrue(opaque.uncertainties.size <= 5, "Only two defaults and three actually reached functions: $opaque")
                    assertFalse(opaque.semanticNodeIds.any { it.startsWith("function:public.unrelated_function(") })
                    val customOperator = BehaviorScope(snapshot).forQuery(insert("operator_rows"), syntax.inspect(insert("operator_rows")))
                    assertTrue(customOperator.uncertainties.isNotEmpty(), customOperator.toString())
                    assertTrue(customOperator.semanticNodeIds.any { it.startsWith("function:pg_catalog.epoch_user_equal(") },
                        "Normal-OID operators and their functions remain reachable even in pg_catalog")
                    // Repeated introspection produces the same stable graph without runtime addresses.
                    assertEquals(graph, PostgresCatalog().inspect(connection).behaviorGraph)
                }
            }
    }

    @Test @Timeout(180)
    fun `large readonly syntax debt is not repeated for unrelated expansion and ordinary renamed attachments stay stable`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val checks = (0 until 96).joinToString(",") { "CONSTRAINT amount_$it CHECK (id >= 0)" }
        val unused = (0 until 96).joinToString("\n") { index ->
            "CREATE FUNCTION helper_$index() RETURNS bigint LANGUAGE sql IMMUTABLE AS 'SELECT 1::bigint';"
        }
        baseline.resolve("V1__baseline.sql").writeText("""
            CREATE TABLE accounts(id bigserial PRIMARY KEY, label text,
              created_at timestamptz DEFAULT CURRENT_TIMESTAMP, modified_at timestamp DEFAULT now(),
              status text DEFAULT 'NEW', CONSTRAINT label_length CHECK (length(label) >= 0), $checks);
            CREATE INDEX accounts_label_idx ON accounts(label);
            CREATE INDEX accounts_lower_idx ON accounts(lower(label)) WHERE id >= 0;
            CREATE TABLE badges(id bigint PRIMARY KEY, account_id bigint);
            CREATE TABLE audit(id bigint PRIMARY KEY);
            $unused
        """.trimIndent())
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__unrelated.sql").writeText("ALTER TABLE audit ADD COLUMN optional text;")
        pending.resolve("V3__compatibility_view.sql").writeText("""
            ALTER TABLE accounts RENAME TO accounts_v2;
            CREATE VIEW accounts AS SELECT * FROM accounts_v2;
        """.trimIndent())
        val readonly = (0 until 120).map { index -> QueryContract("repo.read.$index", Operation.SELECT,
            if (index % 2 == 0) "WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen /* $index */"
            else "SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT b.id FROM public.badges b WHERE b.account_id=a.id) /* $index */",
            tables = listOf("public.accounts", "public.badges"),
            columns = listOf(ColumnReference("public.accounts", "id"), ColumnReference("public.badges", "id"),
                ColumnReference("public.badges", "account_id")), dependenciesComplete = true) }
        val contract = DatabaseContract(queries = readonly + insert("accounts"), coverage = Coverage(complete = true))
        val report = PostgresVerifier().verify(contract, baseline, pending,
            VerificationOptions(migrationTimeoutSeconds = 45, globalTimeoutSeconds = 150))
        val health = requireNotNull(report.baseline)
        assertEquals(Verdict.UNKNOWN, health.verdict, "Readonly semantic coverage debt is still visible")
        assertTrue(health.findings.none { it.affectedContractIds.contains("accounts.insert") }, health.toString())
        assertTrue(health.findings.size <= readonly.size + 2, "No system-language/index/CHECK/default amplification")
        val unrelated = report.migrations[0]
        assertEquals(Verdict.SAFE, unrelated.verdict, unrelated.toString())
        assertTrue(unrelated.findings.none { it.ruleId.startsWith("DATABASE_BEHAVIOR") }, unrelated.toString())
        val renamed = report.migrations[1]
        assertTrue(renamed.findings.none { it.ruleId == "DATABASE_BEHAVIOR_CHANGED" ||
            it.ruleId == "RELATION_RENAME_EXPRESSION_UNPROVEN" }, renamed.toString())
        assertTrue(renamed.findings.filter { it.ruleId == "DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN" }
            .flatMap { it.affectedContractIds }.none { it == "accounts.insert" }, renamed.toString())
        assertTrue(renamed.findings.size <= readonly.size + 12, "Unchanged owned attachments must not multiply rename findings")
        assertEquals(Verdict.UNKNOWN, report.verdict, "Baseline coverage debt is never silently promoted to SAFE")
    }
}
