package dev.epoch.postgres

import dev.epoch.core.SchemaSnapshot
import java.sql.Connection

/** Compare durable catalog facts independently of the migration's temporary search path.
 * Actual-session query/behavior evidence is captured separately and never replaced by this.
 * PostgreSQL deparses stored expressions using search_path, so ignoring the snapshot's path
 * field alone is insufficient. Explicit pg_temp placement also prevents its implicit prefix.
 */
internal object CanonicalCatalog {
    fun inspect(catalog: PostgresCatalog, connection: Connection): SchemaSnapshot {
        val original = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_catalog.current_setting('search_path')").use { rows ->
                check(rows.next()) { "The owning catalog session has no search_path setting" }
                rows.getString(1)
            }
        }
        val local = !connection.autoCommit
        fun set(value: String) = connection.prepareStatement("SELECT pg_catalog.set_config('search_path', ?, ?)").use {
            it.setString(1, value); it.setBoolean(2, local)
            it.executeQuery().use { result -> check(result.next()) }
        }
        set("pg_catalog, pg_temp")
        var failure: Throwable? = null
        try {
            return catalog.inspect(connection)
        } catch (cause: Throwable) {
            failure = cause
            throw cause
        } finally {
            try { set(original) }
            catch (restore: Throwable) { if (failure == null) throw restore else failure.addSuppressed(restore) }
        }
    }
}
