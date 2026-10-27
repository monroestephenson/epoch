package dev.epoch

import dev.epoch.core.*
import dev.epoch.fixtures.*
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class HibernateContractTest {
    @TempDir lateinit var directory: Path

    @Test fun `actual Hibernate generated CRUD contract verifies against nullable addition`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            discoverRepositories = false,
            entityClasses = listOf(PlainCustomer::class.java.name), applicationVersion = "hibernate-fixture-1.0"))
        assertTrue(contract.entities.any { it.table == "public.customers" && it.columns.any { c -> c.name == "email" } })
        assertTrue(contract.queries.any { it.source == QuerySource.HIBERNATE_MAPPING && it.operation == Operation.INSERT })
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE customers (id bigint PRIMARY KEY, email varchar(255) NOT NULL);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__metadata.sql").writeText("ALTER TABLE customers ADD COLUMN metadata text;")
        val report = PostgresVerifier().verify(contract, baseline, pending)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `named JPQL is translated through Hibernate`() {
        val contract = extractQueries()
        val query = contract.queries.firstOrNull { it.id.contains("Customer.byEmail") }
        assertNotNull(query, contract.toString())
        assertEquals(QuerySource.NAMED_HQL, query!!.source)
        assertTrue(query.sql.contains("customers", ignoreCase = true), query.sql)
        assertTrue(query.sql.contains("email", ignoreCase = true), query.sql)
    }

    @Test fun `named native SQL remains distinguishable from translated ORM queries`() {
        val contract = extractQueries()
        val query = contract.queries.firstOrNull { it.id.contains("Customer.nativeByEmail") }
        assertNotNull(query, contract.toString())
        assertEquals(QuerySource.NAMED_NATIVE, query!!.source)
        assertTrue(query.dependenciesComplete, "The supported explicit-column native query has complete structural dependencies")
        assertEquals(setOf("id", "email"), query.columns.map { it.column }.toSet())
    }

    @Test fun `association mapping preserves physical foreign key and query join`() {
        val contract = extractQueries()
        val purchase = contract.entities.first { it.table == "public.purchases" }
        assertTrue(purchase.columns.any { it.name == "customer_id" }, purchase.toString())
        val query = contract.queries.firstOrNull { it.id.contains("Purchase.customerEmail") }
        assertNotNull(query, contract.toString())
        assertTrue(query!!.sql.contains("join", ignoreCase = true), query.sql)
        assertTrue(query.sql.contains("customer_id", ignoreCase = true), query.sql)
    }

    @Test fun `declared finite HQL query variants are enumerated and labeled`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            discoverRepositories = false,
            entityClasses = listOf(PlainCustomer::class.java.name),
            finiteHqlVariants = mapOf("Customer.sort" to listOf(
                "select c from PlainCustomer c order by c.id",
                "select c from PlainCustomer c order by c.email",
            )),
        ))
        val variants = contract.queries.filter { it.source == QuerySource.DYNAMIC_VARIANT }
        assertEquals(2, variants.size, contract.toString())
        assertEquals(2, variants.map { it.sql }.distinct().size)
    }

    @Test fun `unbounded dynamic query remains explicitly unresolved`() {
        val contract = HibernateExtractor().extract(ExtractionRequest(
            discoverRepositories = false,
            entityClasses = listOf(PlainCustomer::class.java.name),
            unresolvedDynamicQueries = mapOf("Customer.search" to "User-controlled predicates produce an unbounded query space"),
        ))
        assertTrue(contract.unresolvedQueries.any { it.id == "Customer.search" }, contract.toString())
        assertFalse(contract.coverage.complete)
    }

    private fun extractQueries() = HibernateExtractor().extract(ExtractionRequest(
        discoverRepositories = false,
        entityClasses = listOf(Customer::class.java.name, Purchase::class.java.name),
    ))
}
