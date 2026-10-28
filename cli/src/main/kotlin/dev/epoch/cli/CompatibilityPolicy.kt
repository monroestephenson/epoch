package dev.epoch.cli

import dev.epoch.core.Verdict
import picocli.CommandLine

/** Exit policy only; compatibility evidence and accepted-baseline verdicts remain unchanged. */
enum class CompatibilityFailurePolicy { BREAKING, UNKNOWN }

class CompatibilityFailurePolicyConverter : CommandLine.ITypeConverter<CompatibilityFailurePolicy> {
    override fun convert(value: String): CompatibilityFailurePolicy = when (value) {
        "breaking" -> CompatibilityFailurePolicy.BREAKING
        "unknown" -> CompatibilityFailurePolicy.UNKNOWN
        else -> throw CommandLine.TypeConversionException("Expected breaking or unknown, received '$value'")
    }
}

internal fun compatibilityPolicyExitCode(verdict: Verdict, policy: CompatibilityFailurePolicy): Int =
    if (verdict == Verdict.UNKNOWN && policy == CompatibilityFailurePolicy.BREAKING) 0 else exitCode(verdict)
