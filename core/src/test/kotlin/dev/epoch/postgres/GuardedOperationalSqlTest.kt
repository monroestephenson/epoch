package dev.epoch.postgres

import dev.epoch.core.OperationalActionKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardedOperationalSqlTest {
    @Test fun `guard keywords are skipped before the actual added column identity`() {
        for (columnWord in listOf("COLUMN ", "")) {
            val statement = OperationalSql.inspect("ALTER TABLE IF EXISTS public.accounts ADD ${columnWord}IF NOT EXISTS metadata text;")
            val intent = statement.intents.single()
            assertEquals("public.accounts", intent.table)
            assertEquals("metadata", intent.detail)
            assertEquals(OperationalActionKind.METADATA_CHANGE, intent.kind)
            assertEquals("ADD_COLUMN", intent.modifier)
            assertTrue(intent.complete)
            assertTrue(intent.ifNotExists)
        }
    }

    @Test fun `quoted IF names and guard words in literals remain ordinary identifiers and values`() {
        val quoted = OperationalSql.inspect("ALTER TABLE public.accounts ADD COLUMN \"IF\" text DEFAULT 'IF NOT EXISTS';").intents.single()
        assertEquals("IF", quoted.detail)
        assertFalse(quoted.ifNotExists)
        val mixed = OperationalSql.inspect("ALTER TABLE IF EXISTS \"Accounts\" ADD COLUMN IF /* comment */ NOT EXISTS \"Tag\" text, ADD COLUMN extra numeric(12, 2);")
        assertEquals(listOf("Tag", "extra"), mixed.intents.map { it.detail })
        assertEquals(listOf(true, false), mixed.intents.map { it.ifNotExists })
    }

    @Test fun `malformed ADD guards cannot create a fictitious column named if`() {
        for (sql in listOf("ALTER TABLE t ADD COLUMN IF EXISTS value text;", "ALTER TABLE t ADD COLUMN IF NOT value text;")) {
            val intent = OperationalSql.inspect(sql).intents.single()
            assertFalse(intent.complete, sql)
            assertEquals(OperationalActionKind.UNKNOWN, intent.kind, sql)
        }
    }
}
