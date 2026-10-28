package dev.epoch.cli

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.epoch.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Saved-report policy checks compile before operational DTOs exist. */
class OperationalPolicyTest {
    @TempDir lateinit var directory: Path

    private fun report(compatibility: Verdict = Verdict.SAFE, operational: String? = "RISKY", complete: Boolean = true): ObjectNode {
        val value = CompatibilityReport(applicationVersion = "fixture", scope = VerificationScope.STRUCTURAL_CONTRACT,
            verdict = compatibility, migrations = emptyList(), complete = complete)
        return json.valueToTree<ObjectNode>(value).apply {
            if (operational != null) put("operationalVerdict", operational)
        }
    }

    private fun replay(value: Any, operationalPolicy: Boolean): Int {
        val input = directory.resolve("report.json")
        writeJson(input, value)
        val args = listOf("report", "--input", input.toString(), "--summary") +
            if (operationalPolicy) listOf("--fail-on-operational") else emptyList()
        return runCli(args.toTypedArray())
    }

    @Test fun `operational risk leaves default compatibility exits unchanged and has an opt-in exit`() {
        assertEquals(0, replay(report(), operationalPolicy = false))
        assertEquals(4, replay(report(), operationalPolicy = true))
        assertEquals(0, replay(report(operational = "SAFE"), operationalPolicy = true))
        assertEquals(4, replay(report(operational = "UNKNOWN"), operationalPolicy = true))
        assertEquals(4, replay(report(operational = null), operationalPolicy = true))
    }

    @Test fun `runtime completeness and compatibility failures retain their exit precedence`() {
        assertEquals(3, replay(report(complete = false), operationalPolicy = true))
        assertEquals(1, replay(report(compatibility = Verdict.BREAKING), operationalPolicy = true))
        assertEquals(2, replay(report(compatibility = Verdict.UNKNOWN), operationalPolicy = true))
    }

    @Test fun `only actual pending migrations supply operational risk in a combined release report`() {
        val combined = mapOf("schemaVersion" to 1, "oldOnNew" to report(operational = "SAFE"),
            "newOnOld" to report(operational = null), "dataCompatibility" to report(operational = null),
            "verdict" to Verdict.SAFE, "operationalVerdict" to "SAFE")
        assertEquals(0, replay(combined, operationalPolicy = true))
        val wrapper = mapOf("schemaVersion" to 1, "release" to ReleaseProvenance("old", "new", "db/migration"),
            "verification" to combined)
        assertEquals(0, replay(wrapper, operationalPolicy = true))
    }
}
