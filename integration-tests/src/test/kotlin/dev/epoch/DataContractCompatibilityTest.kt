package dev.epoch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.*
import dev.epoch.datadifffixture.*
import dev.epoch.hibernate.EntityOverride
import dev.epoch.hibernate.ExtractionRequest
import dev.epoch.hibernate.HibernateExtractor
import dev.epoch.postgres.PostgresVerifier
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** New writers can break old decoders even when both releases' SQL still prepares. */
class DataContractCompatibilityTest {
    @TempDir lateinit var directory: Path
    private val json = jacksonObjectMapper()
    private var scenario = 0

    private fun extract(
        classes: List<Class<*>>,
        overrides: Map<String, EntityOverride> = emptyMap(),
        hql: Map<String, String> = emptyMap(),
    ) = HibernateExtractor().extract(ExtractionRequest(
        entityClasses = classes.map { it.name }, applicationVersion = classes.joinToString { it.simpleName },
        discoverRepositories = false, entityOverrides = overrides, hqlQueries = hql,
    ))

    private fun verify(old: DatabaseContract, new: DatabaseContract, baselineSql: String): BidirectionalReport {
        val root = directory.resolve("scenario-${scenario++}")
        val baseline = root.resolve("baseline").createDirectories()
        baseline.resolve("V1__baseline.sql").writeText(baselineSql)
        val pending = root.resolve("pending").createDirectories()
        return PostgresVerifier().verifyBidirectional(listOf(old), listOf(new), baseline, pending)
    }

    /** JSON keeps the initial test compiling before the new comparison direction is implemented. */
    private fun dataReport(report: BidirectionalReport): JsonNode {
        val data = json.valueToTree<JsonNode>(report).path("dataCompatibility")
        assertTrue(data.isObject, "The bidirectional report must include new-writes-old-reads evidence: $report")
        assertTrue(data.path("complete").asBoolean(), data.toPrettyString())
        return data
    }

    private fun findings(report: JsonNode): List<JsonNode> = report.path("migrations").flatMap { it.path("findings").toList() }

    private fun assertFinding(data: JsonNode, rule: String, objectName: String, oldClass: Class<*>, newClass: Class<*>) {
        val candidates = findings(data).filter { it.path("ruleId").asText() == rule && it.path("objectName").asText() == objectName }
        assertTrue(candidates.isNotEmpty(), data.toPrettyString())
        val ids = candidates.flatMap { it.path("affectedContractIds").map(JsonNode::asText) }
        assertTrue(ids.any { oldClass.name in it }, "Missing old reader ${oldClass.name}: ${data.toPrettyString()}")
        assertTrue(ids.any { newClass.name in it }, "Missing new writer ${newClass.name}: ${data.toPrettyString()}")
    }

    @Test fun `data-only enum nullability and subtype failures are reported even when both schema directions are SAFE`() {
        val old = extract(OLD_CLASSES, rootReads(DataOldCreature::class.java))
        val new = extract(NEW_CLASSES, rootReads(DataNewCreature::class.java))
        val report = verify(old, new, BREAKING_SCHEMA)
        assertEquals(Verdict.SAFE, report.oldOnNew.verdict, report.toString())
        assertEquals(Verdict.SAFE, report.newOnOld.verdict, report.toString())
        val data = dataReport(report)
        assertEquals("BREAKING", data.path("verdict").asText(), data.toPrettyString())
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        assertFinding(data, "DATA_ENUM_VALUE_UNREADABLE", "public.data_name.state", DataOldName::class.java, DataNewName::class.java)
        assertFinding(data, "DATA_ENUM_VALUE_UNREADABLE", "public.data_ordinal_add.state", DataOldOrdinal::class.java, DataNewOrdinal::class.java)
        assertFinding(data, "DATA_ENUM_VALUE_REINTERPRETED", "public.data_ordinal_reorder.state", DataOldOrder::class.java, DataNewOrder::class.java)
        assertFinding(data, "DATA_ENUM_VALUE_UNREADABLE", "public.data_mode.state", DataOldMode::class.java, DataNewMode::class.java)
        assertFinding(data, "DATA_NULLABILITY_RELAXED", "public.data_nullable.label", DataOldNonNull::class.java, DataNewNullable::class.java)
        assertFinding(data, "DATA_DISCRIMINATOR_UNREADABLE", "public.data_creatures.dtype", DataOldCreature::class.java, DataNewCat::class.java)
        assertTrue(findings(data).none { it.path("migration").path("filename").asText().endsWith(".sql") }, data.toPrettyString())
    }

