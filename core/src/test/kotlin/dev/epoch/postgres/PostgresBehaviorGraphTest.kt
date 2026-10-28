package dev.epoch.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PostgresBehaviorGraphTest {
    @Test fun `only direct projections supply a positive view expression proof`() {
        for (sql in listOf(
            "SELECT t.id, t.label FROM public.accounts t",
            "SELECT \"Accounts\".\"Label\" FROM \"Example\".\"Accounts\"",
            "SELECT accounts.* FROM accounts",
            "SELECT \"current_user\" FROM accounts",
            "SELECT accounts.current_user FROM accounts",
        )) assertTrue(PostgresBehaviorGraph.functionFreeView(sql), sql)
    }

    @Test fun `pinned builtin relation name lookups are not made complete by absent pg_depend edges`() {
        for (sql in listOf(
            "SELECT pg_get_serial_sequence('accounts','id') FROM accounts",
            "SELECT to_regclass('accounts') FROM accounts",
            "SELECT current_user FROM accounts",
            "SELECT session_user FROM accounts",
            "SELECT current_schema FROM accounts",
            "SELECT id::regclass FROM accounts",
            "SELECT id FROM accounts WHERE application_filter(id)",
            "SELECT (SELECT id FROM other_table) FROM accounts",
        )) assertFalse(PostgresBehaviorGraph.functionFreeView(sql), sql)
    }

    @Test fun `unsupported view variants remain locally incomplete`() {
        for (sql in listOf(
            "SELECT id FROM accounts UNION SELECT id FROM archived",
            "WITH source AS (SELECT id FROM accounts) SELECT id FROM source",
            "SELECT id FROM accounts LIMIT 1",
            "SELECT id FROM accounts ORDER BY id",
            "SELECT count(*) FROM accounts",
            "SELECT id + 1 FROM accounts",
            "unparseable",
        )) assertFalse(PostgresBehaviorGraph.functionFreeView(sql), sql)
    }
}
