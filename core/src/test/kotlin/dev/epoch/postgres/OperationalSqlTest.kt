package dev.epoch.postgres

import dev.epoch.core.OperationalActionKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationalSqlTest {
    @Test fun `concurrent modifier is a grammar token rather than a comment or literal substring`() {
        val index = OperationalSql.inspect("/* outer /* CONCURRENTLY */ comment */ CREATE INDEX CONCURRENTLY ix ON public.accounts(id);")
        assertEquals("CREATE INDEX", index.commandTag)
        assertTrue(index.intents.single().concurrent)
        assertEquals("public.accounts", index.intents.single().table)
        val ordinary = OperationalSql.inspect("CREATE INDEX \"CONCURRENTLY\" ON accounts(id);")
        assertFalse(ordinary.intents.single().concurrent)
        assertEquals("\"CONCURRENTLY\"", ordinary.intents.single().detail)
    }

    @Test fun `multi-action alter keeps every clause and quoted names while nested commas remain within a clause`() {
        val sql = OperationalSql.inspect("ALTER TABLE \"OpsSchema\".\"Accounts\" ALTER COLUMN amount TYPE numeric(12,2), ADD COLUMN note text, ALTER COLUMN label SET NOT NULL;")
        assertEquals(3, sql.intents.size)
        assertEquals(listOf(OperationalActionKind.ALTER_COLUMN_TYPE, OperationalActionKind.METADATA_CHANGE, OperationalActionKind.SET_NOT_NULL), sql.intents.map { it.kind })
        assertTrue(sql.intents.all { it.table == "\"OpsSchema\".\"Accounts\"" })
        assertEquals("ADD_COLUMN", sql.intents[1].modifier)
        assertTrue(sql.intents.all { it.complete })
    }

    @Test fun `constraint grammar records deferred validation and prior-state lookup target`() {
        val deferred = OperationalSql.inspect("ALTER TABLE accounts ADD CONSTRAINT label_nn CHECK (label IS NOT NULL) NOT VALID;").intents.single()
        assertEquals(OperationalActionKind.ADD_CHECK, deferred.kind)
        assertEquals("label_nn", deferred.detail)
        assertTrue(deferred.notValid)
        val validate = OperationalSql.inspect("ALTER TABLE accounts VALIDATE CONSTRAINT label_nn;").intents.single()
        assertEquals(OperationalActionKind.VALIDATE_CONSTRAINT, validate.kind)
        assertEquals("label_nn", validate.detail)
    }

    @Test fun `opaque SQL never acquires a positive metadata action from embedded words`() {
        for (sql in listOf("DO $$ BEGIN PERFORM 1; PERFORM 2; END $$;", "SELECT 'CREATE INDEX CONCURRENTLY ix ON t(id)'", "ALTER TABLE t ENABLE TRIGGER ALL;", "BEGIN;")) {
            val parsed = OperationalSql.inspect(sql)
            assertTrue(parsed.intents.any { it.kind == OperationalActionKind.UNKNOWN }, sql)
            assertFalse(parsed.intents.any { it.complete }, sql)
        }
        val transformed = OperationalSql.inspect("ALTER TABLE t ALTER COLUMN amount TYPE bigint USING application_transform(amount);")
        assertEquals(OperationalActionKind.ALTER_COLUMN_TYPE, transformed.intents.single().kind)
        assertFalse(transformed.intents.single().complete)
    }

    @Test fun `literal timeout assignments retain scope while expressions remain unsupported`() {
        assertEquals("LOCAL", OperationalSql.inspect("SET LOCAL lock_timeout = '750ms';").timeoutSetting)
        assertEquals("SESSION", OperationalSql.inspect("SET lock_timeout TO 0;").timeoutSetting)
        assertNull(OperationalSql.inspect("SELECT set_config('lock_timeout', '750ms', true);").timeoutSetting)
        assertNull(OperationalSql.inspect("SET lock_timeout TO DEFAULT;").timeoutSetting)
    }

    @Test fun `create-as query and inherited tables are not positively classified as empty ordinary tables`() {
        assertTrue(OperationalSql.inspect("CREATE TABLE fresh(id bigint PRIMARY KEY);").intents.single().complete)
        assertFalse(OperationalSql.inspect("CREATE TABLE fresh AS SELECT * FROM existing;").intents.single().complete)
        assertFalse(OperationalSql.inspect("CREATE TABLE fresh(id bigint) INHERITS (existing);").intents.single().complete)
    }
}
