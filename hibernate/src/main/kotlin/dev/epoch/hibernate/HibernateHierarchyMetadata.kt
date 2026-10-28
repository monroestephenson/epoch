package dev.epoch.hibernate

import dev.epoch.core.*
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.Values
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.persister.entity.JoinedSubclassEntityPersister
import org.hibernate.persister.entity.SingleTableEntityPersister
import org.hibernate.persister.entity.UnionSubclassEntityPersister

/** Finite physical discriminator metadata; wildcard/synthetic strategies never become scalar facts. */
internal object HibernateHierarchyMetadata {
    fun inspect(p: AbstractEntityPersister, mappings: Map<String, Map<String, String>>,
                defaultSchema: String, jdbcName: (org.hibernate.metamodel.mapping.JdbcMapping) -> String): HierarchyContract? {
        val discriminator = p.discriminatorMapping
        if (!p.entityMetamodel.isPolymorphic && discriminator == null) return null
        val root = p.rootEntityDescriptor as? AbstractEntityPersister ?: return null
        val rootTable = SqlDependencies.qualified(root.identifierTableMapping.tableName, defaultSchema)
        val strategy = when (p) {
            is SingleTableEntityPersister -> "SINGLE_TABLE"
            is JoinedSubclassEntityPersister -> "JOINED"
            is UnionSubclassEntityPersister -> "UNION"
            else -> "UNKNOWN"
        }
        var complete = strategy == "SINGLE_TABLE" && p.javaClass == SingleTableEntityPersister::class.java
        var reference: ColumnReference? = null
        var type: String? = null
        if (discriminator != null && discriminator.hasPhysicalColumn() && !discriminator.isFormula) {
            val parts = mutableListOf<ColumnReference>()
            discriminator.forEachSelectable { _, part ->
                if (part.isFormula || !HibernateReadMetadata.directRead(part.customReadExpression, part.selectionExpression)) complete = false
                else {
                    parts += ColumnReference(SqlDependencies.qualified(part.containingTableExpression, defaultSchema),
                        SqlDependencies.identifier(part.selectionExpression))
                    type = jdbcName(part.jdbcMapping)
                }
            }
            reference = parts.singleOrNull()
            if (reference == null) complete = false
        } else complete = false
        val values = linkedSetOf<String>()
        var entityValue: String? = null
        val converter = discriminator?.valueConverter
        if (converter == null || converter.javaClass.name !in setOf(
                "org.hibernate.metamodel.mapping.MappedDiscriminatorConverter",
                "org.hibernate.metamodel.mapping.internal.UnifiedAnyDiscriminatorConverter")) complete = false
        else converter.forEachValueDetail { detail ->
            val value = try {
                when (val scalar = detail.value) {
                    is String, is Char, is Byte, is Short, is Int, is Long -> scalar.toString()
                    else -> null
                }
            } catch (error: RuntimeException) {
                QueryExtraction.failure(p.entityName, "Discriminator scalar metadata unavailable", error)
                null
            }
            if (value == null) complete = false
            else {
                if (!detail.indicatedEntity.isAbstract) values += value
                if (detail.indicatedEntity.entityName == p.entityName) entityValue = value
            }
        }
        val rootId = mappings[root.entityName]?.get(reference?.table ?: rootTable)
        if (rootId == null || !p.entityMetamodel.isAbstract && entityValue == null) complete = false
        return HierarchyContract(rootId, strategy, !p.entityMetamodel.isAbstract, reference, type, entityValue,
            values.sorted(), complete)
    }

    fun write(p: AbstractEntityPersister, hierarchy: HierarchyContract?, sql: String, mappings: Map<String, String>,
              defaultSchema: String): List<DiscriminatorWrite> {
        if (p !is SingleTableEntityPersister || hierarchy?.complete != true || !hierarchy.concrete ||
            p.entityMetamodel.isDynamicInsert || hierarchy.entityValue == null) return emptyList()
        val value = hierarchy.entityValue ?: return emptyList()
        val reference = hierarchy.discriminator ?: return emptyList()
        val entityId = mappings[reference.table] ?: return emptyList()
        var ordinary = false
        p.forEachMutableTable { table ->
            if (SqlDependencies.qualified(table.tableName, defaultSchema) == reference.table)
                ordinary = table.insertCustomSql == null
        }
        if (!ordinary) return emptyList()
        return try {
            val insert = CCJSqlParserUtil.parse(sql) as? Insert ?: return emptyList()
            if (SqlDependencies.qualified(insert.table.fullyQualifiedName, defaultSchema) != reference.table ||
                !insert.withItemsList.isNullOrEmpty() || insert.conflictAction != null ||
                !insert.duplicateUpdateSets.isNullOrEmpty()) return emptyList()
            val values = (insert.select as? Values)?.expressions
            val row = (if (values?.singleOrNull() is ExpressionList<*>) values.single() as ExpressionList<*> else values)
                ?: return emptyList()
            val columns = insert.columns.orEmpty().map { SqlDependencies.identifier(it.columnName) }
            val index = columns.indexOf(reference.column)
            if (index < 0 || row.size != columns.size || row[index].toString() != p.discriminatorSQLValue) emptyList()
            else listOf(DiscriminatorWrite(entityId, reference, value))
        } catch (error: net.sf.jsqlparser.JSQLParserException) { QueryExtraction.rethrowNonRecoverable(error); emptyList() }
    }
}
