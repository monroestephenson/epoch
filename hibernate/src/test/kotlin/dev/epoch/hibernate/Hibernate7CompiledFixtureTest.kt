package dev.epoch.hibernate

import dev.epoch.core.Operation
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Hibernate7CompiledFixtureTest {
    @Test fun `Kotlin List associations compiled against Hibernate 7 extract with the application runtime`() {
        val compileClasspath = requireNotNull(System.getProperty("epoch.hibernate7.fixture.compile.classpath"))
            .split(File.pathSeparator)
        assertTrue(compileClasspath.any { Path.of(it).fileName.toString() == "hibernate-core-7.1.4.Final.jar" })
        assertFalse(compileClasspath.any { Path.of(it).fileName.toString().startsWith("hibernate-core-6.") })
        val fixtureClasspath = requireNotNull(System.getProperty("epoch.hibernate7.fixture.classpath"))
            .split(File.pathSeparator).filter { Files.isDirectory(Path.of(it)) }
        assertTrue(fixtureClasspath.isNotEmpty(), "The Hibernate 7 fixture must be compiled before this test")
        val entities = listOf("GenericTeam", "GenericMember", "GenericTask").map { "dev.epoch.hibernate7fixture.$it" }

        for ((property, version) in listOf("epoch.hibernate7.classpath" to "7.1.4.Final", "epoch.hibernate70.classpath" to "7.0.10.Final")) {
            val runtime = requireNotNull(System.getProperty(property)).split(File.pathSeparator)
            val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = entities,
                classpath = runtime + fixtureClasspath, discoverRepositories = false,
                hqlQueries = mapOf("team.members" to "select m from GenericTeam t join t.members m where t.id = :id",
                    "team.tasks" to "select t from GenericTeam g join g.tasks t where g.id = :id")))

            assertTrue(contract.coverage.notes.any { it.contains("Hibernate runtime $version") }, contract.toString())
            assertTrue(contract.entities.map { it.table }.containsAll(listOf("public.hibernate7_team", "public.hibernate7_member", "public.hibernate7_task")))
            for (table in listOf("public.hibernate7_team_members", "public.hibernate7_team_tasks")) {
                assertTrue(contract.queries.any { it.operation == Operation.INSERT && it.targetTable == table && it.dependenciesComplete }, contract.toString())
                assertTrue(contract.queries.any { it.operation == Operation.SELECT && table in it.tables && it.dependenciesComplete }, contract.toString())
            }
            for (query in listOf("team.members", "team.tasks")) {
                assertTrue(contract.queries.single { it.id == query }.dependenciesComplete, contract.toString())
            }
            assertTrue(contract.coverage.complete, contract.unresolvedQueries.toString())
            assertEquals("6.6.13.Final", org.hibernate.Version.getVersionString(), "The bundled runtime must remain isolated")
        }
    }
}