    @Test fun `renamed enum classes and reordered NAME declarations preserve the same stored domain`() {
        val report = verify(extract(listOf(DataOldName::class.java)), extract(listOf(DataStableName::class.java)), NAME_SCHEMA)
        val data = dataReport(report)
        assertEquals("SAFE", data.path("verdict").asText(), data.toPrettyString())
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `a final CHECK that rejects the new enum label cannot establish an old decoder failure`() {
        val report = verify(extract(listOf(DataOldName::class.java)), extract(listOf(DataNewName::class.java)), """
            CREATE TABLE data_name (id bigint PRIMARY KEY, state varchar(255) CHECK (state IN ('OPEN', 'CLOSED')));
        """.trimIndent())
        val data = dataReport(report)
        assertEquals("SAFE", data.path("verdict").asText(), data.toPrettyString())
        assertTrue(findings(data).none { it.path("verdict").asText() == "BREAKING" }, data.toPrettyString())
    }

    @Test fun `final NOT NULL and CHECK constraints prevent a claimed newly readable NULL`() {
        val old = extract(listOf(DataOldNonNull::class.java))
        val new = extract(listOf(DataNewNullable::class.java))
        for (constraint in listOf("NOT NULL", "CHECK (label IS NOT NULL)")) {
            val report = verify(old, new, "CREATE TABLE data_nullable (id bigint PRIMARY KEY, label varchar(255) $constraint);")
            val data = dataReport(report)
            assertEquals("SAFE", data.path("verdict").asText(), "$constraint: ${data.toPrettyString()}")
            assertTrue(findings(data).none { it.path("ruleId").asText() == "DATA_NULLABILITY_RELAXED" }, data.toPrettyString())
        }
    }

    @Test fun `a read-only new mapping cannot establish a new enum writer`() {
        val old = extract(listOf(DataOldName::class.java))
        val new = extract(listOf(DataNewName::class.java), mapOf(DataNewName::class.java.name to EntityOverride(operations = setOf(Operation.SELECT))))
        assertTrue(new.queries.none { it.operation in setOf(Operation.INSERT, Operation.UPDATE) }, new.toString())
        val data = dataReport(verify(old, new, NAME_SCHEMA))
        assertEquals("SAFE", data.path("verdict").asText(), data.toPrettyString())
    }

    @Test fun `an old enum predicate is not mistaken for a mapped result decoder`() {
        val old = extract(listOf(DataOldName::class.java),
            mapOf(DataOldName::class.java.name to EntityOverride(operations = setOf(Operation.INSERT))),
            mapOf("predicate-only" to "select e.id from DataOldName e where e.state = :state"))
        assertTrue(old.queries.any { it.sql.contains("state") && it.operation == Operation.SELECT }, old.toString())
        val data = dataReport(verify(old, extract(listOf(DataNewName::class.java)), NAME_SCHEMA))
        assertNotEquals("BREAKING", data.path("verdict").asText(), data.toPrettyString())
        assertTrue(findings(data).none { it.path("ruleId").asText() == "DATA_ENUM_VALUE_UNREADABLE" }, data.toPrettyString())
    }

    @Test fun `converter artifacts and unsupported logical type changes remain UNKNOWN`() {
        val old = extract(listOf(DataOldConverted::class.java, DataOldString::class.java))
        val new = extract(listOf(DataNewConverted::class.java, DataNewChar::class.java))
        val oldHash = old.entities.single { it.table == "public.data_converter" }.columns.single { it.name == "label" }.converterBytecodeSha256
        val newHash = new.entities.single { it.table == "public.data_converter" }.columns.single { it.name == "label" }.converterBytecodeSha256
        assertNotNull(oldHash)
        assertNotEquals(oldHash, newHash)
        val data = dataReport(verify(old, new, UNKNOWN_SCHEMA))
        assertEquals("UNKNOWN", data.path("verdict").asText(), data.toPrettyString())
        assertTrue(findings(data).none { it.path("verdict").asText() == "BREAKING" }, data.toPrettyString())
        assertTrue(findings(data).any { it.path("ruleId").asText() == "DATA_CONVERTER_SEMANTICS_UNPROVEN" }, data.toPrettyString())
        assertTrue(findings(data).any { it.path("ruleId").asText() == "DATA_TYPE_MAPPING_UNPROVEN" }, data.toPrettyString())
    }

    @Test fun `a forced discriminator filter cannot be claimed to decode the new subtype`() {
        val old = extract(listOf(DataFilteredCreature::class.java, DataFilteredDog::class.java), rootReads(DataFilteredCreature::class.java))
        val new = extract(listOf(DataNewCreature::class.java, DataNewDog::class.java, DataNewCat::class.java), rootReads(DataNewCreature::class.java))
        val data = dataReport(verify(old, new, CREATURE_SCHEMA))
        assertNotEquals("BREAKING", data.path("verdict").asText(), data.toPrettyString())
        assertTrue(findings(data).none { it.path("ruleId").asText() == "DATA_DISCRIMINATOR_UNREADABLE" }, data.toPrettyString())
    }

    @Test fun `real new Hibernate writes demonstrate old decoder failures and the declared nullability violation`() {
        withDatabase { database ->
            DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
                connection.createStatement().use { it.execute(BREAKING_SCHEMA + UNKNOWN_SCHEMA) }
                withFactory(database, OLD_CLASSES + listOf(DataOldConverted::class.java, DataOldString::class.java)) { old ->
                    withFactory(database, NEW_CLASSES + listOf(DataNewConverted::class.java, DataNewChar::class.java)) { new ->
                        persist(new, listOf(DataNewName(), DataNewOrdinal(), DataNewOrder(), DataNewMode(), DataNewNullable(),
                            DataNewCat().apply { label = "cat" }, DataNewConverted(), DataNewChar()))

                        val nameFailure = assertThrows(RuntimeException::class.java) { read(old, DataOldName::class.java) }
                        assertTrue(causes(nameFailure).contains("ARCHIVED"), causes(nameFailure))
                        assertThrows(RuntimeException::class.java) { read(old, DataOldOrdinal::class.java) }
                        assertEquals(DataOldState.OPEN, read(old, DataOldOrder::class.java)!!.state)
                        assertNotEquals(DataReorderedState.CLOSED.name, read(old, DataOldOrder::class.java)!!.state.name)
                        assertThrows(RuntimeException::class.java) { read(old, DataOldMode::class.java) }
                        val nullable = read(old, DataOldNonNull::class.java)!!
                        val field = DataOldNonNull::class.java.getDeclaredField("label").apply { isAccessible = true }
                        assertNull(field.get(nullable), "Field hydration can bypass the non-null Kotlin setter; the declared read domain is still violated.")
                        val subtypeFailure = assertThrows(RuntimeException::class.java) { read(old, DataOldCreature::class.java) }
                        assertTrue(causes(subtypeFailure).contains("CAT"), causes(subtypeFailure))
                        assertEquals("sample", read(old, DataOldConverted::class.java)!!.label)
                        assertEquals("X", read(old, DataOldString::class.java)!!.label)

                        withFactory(database, listOf(DataFilteredCreature::class.java, DataFilteredDog::class.java)) { filtered ->
                            assertNull(read(filtered, DataFilteredCreature::class.java))
                        }

                        // These values cannot reach an old reader once the final database rejects the new write.
                        connection.createStatement().use { it.execute("""
                            DELETE FROM data_name;
                            ALTER TABLE data_name ADD CHECK (state IN ('OPEN', 'CLOSED'));
                            DELETE FROM data_nullable;
                            ALTER TABLE data_nullable ALTER COLUMN label SET NOT NULL;
                        """.trimIndent()) }
                        assertEquals("23514", sqlState(assertThrows(RuntimeException::class.java) { persist(new, listOf(DataNewName())) }))
                        assertEquals("23502", sqlState(assertThrows(RuntimeException::class.java) { persist(new, listOf(DataNewNullable())) }))
                        connection.createStatement().use { it.execute("""
                            ALTER TABLE data_nullable ALTER COLUMN label DROP NOT NULL;
                            ALTER TABLE data_nullable ADD CHECK (label IS NOT NULL);
                        """.trimIndent()) }
                        assertEquals("23514", sqlState(assertThrows(RuntimeException::class.java) { persist(new, listOf(DataNewNullable())) }))
                    }
                }
            }
        }
    }

