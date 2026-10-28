package dev.epoch.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CatalogIdentityTest {
    private val mapper = jacksonObjectMapper()
    private val table = TableSchema(
        columns = mapOf("id" to ColumnSchema("bigint", false, ordinal = 1)),
        relationSecurity = RelationSecurity("epoch", false, false, false, false),
        relationOid = 19001L,
    )

    @Test fun `runtime relation identity is absent from JSON and baseline fingerprints`() {
        val first = SchemaSnapshot(tables = mapOf("public.accounts" to table))
        val second = first.copy(tables = mapOf("public.accounts" to table.copy(relationOid = 29002L)))
        val json = mapper.writeValueAsString(first)
        assertEquals(json, mapper.writeValueAsString(second))
        assertFalse("relationOid" in json)
        assertFalse("19001" in json)
        val contract = DatabaseContract(coverage = Coverage(complete = true))
        assertEquals(
            BaselineFingerprints.baseline(contract, first, emptyList(), "fixed-input"),
            BaselineFingerprints.baseline(contract, second, emptyList(), "fixed-input"),
        )
        val restored = mapper.readValue<SchemaSnapshot>(json)
        assertNull(restored.tables.getValue("public.accounts").relationOid)
        assertEquals(first.copy(tables = mapOf("public.accounts" to table.copy(relationOid = null))), restored)
    }

    @Test fun `view capability and security facts round trip without live identity`() {
        val view = table.copy(kind = "view", relationOid = null, view = ViewSchema(
            definition = "SELECT accounts_v2.id FROM accounts_v2;",
            updatableMask = 28, updatableMaskWithTriggers = 28,
            updatableColumns = setOf("id"), updatableColumnsWithTriggers = setOf("id"),
            backingTable = "public.accounts_v2", completeDirectProjection = true,
        ))
        assertEquals(view, mapper.readValue<TableSchema>(mapper.writeValueAsString(view)))
        val oldSnapshot = mapper.readValue<TableSchema>("""{"columns":{},"kind":"table"}""")
        assertNull(oldSnapshot.view)
        assertNull(oldSnapshot.relationSecurity)
        assertNull(oldSnapshot.relationOid)
    }
}
