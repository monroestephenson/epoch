package dev.epoch.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL test of Kotlin-internal JVM APIs without broadening their Kotlin visibility. */
class BaselineCacheOwnershipTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private int baselineRuns;

    @Test
    @Timeout(180)
    void copiedArchiveOwnedByAnotherUserCannotBlockRebuildAndIsCleanedUp() throws Exception {
        Path cacheDirectory = Files.createDirectories(directory.resolve("cache"));
        BaselineCache cache = new BaselineCache(cacheDirectory);
        String key = "c".repeat(64);
        Path manifestFile = cacheDirectory.resolve(key + ".manifest.json");
        List<Leftovers> containerLeftovers = new ArrayList<>();

        try (NonRootExecPostgres database = new NonRootExecPostgres()) {
            database.start();
            assertEquals("postgres", database.execInContainer("id", "-un").getStdout().trim());
            CachePreparation result = cache.prepare(database, key, () -> baseline(database));
            assertEquals("MISS", result.getStatus());
            assertSeed(database);
            containerLeftovers.add(new Leftovers("MISS", database.cacheArtifacts()));
            pending(database);
        }
        JsonNode original = json.readTree(manifestFile.toFile());
        archiveReadable(cacheDirectory, manifestFile);
        // Keep the archive and its checksum intact. pg_restore must succeed; only the catalog
        // check forces reset and rebuild within the same verification container.
        ObjectNode incorrect = original.deepCopy();
        incorrect.put("schemaSha256", "0".repeat(64));
        json.writeValue(manifestFile.toFile(), incorrect);

        try (NonRootExecPostgres database = new NonRootExecPostgres()) {
            database.start();
            CachePreparation result = cache.prepare(database, key, () -> baseline(database));
            assertEquals("REBUILT", result.getStatus());
            assertNotNull(result.getReason());
            assertTrue(result.getReason().contains("Restored catalog fingerprint differs"), result.toString());
            assertEquals(List.of(0), database.executions.stream()
                    .filter(e -> !e.command().isEmpty() && e.command().getFirst().equals("pg_restore"))
                    .map(Execution::exitCode).toList());
            assertEquals(1, database.copiedInputs.size());
            assertTrue(database.copiedInputs.stream().allMatch(i -> i.readable() && !i.writable()),
                    database.copiedInputs.toString());
            assertSeed(database);
            containerLeftovers.add(new Leftovers("REBUILT", database.cacheArtifacts()));
            pending(database);
        }
        assertEquals(2, baselineRuns);
        JsonNode rebuilt = json.readTree(manifestFile.toFile());
        assertNotEquals(original.path("archive").asText(), rebuilt.path("archive").asText(),
                "Rebuild publishes a new immutable generation");
        assertEquals(original.path("schemaSha256").asText(), rebuilt.path("schemaSha256").asText());
        archiveReadable(cacheDirectory, manifestFile);

        try (NonRootExecPostgres database = new NonRootExecPostgres()) {
            database.start();
            CachePreparation result = cache.prepare(database, key,
                    () -> fail("A warm valid archive must not replay the baseline"));
            assertEquals("HIT", result.getStatus());
            assertSeed(database);
            assertEquals(1, database.copiedInputs.size());
            assertTrue(database.copiedInputs.stream().allMatch(i -> i.readable() && !i.writable()),
                    database.copiedInputs.toString());
            containerLeftovers.add(new Leftovers("HIT", database.cacheArtifacts()));
        }
        // Defer this assertion until the ownership regression has been exercised. Against the
        // old fixed-path implementation the first meaningful failure is pg_dump permission denied.
        assertTrue(containerLeftovers.stream().allMatch(l -> l.artifacts().isEmpty()), containerLeftovers.toString());
        try (var files = Files.list(cacheDirectory)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "No host temporary archive/manifest remains");
        }
    }

    private void archiveReadable(Path cacheDirectory, Path manifestFile) throws IOException {
        // The old implementation propagates host mode. Let pg_restore read the input so the
        // failure reaches pg_dump's attempt to overwrite that foreign-owned file.
        JsonNode manifest = json.readTree(manifestFile.toFile());
        Files.setPosixFilePermissions(cacheDirectory.resolve(manifest.path("archive").asText()),
                PosixFilePermissions.fromString("rw-r--r--"));
    }

    private Unit baseline(PostgreSQLContainer database) {
        baselineRuns++;
        try (Connection connection = connection(database); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT to_regclass('public.cache_seed') IS NULL")) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1), "A rejected restored catalog must be reset before baseline replay");
            }
            statement.execute("CREATE TABLE cache_seed(id integer PRIMARY KEY, label text NOT NULL)");
            statement.execute("INSERT INTO cache_seed VALUES (1, 'baseline-seed')");
        } catch (SQLException failure) {
            throw new IllegalStateException("Test baseline failed", failure);
        }
        return Unit.INSTANCE;
    }

    private static void assertSeed(PostgreSQLContainer database) throws SQLException {
        try (Connection connection = connection(database); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT id, label FROM cache_seed")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
                assertEquals("baseline-seed", rows.getString(2));
                assertFalse(rows.next());
            }
            try (var rows = statement.executeQuery("SELECT to_regclass('public.pending_only') IS NULL")) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1), "Pending schema must not enter the baseline archive");
            }
        }
    }

    private static void pending(PostgreSQLContainer database) throws SQLException {
        try (Connection connection = connection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pending_only(id integer)");
            statement.execute("DELETE FROM cache_seed");
        }
    }

    private static Connection connection(PostgreSQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private record Leftovers(String status, List<String> artifacts) {}
    private record Execution(List<String> command, int exitCode) {}
    private record CopiedInput(String destination, boolean readable, boolean writable) {}

    private static class NonRootExecPostgres extends PostgreSQLContainer {
        final List<Execution> executions = new ArrayList<>();
        final List<CopiedInput> copiedInputs = new ArrayList<>();

        NonRootExecPostgres() { super(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE)); }

        @Override
        public Container.ExecResult execInContainer(String... command) throws IOException, InterruptedException {
            // Actual commands run as OS postgres. Docker copies still use their transfer UID,
            // making ordinary file permissions enforce the regression even with protected_regular=0.
            String[] nonRoot = new String[command.length + 2];
            nonRoot[0] = "gosu";
            nonRoot[1] = "postgres";
            System.arraycopy(command, 0, nonRoot, 2, command.length);
            Container.ExecResult result = super.execInContainer(nonRoot);
            executions.add(new Execution(List.copyOf(Arrays.asList(command)), result.getExitCode()));
            return result;
        }

        @Override
        public void copyFileToContainer(MountableFile file, String containerPath) {
            super.copyFileToContainer(file, containerPath);
            try {
                copiedInputs.add(new CopiedInput(containerPath,
                        execInContainer("test", "-r", containerPath).getExitCode() == 0,
                        execInContainer("test", "-w", containerPath).getExitCode() == 0));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while checking copied archive permissions", failure);
            } catch (IOException failure) {
                throw new IllegalStateException("Could not check copied archive permissions", failure);
            }
        }

        List<String> cacheArtifacts() throws IOException, InterruptedException {
            Container.ExecResult result = execInContainer("find", "/tmp", "-maxdepth", "1", "-name", "epoch-baseline*", "-printf", "%f\n");
            assertEquals(0, result.getExitCode(), result.getStderr());
            return result.getStdout().lines().filter(s -> !s.isBlank()).sorted().toList();
        }
    }
}
