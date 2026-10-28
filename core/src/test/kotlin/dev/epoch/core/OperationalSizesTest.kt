package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperationalSizesTest {
    private val old = OperationalRelation("public.accounts", "table", preExisting = true, baselineName = "public.accounts")
    private fun sizes(rows: Long? = 0, bytes: Long? = 0, key: String = old.baselineName!!) =
        OperationalSizes.validateAndNormalize(TableSizes(tables = mapOf(key to TableSize(rows, bytes))))
    private fun grade(rows: Long?, bytes: Long?) = OperationalSizes.grade(old, sizes(rows, bytes))

    @Test fun `exact thresholds require both estimates and retain risk evidence`() {
        assertEquals(OperationalSeverity.LOW, grade(0, 0).severity)
        assertEquals(OperationalSeverity.LOW, grade(10_000, 16L * 1024 * 1024).severity)
        assertEquals(OperationalSeverity.MEDIUM, grade(10_001, 0).severity)
        assertEquals(OperationalSeverity.MEDIUM, grade(0, 16L * 1024 * 1024 + 1).severity)
        assertEquals(OperationalSeverity.MEDIUM, grade(1_000_000, 1024L * 1024 * 1024).severity)
        assertEquals(OperationalSeverity.HIGH, grade(1_000_001, 0).severity)
        assertEquals(OperationalSeverity.HIGH, grade(0, 1024L * 1024 * 1024 + 1).severity)
        assertEquals(OperationalSeverity.HIGH, grade(Long.MAX_VALUE, Long.MAX_VALUE).severity)
        assertTrue(grade(0, 0).evidence.contains("Operational risk is unchanged"))
    }

    @Test fun `missing and partial estimates remain high without inventing zero`() {
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old, null).severity)
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old, TableSizes()).severity)
        assertEquals(OperationalSeverity.HIGH, grade(null, null).severity)
        assertEquals(OperationalSeverity.HIGH, grade(null, 0).severity)
        assertEquals(OperationalSeverity.HIGH, grade(0, null).severity)
        assertTrue(grade(23, null).evidence.contains("estimatedRows=23; totalBytes=unknown"))
    }

    @Test fun `renamed table uses its original baseline estimate`() {
        val renamed = old.copy(name = "public.accounts_v2")
        val input = OperationalSizes.validateAndNormalize(TableSizes(tables = mapOf(
            "public.accounts" to TableSize(1, 1), "public.accounts_v2" to TableSize(9_000_000, 9_000_000_000))))
        val result = OperationalSizes.grade(renamed, input)
        assertEquals(OperationalSeverity.LOW, result.severity)
        assertTrue(result.evidence.contains("baselineName=public.accounts; estimatedRows=1; totalBytes=1"))
    }

    @Test fun `new or unknown identity does not borrow a same-named old estimate`() {
        val input = sizes()
        for (identity in listOf(false, null)) {
            assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old.copy(preExisting = identity), input).severity)
        }
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old.copy(baselineName = null), input).severity)
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old.copy(baselineName = "accounts"), input).severity)
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old.copy(baselineName = "public.elsewhere"), input).severity)
    }

    @Test fun `null input is optional and accepted input is copied in stable key order`() {
        assertNull(OperationalSizes.validateAndNormalize(null))
        val source = linkedMapOf("public.z" to TableSize(2, 3), "public.a" to TableSize(4, 5))
        val result = OperationalSizes.validateAndNormalize(TableSizes(tables = source))!!
        assertEquals(listOf("public.a", "public.z"), result.tables.keys.toList())
        source.clear()
        assertEquals(2, result.tables.size)
        assertEquals(result, OperationalSizes.validateAndNormalize(result))
    }

    @Test fun `invalid versions negative values and ambiguous keys fail validation`() {
        for (version in listOf(0, 2)) assertFailsWith<IllegalArgumentException> {
            OperationalSizes.validateAndNormalize(TableSizes(schemaVersion = version))
        }
        for (size in listOf(TableSize(-1, 0), TableSize(0, -1))) assertFailsWith<IllegalArgumentException> {
            OperationalSizes.validateAndNormalize(TableSizes(tables = mapOf("public.accounts" to size)))
        }
        for (key in listOf("", "accounts", ".accounts", "public.", "a.b.c")) assertFailsWith<IllegalArgumentException> {
            OperationalSizes.validateAndNormalize(TableSizes(tables = mapOf(key to TableSize())))
        }
    }

    @Test fun `PostgreSQL truncation normalizes each component and rejects collisions`() {
        val longSchema = "s".repeat(67)
        val longTable = "t".repeat(67)
        val input = sizes(3, 5, "$longSchema.$longTable")!!
        val expected = "${"s".repeat(63)}.${"t".repeat(63)}"
        assertEquals(setOf(expected), input.tables.keys)
        assertEquals(OperationalSeverity.LOW, OperationalSizes.grade(old.copy(baselineName = "$longSchema.$longTable"), input).severity)
        val collision = assertFailsWith<IllegalArgumentException> {
            OperationalSizes.validateAndNormalize(TableSizes(tables = mapOf(
                "public.${"x".repeat(63)}a" to TableSize(1, 1), "public.${"x".repeat(63)}b" to TableSize(2, 2))))
        }
        assertTrue(collision.message!!.contains("both normalize"))
    }

    @Test fun `UTF8 identifiers truncate at a code point boundary and preserve physical case`() {
        val name = "É".repeat(40)
        val input = sizes(1, 1, "Public.$name")!!
        val key = input.tables.keys.single()
        assertEquals("Public.${"É".repeat(31)}", key)
        assertTrue(key.substringAfter('.').toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals(OperationalSeverity.LOW, OperationalSizes.grade(old.copy(baselineName = key), input).severity)
        assertEquals(OperationalSeverity.HIGH, OperationalSizes.grade(old.copy(baselineName = key.lowercase()), input).severity)
    }
    @Test fun `unvalidated negative estimates cannot lower severity through the public grading helper`() {
        val relation = OperationalRelation("public.accounts", "table", true, "public.accounts")
        val grade = OperationalSizes.grade(relation, TableSizes(tables = mapOf("public.accounts" to TableSize(-1, -1))))
        assertEquals(OperationalSeverity.HIGH, grade.severity)
        assertTrue(grade.evidence.contains("Invalid negative"))
    }

}
