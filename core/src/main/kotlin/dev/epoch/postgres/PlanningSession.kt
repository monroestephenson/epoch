package dev.epoch.postgres

import java.sql.Connection

internal class PlanningSessionMismatch : IllegalStateException("Planning worker session differs from the owning connection")

/** Fresh connections must resolve SQL with the same role, search path and session semantics. */
internal object PlanningSession {
    fun capture(connection: Connection): Map<String, String?> {
        val values = sortedMapOf<String, String?>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name, setting FROM pg_settings WHERE name NOT IN ('application_name','search_path') ORDER BY name").use { rows ->
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
}
