package dev.epoch

import dev.epoch.postgres.PostgresVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

class WholeRowIdentityWitnessTest {
    @Test @Timeout(120)
    fun `actual whole-row and same-named scalar reads have different result contracts`() {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-test-only").use { database ->
                database.start()
                DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE TABLE accounts(id bigint, accounts bigint); INSERT INTO accounts VALUES (1,2);")
                        statement.executeQuery("SELECT a FROM public.accounts a").use { rows ->
                            assertTrue(rows.next())
                            assertEquals("accounts", rows.metaData.getColumnTypeName(1))
                            assertEquals("(1,2)", rows.getString(1))
                        }
                        statement.executeQuery("SELECT a.accounts FROM public.accounts a").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(2L, rows.getLong(1))
                            assertEquals("int8", rows.metaData.getColumnTypeName(1))
                        }
                        // PostgreSQL's two-part ColumnRef is alias.column here. A parser's
                        // Column(table=public,name=accounts) must not imply executable scalar SQL.
                        val failure = assertThrows(SQLException::class.java) {
                            statement.executeQuery("SELECT public.accounts FROM public.accounts").use { }
                        }
                        assertEquals("42P01", failure.sqlState)
                    }
                }
            }
    }
}
