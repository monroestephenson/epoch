package dev.epoch.hibernate

import org.hibernate.boot.Metadata
import org.hibernate.mapping.Value

/** Boot declarations retain SQL types that Hibernate 7 no longer exposes on runtime selectables. */
internal object BootColumnBounds {
    data class Key(val mapping: String, val table: String, val column: String)

    fun unboundedStrings(metadata: Metadata, defaultSchema: String): Set<Key> {
        val declarations = linkedMapOf<Key, MutableSet<Boolean>>()
        val dialect = metadata.database.dialect
        fun collect(mapping: String, value: Value) {
            val table = value.table.qualifiedTableName
            // PostgreSQL cross-database names are outside the adapter's schema.table model.
            if (table.catalogName != null) return
            val name = listOfNotNull(table.schemaName?.render(dialect), table.tableName.render(dialect)).joinToString(".")
            val qualified = SqlDependencies.qualified(name, defaultSchema)
            // Formula-valued mappings have no complete physical-column declaration.
            val columns = try { value.columns } catch (_: RuntimeException) { return }
            for (column in columns) {
                val key = Key(mapping, qualified, SqlDependencies.identifier(column.getQuotedName(dialect)))
                val sqlType = try { column.getSqlType(metadata).trim().lowercase(java.util.Locale.ROOT) }
                    catch (_: RuntimeException) { null }
                declarations.getOrPut(key) { linkedSetOf() } += sqlType in setOf(
                    "text", "varchar", "character varying", "pg_catalog.text", "pg_catalog.varchar",
                )
            }
        }
        metadata.entityBindings.forEach { binding ->
            collect(binding.entityName, binding.identifier)
            binding.propertyClosure.forEach { collect(binding.entityName, it.value) }
        }
        metadata.collectionBindings.forEach { binding ->
            val mapping = "${binding.role}@collection"
            collect(mapping, binding.key)
            collect(mapping, binding.element)
            (binding as? org.hibernate.mapping.IndexedCollection)?.index?.let { collect(mapping, it) }
        }
        // Shared physical columns with contradictory declarations do not acquire an unlimited domain.
        return declarations.filterValues { it == setOf(true) }.keys.toSet()
    }
}
