package dev.epoch

import dev.epoch.core.*
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Every verification uses a real disposable PostgreSQL server; these tests never skip Docker failures. */
class PostgresCompatibilityTest {
    @TempDir lateinit var directory: Path

    private fun verify(vararg migrations: Pair<String, String>, contract: DatabaseContract = customerContract()): CompatibilityReport {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(BASELINE)
        val pending = directory.resolve("pending").createDirectories()
        migrations.forEach { (name, sql) -> pending.resolve(name).writeText(sql) }
        return PostgresVerifier().verify(contract, baseline, pending)
    }

    @Test fun `adding nullable column preserves explicit column contract`() {
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;")
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertEquals(VerificationScope.STRUCTURAL_CONTRACT, report.scope)
        assertEquals(listOf("2"), report.migrations.map { it.migration.version })
    }

    @Test fun `dropped mapped column identifies affected old operation`() {
        val report = verify("V2__drop_email.sql" to "ALTER TABLE customers DROP COLUMN email;")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.BREAKING && it.affectedContractIds.isNotEmpty() && it.evidence.isNotBlank()
        }, report.toString())
    }

    @Test fun `renamed referenced column is breaking without inferring rename intent`() {
        assertEquals(Verdict.BREAKING, verify("V2__rename_email.sql" to
            "ALTER TABLE customers RENAME COLUMN email TO email_address;").verdict)
    }

    @Test fun `renamed table breaks old queries`() {
        assertEquals(Verdict.BREAKING, verify("V2__rename_customers.sql" to
            "ALTER TABLE customers RENAME TO clients;").verdict)
    }

    @Test fun `new mandatory column without default breaks established insert`() {
        val report = verify("V2__mandatory.sql" to "ALTER TABLE customers ADD COLUMN region text NOT NULL;")
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any {
            it.verdict == Verdict.BREAKING && it.objectName.contains("region")
        }, report.toString())
    }

    @Test fun `new check is unknown without accepted input proof`() {
        val report = verify("V2__check.sql" to
            "ALTER TABLE customers ADD CONSTRAINT nonempty_email CHECK (length(email) > 0);")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    @Test fun `new unique constraint is unknown rather than automatically safe`() {
        val report = verify("V2__unique.sql" to "ALTER TABLE customers ADD CONSTRAINT unique_email UNIQUE (email);")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    @Test fun `changed result and parameter type cannot be reported safe`() {
        val report = verify("V2__type.sql" to "ALTER TABLE customers ALTER COLUMN email TYPE integer USING email::integer;")
        assertNotEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `intermediate incompatibility remains breaking when final migration restores column`() {
        val report = verify(
            "V2__drop.sql" to "ALTER TABLE customers DROP COLUMN email;",
            "V3__restore.sql" to "ALTER TABLE customers ADD COLUMN email text NOT NULL;",
        )
        assertEquals(listOf("2", "3"), report.migrations.map { it.migration.version })
        assertEquals(Verdict.BREAKING, report.migrations.first().verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
    }

    @Test fun `unrelated nullable column change preserves scoped contract`() {
        val report = verify("V2__unrelated.sql" to "ALTER TABLE audit_log ADD COLUMN details text;")
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `unresolved dynamic query prevents safe verdict`() {
        val contract = customerContract().copy(unresolvedQueries = listOf(
            UnresolvedQuery("SearchRepository.search", "Runtime concatenation has no declared finite variant set")))
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;", contract = contract)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    @Test fun `data transformation remains unknown even when catalog is unchanged`() {
        val report = verify("V2__rewrite.sql" to "UPDATE customers SET email = lower(email);")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(report.migrations.single().findings.any { it.verdict == Verdict.UNKNOWN && it.evidence.isNotBlank() })
    }

    @Test fun `same-type USING data rewrite is not hidden by unchanged catalogs`() {
        val report = verify("V2__rewrite_using.sql" to
            "ALTER TABLE customers ALTER COLUMN email TYPE text USING reverse(email);")
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    @Test fun `drop and recreate same table shape cannot hide destroyed data`() {
        val report = verify("V2__replace.sql" to """
            DROP TABLE customers;
            CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);
        """.trimIndent())
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    @Test fun `Flyway numeric ordering is used rather than lexical filename order`() {
        val report = verify(
            "V10__drop.sql" to "ALTER TABLE customers DROP COLUMN email;",
            "V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;",
        )
        assertEquals(listOf("2", "10"), report.migrations.map { it.migration.version })
        assertEquals(Verdict.SAFE, report.migrations.first().verdict, report.toString())
        assertEquals(Verdict.BREAKING, report.verdict)
    }

    @Test fun `SELECT star result shape is not compatible with added column`() {
        val contract = customerContract().copy(queries = listOf(QueryContract(
            id = "customer.star", operation = Operation.SELECT, sql = "select * from customers",
            tables = listOf("public.customers"), columns = listOf(ColumnReference("public.customers", "id"), ColumnReference("public.customers", "email")),
            resultColumns = listOf(ResultColumn("id", "bigint"), ResultColumn("email", "text")),
            dependenciesComplete = true, explicitColumnList = false, exactResultShape = true,
        )))
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;", contract = contract)
        assertNotEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `incorrect declared result columns cannot establish a valid baseline`() {
        val original = customerContract()
        val contract = original.copy(queries = original.queries.map { query ->
            if (query.id == "Customer.find") query.copy(resultColumns = listOf(
                ResultColumn("id", "bigint"), ResultColumn("wrong_email_label", "integer"),
            )) else query
        })
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;", contract = contract)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(requireNotNull(report.baseline).findings.any {
            it.ruleId == "BASELINE_SQL_UNVERIFIED" && it.migration == null && "Customer.find" in it.affectedContractIds &&
                it.evidence.contains("result columns")
        }, report.toString())
    }

    @Test fun `incorrect parameter count cannot establish a valid baseline`() {
        val original = customerContract()
        val contract = original.copy(queries = original.queries.map { query ->
            if (query.id == "Customer.find") query.copy(parameterTypes = emptyList()) else query
        })
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;", contract = contract)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
        assertTrue(requireNotNull(report.baseline).findings.any {
            it.ruleId == "BASELINE_SQL_UNVERIFIED" && it.migration == null && "Customer.find" in it.affectedContractIds &&
                it.evidence.contains("1 parameters but 0 declared")
        }, report.toString())
    }

    @Test fun `whole application safety cannot be asserted from declared CRUD alone`() {
        val report = verify("V2__metadata.sql" to "ALTER TABLE customers ADD COLUMN metadata text;",
            contract = customerContract().copy(scope = VerificationScope.WHOLE_APPLICATION))
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }

    companion object {
        const val BASELINE = """
            CREATE TABLE customers (id bigint PRIMARY KEY, email text NOT NULL);
            CREATE TABLE audit_log (id bigint PRIMARY KEY);
        """

        fun customerContract(): DatabaseContract {
            val id = ColumnReference("public.customers", "id")
            val email = ColumnReference("public.customers", "email")
            return DatabaseContract(
                applicationVersion = "fixture-1.0",
                entities = listOf(EntityContract("Customer", "public.customers", listOf(
                    ColumnContract("id", "bigint", nullable = false),
                    ColumnContract("email", "text", nullable = false),
                ))),
                queries = listOf(
                    QueryContract("Customer.find", Operation.SELECT, "select id, email from customers where id = ?",
                        tables = listOf("public.customers"), columns = listOf(id, email), parameterTypes = listOf("bigint"),
                        resultColumns = listOf(ResultColumn("id", "bigint"), ResultColumn("email", "text")), dependenciesComplete = true),
                    QueryContract("Customer.insert", Operation.INSERT, "insert into customers (id, email) values (?, ?)",
                        tables = listOf("public.customers"), columns = listOf(id, email), writeColumns = listOf(id, email),
                        parameterTypes = listOf("bigint", "text"), dependenciesComplete = true, targetTable = "public.customers"),
                    QueryContract("Customer.update", Operation.UPDATE, "update customers set email = ? where id = ?",
                        tables = listOf("public.customers"), columns = listOf(id, email), writeColumns = listOf(email),
                        parameterTypes = listOf("text", "bigint"), dependenciesComplete = true, targetTable = "public.customers"),
                    QueryContract("Customer.delete", Operation.DELETE, "delete from customers where id = ?",
                        tables = listOf("public.customers"), columns = listOf(id), parameterTypes = listOf("bigint"), dependenciesComplete = true, targetTable = "public.customers"),
                ),
                coverage = Coverage(complete = true, mechanisms = listOf("Explicit finite fixture CRUD contract"),
                    notes = listOf("Only these four SQL operations and their structural dependencies are in scope")),
            )
        }
    }
}
