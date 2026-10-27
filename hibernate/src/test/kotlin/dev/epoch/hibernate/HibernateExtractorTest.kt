package dev.epoch.hibernate

import dev.epoch.core.Operation
import dev.epoch.core.QuerySource
import dev.epoch.core.VerificationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicInsert
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment
import org.junit.jupiter.api.Test
import kotlin.test.*

class HibernateExtractorTest {
    @Test fun `actual persister plans contain physical table and column names`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name), discoverRepositories = false))
        assertTrue(c.unresolvedQueries.isEmpty(), c.unresolvedQueries.toString())
        assertTrue(c.coverage.complete)
        assertEquals("public.customers", c.entities.single().table)
        assertEquals(setOf("id", "email_address"), c.entities.single().columns.map { it.name }.toSet())
        assertEquals(Operation.entries.toSet(), c.queries.map { it.operation }.toSet())
        assertTrue(c.queries.all { it.source == QuerySource.HIBERNATE_MAPPING && it.sql.contains("customers") })
        assertTrue(c.queries.single { it.operation == Operation.INSERT }.sql.contains("email_address"))
        assertEquals(listOf("BIGINT"), c.queries.single { it.operation == Operation.SELECT }.parameterTypes)
    }

    @Test fun `configured physical naming strategy is applied by Hibernate`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            physicalNamingStrategy = PrefixNamingStrategy::class.java.name, discoverRepositories = false))
        assertEquals("public.pref_customers", c.entities.single().table)
        assertTrue(c.queries.all { it.sql.contains("pref_customers") })
    }

    @Test fun `custom transformer remains unresolved while standard question-mark writes are supported`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(TransformedCustomer::class.java.name), discoverRepositories = false))
        assertFalse(c.coverage.complete)
        assertTrue(c.unresolvedQueries.any { it.reason.contains("Custom column transformer") })
    }

    @Test fun `dynamic generated mutations explicitly remain unresolved`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(DynamicCustomer::class.java.name), discoverRepositories = false))
        assertFalse(c.coverage.complete)
        assertTrue(c.unresolvedQueries.any { it.reason.contains("Dynamic insert/update") })
    }

    @Test fun `fixed HQL is translated through Hibernate with parameter and physical column dependencies`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            hqlQueries = mapOf("lookup" to "select c from Customer c where c.email = :email"), discoverRepositories = false))
        val q = c.queries.single { it.id == "lookup" }
        assertTrue(q.dependenciesComplete, c.unresolvedQueries.toString())
        assertTrue(q.columns.any { it.table == "public.customers" && it.column == "email_address" })
        assertEquals(listOf("VARCHAR"), q.parameterTypes)
        assertTrue(q.sql.contains("email_address"))
        assertTrue(c.unresolvedQueries.isEmpty(), c.unresolvedQueries.toString())
    }

    @Test fun `unsupported native statements become unresolved without losing the rest of extraction`() {
        val c = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name),
            nativeQueries = mapOf("schemaChange" to "create table unexpected (id integer)"), discoverRepositories = false))
        assertFalse(c.coverage.complete)
        assertTrue(c.unresolvedQueries.any { it.id == "schemaChange" })
        assertTrue(c.queries.any { it.source == QuerySource.HIBERNATE_MAPPING })
    }

    @Test fun `runtime collector is deterministic deduplicated and never exhaustive`() {
        val collector = SqlCollector()
        val sql = "select email from customers where id = ?"
        assertEquals(sql, collector.inspect(sql))
        collector.inspect(sql)
        assertEquals(listOf(sql), collector.statements())
        assertEquals(VerificationStatus.OBSERVED, collector.queries().single().verificationStatus)
        assertFalse(collector.queries().single().dependenciesComplete)
        collector.clear()
        assertTrue(collector.statements().isEmpty())
    }
}

@Entity(name = "Customer") @Table(name = "customers")
open class Customer {
    @field:Id open var id: Long = 0
    @field:Column(name = "email_address", nullable = false) open var email: String = ""
}

@Entity(name = "DynamicCustomer") @Table(name = "customers") @DynamicInsert
open class DynamicCustomer {
    @field:Id open var id: Long = 0
    open var email: String? = null
}

class PrefixNamingStrategy : PhysicalNamingStrategyStandardImpl() {
    override fun toPhysicalTableName(logicalName: Identifier, jdbcEnvironment: JdbcEnvironment): Identifier =
        Identifier.toIdentifier("pref_${logicalName.text}", logicalName.isQuoted)
}

@Entity(name = "TransformedCustomer") @Table(name = "customers")
open class TransformedCustomer {
    @field:Id open var id: Long = 0
    @field:ColumnTransformer(read = "lower(email)", write = "upper(?)")
    open var email: String? = null
}
