package dev.epoch.paginationfixture

import dev.epoch.core.AccessPathStatus
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import jakarta.persistence.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.*

class PaginationSqlIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test fun `actual Hibernate 6 and 749 pagination binders prepare and produce the declared page on PostgreSQL`() {
        val jar = directory.resolve("pagination-fixture.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            listOf(PageRow::class.java, PageDao::class.java).forEach { type ->
                val entry = type.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(entry).apply { time = 0 })
                type.classLoader.getResourceAsStream(entry)!!.use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        PostgreSQLContainer("postgres:16.8").use { database ->
            database.start()
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE page_rows (id bigint PRIMARY KEY, email varchar(255), age integer NOT NULL)")
                    statement.execute("INSERT INTO page_rows SELECT i, 'fixed', 1 FROM generate_series(1,10) i")
                }
                val runtimes = listOf(emptyList(), requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator))
                runtimes.forEach { runtime ->
                    val contract = HibernateExtractor().extract(ExtractionRequest(entityClasses = listOf(PageRow::class.java.name),
                        classpath = runtime + jar.toString(), repositoryPackages = listOf("dev.epoch.paginationfixture")))
                    val paths = contract.accessPaths.filter { it.className == PageDao::class.java.name && it.method.startsWith("page(") }
                    assertTrue(paths.isNotEmpty(), contract.toString())
                    assertTrue(paths.all { it.status == AccessPathStatus.STATIC }, paths.toString())
                    val query = contract.queries.single { it.id in paths.flatMap { path -> path.queryIds } }
                    assertTrue(query.dependenciesComplete, query.toString())
                    assertEquals(4, query.parameterTypes.size)
                    assertTrue(query.parameterTypes.all { it in setOf("VARCHAR", "INTEGER") }, query.parameterTypes.toString())
                    // Resolve this fixture's value roles from the actual rendered SQL, not an assumed 6/7 limit-binder ordering.
                    val binds = query.sql.indices.filter { query.sql[it] == '?' }.map { position ->
                        val before = query.sql.substring(0, position).lowercase().trimEnd()
                        when {
                            Regex("email\\s*=\\s*$").containsMatchIn(before) -> "'fixed'"
                            Regex("age\\s*>=\\s*$").containsMatchIn(before) -> "1"
                            Regex("offset\\s*$").containsMatchIn(before) -> "2"
                            Regex("(?:limit|fetch first|fetch next)\\s*$").containsMatchIn(before) -> "3"
                            else -> error("Unexpected generated fixture parameter at $position: ${query.sql}")
                        }
                    }
                    assertEquals(4, binds.size, query.sql)
                    var slot = 0
                    val postgresSql = Regex("\\?").replace(query.sql) { "\$${++slot}" }
                    connection.createStatement().use { statement ->
                        statement.execute("PREPARE epoch_page (${query.parameterTypes.joinToString { it.lowercase() }}) AS $postgresSql")
                        statement.executeQuery("EXPLAIN EXECUTE epoch_page (${binds.joinToString()})").use { assertTrue(it.next()) }
                        statement.executeQuery("EXECUTE epoch_page (${binds.joinToString()})").use { rows ->
                            val ids = mutableListOf<Long>()
                            while (rows.next()) ids += rows.getLong("id")
                            assertEquals(listOf(3L, 4L, 5L), ids, query.sql)
                        }
                        statement.execute("DEALLOCATE epoch_page")
                    }
                }
            }
        }
    }
}

@Entity @Table(name = "page_rows")
open class PageRow {
    @field:Id open var id: Long = 0
    open var email: String? = null
    open var age: Int = 0
}

class PageDao {
    fun page(manager: EntityManager) {
        manager.createQuery("select r from PageRow r where r.email = :email and r.age >= :age order by r.id")
            .setParameter("email", "fixed").setParameter("age", 1).setFirstResult(2).setMaxResults(3).resultList
    }
}
