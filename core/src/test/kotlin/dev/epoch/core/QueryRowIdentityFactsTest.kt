package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Apply with the additive row-identity fact. AST qualification alone is not a scalar proof. */
class QueryRowIdentityFactsTest {
    private fun facts(sql: String) = BehaviorQuerySyntax().inspect(QueryContract("q", Operation.SELECT, sql,
        tables = listOf("public.accounts"), columns = listOf(ColumnReference("public.accounts", "id"),
            ColumnReference("public.accounts", "accounts")), dependenciesComplete = true))

    @Test fun `scalar id remains positive but any relation-name column collision stays unproven`() {
        assertTrue(facts("WITH selected AS (SELECT a.id FROM public.accounts a) SELECT id FROM selected").rowIdentityIndependent)
        for (sql in listOf(
            "WITH selected AS (SELECT public.accounts FROM public.accounts) SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT 1 FROM selected)",
            "WITH selected AS (SELECT a FROM public.accounts a) SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT 1 FROM selected)",
            "WITH selected AS (SELECT a.accounts FROM public.accounts a) SELECT a.id FROM public.accounts a WHERE EXISTS (SELECT 1 FROM selected)",
        )) assertFalse(facts(sql).rowIdentityIndependent, sql)
    }
}
