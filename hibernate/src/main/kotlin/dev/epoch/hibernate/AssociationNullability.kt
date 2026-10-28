package dev.epoch.hibernate

import org.hibernate.metamodel.mapping.AttributeMapping
import org.hibernate.metamodel.mapping.BasicEntityIdentifierMapping
import org.hibernate.metamodel.mapping.ForeignKeyDescriptor
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.metamodel.mapping.internal.ToOneAttributeMapping

/** A declaration about a direct identifier reference, not proof of every lifecycle JDBC binding. */
internal object AssociationNullability {
    data class Fact(val table: String, val column: String, val declaredNonNull: Boolean) {
        fun appliesTo(selectable: SelectableMapping): Boolean =
            table == selectable.containingTableExpression && column == selectable.selectionExpression
    }

    fun inspect(attribute: AttributeMapping): Fact? {
        val relation = attribute as? ToOneAttributeMapping ?: return null
        if (relation.cardinality != ToOneAttributeMapping.Cardinality.MANY_TO_ONE ||
            relation.sideNature != ForeignKeyDescriptor.Nature.KEY || !relation.isReferenceToPrimaryKey ||
            relation.hasJoinTable() || relation.hasNotFoundAction()) return null
        val declared = DeclaredNullability.of(attribute.propertyAccess?.getter?.member) ?: return null
        val identifier = relation.associatedEntityMappingType.identifierMappingForJoin as? BasicEntityIdentifierMapping
            ?: return null
        val descriptor: ForeignKeyDescriptor = relation.foreignKeyDescriptor ?: return null
        // Do not infer key equivalence from a same-named natural key, wrapper or composite tuple.
        if (descriptor.targetPart !== identifier || identifier.isNullable || descriptor.isEmbedded) return null
        val keys = mutableListOf<SelectableMapping>()
        val targets = mutableListOf<SelectableMapping>()
        descriptor.visitKeySelectables { _, selectable -> keys += selectable }
        descriptor.visitTargetSelectables { _, selectable -> targets += selectable }
        val key = keys.singleOrNull() ?: return null
        val target = targets.singleOrNull() ?: return null
        // Hibernate represents some owning @JoinTable mappings as a secondary table
        // without setting hasJoinTable(). Only the owner's primary physical row is
        // within this declaration proof; optional/joined rows need separate facts.
        val owner = relation.declaringType.findContainingEntityMapping() ?: return null
        if (key.containingTableExpression != owner.mappedTableDetails.tableName) return null
        if (!direct(key) || !direct(target) || !direct(identifier)) return null
        if (key.jdbcMapping.javaTypeDescriptor.javaTypeClass != target.jdbcMapping.javaTypeDescriptor.javaTypeClass ||
            key.jdbcMapping.jdbcType.jdbcTypeCode != target.jdbcMapping.jdbcType.jdbcTypeCode) return null
        if (key.containingTableExpression != descriptor.keyTable ||
            target.containingTableExpression != descriptor.targetTable ||
            target.containingTableExpression != identifier.containingTableExpression ||
            target.selectionExpression != identifier.selectionExpression) return null
        val selectables = mutableListOf<SelectableMapping>()
        relation.forEachSelectable { _, selectable -> selectables += selectable }
        val actual = selectables.singleOrNull() ?: return null
        if (actual.containingTableExpression != key.containingTableExpression ||
            actual.selectionExpression != key.selectionExpression || !direct(actual)) return null
        return Fact(key.containingTableExpression, key.selectionExpression, declared)
    }

    private fun direct(selectable: SelectableMapping): Boolean {
        if (selectable.isFormula || !HibernateReadMetadata.directRead(selectable.customReadExpression, selectable.selectionExpression) ||
            selectable.customWriteExpression.let { it != null && it.trim() != "?" }) return false
        val jdbc = selectable.jdbcMapping
        if (jdbc.valueConverter != null || jdbc.javaTypeDescriptor.javaClass.name !in javaTypes) return false
        return jdbc.jdbcType.javaClass.name in jdbcTypes
    }

    private val javaTypes = setOf("LongJavaType", "IntegerJavaType", "ShortJavaType", "ByteJavaType", "StringJavaType", "UUIDJavaType")
        .map { "org.hibernate.type.descriptor.java.$it" }.toSet()
    private val jdbcTypes = setOf("BigIntJdbcType", "IntegerJdbcType", "SmallIntJdbcType", "TinyIntJdbcType", "TinyIntAsSmallIntJdbcType",
        "VarcharJdbcType", "NVarcharJdbcType", "LongVarcharJdbcType", "LongNVarcharJdbcType", "UUIDJdbcType")
        .map { "org.hibernate.type.descriptor.jdbc.$it" }.toSet() + setOf(
            "org.hibernate.dialect.PostgreSQLUUIDJdbcType", "org.hibernate.dialect.type.PostgreSQLUUIDJdbcType")
}
