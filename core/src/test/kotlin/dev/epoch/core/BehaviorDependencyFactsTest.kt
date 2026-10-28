package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Apply after the additive fact exists; old DTOs default to no dependency proof. */
class BehaviorDependencyFactsTest {
    private fun facts(sql: String, complete: Boolean = true) = BehaviorQuerySyntax().inspect(QueryContract(
        "read", Operation.SELECT, sql, tables = listOf("public.accounts", "public.badges"), dependenciesComplete = complete))

    @Test fun `read only lexical CTE nesting is bounded independently of semantic support`() {
        for (sql in listOf(
            "WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen",
            "WITH chosen AS (SELECT id FROM public.accounts), next AS (SELECT id FROM chosen) SELECT id FROM next",
            "WITH accounts AS (SELECT id FROM accounts) SELECT id FROM accounts",
            "WITH x AS (SELECT id FROM public.accounts) SELECT x.id FROM x WHERE EXISTS (WITH x AS (SELECT id FROM public.badges) SELECT id FROM x)",
            "SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT b.id FROM public.badges b WHERE b.id=a.id)",
        )) {
            val facts = facts(sql)
            assertFalse(facts.complete, sql)
            assertTrue(facts.dependencyCoverageComplete, "$sql: ${facts.unresolvedReasons}")
        }
    }

    @Test fun `missing inventory and opaque nested forms cannot acquire bounded dependency proof`() {
        assertFalse(QueryBehaviorFacts().dependencyCoverageComplete)
        assertFalse(facts("WITH chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen", false).dependencyCoverageComplete)
        for (sql in listOf(
            "WITH chosen AS (SELECT id FROM missing) SELECT id FROM chosen",
            "WITH RECURSIVE chosen AS (SELECT id FROM public.accounts) SELECT id FROM chosen",
            "WITH changed AS (UPDATE accounts SET id=id RETURNING id) SELECT id FROM changed",
            "SELECT id FROM accounts WHERE EXISTS (SELECT hidden_lookup(id) FROM badges)",
            "SELECT id FROM accounts WHERE EXISTS (SELECT id FROM badges WHERE id OPERATOR(public.===) 1)",
            "SELECT id FROM accounts WHERE EXISTS (SELECT id::regclass FROM badges)",
            "SELECT id FROM accounts WHERE EXISTS (SELECT * FROM hidden_table_function())",
            "WITH chosen AS (SELECT id FROM accounts) SELECT id FROM chosen UNION SELECT id FROM badges",
        )) assertFalse(facts(sql).dependencyCoverageComplete, sql)
    }
}
