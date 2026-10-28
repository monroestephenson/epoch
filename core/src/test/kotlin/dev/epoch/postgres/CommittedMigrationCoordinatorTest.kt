package dev.epoch.postgres

import dev.epoch.core.*
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Ordering/attribution assertions use callback values, never a fabricated compatibility verdict. */
class CommittedMigrationCoordinatorTest {
    private fun owner(aborted: CountDownLatch = CountDownLatch(1)): Connection =
        verificationSessionConnection(verificationFixtureSession(), aborted)
    private fun context(version: String, connection: Connection): Context {
        val migration = proxy(org.flywaydb.core.api.MigrationInfo::class.java) { name -> when (name) {
            "getVersion" -> MigrationVersion.fromVersion(version)
            "getScript" -> "V${version}__fixture.sql"
            else -> error(name)
        } }
        return proxy(Context::class.java) { name -> when (name) {
            "getConnection" -> connection
            "getMigrationInfo" -> migration
            else -> error(name)
        } }
    }
    private fun <T> proxy(type: Class<T>, answer: (String) -> Any?): T = type.cast(Proxy.newProxyInstance(
        type.classLoader, arrayOf(type),
    ) { value, method, args -> when (method.name) {
        "hashCode" -> System.identityHashCode(value)
        "equals" -> value === args?.singleOrNull()
        "toString" -> "Fixture${type.simpleName}"
        else -> answer(method.name)
    } })

    @Test fun `previous completion precedes next observation and final flush happens once`() {
        val events = mutableListOf<String>()
        val connection = owner()
        CommittedMigrationCoordinator(60,
            observe = { event, context -> events += "observe:${context.migrationInfo.version}:$event" },
            capture = { _, lease -> events += "capture:${lease.migration.version}"; lease.migration },
            complete = { migration, _ -> events += "complete:${migration.version}" },
        ).use { coordinator ->
            val second = context("2", connection)
            val third = context("3", connection)
            coordinator.handle(Event.BEFORE_EACH_MIGRATE, second)
            coordinator.handle(Event.AFTER_EACH_MIGRATE, second)
            assertTrue(events.none { it.startsWith("complete:") }, "Precommit capture must not publish success")
            coordinator.handle(Event.BEFORE_EACH_MIGRATE, third)
            assertTrue(events.indexOf("complete:2") < events.indexOf("observe:3:${Event.BEFORE_EACH_MIGRATE}"))
            coordinator.handle(Event.AFTER_EACH_MIGRATE, third)
            coordinator.flushCommitted()
            coordinator.flushCommitted()
        }
        assertEquals(listOf("complete:2", "complete:3"), events.filter { it.startsWith("complete:") })
    }

    @Test fun `failed committed worker retains prior migration attribution and prevents the next script`() {
        val observed = mutableListOf<String>()
        val aborted = CountDownLatch(1)
        val connection = owner(aborted)
        val queries = (1..4).map { QueryContract("q$it", Operation.SELECT, "SELECT id FROM accounts",
            tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")), dependenciesComplete = true) }
        val selection = QueryStepSelection(queries, emptyMap(), emptyMap())
        val planner = DeferredQueryPlanning(PostgresQueryPlanner(), {
            throw SQLTransientConnectionException("Committed worker connection lost", "08006")
        }, 4, verificationFixtureSession())
        CommittedMigrationCoordinator(60,
            observe = { event, context -> observed += "${context.migrationInfo.version}:$event" },
            capture = { _, _ -> selection },
            complete = { value, lease -> planner.inspectCommitted(connection, SchemaSnapshot(), value, verificationFixtureSession(), lease); Unit },
        ).use { coordinator ->
            val second = context("2", connection)
            coordinator.handle(Event.BEFORE_EACH_MIGRATE, second)
            coordinator.handle(Event.AFTER_EACH_MIGRATE, second)
            val failure = assertThrows(SQLTransientConnectionException::class.java) {
                coordinator.handle(Event.BEFORE_EACH_MIGRATE, context("3", connection))
            }
            assertEquals("08006", failure.sqlState)
            assertEquals("2", coordinator.activeMigration?.version)
            assertTrue(observed.none { it.startsWith("3:") })
            assertTrue(aborted.await(2, TimeUnit.SECONDS), "Infrastructure failure aborts the active migration connection")
        }
    }
}
