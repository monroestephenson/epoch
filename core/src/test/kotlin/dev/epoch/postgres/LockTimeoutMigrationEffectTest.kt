package dev.epoch.postgres

import dev.epoch.core.MigrationInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockTimeoutMigrationEffectTest {
    private fun classify(sql: String) = MigrationClassifier().findings(sql, MigrationInfo("2", "V2__timeout.sql"))

    @Test fun `literal migration lock timeouts do not invent a data or schema effect`() {
        for (sql in listOf("SET LOCAL lock_timeout = '500ms';", "SET SESSION lock_timeout TO '2s';",
            "SET lock_timeout = 0;", "SET /* policy */ LOCAL lock_timeout = '1min';")) {
            assertTrue(classify(sql).isEmpty(), "$sql: ${classify(sql)}")
        }
    }

    @Test fun `other settings function calls and DML are not waived by an earlier timeout`() {
        for (sql in listOf("SET search_path = malicious, public;", "SELECT set_config('lock_timeout', '1s', true);",
            "SET LOCAL lock_timeout = '1s'; UPDATE accounts SET value = 'changed';",
            "SET LOCAL lock_timeout = '1s'; DO ${'$'}${'$'} BEGIN PERFORM 1; END ${'$'}${'$'};")) {
            assertFalse(classify(sql).isEmpty(), sql)
        }
    }
}
