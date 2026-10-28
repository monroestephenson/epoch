package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.*

class StringNarrowingProofTest {
    private val standard = ColumnContract("label", "VARCHAR", length = 255,
        logicalJavaType = "java.lang.String", inputDomainComplete = true, stringInputLengthUnrestricted = true)

    @Test fun `known standard binding intersects the actual baseline rather than its DDL hint`() {
        assertTrue(requireNotNull(RuleProofs.mappedNarrowing(standard, "text", "character varying(500)")).contains("501"))
        assertTrue(requireNotNull(RuleProofs.mappedNarrowing(standard.copy(length = 5), "text", "character varying(500)")).contains("501"))
        assertNull(RuleProofs.mappedNarrowing(standard, "character varying(400)", "character varying(500)"))
        assertNull(RuleProofs.mappedNarrowing(standard, "text", "text"))
    }

    @Test fun `legacy missing facts and unsupported codecs cannot acquire an unrestricted domain`() {
        assertNull(RuleProofs.mappedNarrowing(standard.copy(stringInputLengthUnrestricted = false), "text", "character varying(500)"))
        assertNull(RuleProofs.mappedNarrowing(standard.copy(inputDomainComplete = false), "text", "character varying(500)"))
        assertNull(RuleProofs.mappedNarrowing(standard.copy(logicalJavaType = "java.util.UUID"), "text", "character varying(500)"))
        assertNull(RuleProofs.mappedNarrowing(standard.copy(enumValues = listOf("OPEN", "CLOSED")), "text", "character varying(500)"))
    }
}
