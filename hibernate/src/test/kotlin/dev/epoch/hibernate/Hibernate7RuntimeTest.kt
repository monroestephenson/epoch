package dev.epoch.hibernate

import dev.epoch.core.Operation
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class Hibernate7RuntimeTest {
    @Test fun `application Hibernate 7 boots in isolation and produces mapping and SQL contracts`() {
        for ((property, version) in listOf("epoch.hibernate7.classpath" to "7.1.4.Final", "epoch.hibernate70.classpath" to "7.0.10.Final")) {
        val classpath = System.getProperty(property).split(File.pathSeparator) +
            Path.of(Customer::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val contract = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name, CollectionRecord::class.java.name,
            IdentityRecord::class.java.name, SequenceRecord::class.java.name, BoundedRecord::class.java.name, JoinedParent::class.java.name, JoinedChild::class.java.name), classpath = classpath,
            hqlQueries = mapOf("lookup7" to "select c from Customer c where c.email = :email",
                "update7" to "update Customer c set c.email = :email where c.id = :id",
                "delete7" to "delete from Customer c where c.id = :id"), discoverRepositories = false))
        assertTrue(contract.coverage.notes.any { it.contains("Hibernate runtime $version") }, contract.toString())
        assertTrue(contract.queries.any { it.id == "lookup7" && it.dependenciesComplete }, contract.toString())
        assertTrue(contract.queries.any { it.id == "update7" && it.operation == Operation.UPDATE && it.dependenciesComplete }, contract.toString())
        assertTrue(contract.queries.any { it.id == "delete7" && it.operation == Operation.DELETE && it.dependenciesComplete }, contract.toString())
        assertTrue(contract.queries.any { it.operation == Operation.INSERT && it.targetTable == "public.record_tags" }, contract.toString())
        assertTrue(contract.coverage.complete, contract.toString())
        // The parent runtime remains on 6; the app's 7 classes never replace the tool's runtime.
        assertEquals("6.6.13.Final", org.hibernate.Version.getVersionString())
        val native = HibernateExtractor().extract(ExtractionRequest(listOf(Customer::class.java.name), classpath = classpath,
            nativeQueries = mapOf("native7" to "select id from customers where id = :id"), discoverRepositories = false))
        assertEquals(listOf("OTHER"), native.queries.single { it.id == "native7" }.parameterTypes)
        assertTrue(native.unresolvedQueries.any { it.id == "native7" })
        }
    }
}
