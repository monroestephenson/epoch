package dev.epoch.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.epoch.core.DatabaseContract
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApiInventoryReportTest {
    @Test fun `raw API calls query sites declarations and observations have separate denominators`() {
        val contract = jacksonObjectMapper().readValue<DatabaseContract>("""
          {"accessPaths":[
            {"id":"Repo#run:0","className":"Repo","method":"run()V","status":"STATIC","queryIds":["q"]},
            {"id":"Repo#run:1","className":"Repo","method":"run()V","status":"STATIC","queryIds":["q"]},
            {"id":"Repo#decl:annotation","className":"Repo","method":"decl()V","status":"DECLARED","queryIds":["named"]},
            {"id":"runtime-frame:Repo#observed","className":"Repo","method":"observed","status":"OBSERVED","queryIds":["seen"]}
          ],"apiInventory":{"inspectedMethodInvocations":5,"scannedClasses":["Repo"],
            "completeWithinDeclaredScope":true,"declarationAccessPathIds":["Repo#decl:annotation"],
            "invocations":[
              {"id":"Repo#run:0","className":"Repo","method":"run()V","invocationIndex":0,"owner":"jakarta.persistence.EntityManager","name":"createQuery","descriptor":"(Ljava/lang/String;)Ljakarta/persistence/Query;","family":"JPA","role":"FACTORY","queryCapable":true,"accessPathIds":["Repo#run:0"]},
              {"id":"Repo#run:1","className":"Repo","method":"run()V","invocationIndex":1,"owner":"jakarta.persistence.Query","name":"getResultList","descriptor":"()Ljava/util/List;","family":"JPA","role":"EXECUTION","queryCapable":true,"accessPathIds":["Repo#run:1"]},
              {"id":"Repo#run:2","className":"Repo","method":"run()V","invocationIndex":2,"owner":"jakarta.persistence.EntityManager","name":"isOpen","descriptor":"()Z","family":"JPA","role":"LIFECYCLE_OR_METADATA","queryCapable":false}
            ]}}
        """)
        val rendered = renderCoverage(contract)
        assertTrue(rendered.contains("5 method-call instructions inspected; 3 database API invocations"), rendered)
        assertTrue(rendered.contains("2/2"), rendered)
        assertTrue(rendered.contains("declarations: 1"), rendered)
        assertTrue(rendered.contains("runtime observations are separate"), rendered)
        assertFalse(rendered.contains("whole-application coverage: 100"), rendered)
    }

    @Test fun `missing class inventory cannot be presented as an empty complete denominator`() {
        val contract = jacksonObjectMapper().readValue<DatabaseContract>("""
          {"apiInventory":{"completeWithinDeclaredScope":false,"failedSources":["unreadable.jar!/Repo.class"],
             "ownerGaps":[{"owner":"external.Base","callSiteIds":["Repo#run()V:4"],"reason":"Parent header unavailable"}]}}
        """)
        val rendered = renderCoverage(contract, details = true)
        assertTrue(rendered.contains("full denominator is unknown"), rendered)
        assertTrue(rendered.contains("Unreadable sources=1"), rendered)
        assertTrue(rendered.contains("Repo#run()V:4"), rendered)
        val legacy = renderCoverage(DatabaseContract())
        assertTrue(legacy.contains("No repository method inventory"), legacy)
        assertFalse(legacy.contains("complete within declared known-API scope"), legacy)
    }
}
