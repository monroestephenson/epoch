package dev.epoch.postgres

import dev.epoch.core.*
import java.sql.Connection
import java.sql.ResultSet

/** Reads PostgreSQL's catalogs; migration text is never the source of schema truth. */
class PostgresCatalog {
    fun inspect(connection: Connection): SchemaSnapshot {
        val tables = sortedMapOf<String, TableSchema>()
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS name
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r','p') AND n.nspname NOT LIKE 'pg_%'
              AND n.nspname <> 'information_schema' AND c.relname <> 'flyway_schema_history'
            ORDER BY 1
        """) { tables[it.getString("name")] = TableSchema() }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, a.attname,
                   format_type(a.atttypid,a.atttypmod) AS type, a.attnotnull, a.attnum,
                   pg_get_expr(d.adbin,d.adrelid) AS default_expr, a.attidentity, a.attgenerated
            FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_attribute a ON a.attrelid=c.oid
            LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            WHERE c.relkind IN ('r','p') AND a.attnum > 0 AND NOT a.attisdropped
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
            )))
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, co.conname, co.contype,
                   pg_get_constraintdef(co.oid,true) || '; validated=' || co.convalidated
                   || '; deferrable=' || co.condeferrable || '; deferred=' || co.condeferred AS definition
            FROM pg_constraint co JOIN pg_class c ON c.oid=co.conrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1,co.conname
        """) { row ->
            val name = row.getString("table_name")
            val table = tables[name] ?: return@rows
            tables[name] = table.copy(constraints = table.constraints + (row.getString("conname") to
                ConstraintSchema(row.getString("contype"), row.getString("definition"))))
        }
        connection.rows("""
            SELECT n.nspname || '.' || c.relname AS table_name, i.relname AS index_name,
                   pg_get_indexdef(ix.indexrelid) || '; valid=' || ix.indisvalid AS definition
            FROM pg_index ix JOIN pg_class c ON c.oid=ix.indrelid
            JOIN pg_class i ON i.oid=ix.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
            ORDER BY 1,2
        """) { row ->
            val name = row.getString("table_name")
            val table = tables[name] ?: return@rows
            tables[name] = table.copy(indexes = table.indexes + (row.getString("index_name") to row.getString("definition")))
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
        behavior("""
            SELECT 'enum:' || n.nspname || '.' || t.typname, jsonb_agg(e.enumlabel ORDER BY e.enumsortorder)::text
            FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace JOIN pg_enum e ON e.enumtypid=t.oid
            WHERE n.nspname NOT LIKE 'pg_%' AND ${notExtensionOwned("pg_type", "t.oid")}
            GROUP BY n.nspname,t.typname
        """)
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
        val extensions = sortedMapOf<String, String>()
        connection.rows("SELECT extname, extversion FROM pg_extension ORDER BY extname") {
            extensions[it.getString(1)] = it.getString(2)
        }
        return SchemaSnapshot(tables = tables, behaviors = behaviors, sequences = sequences, extensions = extensions)
    }
}

/** Extension membership is an object dependency, not a schema/name convention. */
private fun notExtensionOwned(catalog: String, objectId: String) =
    "NOT EXISTS (SELECT 1 FROM pg_depend dependency WHERE dependency.classid = '$catalog'::regclass " +
        "AND dependency.objid = $objectId AND dependency.objsubid = 0 AND dependency.deptype = 'e')"

private fun Connection.rows(sql: String, consume: (ResultSet) -> Unit) = createStatement().use { statement ->
    statement.executeQuery(sql).use { result -> while (result.next()) consume(result) }
}
