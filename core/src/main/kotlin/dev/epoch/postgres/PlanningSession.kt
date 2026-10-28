package dev.epoch.postgres

import java.sql.Connection

internal class PlanningSessionMismatch : IllegalStateException("Planning worker session differs from the owning connection")

/** Fresh connections must resolve SQL with the same role, search path and session semantics. */
internal object PlanningSession {
    fun capture(connection: Connection): Map<String, String?> {
        val values = sortedMapOf<String, String?>()
        connection.createStatement().use { statement ->
            statement.executeQuery(policySettings).use { rows ->
                while (rows.next()) values[rows.getString(1)] = rows.getString(2)
            }
            statement.executeQuery("SELECT current_schemas(true)::text, current_user::text, session_user::text").use { row ->
                check(row.next()) { "PostgreSQL did not return planning session settings" }
                values["epoch.effective_search_path"] = row.getString(1)
                values["epoch.current_user"] = row.getString(2)
                values["epoch.session_user"] = row.getString(3)
            }
        }
        return values
    }

    // PL/pgSQL and extensions such as pg_trgm register default GUCs only when their
    // code is first loaded. That registration is not a changed verification policy.
    // Ignore only dotted names at their actual boot value with PostgreSQL's default
    // source; authored/session/non-default values remain part of the policy.
    //
    // Unloaded custom role/database settings are placeholders absent from pg_settings.
    // Track only the applicable login-role/database configuration, with PostgreSQL's
    // precedence, before and after registration. Keep its declared literal when the
    // registered source still matches it: the module may canonicalize '.9' to '0.9'.
    // SET ROLE does not apply the target role's login settings. Session overrides
    // remain distinct, including overrides of a configured value back to its default.
    // Capture reads catalog/settings metadata without a procedural block or preload.
    private val policySettings = """
        WITH registered AS (
          SELECT name, setting, boot_val, source FROM pg_catalog.pg_settings
        ), configured AS (
          SELECT DISTINCT ON (name) name, declared_value, declared_source
          FROM (
            SELECT pg_catalog.split_part(entry, '=', 1) AS name,
              pg_catalog.substr(entry, pg_catalog.strpos(entry, '=') + 1) AS declared_value,
              CASE WHEN d.setdatabase <> 0 AND d.setrole <> 0 THEN 'database user'
                   WHEN d.setrole <> 0 THEN 'user' WHEN d.setdatabase <> 0 THEN 'database' ELSE 'global' END AS declared_source,
              CASE WHEN d.setdatabase <> 0 AND d.setrole <> 0 THEN 3
                   WHEN d.setrole <> 0 THEN 2 WHEN d.setdatabase <> 0 THEN 1 ELSE 0 END AS priority
            FROM pg_catalog.pg_db_role_setting d
            CROSS JOIN LATERAL pg_catalog.unnest(d.setconfig) AS entries(entry)
            WHERE d.setdatabase IN (0, (SELECT oid FROM pg_catalog.pg_database WHERE datname=pg_catalog.current_database()))
              AND d.setrole IN (0, (SELECT oid FROM pg_catalog.pg_roles WHERE rolname=SESSION_USER))
              AND pg_catalog.strpos(pg_catalog.split_part(entry, '=', 1), '.') > 0
          ) candidates ORDER BY name, priority DESC
        )
        SELECT s.name,
          CASE WHEN pg_catalog.strpos(s.name, '.') = 0 THEN s.setting
               WHEN c.name IS NOT NULL AND s.source=c.declared_source
                 THEN 'configured:' || c.declared_source || ':' || c.declared_value
               ELSE 'active:' || COALESCE(s.source, '<unknown>') || ':' || COALESCE(pg_catalog.length(s.setting), -1)::text || ':' || COALESCE(s.setting, '')
          END AS setting
        FROM registered s LEFT JOIN configured c USING (name)
        WHERE s.name NOT IN ('application_name','search_path')
          AND (pg_catalog.strpos(s.name, '.') = 0 OR s.source IS DISTINCT FROM 'default' OR s.setting IS DISTINCT FROM s.boot_val)
        UNION ALL
        SELECT c.name,
          CASE WHEN pg_catalog.current_setting(c.name, true)=c.declared_value
                 THEN 'configured:' || c.declared_source || ':' || c.declared_value
               ELSE 'unregistered:' || COALESCE(pg_catalog.length(pg_catalog.current_setting(c.name, true)), -1)::text || ':' ||
                 COALESCE(pg_catalog.current_setting(c.name, true), '')
          END AS setting
        FROM configured c WHERE NOT EXISTS (SELECT 1 FROM registered s WHERE s.name=c.name)
        ORDER BY name
    """.trimIndent()
}
