package dev.epoch.hibernate

import dev.epoch.core.Operation
import dev.epoch.core.QueryContract
import dev.epoch.core.UnresolvedQuery
import org.hibernate.metamodel.mapping.JdbcMappingContainer
import org.hibernate.sql.ast.SqlAstWalker
import org.hibernate.sql.ast.spi.SqlSelection
import org.hibernate.sql.ast.tree.expression.Expression
import org.hibernate.sql.ast.tree.expression.SqlSelectionExpression
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlin.test.*

class QueryExtractionBoundaryTest {
    private fun selection(expression: () -> Expression): SqlSelection = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SqlSelection::class.java),
    ) { _, method, _ ->
        if (method.name == "getExpression") expression() else error("Unexpected selection accessor ${method.name}")
    } as SqlSelection

    @Test fun `selection references detect direct and indirect cycles without recursion`() {
        lateinit var self: SqlSelectionExpression
        self = SqlSelectionExpression(selection { self })
        val direct = SqlAstDependencies()
        self.accept(direct)
        assertFalse(direct.complete)
        assertEquals(setOf("Hibernate SQL AST contains a cyclic selection reference"), direct.limitations)

        lateinit var first: SqlSelectionExpression
        lateinit var second: SqlSelectionExpression
        first = SqlSelectionExpression(selection { second })
        second = SqlSelectionExpression(selection { first })
        val indirect = SqlAstDependencies()
        first.accept(indirect)
        assertFalse(indirect.complete)
        assertEquals(direct.limitations, indirect.limitations)
    }

    @Test fun `reusing a completed selection reference is not a cycle`() {
        var visits = 0
        val leaf = object : Expression {
            override fun getExpressionType(): JdbcMappingContainer? = null
            override fun accept(walker: SqlAstWalker) { visits++ }
        }
        val shared = SqlSelectionExpression(selection { leaf })
        val walker = SqlAstDependencies()
        shared.accept(walker)
        shared.accept(walker)
        assertEquals(2, visits)
        assertTrue(walker.complete)
        assertTrue(walker.limitations.isEmpty())
    }

    @Test fun `failed metadata cannot publish a half-extracted query and healthy siblings survive`() {
        val queries = mutableListOf<QueryContract>()
        val unresolved = mutableListOf<UnresolvedQuery>()
        QueryExtraction.record("Repo#bad:0", "metadata failed", queries, unresolved) {
            val staged = QueryContract("Repo#bad:0", Operation.SELECT, "select 1")
            assertEquals("Repo#bad:0", staged.id)
            throw InvocationTargetException(StackOverflowError("fixture query recursion"))
        }
        QueryExtraction.record("Repo#good:0", "metadata failed", queries, unresolved) {
            ExtractedSql(QueryContract("Repo#good:0", Operation.SELECT, "select 2"))
        }
        assertEquals(listOf("Repo#good:0"), queries.map { it.id })
        assertEquals("Repo#bad:0", unresolved.single().id)
        assertContains(unresolved.single().reason, "StackOverflowError: fixture query recursion")
    }

    @Test fun `fatal VM errors thread death and cancellation never become unresolved query debt`() {
        val errors = listOf<Throwable>(OutOfMemoryError("fixture"), InternalError("fixture"), ThreadDeath(),
            CancellationException("fixture cancellation"))
        for (error in errors) {
            for (envelope in listOf(InvocationTargetException(error),
                IllegalStateException("ordinary primary failure").apply { addSuppressed(error) })) {
            val unresolved = mutableListOf<UnresolvedQuery>()
            val thrown = assertFails {
                QueryExtraction.attempt("bad", "translation failed", unresolved) { throw envelope }
            }
            assertSame(error, thrown)
            assertTrue(unresolved.isEmpty())
            }
        }
    }

    @Test fun `wrapped interruption restores interrupt state and propagates`() {
        assertFalse(Thread.currentThread().isInterrupted)
        for (suppressed in listOf(false, true)) {
            val interrupted = InterruptedException("fixture interrupt")
            val unresolved = mutableListOf<UnresolvedQuery>()
            val envelope = if (suppressed) IllegalStateException("ordinary primary failure").apply { addSuppressed(interrupted) }
                else InvocationTargetException(interrupted)
            try {
                val thrown = assertFails {
                    QueryExtraction.attempt("bad", "translation failed", unresolved) { throw envelope }
                }
                assertSame(interrupted, thrown)
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue(unresolved.isEmpty())
            } finally { Thread.interrupted() }
        }
    }

    @Test fun `cause and suppressed cycles cannot trap the failure policy`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        first.addSuppressed(second)
        second.addSuppressed(first)
        val unresolved = mutableListOf<UnresolvedQuery>()
        assertNull(QueryExtraction.attempt<String>("cyclic-error", "translation failed", unresolved) { throw first })
        assertEquals(1, unresolved.size)
        val cancellation = CancellationException("cancel within cycle")
        second.addSuppressed(cancellation)
        assertSame(cancellation, assertFails { QueryExtraction.rethrowNonRecoverable(first) })
    }

    @Test fun `parser failures retain bounded deterministic causes instead of silently losing them`() {
        val first = SqlDependencies.inspect("select from where", sourceId = "Repo#brokenNative:0")
        val second = SqlDependencies.inspect("select from where", sourceId = "Repo#brokenNative:0")
        assertFalse(first.complete)
        assertNotNull(first.failureReason)
        assertEquals(first.failureReason, second.failureReason)
        assertTrue(first.failureReason!!.length <= 4200)
        assertContains(first.failureReason!!, "SQL dependency parsing failed")
        assertContains(first.failureReason!!, "Exception")
        val summarized = QueryExtraction.describe(IllegalArgumentException(
            "loader@1a2b3c /tmp/random-build/app.jar " + "x".repeat(2000)))
        assertContains(summarized, "loader@<identity>")
        assertContains(summarized, "<artifact>")
        assertFalse(summarized.contains("random-build"))
        assertTrue(summarized.length < 600)
    }
}