    private fun <T : Any> read(factory: SessionFactory, type: Class<T>): T? = factory.openSession().use { it.find(type, 1L) }

    private fun persist(factory: SessionFactory, entities: List<Any>) {
        factory.openSession().use { session ->
            val transaction = session.beginTransaction()
            try {
                entities.forEach(session::persist)
                session.flush()
                transaction.commit()
            } catch (failure: Throwable) {
                if (transaction.isActive) transaction.rollback()
                throw failure
            }
        }
    }

    private fun causes(failure: Throwable) = generateSequence(failure) { it.cause }.joinToString("; ") { it.message.orEmpty() }
    private fun sqlState(failure: Throwable): String? = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().firstOrNull()?.sqlState

    private fun withFactory(database: PostgreSQLContainer, classes: List<Class<*>>, block: (SessionFactory) -> Unit) {
        val registry = StandardServiceRegistryBuilder().applySettings(mapOf(
            "hibernate.connection.url" to database.jdbcUrl,
            "hibernate.connection.username" to database.username,
            "hibernate.connection.password" to database.password,
            "hibernate.hbm2ddl.auto" to "none",
            "jakarta.persistence.validation.mode" to "none",
        )).build()
        try {
            val sources = MetadataSources(registry)
            classes.forEach(sources::addAnnotatedClass)
            sources.buildMetadata().buildSessionFactory().use(block)
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    private fun withDatabase(block: (PostgreSQLContainer) -> Unit) {
        PostgreSQLContainer(DockerImageName.parse(PostgresVerifier.POSTGRES_IMAGE))
            .withDatabaseName("epoch").withUsername("epoch").withPassword("epoch-isolated-test-only").use { database ->
                database.start()
                block(database)
            }
    }

    companion object {
        private fun rootReads(type: Class<*>) = mapOf(type.name to EntityOverride(operations = setOf(Operation.SELECT)))
        private val OLD_CLASSES = listOf(DataOldName::class.java, DataOldOrdinal::class.java, DataOldOrder::class.java,
            DataOldMode::class.java, DataOldNonNull::class.java, DataOldCreature::class.java, DataOldDog::class.java)
        private val NEW_CLASSES = listOf(DataNewName::class.java, DataNewOrdinal::class.java, DataNewOrder::class.java,
            DataNewMode::class.java, DataNewNullable::class.java, DataNewCreature::class.java, DataNewDog::class.java, DataNewCat::class.java)
        private const val NAME_SCHEMA = "CREATE TABLE data_name (id bigint PRIMARY KEY, state varchar(255));"
        private const val CREATURE_SCHEMA = "CREATE TABLE data_creatures (id bigint PRIMARY KEY, dtype varchar(31) NOT NULL, label varchar(255));"
        private val BREAKING_SCHEMA = """
            $NAME_SCHEMA
            CREATE TABLE data_ordinal_add (id bigint PRIMARY KEY, state smallint);
            CREATE TABLE data_ordinal_reorder (id bigint PRIMARY KEY, state smallint);
            CREATE TABLE data_mode (id bigint PRIMARY KEY, state varchar(255));
            CREATE TABLE data_nullable (id bigint PRIMARY KEY, label varchar(255));
            $CREATURE_SCHEMA
        """.trimIndent()
        private val UNKNOWN_SCHEMA = """
            CREATE TABLE data_converter (id bigint PRIMARY KEY, label varchar(255));
            CREATE TABLE data_type_change (id bigint PRIMARY KEY, label varchar(255));
        """.trimIndent()
    }
}
