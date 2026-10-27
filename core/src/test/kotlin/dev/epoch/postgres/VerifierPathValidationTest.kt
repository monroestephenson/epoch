package dev.epoch.postgres

import dev.epoch.core.DatabaseContract
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** Invalid directory layouts must fail before any Docker client or container is constructed. */
class VerifierPathValidationTest {
    @TempDir lateinit var directory: Path
    private val contract = DatabaseContract(applicationVersion = "path-validation")

    private fun rejected(baseline: Path, pending: Path, cache: Path? = null, message: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PostgresVerifier().verify(contract, baseline, pending, VerificationOptions(baselineCache = cache))
        }
        assertTrue(error.message.orEmpty().contains(message), error.message)
    }

    @Test fun `pending nested inside baseline is rejected before Docker`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val pending = baseline.resolve("pending").createDirectories()
        rejected(baseline, pending, message = "must not overlap")
    }

    @Test fun `baseline nested inside pending is rejected before Docker`() {
        val pending = directory.resolve("pending").createDirectories()
        val baseline = pending.resolve("baseline").createDirectories()
        rejected(baseline, pending, message = "must not overlap")
    }

    @Test fun `identical migration directories are rejected before Docker`() {
        val baseline = directory.resolve("baseline").createDirectories()
        rejected(baseline, baseline, message = "must not overlap")
    }

    @Test fun `new cache directory under either migration directory is rejected before creation`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val pending = directory.resolve("pending").createDirectories()
        rejected(baseline, pending, baseline.resolve("new/cache"), "must be outside")
        rejected(baseline, pending, pending.resolve("new/cache"), "must be outside")
        rejected(baseline, pending, baseline, "must be outside")
    }

    @Test fun `canonical symlink aliases cannot bypass containment checks`() {
        val baseline = directory.resolve("baseline").createDirectories()
        val pending = directory.resolve("pending").createDirectories()
        val alias = Files.createSymbolicLink(directory.resolve("alias"), baseline)
        rejected(baseline, pending, alias.resolve("new/cache"), "must be outside")
        val child = baseline.resolve("child").createDirectories()
        rejected(alias, child, message = "must not overlap")
    }
}
