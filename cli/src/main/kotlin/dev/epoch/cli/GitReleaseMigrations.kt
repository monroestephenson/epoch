package dev.epoch.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

data class ReleaseProvenance(val baseCommit: String, val targetCommit: String, val migrationPath: String)

/** Reads immutable Git blobs; never changes the checkout or invents Flyway ordering. */
class GitReleaseMigrations private constructor(
    private val temporary: Path,
    val baseline: Path,
    val pending: Path,
    val provenance: ReleaseProvenance,
) : AutoCloseable {
    override fun close() {
        Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    companion object {
        fun materialize(repository: Path, baseRef: String, targetRef: String, migrationPath: String): GitReleaseMigrations {
            val relative = Path.of(migrationPath)
            require(!relative.isAbsolute && relative.nameCount > 0 && relative.none { it.toString() in setOf("..", ".") }) {
                "--migration-path must be a repository-relative directory without . or .. components"
            }
            val prefix = relative.toString().replace('\\', '/').trimEnd('/')
            require(prefix.isNotBlank()) { "--migration-path cannot be empty" }
            fun git(vararg args: String): ByteArray {
                val process = ProcessBuilder(listOf("git", "--literal-pathspecs", "-C", repository.toAbsolutePath().toString()) + args)
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start()
                val output = process.inputStream.use { it.readBytes() }
                check(process.waitFor() == 0) { "Git could not read the requested release history (${args.first()})" }
                return output
            }
            fun resolve(ref: String): String = git("rev-parse", "--verify", "--end-of-options", "$ref^{commit}")
                .toString(Charsets.UTF_8).trim().also { require(it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) }
            fun files(commit: String): Map<String, String> = buildMap {
                val records = git("ls-tree", "-rz", "--full-tree", commit, "--", prefix).toString(Charsets.UTF_8)
                for (record in records.split('\u0000').filter(String::isNotEmpty)) {
                    val (header, name) = record.split('\t', limit = 2)
                    if (!name.endsWith(".sql") && !name.endsWith(".sql.conf")) continue
                    val fields = header.split(' ')
                    require(fields[0] in setOf("100644", "100755") && fields[1] == "blob") {
                        "Migration resources must be regular Git files: $name"
                    }
                    require(name.startsWith("$prefix/")) { "Migration path must select a directory: $prefix" }
                    put(name.removePrefix("$prefix/"), fields[2])
                }
            }
            val baseCommit = resolve(baseRef)
            val targetCommit = resolve(targetRef)
            val oldFiles = files(baseCommit)
            val newFiles = files(targetCommit)
            require(oldFiles.isNotEmpty()) { "No baseline SQL migrations found at $baseRef:$prefix" }
            val changed = oldFiles.keys.filter { oldFiles[it] != newFiles[it] }
            require(changed.isEmpty()) {
                "Release workflow requires immutable existing migrations and configuration; changed/deleted: ${changed.sorted().joinToString()}. Use explicit baseline/pending directories for a reviewed custom history."
            }
            val additions = newFiles - oldFiles.keys
            require(additions.keys.none { it.endsWith(".sql.conf") && it.removeSuffix(".conf") in oldFiles }) {
                "Adding configuration to an existing migration changes its release history"
            }
            val temporary = Files.createTempDirectory("epoch-release-")
            val result = GitReleaseMigrations(temporary, Files.createDirectory(temporary.resolve("baseline")),
                Files.createDirectory(temporary.resolve("pending")), ReleaseProvenance(baseCommit, targetCommit, prefix))
            try {
                fun export(entries: Map<String, String>, destination: Path) {
                    for ((name, blob) in entries.toSortedMap()) {
                        val output = destination.resolve(name).normalize()
                        require(output.startsWith(destination)) { "Migration path escapes export directory: $name" }
                        Files.createDirectories(output.parent)
                        Files.write(output, git("cat-file", "blob", blob))
                    }
                }
                export(oldFiles, result.baseline)
                export(additions, result.pending)
                return result
            } catch (failure: Throwable) {
                result.close()
                throw failure
            }
        }
    }
}
