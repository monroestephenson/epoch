package dev.epoch

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import dev.epoch.cli.runCli
import dev.epoch.core.ColumnReference
import dev.epoch.core.Coverage
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.testcontainers.DockerClientFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Exercises the real CLI boundary, rather than substituting a thrown exception for PostgreSQL. */
@ResourceLock(Resources.SYSTEM_ERR)
class RuntimeCliPostgresTest {
    @TempDir lateinit var directory: Path

    private fun arguments(sql: String): Array<String> {
        val baseline = directory.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText("CREATE TABLE accounts (id bigint PRIMARY KEY);")
        val pending = directory.resolve("pending").createDirectories()
        pending.resolve("V2__runtime_failure.sql").writeText(sql)
        val contract = DatabaseContract(
            applicationVersion = "runtime-cli-regression",
            queries = listOf(QueryContract("Account.read", Operation.SELECT, "SELECT id FROM accounts",
                tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id")),
                dependenciesComplete = true)),
            coverage = Coverage(complete = true, mechanisms = listOf("Explicit fixed SQL regression contract")),
        )
        val contractPath = directory.resolve("contract.json")
        jacksonObjectMapper().writeValue(contractPath.toFile(), contract)
        return arrayOf("verify", "--contract", contractPath.toString(), "--baseline", baseline.toString(),
            "--migrations", pending.toString(), "--report", directory.resolve("report.json").toString(),
            "--migration-timeout-seconds", "90", "--global-timeout-seconds", "120")
    }

    @Test @Timeout(90)
    fun `malformed pending SQL exits 3 with PostgreSQL syntax evidence and migration stage`() {
        val errors = ByteArrayOutputStream()
        val previous = System.err
        val exit: Int
        try {
            System.setErr(PrintStream(errors, true, Charsets.UTF_8))
            exit = runCli(arguments("ALTER TABLE accounts ADD COLUMN ;"))
        } finally { System.setErr(previous) }
        val diagnostic = errors.toString(Charsets.UTF_8)
        assertEquals(3, exit, diagnostic)
        assertTrue(diagnostic.contains("MIGRATION_EXECUTION_FAILED"), diagnostic)
        assertTrue(diagnostic.contains("V2__runtime_failure.sql"), diagnostic)
        assertTrue(Regex("SQL State\\s*:\\s*42601").containsMatchIn(diagnostic), diagnostic)
    }

    @Test @Timeout(150)
    fun `stopping the owned container during a pending migration exits 3 instead of UNKNOWN`() {
        val docker = DockerClientFactory.instance().client()
        val existing = ownedPostgresIds(docker)
        val marker = "epoch_pending_container_stop_${UUID.randomUUID()}"
        val args = arguments("DO \$\$ BEGIN RAISE LOG '$marker'; PERFORM pg_sleep(120); END \$\$;")
        val errors = ByteArrayOutputStream()
        val previous = System.err
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "epoch-cli-container-stop-test").apply { isDaemon = true }
        }
        var result: Future<Int>? = null
        try {
            System.setErr(PrintStream(errors, true, Charsets.UTF_8))
            val running = executor.submit<Int> { runCli(args) }
            result = running
            val containerId = awaitNewOwnedContainer(docker, existing, running, errors)
            val ready = CountDownLatch(1)
            val logFailure = AtomicReference<Throwable?>()
            val logs = StringBuilder()
            val callback = object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    synchronized(logs) {
                        logs.append(frame.payload.toString(Charsets.UTF_8))
                        if (logs.contains(marker)) ready.countDown()
                    }
                }
                override fun onError(throwable: Throwable) {
                    logFailure.set(throwable)
                    ready.countDown()
                    super.onError(throwable)
                }
            }
            callback.use {
                docker.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .withFollowStream(true).withTailAll().exec(callback)
                assertTrue(ready.await(45, TimeUnit.SECONDS), "Pending migration never reached its stop marker: ${errors.toString(Charsets.UTF_8)}")
                assertNull(logFailure.get(), "Could not observe the pending migration's logs")
                assertTrue(synchronized(logs) { logs.contains(marker) }, "The unique pending migration marker was not observed")
                assertFalse(running.isDone, "Verification ended before this test stopped its container")

                // Never stop a pre-existing or another JVM's container. The unique SQL marker
                // additionally proves this exact container is executing this test's migration.
                assertFalse(containerId in existing)
                val labels = requireNotNull(docker.inspectContainerCmd(containerId).exec().config.labels)
                assertEquals(DockerClientFactory.SESSION_ID,
                    labels[DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL])
                docker.stopContainerCmd(containerId).withTimeout(0).exec()
            }
            val exit = running.get(30, TimeUnit.SECONDS)
            val diagnostic = errors.toString(Charsets.UTF_8)
            assertEquals(3, exit, diagnostic)
            assertTrue(diagnostic.contains("MIGRATION_EXECUTION_FAILED"), diagnostic)
            assertTrue(diagnostic.contains("V2__runtime_failure.sql"), diagnostic)
        } finally {
            result?.takeUnless { it.isDone }?.cancel(true)
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            System.setErr(previous)
        }
    }

    private fun ownedPostgresIds(docker: DockerClient): Set<String> = docker.listContainersCmd()
        .withLabelFilter(mapOf(DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL to DockerClientFactory.SESSION_ID))
        .withAncestorFilter(listOf(PostgresVerifier.POSTGRES_IMAGE))
        .exec().map { it.id }.toSet()

    private fun awaitNewOwnedContainer(docker: DockerClient, existing: Set<String>, running: Future<Int>, errors: ByteArrayOutputStream): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            val candidates = ownedPostgresIds(docker) - existing
            check(candidates.size <= 1) { "Ambiguous newly started containers; this test will not stop any container." }
            candidates.singleOrNull()?.let { return it }
            check(!running.isDone) { "CLI finished before its container appeared: ${errors.toString(Charsets.UTF_8)}" }
            Thread.sleep(100)
        }
        error("The verifier did not start an identifiable owned PostgreSQL container within 45 seconds.")
    }
}
