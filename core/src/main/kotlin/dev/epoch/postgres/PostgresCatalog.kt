package dev.epoch.postgres

import dev.epoch.core.*
import java.sql.Connection
import java.sql.ResultSet

/** Reads PostgreSQL's catalogs; migration text is never the source of schema truth. */
class PostgresCatalog {
    fun inspect(connection: Connection): SchemaSnapshot {
        var expressionSearchPath = emptyList<String>()
        connection.rows("SELECT current_schemas(true) AS schemas") { expressionSearchPath = it.strings("schemas") }
        val tables = sortedMapOf<String, TableSchema>()
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS name, c.relkind, c.oid AS relation_oid,
                   pg_get_userbyid(c.relowner) AS owner,
                   c.relacl IS NOT NULL OR EXISTS (
                       SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum > 0
                         AND NOT a.attisdropped AND a.attacl IS NOT NULL
                   ) AS explicit_privileges,
                   c.relrowsecurity OR c.relforcerowsecurity AS row_security,
                   EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal) AS user_triggers,
                   EXISTS (SELECT 1 FROM pg_rewrite r WHERE r.ev_class=c.oid AND r.rulename <> '_RETURN') AS user_rules,
                   c.relispartition OR c.relkind='p' OR EXISTS (
                       SELECT 1 FROM pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid
                   ) AS inheritance
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r','p','v','m') AND n.nspname NOT LIKE 'pg_%'
              AND n.nspname <> 'information_schema' AND c.relname <> 'flyway_schema_history'
            ORDER BY 1
        """) { row ->
            tables[row.getString("name")] = TableSchema(
                kind = when (row.getString("relkind")) { "v" -> "view"; "m" -> "materialized_view"; else -> "table" },
                relationOid = row.getLong("relation_oid"),
                relationSecurity = RelationSecurity(
                    owner = row.getString("owner"), explicitPrivileges = row.getBoolean("explicit_privileges"),
                    rowSecurity = row.getBoolean("row_security"), userTriggers = row.getBoolean("user_triggers"),
                    userRules = row.getBoolean("user_rules"), inheritance = row.getBoolean("inheritance"),
                ),
            )
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, a.attname,
                   format_type(a.atttypid,a.atttypmod) AS type, a.attnotnull, row_number() OVER (PARTITION BY c.oid ORDER BY a.attnum) AS attnum,
                   pg_get_expr(d.adbin,d.adrelid) AS default_expr, a.attidentity, a.attgenerated,
                   cn.nspname || '.' || coll.collname AS collation
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_attribute a ON a.attrelid=c.oid
            LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            LEFT JOIN pg_collation coll ON coll.oid=a.attcollation
            LEFT JOIN pg_namespace cn ON cn.oid=coll.collnamespace
            WHERE c.relkind IN ('r','p','v','m') AND a.attnum > 0 AND NOT a.attisdropped
              AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1,a.attnum
        """) { row ->
            val name = row.getString("table_name")
            val table = tables[name] ?: return@rows
            tables[name] = table.copy(columns = table.columns + (row.getString("attname") to ColumnSchema(
                type = row.getString("type"), nullable = !row.getBoolean("attnotnull"),
                defaultExpression = row.getString("default_expr"), ordinal = row.getInt("attnum"),
                identity = row.getString("attidentity").takeIf { it.isNotEmpty() },
                generated = row.getString("attgenerated").takeIf { it.isNotEmpty() },
                collation = row.getString("collation"),
            )))
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS name, pg_get_viewdef(c.oid,true) AS definition,
                   pg_relation_is_updatable(c.oid,false) AS updatable,
                   pg_relation_is_updatable(c.oid,true) AS updatable_with_triggers,
                   ARRAY(SELECT a.attname FROM pg_attribute a
                         WHERE a.attrelid=c.oid AND a.attnum > 0 AND NOT a.attisdropped
                           AND pg_column_is_updatable(c.oid,a.attnum,false) ORDER BY a.attnum) AS updatable_columns,
                   ARRAY(SELECT a.attname FROM pg_attribute a
                         WHERE a.attrelid=c.oid AND a.attnum > 0 AND NOT a.attisdropped
                           AND pg_column_is_updatable(c.oid,a.attnum,true) ORDER BY a.attnum) AS trigger_updatable_columns,
                   ARRAY(SELECT DISTINCT bn.nspname || '.' || b.relname
                         FROM pg_rewrite r JOIN pg_depend d ON d.classid='pg_rewrite'::regclass AND d.objid=r.oid
                         JOIN pg_class b ON d.refclassid='pg_class'::regclass AND b.oid=d.refobjid
                         JOIN pg_namespace bn ON bn.oid=b.relnamespace
                         WHERE r.ev_class=c.oid AND r.rulename='_RETURN' AND b.oid <> c.oid
                           AND b.relkind IN ('r','p','v','m') ORDER BY 1) AS backing_relations,
                   c.reloptions
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE c.relkind='v' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1
        """) { row ->
            val name = row.getString("name")
            val table = tables[name] ?: return@rows
            val definition = row.getString("definition")
            val backingName = row.strings("backing_relations").singleOrNull()
            val backing = backingName?.let(tables::get)
            tables[name] = table.copy(view = ViewSchema(
                definition = definition,
                updatableMask = row.getInt("updatable"),
                updatableMaskWithTriggers = row.getInt("updatable_with_triggers"),
                updatableColumns = row.strings("updatable_columns").toSet(),
                updatableColumnsWithTriggers = row.strings("trigger_updatable_columns").toSet(),
                backingTable = backingName,
                completeDirectProjection = backingName != null && backing != null && DirectViewProjection.complete(definition, backingName, table, backing),
                options = row.strings("reloptions").sorted(),
            ))
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, co.conname, co.contype,
                   pg_get_constraintdef(co.oid,true) || '; validated=' || co.convalidated
                   || '; deferrable=' || co.condeferrable || '; deferred=' || co.condeferred AS definition,
                   ARRAY(SELECT a.attname FROM unnest(co.conkey) WITH ORDINALITY k(num, position)
                         JOIN pg_attribute a ON a.attrelid=co.conrelid AND a.attnum=k.num ORDER BY k.position) AS columns,
                   CASE WHEN co.confrelid <> 0 THEN rn.nspname || '.' || rc.relname END AS referenced_table,
                   ARRAY(SELECT a.attname FROM unnest(co.confkey) WITH ORDINALITY k(num, position)
                         JOIN pg_attribute a ON a.attrelid=co.confrelid AND a.attnum=k.num ORDER BY k.position) AS referenced_columns,
                   co.confdeltype, co.confupdtype, pg_get_expr(co.conbin,co.conrelid) AS check_expression, co.convalidated
            FROM pg_constraint co JOIN pg_class c ON c.oid=co.conrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            LEFT JOIN pg_class rc ON rc.oid=co.confrelid
            LEFT JOIN pg_namespace rn ON rn.oid=rc.relnamespace
            WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1,co.conname
        """) { row ->
            val name = row.getString("table_name")
            val table = tables[name] ?: return@rows
            tables[name] = table.copy(constraints = table.constraints + (row.getString("conname") to
                ConstraintSchema(row.getString("contype"), row.getString("definition"),
                    columns = row.strings("columns"), referencedTable = row.getString("referenced_table"),
                    referencedColumns = row.strings("referenced_columns"), onDelete = row.getString("confdeltype"),
                    onUpdate = row.getString("confupdtype"), checkExpression = row.getString("check_expression"),
                    validated = row.getBoolean("convalidated"))))
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, i.relname AS index_name,
                   pg_get_indexdef(ix.indexrelid) || '; valid=' || ix.indisvalid AS definition, ix.indisunique,
                   am.amname AS method,
                   ARRAY(SELECT a.attname FROM unnest(ix.indkey) WITH ORDINALITY k(num, position)
                         JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=k.num ORDER BY k.position) AS columns,
                   ix.indexprs IS NULL AND ix.indpred IS NULL AND NOT EXISTS (
                       SELECT 1 FROM unnest(ix.indclass) op(oid) JOIN pg_opclass oc ON oc.oid=op.oid
                       JOIN pg_namespace ons ON ons.oid=oc.opcnamespace WHERE ons.nspname <> 'pg_catalog'
                   ) AS plain_index
            FROM pg_index ix JOIN pg_class c ON c.oid=ix.indrelid
            JOIN pg_class i ON i.oid=ix.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_am am ON am.oid=i.relam
            WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1,2
        """) { row ->
            val name = row.getString("table_name")
            val table = tables[name] ?: return@rows
            val index = row.getString("index_name")
            tables[name] = table.copy(indexes = table.indexes + (index to row.getString("definition")),
                uniqueIndexes = table.uniqueIndexes + (index to row.getBoolean("indisunique")),
                plainIndexes = table.plainIndexes + (if (row.getBoolean("plain_index")) setOf(index) else emptySet()),
                indexColumns = table.indexColumns + (index to row.strings("columns")),
                indexMethods = table.indexMethods + (index to row.getString("method")))
        }
        val behaviors = sortedMapOf<String, String>()
        fun behavior(sql: String) = connection.rows(sql) { behaviors[it.getString(1)] = it.getString(2) }
        behavior("""
            SELECT 'view:' || n.nspname || '.' || c.relname, pg_get_viewdef(c.oid,true)
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE c.relkind IN ('v','m') AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'function:' || n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
                   pg_get_functiondef(p.oid)
            FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
            WHERE p.prokind IN ('f','p','w') AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_proc", "p.oid")}
        """)
        behavior("""
            SELECT 'trigger:' || n.nspname || '.' || c.relname || '.' || t.tgname,
                   pg_get_triggerdef(t.oid,true) || '; enabled=' || t.tgenabled::text
            FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE NOT t.tgisinternal AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_trigger", "t.oid")}
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'rule:' || n.nspname || '.' || c.relname || '.' || r.rulename, pg_get_ruledef(r.oid,true)
            FROM pg_rewrite r JOIN pg_class c ON c.oid=r.ev_class JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE r.rulename <> '_RETURN' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_rewrite", "r.oid")}
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'rls:' || n.nspname || '.' || c.relname, c.relrowsecurity::text || ':' || c.relforcerowsecurity::text
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE (c.relrowsecurity OR c.relforcerowsecurity) AND n.nspname NOT LIKE 'pg_%'
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'policy:' || n.nspname || '.' || c.relname || '.' || p.polname,
                   p.polcmd::text || ':' || p.polpermissive || ':' || p.polroles::text || ':' ||
                   coalesce(pg_get_expr(p.polqual,p.polrelid),'') || ':' || coalesce(pg_get_expr(p.polwithcheck,p.polrelid),'')
            FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT LIKE 'pg_%'
              AND ${notExtensionOwned("pg_policy", "p.oid")}
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'partition:' || n.nspname || '.' || c.relname, pg_get_partkeydef(c.oid)
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='p' AND n.nspname NOT LIKE 'pg_%'
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        behavior("""
            SELECT 'inheritance:' || child.oid::regclass::text, parent.oid::regclass::text || ':' ||
                   coalesce(pg_get_expr(child.relpartbound,child.oid),'')
            FROM pg_inherits i JOIN pg_class child ON child.oid=i.inhrelid JOIN pg_class parent ON parent.oid=i.inhparent
            WHERE ${notExtensionOwned("pg_class", "child.oid")}
        """)
        val enumTypes = sortedMapOf<String, List<String>>()
        connection.rows("""
            SELECT n.nspname || '.' || t.typname AS name, array_agg(e.enumlabel ORDER BY e.enumsortorder) AS labels
            FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace JOIN pg_enum e ON e.enumtypid=t.oid
            WHERE n.nspname NOT LIKE 'pg_%' AND ${notExtensionOwned("pg_type", "t.oid")}
            GROUP BY n.nspname,t.typname
        """) { enumTypes[it.getString("name")] = it.strings("labels") }
        behavior("""
            SELECT 'domain:' || n.nspname || '.' || t.typname,
                   format_type(t.typbasetype,t.typtypmod) || ':' || t.typnotnull || ':' || coalesce(t.typdefault,'') || ':' ||
                   coalesce((SELECT string_agg(pg_get_constraintdef(co.oid,true),';' ORDER BY co.conname)
                             FROM pg_constraint co WHERE co.contypid=t.oid),'')
            FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
            WHERE t.typtype='d' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_type", "t.oid")}
        """)
        behavior("""
            SELECT 'collation:' || n.nspname || '.' || c.relname || '.' || a.attname,
                   cn.nspname || '.' || coll.collname || ':' || coalesce(coll.collversion,'')
            FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_collation coll ON coll.oid=a.attcollation JOIN pg_namespace cn ON cn.oid=coll.collnamespace
            WHERE c.relkind IN ('r','p') AND a.attnum > 0 AND NOT a.attisdropped
              AND coll.collname <> 'default' AND n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
              AND ${notExtensionOwned("pg_collation", "coll.oid")}
              AND ${notExtensionOwned("pg_class", "c.oid")}
        """)
        val sequences = sortedMapOf<String, String>()
        connection.rows("""
            SELECT schemaname || '.' || sequencename, data_type::text || ':' || start_value || ':' || min_value || ':' ||
                   max_value || ':' || increment_by || ':' || cycle || ':' || cache_size
            FROM pg_sequences WHERE schemaname NOT LIKE 'pg_%' ORDER BY 1
        """) { sequences[it.getString(1)] = it.getString(2) }
        val sequenceOwners = sortedMapOf<String, ColumnReference>()
        connection.rows("""
            SELECT sn.nspname || '.' || seq.relname AS name,
                   tn.nspname || '.' || tab.relname AS table_name, a.attname
            FROM pg_class seq JOIN pg_namespace sn ON sn.oid=seq.relnamespace
            JOIN pg_depend d ON d.classid='pg_class'::regclass AND d.objid=seq.oid
              AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i')
            JOIN pg_class tab ON tab.oid=d.refobjid JOIN pg_namespace tn ON tn.oid=tab.relnamespace
            JOIN pg_attribute a ON a.attrelid=tab.oid AND a.attnum=d.refobjsubid
            WHERE seq.relkind='S' AND sn.nspname NOT LIKE 'pg_%'
            ORDER BY 1
        """) { sequenceOwners[it.getString("name")] = ColumnReference(it.getString("table_name"), it.getString("attname")) }
        val extensions = sortedMapOf<String, String>()
        connection.rows("SELECT extname, extversion FROM pg_extension ORDER BY extname") {
            extensions[it.getString(1)] = it.getString(2)
        }
        return SchemaSnapshot(tables = tables, behaviors = behaviors, sequences = sequences, extensions = extensions,
            sequenceOwners = sequenceOwners, enumTypes = enumTypes, expressionSearchPath = expressionSearchPath)
    }
}

/** Extension membership is an object dependency, not a schema/name convention. */
private fun notExtensionOwned(catalog: String, objectId: String) =
    "NOT EXISTS (SELECT 1 FROM pg_depend dependency WHERE dependency.classid = '$catalog'::regclass " +
        "AND dependency.objid = $objectId AND dependency.objsubid = 0 AND dependency.deptype = 'e')"

private fun Connection.rows(sql: String, consume: (ResultSet) -> Unit) = createStatement().use { statement ->
    statement.executeQuery(sql).use { result -> while (result.next()) consume(result) }
}

private fun ResultSet.strings(column: String): List<String> {
    val value = getArray(column) ?: return emptyList()
    return try { (value.array as Array<*>).map { it.toString() } } finally { value.free() }
}
