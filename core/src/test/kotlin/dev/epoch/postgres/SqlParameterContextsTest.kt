package dev.epoch.postgres

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SqlParameterContextsTest {
    @Test fun `column comparisons and explicit casts establish text contexts`() {
        assertEquals(setOf(0, 1, 2), SqlParameterContexts.constrainedSlots(
            "SELECT ?::text FROM customers c WHERE c.email = ? AND c.name LIKE ?"))
        assertEquals(setOf(0, 1), SqlParameterContexts.constrainedSlots(
            "SELECT id FROM customers WHERE state IN (?, ?)"))
        assertEquals(setOf(0, 1), SqlParameterContexts.constrainedSlots(
            "INSERT INTO customers (id,email) VALUES (?,?)"))
        assertEquals(setOf(0, 1), SqlParameterContexts.constrainedSlots(
            "UPDATE customers SET email=? WHERE id=?"))
    }

    @Test fun `unconstrained values and literal comparisons do not establish column types`() {
        assertEquals(emptySet<Int>(), SqlParameterContexts.constrainedSlots("SELECT ?"))
        assertEquals(emptySet<Int>(), SqlParameterContexts.constrainedSlots("SELECT ? = 'value'"))
        assertEquals(emptySet<Int>(), SqlParameterContexts.constrainedSlots("not valid sql ?"))
        assertEquals(setOf(1), SqlParameterContexts.constrainedSlots("SELECT ? FROM customers WHERE email=?"))
    }
}
