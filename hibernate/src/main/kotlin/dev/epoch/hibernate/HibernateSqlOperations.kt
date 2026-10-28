package dev.epoch.hibernate

import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMappingProducer
import org.hibernate.query.spi.QueryOptions
import org.hibernate.sql.ast.SqlAstTranslator
import org.hibernate.sql.ast.tree.MutationStatement
import org.hibernate.sql.ast.tree.Statement
import org.hibernate.sql.ast.tree.select.SelectStatement
import org.hibernate.sql.exec.spi.JdbcOperation
import org.hibernate.sql.exec.spi.JdbcParameterBindings

/** Bridges select-operation types without linking to Hibernate 6/early-7 concrete result classes. */
internal object HibernateSqlOperations {
    fun loadPlanSelect(plan: Any): JdbcOperation =
        // Hibernate 7.4 changes this method's return descriptor from JdbcOperationQuerySelect to
        // JdbcSelect. Resolving the accessor at runtime avoids binding to the removed old class.
        plan.javaClass.getMethod("getJdbcSelect").invoke(plan) as JdbcOperation

    fun affectedTableNames(operation: JdbcOperation): List<String> =
        // This accessor lived on query implementations before joining JdbcOperation in 7.4.
        (operation.javaClass.getMethod("getAffectedTableNames").invoke(operation) as Collection<*>)
            .map { requireNotNull(it as? String) { "Hibernate returned a non-string affected table name" } }

    fun valuesMappingProducer(operation: JdbcOperation): JdbcValuesMappingProducer =
        operation.javaClass.getMethod("getJdbcValuesMappingProducer").invoke(operation) as JdbcValuesMappingProducer

    fun translate(factory: SessionFactoryImplementor, statement: Statement, options: QueryOptions): JdbcOperation {
        val factoryApi = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory
        // Widen the translator before translating: otherwise the Kotlin compiler inserts a cast
        // to JdbcOperationQuerySelect, a type no longer present in Hibernate 7.4.
        val translator: SqlAstTranslator<out JdbcOperation> = when (statement) {
            is SelectStatement -> factoryApi.buildSelectTranslator(factory, statement)
            is MutationStatement -> factoryApi.buildMutationTranslator(factory, statement)
            else -> error("Unsupported Hibernate SQL AST ${statement.javaClass.name}")
        }
        return translator.translate(JdbcParameterBindings.NO_BINDINGS, options)
    }
}
