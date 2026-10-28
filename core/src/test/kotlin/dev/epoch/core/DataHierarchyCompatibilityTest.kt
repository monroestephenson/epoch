package dev.epoch.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataHierarchyCompatibilityTest {
    private val relation = "public.creatures"
    private val discriminator = ColumnReference(relation, "dtype")
    private val idReference = ColumnReference(relation, "id")
    private val id = ColumnContract("id", "BIGINT", nullable = false, updatable = false,
        logicalJavaType = "long", declaredNonNull = true, hibernateNullable = false, readDomainComplete = true)
    private val rootHierarchy = HierarchyContract("OldCreature", "SINGLE_TABLE", concrete = false,
        discriminator = discriminator, discriminatorType = "VARCHAR", acceptedValues = listOf("DOG"), complete = true)
    private val root = EntityContract("OldCreature", relation, listOf(id), operations = setOf(Operation.SELECT), hierarchy = rootHierarchy)
    private val select = QueryContract("OldCreature.read", Operation.SELECT, "select id,dtype from creatures where id=?",
        tables = listOf(relation), columns = listOf(idReference, discriminator), parameterTypes = listOf("BIGINT"),
        source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true, mappedEntityIds = listOf(root.id),
        mappedReads = listOf(MappedColumnRead(root.id, idReference)), mappedReadCoverageComplete = true,
        polymorphicReads = listOf(PolymorphicRead(root.id, unrestricted = true)))
    private val producer = EntityContract("NewCat", relation, listOf(id), operations = setOf(Operation.INSERT),
        hierarchy = HierarchyContract("NewCreature", "SINGLE_TABLE", concrete = true, discriminator = discriminator,
            discriminatorType = "VARCHAR", entityValue = "CAT", acceptedValues = listOf("DOG", "CAT"), complete = true))
    private val insert = QueryContract("NewCat.insert", Operation.INSERT, "insert into creatures (id,dtype) values (?,'CAT')",
        tables = listOf(relation), columns = listOf(idReference, discriminator), writeColumns = listOf(idReference, discriminator),
        parameterTypes = listOf("BIGINT"), source = QuerySource.HIBERNATE_MAPPING, dependenciesComplete = true,
        targetTable = relation, mappedEntityIds = listOf(producer.id),
        discriminatorWrites = listOf(DiscriminatorWrite(producer.id, discriminator, "CAT")))
    private val table = TableSchema(columns = mapOf(
        "id" to ColumnSchema("bigint", false, typeAcceptsImplicitNull = true),
        "dtype" to ColumnSchema("character varying(20)", false, collation = "pg_catalog.default", typeAcceptsImplicitNull = true)),
        relationSecurity = RelationSecurity("epoch", false, false, false, false))
    private val schema = SchemaSnapshot(tables = mapOf(relation to table), expressionSearchPath = listOf("pg_catalog", "public"))

    private fun assess(oldRoot: EntityContract = root, read: QueryContract = select,
                       newProducer: EntityContract = producer, write: QueryContract = insert,
                       finalSchema: SchemaSnapshot = schema): CompatibilityReport {
        val old = DatabaseContract(applicationVersion = "old", entities = listOf(oldRoot), queries = listOf(read), coverage = Coverage(complete = true))
        val new = DatabaseContract(applicationVersion = "new", entities = listOf(newProducer), queries = listOf(write), coverage = Coverage(complete = true))
        return DataContractCompatibility().assess(old, new, finalSchema, setOf(read.id), setOf(write.id))
    }
    private fun findings(report: CompatibilityReport) = report.migrations.flatMap { it.findings }
    private fun noBreaking(report: CompatibilityReport) = assertFalse(findings(report).any { it.verdict == Verdict.BREAKING }, report.toString())

    @Test fun `new concrete discriminator has exact old root and new INSERT evidence`() {
        val report = assess()
        assertEquals(Verdict.BREAKING, report.verdict, report.toString())
        val finding = findings(report).single { it.ruleId == "DATA_DISCRIMINATOR_UNREADABLE" }
        assertEquals("public.creatures.dtype", finding.objectName)
        assertTrue(finding.affectedContractIds.containsAll(listOf("old/OldCreature.read", "old/OldCreature", "new/NewCat.insert", "new/NewCat")))
        assertTrue(finding.evidence.contains("CAT") && finding.evidence.contains("DOG"))
    }

    @Test fun `forced root and incomplete finite decoder do not establish encounter`() {
        val forced = assess(read = select.copy(polymorphicReads = listOf(PolymorphicRead(root.id, unrestricted = false))))
        noBreaking(forced)
        assertEquals(Verdict.UNKNOWN, forced.verdict)
        val unsupported = assess(oldRoot = root.copy(hierarchy = rootHierarchy.copy(complete = false)))
        noBreaking(unsupported)
        assertEquals(Verdict.UNKNOWN, unsupported.verdict)
    }

    @Test fun `abstract and read-only producers cannot establish a new subtype write`() {
        val abstract = assess(newProducer = producer.copy(hierarchy = producer.hierarchy!!.copy(concrete = false)))
        noBreaking(abstract)
        assertEquals(Verdict.UNKNOWN, abstract.verdict)
        val readOnly = assess(newProducer = producer.copy(operations = setOf(Operation.SELECT)))
        noBreaking(readOnly)
        assertEquals(Verdict.UNKNOWN, readOnly.verdict)
    }

    @Test fun `final CHECK blocks an unsupported discriminator from old readers`() {
        val constraint = ConstraintSchema("c", "CHECK (dtype IN ('DOG'))", columns = listOf("dtype"),
            checkExpression = "dtype IN ('DOG')")
        val report = assess(finalSchema = schema.copy(tables = mapOf(relation to table.copy(constraints = mapOf("known_subtype" to constraint)))))
        noBreaking(report)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
        assertTrue(findings(report).any { it.objectName == "public.creatures.dtype" && it.evidence.contains("excludes") })
    }

    @Test fun `a known discriminator remains readable across renamed hierarchy classes`() {
        val dog = producer.copy(id = "NewDog", hierarchy = producer.hierarchy!!.copy(entityValue = "DOG"))
        val write = insert.copy(id = "NewDog.insert", sql = "insert into creatures (id,dtype) values (?,'DOG')",
            mappedEntityIds = listOf(dog.id), discriminatorWrites = listOf(DiscriminatorWrite(dog.id, discriminator, "DOG")))
        val report = assess(newProducer = dog, write = write)
        assertEquals(Verdict.SAFE, report.verdict, report.toString())
    }

    @Test fun `JOINED and missing physical discriminator remain explicitly unknown`() {
        val joined = assess(oldRoot = root.copy(hierarchy = rootHierarchy.copy(strategy = "JOINED", complete = false)))
        noBreaking(joined)
        assertEquals(Verdict.UNKNOWN, joined.verdict)
        val synthetic = assess(oldRoot = root.copy(hierarchy = rootHierarchy.copy(strategy = "JOINED", discriminator = null, complete = false)))
        noBreaking(synthetic)
        assertEquals(Verdict.UNKNOWN, synthetic.verdict, synthetic.toString())
        val formula = assess(oldRoot = root.copy(hierarchy = rootHierarchy.copy(discriminator = null, complete = false)))
        noBreaking(formula)
        assertEquals(Verdict.UNKNOWN, formula.verdict, formula.toString())
    }

    @Test fun `subtype metadata without a captured literal cannot invent a write`() {
        val report = assess(write = insert.copy(discriminatorWrites = emptyList()))
        noBreaking(report)
        assertEquals(Verdict.UNKNOWN, report.verdict, report.toString())
    }
}
