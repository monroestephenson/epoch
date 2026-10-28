package dev.epoch.postgres;

import dev.epoch.core.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Lazy registration of unchanged extension defaults is not a verification policy change. */
class PlanningSessionInitializationTest {
    private static final Set<String> PLPGSQL_SETTINGS = Set.of(
            "plpgsql.check_asserts", "plpgsql.extra_errors", "plpgsql.extra_warnings",
            "plpgsql.print_strict_params", "plpgsql.variable_conflict");
    private static final Set<String> TRGM_SETTINGS = Set.of(
            "pg_trgm.similarity_threshold", "pg_trgm.word_similarity_threshold", "pg_trgm.strict_word_similarity_threshold");

    @Test @Timeout(120)
    void harmlessDefaultPlanningDoesNotManufactureSessionDriftOrPreloadApplicationCode() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) {
                execute(setup, "CREATE FUNCTION public.harmless_default() RETURNS bigint LANGUAGE plpgsql STABLE " +
                        "AS $$ BEGIN RETURN 7; END $$");
                execute(setup, "CREATE TABLE accounts(id bigint DEFAULT public.harmless_default())");
            }
            try (Connection connection = connect(database)) {
                assertTrue(extensionSettings(connection, "plpgsql").isEmpty());
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
                assertTrue(extensionSettings(connection, "plpgsql").isEmpty(), "Capturing policy must not load or execute the language.");
                execute(connection, "PREPARE epoch_default_probe AS INSERT INTO accounts DEFAULT VALUES");
                execute(connection, "EXPLAIN EXECUTE epoch_default_probe");
                assertEquals(PLPGSQL_SETTINGS, extensionSettings(connection, "plpgsql"));
                assertEquals(baseline, PlanningSession.INSTANCE.capture(connection),
                        "Only unchanged default settings appeared during harmless SQL planning.");
                assertEquals(0, scalarInt(connection, "SELECT count(*) FROM accounts"), "EXPLAIN must not execute the INSERT.");
                MigrationInfo migration = new MigrationInfo("2", "V2__harmless_default.sql");
                try (PendingMigrationLease lease = new PendingMigrationLease(migration, 30, connection)) {
                    checker(baseline).checkSession(connection, baseline, lease);
                    lease.finish();
                }
            }
        }
    }

    @Test @Timeout(120)
    void unchangedPgTrgmDefaultsAreAlsoStableAcrossLazyRegistration() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) { execute(setup, "CREATE EXTENSION pg_trgm"); }
            try (Connection connection = connect(database)) {
                assertTrue(extensionSettings(connection, "pg_trgm").isEmpty());
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
                assertTrue(extensionSettings(connection, "pg_trgm").isEmpty(), "Capture must not preload extensions.");
                execute(connection, "SELECT public.similarity('epoch', 'epoch')");
                assertEquals(TRGM_SETTINGS, extensionSettings(connection, "pg_trgm"));
                assertEquals(baseline, PlanningSession.INSTANCE.capture(connection));
            }
        }
    }

    @Test @Timeout(120)
    void rawRoleThresholdRemainsTheSamePolicyAfterPostgresNormalizesItsRepresentation() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) {
                execute(setup, "CREATE EXTENSION pg_trgm");
                execute(setup, "ALTER ROLE epoch SET pg_trgm.similarity_threshold = '.9'");
            }
            assertLazyThreshold(database, "epoch", "fixture-admin", ".9", 0.9, null);
        }
    }

    @Test @Timeout(180)
    void databaseAndLoginRolePrecedenceIsPreservedAndSetRoleDoesNotApplyAnotherRolesDefaults() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) {
                execute(setup, "CREATE EXTENSION pg_trgm");
                execute(setup, "CREATE ROLE epoch_reader LOGIN PASSWORD 'fixture-reader'");
                execute(setup, "CREATE ROLE epoch_other NOLOGIN");
                execute(setup, "GRANT epoch_other TO epoch_reader");
                execute(setup, "ALTER DATABASE epoch SET pg_trgm.similarity_threshold = '.4'");
                execute(setup, "ALTER ROLE epoch_reader SET pg_trgm.similarity_threshold = '.7'");
                execute(setup, "ALTER ROLE epoch_reader IN DATABASE epoch SET pg_trgm.similarity_threshold = '.9'");
                execute(setup, "ALTER ROLE epoch_other SET pg_trgm.similarity_threshold = '.2'");
            }
            assertLazyThreshold(database, "epoch_reader", "fixture-reader", ".9", 0.9, "epoch_other");
            try (Connection setup = connect(database)) {
                execute(setup, "ALTER ROLE epoch_reader IN DATABASE epoch RESET pg_trgm.similarity_threshold");
            }
            assertLazyThreshold(database, "epoch_reader", "fixture-reader", ".7", 0.7, "epoch_other");
            try (Connection setup = connect(database)) {
                execute(setup, "ALTER ROLE epoch_reader RESET pg_trgm.similarity_threshold");
            }
            assertLazyThreshold(database, "epoch_reader", "fixture-reader", ".4", 0.4, "epoch_other");
        }
    }

    @Test @Timeout(120)
    void realRegisteredSetAndSetConfigChangesRemainAttributedInfrastructureFailures() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection connection = connect(database)) {
                execute(connection, "DO LANGUAGE plpgsql 'BEGIN NULL; END'");
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
                assertEquals("error", scalarText(connection, "SELECT current_setting('plpgsql.variable_conflict')"));
                MigrationInfo migration = new MigrationInfo("2", "V2__changed_policy.sql");
                try (PendingMigrationLease lease = new PendingMigrationLease(migration, 30, connection)) {
                    execute(connection, "SET plpgsql.variable_conflict = use_variable");
                    assertPolicyFailure(connection, baseline, lease, migration, "use_variable");
                    execute(connection, "RESET plpgsql.variable_conflict");
                    checker(baseline).checkSession(connection, baseline, lease);
                    execute(connection, "SELECT set_config('plpgsql.variable_conflict', 'use_column', false)");
                    assertPolicyFailure(connection, baseline, lease, migration, "use_column");
                    lease.finish();
                }
            }
        }
    }

    @Test @Timeout(120)
    void ordinaryReaderCapturesPolicyWithoutLoadingAProceduralLanguage() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) { execute(setup, "CREATE ROLE epoch_reader LOGIN PASSWORD 'fixture-reader'"); }
            try (Connection reader = DriverManager.getConnection(database.getJdbcUrl(), "epoch_reader", "fixture-reader")) {
                assertTrue(extensionSettings(reader, "plpgsql").isEmpty());
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(reader);
                assertEquals("epoch_reader", baseline.get("epoch.current_user"));
                assertTrue(extensionSettings(reader, "plpgsql").isEmpty());
                assertEquals(baseline, PlanningSession.INSTANCE.capture(reader));
                assertEquals(1, scalarInt(reader, "SELECT 1"));
            }
        }
    }

    @Test @Timeout(120)
    void absentLanguageDoesNotExecuteOrAbortTheCapture() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) { execute(setup, "DROP EXTENSION plpgsql"); }
            try (Connection connection = connect(database)) {
                connection.setAutoCommit(false);
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
                assertTrue(extensionSettings(connection, "plpgsql").isEmpty());
                assertEquals(1, scalarInt(connection, "SELECT 1"), "The transaction remains usable.");
                assertEquals(baseline, PlanningSession.INSTANCE.capture(connection));
                connection.rollback();
            }
        }
    }

    @Test @Timeout(120)
    void modifiedInlineHandlerIsNotInvoked() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) {
                execute(setup, "ALTER FUNCTION pg_catalog.plpgsql_inline_handler(internal) SET application_name = 'epoch-handler-must-not-run'");
            }
            try (Connection connection = connect(database)) {
                assertTrue(extensionSettings(connection, "plpgsql").isEmpty());
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
                assertTrue(extensionSettings(connection, "plpgsql").isEmpty(),
                        "Invoking the modified inline handler would load PL/pgSQL and register its settings.");
                assertEquals(baseline, PlanningSession.INSTANCE.capture(connection));
                assertEquals(1, scalarInt(connection, "SELECT 1"));
            }
        }
    }

    @Test @Timeout(120)
    void revokedLanguageUsageDoesNotAbortTheReadersTransactionOrRequireElevation() throws Exception {
        try (PostgreSQLContainer database = database()) {
            database.start();
            try (Connection setup = connect(database)) {
                execute(setup, "CREATE ROLE epoch_reader LOGIN PASSWORD 'fixture-reader'");
                execute(setup, "REVOKE USAGE ON LANGUAGE plpgsql FROM PUBLIC");
            }
            try (Connection reader = DriverManager.getConnection(database.getJdbcUrl(), "epoch_reader", "fixture-reader")) {
                reader.setAutoCommit(false);
                assertTrue(extensionSettings(reader, "plpgsql").isEmpty());
                Map<String, String> baseline = PlanningSession.INSTANCE.capture(reader);
                assertTrue(extensionSettings(reader, "plpgsql").isEmpty());
                assertEquals(1, scalarInt(reader, "SELECT 1"), "Do not attempt an unauthorized DO that aborts the transaction.");
                assertEquals(baseline, PlanningSession.INSTANCE.capture(reader));
                reader.rollback();
            }
        }
    }

    private static void assertLazyThreshold(PostgreSQLContainer database, String login, String password,
                                            String rawValue, double numericValue, String assumedRole) throws SQLException {
        try (Connection connection = DriverManager.getConnection(database.getJdbcUrl(), login, password)) {
            if (assumedRole != null) execute(connection, "SET ROLE " + assumedRole);
            assertTrue(extensionSettings(connection, "pg_trgm").isEmpty());
            assertEquals(rawValue, scalarText(connection, "SELECT current_setting('pg_trgm.similarity_threshold')"));
            Map<String, String> baseline = PlanningSession.INSTANCE.capture(connection);
            assertTrue(extensionSettings(connection, "pg_trgm").isEmpty());
            assertNotNull(baseline.get("pg_trgm.similarity_threshold"),
                    "An unloaded configured extension value is part of the policy already.");
            assertEquals(login, baseline.get("epoch.session_user"));
            assertEquals(assumedRole == null ? login : assumedRole, baseline.get("epoch.current_user"));
            execute(connection, "SELECT public.similarity('epoch', 'epoch')");
            assertEquals(numericValue, Double.parseDouble(scalarText(connection,
                    "SELECT current_setting('pg_trgm.similarity_threshold')")), 0.000001);
            assertEquals(TRGM_SETTINGS, extensionSettings(connection, "pg_trgm"));
            assertEquals(baseline, PlanningSession.INSTANCE.capture(connection),
                    "A declared raw value and its normalized registered representation are the same active policy.");
            execute(connection, "SET pg_trgm.similarity_threshold = 0.3");
            assertNotEquals(baseline, PlanningSession.INSTANCE.capture(connection),
                    "An authored override to the module boot value must not be mistaken for lazy default registration.");
            execute(connection, "RESET pg_trgm.similarity_threshold");
            assertEquals(baseline, PlanningSession.INSTANCE.capture(connection));
        }
    }

    private static void assertPolicyFailure(Connection connection, Map<String, String> baseline,
                                            PendingMigrationLease lease, MigrationInfo migration, String value) {
        VerificationException failure = assertThrows(VerificationException.class,
                () -> checker(baseline).checkSession(connection, baseline, lease));
        assertEquals("PENDING_PLANNING_SESSION_CHANGED", failure.getStage());
        assertEquals(migration, failure.getMigration());
        assertTrue(failure.getMessage().contains("plpgsql.variable_conflict"), failure.toString());
        assertFalse(failure.getMessage().contains(value), "Diagnostics must name settings without exposing values.");
    }

    private static DeferredQueryPlanning checker(Map<String, String> baseline) {
        return new DeferredQueryPlanning(new PostgresQueryPlanner(),
                () -> { throw new AssertionError("A session equality check does not need a planning worker"); }, 1, baseline);
    }

    private static PostgreSQLContainer database() {
        return new PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
                .withDatabaseName("epoch").withUsername("epoch").withPassword("fixture-admin");
    }

    private static Connection connect(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Set<String> extensionSettings(Connection connection, String prefix) throws SQLException {
        var settings = new TreeSet<String>();
        try (var statement = connection.prepareStatement("SELECT name FROM pg_settings WHERE name LIKE ? ORDER BY name")) {
            statement.setString(1, prefix + ".%");
            try (var rows = statement.executeQuery()) {
                while (rows.next()) settings.add(rows.getString(1));
            }
        }
        return settings;
    }
}
