package dev.epoch.associationfixture

import jakarta.persistence.*
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.Version
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Executed in a fresh JVM with the real selected Hibernate runtime, against an owned test database. */
object AssociationTransactionWitness {
    val entities = listOf(AssociationWriteParent::class.java, AssociationDirectWrite::class.java,
        AssociationNullableWrite::class.java, AssociationNaturalWrite::class.java, AssociationIdentitySelfWrite::class.java)

    @JvmStatic fun main(arguments: Array<String>) {
        val (expectedVersion, url, user, password) = arguments.toList()
        check(Version.getVersionString() == expectedVersion) { "Wrong actual Hibernate runtime: ${Version.getVersionString()}" }
        DriverManager.getConnection(url, user, password).use { connection ->
            val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
                "hibernate.connection.url" to url, "hibernate.connection.username" to user,
                "hibernate.connection.password" to password, "hibernate.hbm2ddl.auto" to "none",
                "jakarta.persistence.validation.mode" to "none",
            )).build()
            try {
                val metadata = MetadataSources(registry)
                entities.forEach(metadata::addAnnotatedClass)
                val factory = metadata.buildMetadata().buildSessionFactory()
                try {
                    commit(factory) { it.persist(AssociationWriteParent().apply { id = 100L }) }
                    commit(factory) { session ->
                        session.persist(AssociationDirectWrite().apply { id = 1L; parent = session.find(AssociationWriteParent::class.java, 100L) })
                    }
                    check(value(connection, "association_direct_write", "parent_id", 1L) == 100L)
                    connection.createStatement().use { it.execute("ALTER TABLE association_direct_write ALTER COLUMN parent_id SET NOT NULL") }
                    commit(factory) { session ->
                        session.persist(AssociationDirectWrite().apply { id = 2L; parent = session.find(AssociationWriteParent::class.java, 100L) })
                    }
                    check(value(connection, "association_direct_write", "parent_id", 2L) == 100L)
                    println("PERSISTED_TARGET_COMMITTED_BEFORE_AND_AFTER")

                    commit(factory) { it.persist(AssociationNullableWrite().apply { id = 1L; parent = null }) }
                    check(value(connection, "association_nullable_write", "parent_id", 1L) == null)
                    connection.createStatement().use {
                        it.execute("DELETE FROM association_nullable_write; ALTER TABLE association_nullable_write ALTER COLUMN parent_id SET NOT NULL")
                    }
                    rejected23502 { commit(factory) { it.persist(AssociationNullableWrite().apply { id = 2L; parent = null }) } }
                    println("NULL_REFERENCE_COMMITTED_THEN_REJECTED_23502")

                    commit(factory) { session ->
                        val parent = session.find(AssociationWriteParent::class.java, 100L)
                        check(parent != null && parent.code == null)
                        session.persist(AssociationNaturalWrite().apply { id = 1L; this.parent = parent })
                    }
                    check(value(connection, "association_natural_write", "parent_code", 1L) == null)
                    connection.createStatement().use {
                        it.execute("DELETE FROM association_natural_write; ALTER TABLE association_natural_write ALTER COLUMN parent_code SET NOT NULL")
                    }
                    rejected23502 { commit(factory) { session ->
                        session.persist(AssociationNaturalWrite().apply { id = 2L; parent = session.find(AssociationWriteParent::class.java, 100L) })
                    } }
                    println("NON_NULL_REFERENCE_WITH_NULL_NATURAL_KEY_COMMITTED_THEN_REJECTED_23502")

                    // IDENTITY performs an early INSERT before its own generated key is available.
                    // This is a lifecycle check, including flush+commit, not an observation of one
                    // intermediate SQL statement. Successful commit must precede the rejection witness.
                    val self = AssociationIdentitySelfWrite().also { it.parent = it }
                    commit(factory) { it.persist(self) }
                    val oldId = checkNotNull(self.id)
                    val storedKey = value(connection, "association_identity_self_write", "parent_id", oldId)
                    check(storedKey == null || storedKey == oldId)
                    connection.createStatement().use {
                        it.execute("DELETE FROM association_identity_self_write; ALTER TABLE association_identity_self_write ALTER COLUMN parent_id SET NOT NULL")
                    }
                    rejected23502 { commit(factory) { session ->
                        session.persist(AssociationIdentitySelfWrite().also { it.parent = it })
                    } }
                    println("IDENTITY_SELF_REFERENCE_COMMITTED_THEN_REJECTED_23502 oldStoredKey=$storedKey")
                } finally { factory.close() }
            } finally { StandardServiceRegistryBuilder.destroy(registry) }
        }
    }

    private fun commit(factory: SessionFactory, operation: (Session) -> Unit) {
        val session = factory.openSession()
        try {
            val transaction = session.beginTransaction()
            try {
                operation(session)
                session.flush()
                transaction.commit()
            } catch (failure: Throwable) {
                if (transaction.isActive) transaction.rollback()
                throw failure
            }
        } finally { session.close() }
    }

    private fun rejected23502(operation: () -> Unit) {
        val failure = try { operation(); null } catch (failure: RuntimeException) { failure }
        check(failure != null) { "Expected the already-demonstrated old input to fail after SET NOT NULL" }
        check(generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "23502" }) {
            "Expected PostgreSQL NOT NULL evidence, received $failure"
        }
    }

    private fun value(connection: Connection, table: String, column: String, id: Long): Any? =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $column FROM $table WHERE id=$id").use { result ->
                check(result.next()) { "The transaction did not commit its row" }
                result.getObject(1).also { check(!result.next()) }
            }
        }

    val schema = """
        CREATE TABLE association_write_parent(id bigint PRIMARY KEY, code text UNIQUE);
        CREATE TABLE association_direct_write(id bigint PRIMARY KEY, parent_id bigint REFERENCES association_write_parent(id));
        CREATE TABLE association_nullable_write(id bigint PRIMARY KEY, parent_id bigint REFERENCES association_write_parent(id));
        CREATE TABLE association_natural_write(id bigint PRIMARY KEY, parent_code text REFERENCES association_write_parent(code));
        CREATE TABLE association_identity_self_write(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, parent_id bigint REFERENCES association_identity_self_write(id));
    """.trimIndent()
}

@Entity @Table(name = "association_write_parent")
open class AssociationWriteParent {
    @field:Id open var id: Long = 0
    @field:Column(unique = true) open var code: String? = null
}

@Entity @Table(name = "association_direct_write")
open class AssociationDirectWrite {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id")
    open lateinit var parent: AssociationWriteParent
}

@Entity @Table(name = "association_nullable_write")
open class AssociationNullableWrite {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id")
    open var parent: AssociationWriteParent? = null
}

@Entity @Table(name = "association_natural_write")
open class AssociationNaturalWrite {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_code", referencedColumnName = "code")
    open lateinit var parent: AssociationWriteParent
}

@Entity @Table(name = "association_identity_self_write")
open class AssociationIdentitySelfWrite {
    @field:Id @field:GeneratedValue(strategy = GenerationType.IDENTITY) open var id: Long? = null
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id")
    open lateinit var parent: AssociationIdentitySelfWrite
}
