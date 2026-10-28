package dev.epoch.hibernate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.AccessPathStatus
import dev.epoch.methodreferencefixture.MethodReferenceDao
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RepositoryMethodReferenceTest {
    @Test fun `direct database API method reference is located unresolved access outside ordinary invocation counts`() {
        val root = Path.of(MethodReferenceDao::class.java.protectionDomain.codeSource.location.toURI())
        val result = RepositoryDiscovery.discover(listOf(root.toString()), listOf("dev.epoch.methodreferencefixture"), javaClass.classLoader)
        val references = result.accessPaths.filter { it.mechanism == "DATABASE_METHOD_REFERENCE" }
        assertEquals(1, references.size, result.toString())
        assertTrue(references.single().method.startsWith("direct("))
        assertTrue(references.single().id.contains(":method-reference:"))
        assertEquals(AccessPathStatus.UNRESOLVED, references.single().status)
        assertNotNull(references.single().line)
        val inventory = jacksonObjectMapper().valueToTree<JsonNode>(result).path("apiInventory")
        assertFalse(inventory.path("completeWithinDeclaredScope").asBoolean(), inventory.toString())
        assertEquals(listOf(references.single().id), inventory.path("methodReferenceAccessPathIds").map { it.asText() })
        assertTrue(inventory.path("declarationAccessPathIds").none { it.asText() == references.single().id })
        assertTrue(inventory.path("invocations").none { it.path("id").asText() == references.single().id })
        assertFalse(result.accessPaths.any { it.method.startsWith("unrelated(") }, result.toString())
    }
}
