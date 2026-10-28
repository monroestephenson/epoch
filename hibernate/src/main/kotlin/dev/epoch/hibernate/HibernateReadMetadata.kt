package dev.epoch.hibernate

import dev.epoch.core.*
import net.sf.jsqlparser.expression.JdbcParameter
import net.sf.jsqlparser.expression.operators.relational.EqualsTo
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.PlainSelect
import org.hibernate.engine.FetchTiming
import org.hibernate.engine.spi.LoadQueryInfluencers
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl
import org.hibernate.metamodel.mapping.BasicValuedModelPart
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.sql.ast.tree.expression.ColumnReference as SqlColumnReference
import org.hibernate.sql.exec.spi.JdbcOperation
import org.hibernate.sql.results.graph.basic.BasicFetch
import org.hibernate.sql.results.graph.entity.AbstractEntityResultGraphNode
import org.hibernate.sql.results.jdbc.internal.JdbcValuesMappingProducerStandard
import org.hibernate.sql.results.jdbc.spi.JdbcValuesMetadata
import org.hibernate.type.BasicType
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.spi.TypeConfiguration
import java.util.BitSet

/** Positive decoder provenance for a deliberately small ordinary by-ID loader subset. */
internal object HibernateReadMetadata {
    data class Facts(val reads: List<MappedColumnRead> = emptyList(), val complete: Boolean = false,
                     val discriminatorSelected: Boolean = false, val unrestricted: Boolean = false)

    fun inspect(factory: SessionFactoryImplementor, p: AbstractEntityPersister, jdbc: JdbcOperation,
                influencers: LoadQueryInfluencers, mappings: Map<String, String>, defaultSchema: String): Facts {
        if (p.singleIdLoader.javaClass != SingleIdEntityLoaderStandardImpl::class.java || p.hasWhereRestrictions() ||
            p.softDeleteMapping != null || p.isAffectedByEnabledFilters(influencers, true) ||
            !identifierOnly(jdbc.sqlString, p, defaultSchema)) return Facts()
        val producer = HibernateSqlOperations.valuesMappingProducer(jdbc)
        if (producer.javaClass != JdbcValuesMappingProducerStandard::class.java) return Facts()
        val mapping = producer.resolve(RejectMetadata, influencers, factory)
        val result = mapping.domainResults.singleOrNull() as? AbstractEntityResultGraphNode ?: return Facts()
        if (result.referencedMappingContainer.entityName != p.entityName) return Facts()
        val selections = mapping.sqlSelections.groupBy { it.valuesArrayPosition }
        val reads = linkedSetOf<MappedColumnRead>()
        var complete = true
        fun selected(fetch: BasicFetch<*>): Boolean {
            if (fetch.timing != FetchTiming.IMMEDIATE) return false
            val positions = BitSet().also(fetch::collectValueIndexesToCache)
            if (positions.cardinality() != 1) return false
            val selection = selections[positions.nextSetBit(0)]?.singleOrNull() ?: return false
            return !selection.isVirtual && selection.expression is SqlColumnReference &&
                !(selection.expression as SqlColumnReference).isColumnExpressionFormula
        }
        fun read(fetch: BasicFetch<*>) {
            val part = fetch.fetchedMapping as? BasicValuedModelPart
            if (part == null || part.isFormula || !selected(fetch) || !directRead(part.customReadExpression,
                    part.selectionExpression)) { complete = false; return }
            val table = SqlDependencies.qualified(p.physicalTableNameForMutation(part), defaultSchema)
            val entityId = mappings[table]
            if (entityId == null) { complete = false; return }
            val positions = BitSet().also(fetch::collectValueIndexesToCache)
            val selection = selections.getValue(positions.nextSetBit(0)).single()
            val expression = selection.expression as SqlColumnReference
            if (SqlDependencies.identifier(expression.columnExpression) != SqlDependencies.identifier(part.selectionExpression)) {
                complete = false; return
            }
            reads += MappedColumnRead(entityId, ColumnReference(table, SqlDependencies.identifier(part.selectionExpression)),
                selection.jdbcResultSetIndex)
        }
        val identifier = result.identifierFetch
        // A standard by-ID result may use the supplied identifier instead of decoding it from the row.
        if (identifier is BasicFetch<*>) read(identifier) else if (identifier != null) complete = false
        if (result.rowIdResult != null) complete = false
        result.fetches.forEach { fetch ->
            if (fetch is BasicFetch<*>) read(fetch) else complete = false
        }
        val discriminator = result.discriminatorFetch?.let(::selected) == true
        if (result.discriminatorFetch != null && !discriminator) complete = false
        return Facts(reads.sortedWith(compareBy({ it.entityId }, { it.column.table }, { it.column.column })),
            complete, discriminator, unrestricted = true)
    }

    fun directRead(expression: String?, column: String): Boolean {
        if (expression == null) return true
        val template = org.hibernate.sql.Template::class.java.getField("TEMPLATE").get(null) as String
        return expression == "$template.$column"
    }

    private fun identifierOnly(sql: String, p: AbstractEntityPersister, defaultSchema: String): Boolean = try {
        val select = CCJSqlParserUtil.parse(sql) as? PlainSelect
        val table = select?.fromItem as? Table
        val predicate = select?.where as? EqualsTo
        val column = predicate?.leftExpression as? Column
        val identifier = p.identifierMapping
        val keys = mutableListOf<String>()
        identifier.forEachSelectable { _, part -> if (!part.isFormula) keys += SqlDependencies.identifier(part.selectionExpression) }
        select != null && table != null && predicate?.rightExpression is JdbcParameter && column != null &&
            keys.size == 1 && identifier.jdbcTypeCount == 1 && SqlDependencies.identifier(column.columnName) == keys.single() &&
            SqlDependencies.qualified(table.fullyQualifiedName, defaultSchema) == SqlDependencies.qualified(p.identifierTableMapping.tableName, defaultSchema) &&
            (column.table?.name.isNullOrBlank() || SqlDependencies.identifier(column.table.name) in
                setOf(SqlDependencies.identifier(table.name), table.alias?.name?.let(SqlDependencies::identifier))) &&
            select.joins.isNullOrEmpty() && select.withItemsList.isNullOrEmpty() && select.distinct == null &&
            select.groupBy == null && select.having == null && select.qualify == null && select.limit == null &&
            select.offset == null && select.fetch == null && select.limitBy == null && select.windowDefinitions.isNullOrEmpty()
    } catch (error: net.sf.jsqlparser.JSQLParserException) { QueryExtraction.rethrowNonRecoverable(error); false }

    /** A typed ORM result can resolve offline. Unavailable JDBC metadata is never fabricated. */
    private object RejectMetadata : JdbcValuesMetadata {
        override fun getColumnCount(): Int = throw UnsupportedOperationException("Read provenance requires JDBC result metadata")
        override fun resolveColumnPosition(columnName: String): Int = throw UnsupportedOperationException("Read provenance requires JDBC result metadata")
        override fun resolveColumnName(position: Int): String = throw UnsupportedOperationException("Read provenance requires JDBC result metadata")
        override fun <J : Any?> resolveType(position: Int, explicitJavaType: JavaType<J>?, typeConfiguration: TypeConfiguration): BasicType<J> =
            throw UnsupportedOperationException("Read provenance requires JDBC result metadata")
    }
}
