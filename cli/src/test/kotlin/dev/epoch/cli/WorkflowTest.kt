package dev.epoch.cli

import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkflowTest {
    @TempDir lateinit var directory: Path

    @Test fun `coverage percentage counts a method with an unresolved call as unresolved`() {
        val paths = listOf(
            AccessPathCoverage("Repo#find:0", "Repo", "find", AccessPathStatus.STATIC, listOf("query1")),
            AccessPathCoverage("Repo#find:1", "Repo", "find", AccessPathStatus.UNRESOLVED, reason = "Dynamic SQL"),
            AccessPathCoverage("Repo#count:0", "Repo", "count", AccessPathStatus.STATIC, listOf("query2")),
        )
        val text = renderCoverage(paths)
        assertTrue(text.contains("2 methods"), text)
        assertTrue(text.contains("unresolved=1 (50.0%)"), text)
        assertTrue(text.contains("static=1 (50.0%)"), text)
        assertTrue(text.contains("Repo#find:1: Dynamic SQL"), text)
        assertTrue(text.contains("unscanned application coverage"), text)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }

    private fun initialize(): Path {
        git("init", "--initial-branch=main")
        git("config", "user.name", "Fixture")
        git("config", "user.email", "fixture@example.test")
        val migrations = Files.createDirectories(directory.resolve("db/migration"))
        Files.writeString(migrations.resolve("V1__create.sql"), "CREATE TABLE example(id integer);")
        git("add", ".")
        git("commit", "-m", "baseline")
        git("tag", "v1")
        return migrations
    }

    @Test fun `release export uses immutable blobs and preserves dirty checkout and flyway config`() {
        val migrations = initialize()
        Files.writeString(migrations.resolve("V10__index.sql"), "CREATE INDEX CONCURRENTLY idx ON example(id);")
        Files.writeString(migrations.resolve("V10__index.sql.conf"), "executeInTransaction=false\n")
        Files.writeString(migrations.resolve("V2__add.sql"), "ALTER TABLE example ADD COLUMN note text;")
        git("add", ".")
        git("commit", "-m", "release")
        val target = git("rev-parse", "HEAD")
        Files.writeString(migrations.resolve("V1__create.sql"), "uncommitted user edits")
        var exportedRoot: Path? = null
        GitReleaseMigrations.materialize(directory, "v1", "HEAD", "db/migration").use { result ->
            exportedRoot = result.baseline.parent
            assertEquals(target, result.provenance.targetCommit)
            assertEquals("CREATE TABLE example(id integer);", Files.readString(result.baseline.resolve("V1__create.sql")))
            assertEquals("executeInTransaction=false\n", Files.readString(result.pending.resolve("V10__index.sql.conf")))
            assertTrue(Files.exists(result.pending.resolve("V2__add.sql")))
            assertFalse(Files.exists(result.pending.resolve("V1__create.sql")))
        }
        assertFalse(Files.exists(exportedRoot))
        assertEquals("uncommitted user edits", Files.readString(migrations.resolve("V1__create.sql")))
        assertEquals(target, git("rev-parse", "HEAD"))
    }

    @Test fun `release workflow rejects rewriting an existing migration`() {
        val migrations = initialize()
        Files.writeString(migrations.resolve("V1__create.sql"), "CREATE TABLE changed(id integer);")
        git("add", ".")
        git("commit", "-m", "rewrite")
        val failure = assertThrows(IllegalArgumentException::class.java) {
            GitReleaseMigrations.materialize(directory, "v1", "HEAD", "db/migration")
        }
        assertTrue(failure.message!!.contains("changed/deleted: V1__create.sql"))
    }

    @Test fun `release workflow rejects path traversal and changed execution configuration`() {
        initialize()
        assertThrows(IllegalArgumentException::class.java) {
            GitReleaseMigrations.materialize(directory, "v1", "HEAD", "../db")
        }
        Files.writeString(directory.resolve("db/migration/V1__create.sql.conf"), "executeInTransaction=false")
        git("add", ".")
        git("commit", "-m", "change configuration")
        assertThrows(IllegalArgumentException::class.java) {
            GitReleaseMigrations.materialize(directory, "v1", "HEAD", "db/migration")
        }
    }

    @Test fun `terminal puts breaking migrations before unknown details and supplies next evidence`() {
        val uncertain = MigrationInfo("2", "V2__uncertain.sql")
        val broken = MigrationInfo("3", "V3__broken.sql")
        val report = CompatibilityReport(applicationVersion = "test", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.BREAKING, migrations = listOf(
                MigrationReport(uncertain, Verdict.UNKNOWN, listOf(Finding(Verdict.UNKNOWN, "COLUMN_TYPE_CHANGED", "public.t.c", "Type unknown", "Types differ", migration = uncertain))),
                MigrationReport(broken, Verdict.BREAKING, listOf(Finding(Verdict.BREAKING, "REFERENCED_TABLE_DROPPED", "public.t", "Missing table", "Absent", migration = broken))),
            ))
        val output = renderReport(report)
        assertTrue(output.indexOf("3 V3__broken.sql") < output.indexOf("2 V2__uncertain.sql"))
        assertTrue(output.contains("Next evidence: Declare accepted input domains"))
        val sarif = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(sarifReport(listOf("old-on-new" to report), "src/db migration"))
        val results = sarif["runs"][0]["results"]
        assertEquals("error", results[0]["level"].asText())
        assertEquals("src/db%20migration/V3__broken.sql", results[0]["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText())
        assertEquals(1, results[0]["locations"][0]["physicalLocation"]["region"]["startLine"].asInt())
        assertEquals("migration-file", results[0]["properties"]["locationPrecision"].asText())
    }

    @Test fun `SARIF baseline findings are not attributed to pending files`() {
        val finding = Finding(Verdict.UNKNOWN, "BASELINE_SQL_UNVERIFIED", "query", "Invalid baseline", "Missing relation")
        val report = CompatibilityReport(applicationVersion = "test", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.UNKNOWN, migrations = emptyList(), baseline = BaselineReport(Verdict.UNKNOWN, listOf(finding), "fingerprint"))
        val sarif = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(sarifReport(listOf("old-on-new" to report)))
        assertFalse(sarif["runs"][0]["results"][0].has("locations"))
        assertTrue(sarif["runs"][0]["results"][0]["properties"]["baseline"].asBoolean())
    }

    @Test fun `saved release SARIF reuses its migration path and rejects unknown wrapper versions`() {
        val migration = MigrationInfo("2", "V2__drop.sql")
        val report = CompatibilityReport(applicationVersion = "test", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = Verdict.BREAKING, migrations = listOf(MigrationReport(migration, Verdict.BREAKING,
                listOf(Finding(Verdict.BREAKING, "DROPPED", "public.t", "Dropped", "Absent", migration = migration)))))
        val input = directory.resolve("release.json")
        val output = directory.resolve("release.sarif")
        val wrapper = mapOf("schemaVersion" to 1, "release" to ReleaseProvenance("old", "new", "src/db/migration"), "verification" to report)
        writeJson(input, wrapper)
        assertEquals(1, runCli(arrayOf("report", "--input", input.toString(), "--summary", "--sarif", output.toString())))
        val uri = json.readTree(output.toFile())["runs"][0]["results"][0]["locations"][0]["physicalLocation"]["artifactLocation"]["uri"].asText()
        assertEquals("src/db/migration/V2__drop.sql", uri)
        writeJson(input, wrapper + ("schemaVersion" to 2))
        assertEquals(3, runCli(arrayOf("report", "--input", input.toString())))
    }
}
