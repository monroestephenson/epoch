package dev.epoch.hibernate

import dev.epoch.core.Operation
import dev.epoch.hibernate.queryisolationfixture.QueryIsolationRecord
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.dialect.function.StandardSQLFunction
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.query.ReturnableType
import org.hibernate.sql.ast.SqlAstTranslator
import org.hibernate.sql.ast.spi.SqlAppender
import org.hibernate.sql.ast.tree.SqlAstNode
import org.hibernate.type.StandardBasicTypes
import org.junit.jupiter.api.Test
import kotlin.test.*

class FailingQueryRendererTest {
    @Test fun `a renderer StackOverflow leaves one named failure and preserves other translated queries`() {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.dialect" to PostgreSQLDialect(),
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "hibernate.hbm2ddl.auto" to "none",
            "hibernate.connection.provider_class" to "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl",
            "jakarta.persistence.validation.mode" to "none",
            "hibernate.query.startup_check" to false,
        )).build()
        try {
            val builder = MetadataSources(registry).addAnnotatedClass(QueryIsolationRecord::class.java).metadataBuilder
            builder.applySqlFunction("fixture_stack", object : StandardSQLFunction("fixture_stack", StandardBasicTypes.STRING) {
                override fun render(sqlAppender: SqlAppender, sqlAstArguments: MutableList<out SqlAstNode>,
                                    returnType: ReturnableType<*>?, walker: SqlAstTranslator<*>) {
                    throw StackOverflowError("fixture function renderer failed")
                }
            })
            (builder.build().buildSessionFactory() as SessionFactoryImplementor).use { factory ->
                val contract = HibernateExtractor().extractFactory(factory, ExtractionRequest(
                    discoverRepositories = false,
                    hqlQueries = sortedMapOf(
                        "a.badRenderer" to "select fixture_stack(r.label) from QueryIsolationRecord r",
                        "z.healthyHql" to "select r.label from QueryIsolationRecord r where r.id = :id",
                    ),
                    nativeQueries = sortedMapOf(
                        "a.badNative" to "select from where",
                        "z.healthyNative" to "select label from query_isolation_records where id = ?",
                    ),
                    finiteHqlVariants = mapOf("finite" to listOf(
                        "select fixture_stack(r.label) from QueryIsolationRecord r",
                        "select r.label from QueryIsolationRecord r",
                    )),
                ))
                assertTrue(contract.queries.any { it.id == "z.healthyHql" && it.dependenciesComplete }, contract.toString())
                assertTrue(contract.queries.any { it.id == "z.healthyNative" && it.dependenciesComplete }, contract.toString())
                assertTrue(contract.queries.any { it.id.startsWith("finite.variant.") }, contract.toString())
                assertTrue(contract.queries.any { it.operation == Operation.INSERT }, contract.toString())
                assertFalse(contract.queries.any { it.id in setOf("a.badRenderer", "a.badNative") }, contract.toString())
                val renderer = contract.unresolvedQueries.single { it.id == "a.badRenderer" || "a.badRenderer" in it.affectedIds }
                assertContains(renderer.reason, "StackOverflowError: fixture function renderer failed")
                assertTrue(contract.unresolvedQueries.any { it.id == "a.badNative" && it.reason.contains("Exception") }, contract.toString())
                val failedVariants = contract.unresolvedQueries.flatMap { it.affectedIds.ifEmpty { listOf(it.id) } }
                    .filter { it.startsWith("finite.variant.") }
                assertEquals(1, failedVariants.size)
                assertEquals(setOf("a.badRenderer", failedVariants.single()), renderer.affectedIds.toSet(),
                    "The repeated renderer failure retains both query identities without duplicating its cause")
                assertFalse(contract.coverage.complete)
            }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }
}
