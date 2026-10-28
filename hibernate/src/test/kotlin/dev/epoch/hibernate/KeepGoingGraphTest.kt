package dev.epoch.hibernate

import dev.epoch.core.*
import jakarta.persistence.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class KeepGoingGraphTest {
    @Test fun `one bad entity in a large connected graph preserves every healthy entity and exact omitted associations`() {
        val healthy = LEAVES + GraphHub::class.java + GraphPropertyOwner::class.java
        val classes = healthy + GraphBroken::class.java
        val hibernate749 = System.getProperty("epoch.hibernate749.classpath").split(File.pathSeparator) +
            Path.of(GraphHub::class.java.protectionDomain.codeSource.location.toURI()).toString()
        for ((version, classpath) in listOf("6.6.13.Final" to emptyList(), "7.4.9.Final" to hibernate749)) {
        fun extract(types: List<Class<*>>) = HibernateExtractor().extract(ExtractionRequest(
            entityClasses = types.map { it.name }, classpath = classpath, keepGoing = true, discoverRepositories = false))
        val contract = extract(classes)

        assertTrue(contract.coverage.notes.any { it.contains("Hibernate runtime $version") }, contract.toString())
        assertEquals(healthy.map { it.name }.toSet(), contract.entities.map { it.id }.toSet(), contract.toString())
        assertFalse(contract.coverage.complete)
        healthy.forEach { type ->
            val entity = contract.entities.single { it.id == type.name }
            assertEquals(Operation.entries.toSet(), entity.operations, type.name)
            val queries = contract.queries.filter { it.id.startsWith("${type.name}.") }
            assertEquals(Operation.entries.toSet(), queries.map { it.operation }.toSet(), "$type: $queries")
            assertTrue(queries.all { it.dependenciesComplete }, "$type: $queries")
        }
        LEAVES.forEach { type ->
            val entity = contract.entities.single { it.id == type.name }
            assertTrue(entity.columns.any { it.name == "hub_id" }, entity.toString())
            assertFalse(entity.columns.any { it.name in setOf("broken_id", "explicit_broken_id") }, entity.toString())
            assertTrue(contract.queries.filter { it.id.startsWith("${type.name}.") && it.operation == Operation.INSERT }
                .all { query -> query.writeColumns.any { it.column == "hub_id" } }, contract.toString())
        }
        val propertyEntity = contract.entities.single { it.id == GraphPropertyOwner::class.java.name }
        assertTrue(propertyEntity.columns.any { it.name == "hub_id" }, propertyEntity.toString())
        assertFalse(propertyEntity.columns.any { it.name == "broken_id" }, propertyEntity.toString())
        val expectedOmissions = LEAVES.map { "${it.name}.broken" } + GraphBroken::class.java.name +
            "${GraphLeafA::class.java.name}.explicitBroken" + "${GraphPropertyOwner::class.java.name}.broken"
        assertEquals(expectedOmissions.toSet(), contract.unresolvedQueries.map { it.id }.toSet(), contract.toString())
        contract.unresolvedQueries.filter { it.id != GraphBroken::class.java.name }.forEach { omission ->
            assertTrue(omission.reason.contains(GraphBroken::class.java.name), omission.toString())
            assertTrue(omission.reason.contains("Association declared by"), omission.toString())
        }
        assertEquals(contract, extract(classes.reversed()), "Input class ordering must not affect partial extraction")
        }
    }

    @Test fun `shared generic superclass pruning never removes a healthy specialization`() {
        val classes = listOf(GraphHub::class.java, GenericMissingIdentifier::class.java,
            GenericBrokenOwner::class.java, GenericHealthyOwner::class.java)
        val contract = HibernateExtractor().extract(ExtractionRequest(classes.map { it.name },
            keepGoing = true, discoverRepositories = false))
        val healthy = contract.entities.single { it.id == GenericHealthyOwner::class.java.name }
        assertTrue(healthy.columns.any { it.name == "related_id" }, contract.toString())
        assertTrue(contract.queries.filter { it.id.startsWith("${GenericHealthyOwner::class.java.name}.") &&
            it.operation == Operation.INSERT }.all { it.writeColumns.any { column -> column.column == "related_id" } }, contract.toString())
        assertTrue(contract.unresolvedQueries.any {
            it.id == GenericMissingIdentifier::class.java.name || GenericMissingIdentifier::class.java.name in it.affectedIds
        }, contract.toString())
        assertFalse(contract.unresolvedQueries.any { it.id == "${GenericHealthyOwner::class.java.name}.related" }, contract.toString())
        assertFalse(contract.coverage.complete)
    }

    @Test fun `inheritance expansion cannot exceed the eight entity pruning budget`() {
        val hierarchy = listOf(BudgetRoot::class.java, BudgetA::class.java, BudgetB::class.java,
            BudgetC::class.java, BudgetD::class.java, BudgetE::class.java, BudgetF::class.java,
            BudgetG::class.java, BudgetH::class.java)
        val classes = hierarchy + GraphHub::class.java
        val attempts = mutableListOf<List<Class<*>>>()
        val initial = IllegalArgumentException("Cannot bootstrap ${BudgetRoot::class.java.name}")
        val contract = BootstrapRecovery.recover(classes, ExtractionRequest(classes.map { it.name }), initial,
            bootstrap = { retained ->
                if (retained.any { it in hierarchy }) throw initial
                DatabaseContract(entities = listOf(EntityContract(GraphHub::class.java.name, "public.graph_hub",
                    listOf(ColumnContract("id")))) )
            },
            bootstrapPruned = { retained, _ ->
                attempts += retained
                error("An oversized inherited exclusion must not reach bootstrap")
            })
        assertTrue(attempts.isEmpty(), attempts.toString())
        assertTrue(contract.entities.any { it.id == GraphHub::class.java.name }, contract.toString())
        val diagnostic = contract.unresolvedQueries.single()
        assertEquals(hierarchy.map { it.name }.sorted(), diagnostic.affectedIds, contract.toString())
    }

    @Test fun `a failing class name does not select a healthy class with a shared name prefix`() {
        val (contract, attempts) = recoverCandidates("Cannot bootstrap '${GraphCandidateAB::class.java.name}'")
        assertEquals(setOf(GraphCandidateA::class.java.name, GraphCandidateZ::class.java.name),
            contract.entities.map { it.id }.toSet(), contract.toString())
        assertEquals(listOf(setOf(GraphCandidateAB::class.java.name)), attempts)
        assertEquals(listOf(GraphCandidateAB::class.java.name), contract.unresolvedQueries.map { it.id })
    }

    @Test fun `all single exclusions are tried before combining exclusions mentioned by a diagnostic`() {
        val (contract, attempts) = recoverCandidates("Caller '${GraphCandidateA::class.java.name}' references failing '${GraphCandidateAB::class.java.name}'")
        assertEquals(setOf(GraphCandidateA::class.java.name, GraphCandidateZ::class.java.name),
            contract.entities.map { it.id }.toSet(), contract.toString())
        assertEquals(listOf(setOf(GraphCandidateA::class.java.name), setOf(GraphCandidateAB::class.java.name)), attempts)
        assertEquals(listOf(GraphCandidateAB::class.java.name), contract.unresolvedQueries.map { it.id })
    }

    private fun recoverCandidates(message: String): Pair<DatabaseContract, List<Set<String>>> {
        val classes = listOf(GraphCandidateA::class.java, GraphCandidateAB::class.java, GraphCandidateZ::class.java)
        val attempts = mutableListOf<Set<String>>()
        val contract = BootstrapRecovery.recover(classes, ExtractionRequest(classes.map { it.name }), IllegalArgumentException(message),
            bootstrap = { error("Component fallback must not be needed when one exact exclusion succeeds") },
            bootstrapPruned = { retained, _ ->
                attempts += (classes - retained.toSet()).map { it.name }.toSet()
                if (GraphCandidateAB::class.java in retained) throw IllegalArgumentException("Cannot bootstrap '${GraphCandidateAB::class.java.name}'")
                DatabaseContract(entities = retained.map { EntityContract(it.name, "public.${it.simpleName.lowercase()}",
                    listOf(ColumnContract("id"))) }, coverage = Coverage(complete = true))
            })
        return contract to attempts
    }

    companion object {
        private val LEAVES = listOf(GraphLeafA::class.java, GraphLeafB::class.java, GraphLeafC::class.java,
            GraphLeafD::class.java, GraphLeafE::class.java, GraphLeafF::class.java, GraphLeafG::class.java,
            GraphLeafH::class.java, GraphLeafI::class.java, GraphLeafJ::class.java, GraphLeafK::class.java,
            GraphLeafL::class.java)
    }
}

@Entity @Table(name = "graph_hub")
open class GraphHub { @field:Id open var id: Long = 0; open var label: String = "" }

@Entity @Table(name = "graph_broken")
open class GraphBroken {
    open var label: String = ""
    @field:ManyToOne(fetch = FetchType.LAZY) open var hub: GraphHub? = null
}

@MappedSuperclass
open class GraphLeafBase {
    @field:Id open var id: Long = 0
    open var label: String = ""
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "hub_id") open var hub: GraphHub? = null
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "broken_id") open var broken: GraphBroken? = null
}

@Entity @Table(name = "graph_a")
open class GraphLeafA : GraphLeafBase() {
    @field:ManyToOne(targetEntity = GraphBroken::class, fetch = FetchType.LAZY)
    @field:JoinColumn(name = "explicit_broken_id") open var explicitBroken: Any? = null
}
@Entity @Table(name = "graph_b") open class GraphLeafB : GraphLeafBase()
@Entity @Table(name = "graph_c") open class GraphLeafC : GraphLeafBase()
@Entity @Table(name = "graph_d") open class GraphLeafD : GraphLeafBase()
@Entity @Table(name = "graph_e") open class GraphLeafE : GraphLeafBase()
@Entity @Table(name = "graph_f") open class GraphLeafF : GraphLeafBase()
@Entity @Table(name = "graph_g") open class GraphLeafG : GraphLeafBase()
@Entity @Table(name = "graph_h") open class GraphLeafH : GraphLeafBase()
@Entity @Table(name = "graph_i") open class GraphLeafI : GraphLeafBase()
@Entity @Table(name = "graph_j") open class GraphLeafJ : GraphLeafBase()
@Entity @Table(name = "graph_k") open class GraphLeafK : GraphLeafBase()
@Entity @Table(name = "graph_l") open class GraphLeafL : GraphLeafBase()

@Entity @Table(name = "graph_property") @Access(AccessType.PROPERTY)
open class GraphPropertyOwner {
    @get:Id open var id: Long = 0
    open var label: String = ""
    @get:ManyToOne(fetch = FetchType.LAZY) @get:JoinColumn(name = "hub_id") open var hub: GraphHub? = null
    @get:ManyToOne(targetEntity = GraphBroken::class, fetch = FetchType.LAZY)
    @get:JoinColumn(name = "broken_id") open var broken: Any? = null
}

@MappedSuperclass
open class GenericOwner<T> {
    @field:Id open var id: Long = 0
    open var label: String = ""
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "related_id") open var related: T? = null
}
@Entity @Table(name = "generic_missing_identifier") open class GenericMissingIdentifier { open var label: String = "" }
@Entity @Table(name = "generic_broken") open class GenericBrokenOwner : GenericOwner<GenericMissingIdentifier>()
@Entity @Table(name = "generic_healthy") open class GenericHealthyOwner : GenericOwner<GraphHub>()

open class BudgetRoot
class BudgetA : BudgetRoot()
class BudgetB : BudgetRoot()
class BudgetC : BudgetRoot()
class BudgetD : BudgetRoot()
class BudgetE : BudgetRoot()
class BudgetF : BudgetRoot()
class BudgetG : BudgetRoot()
class BudgetH : BudgetRoot()

class GraphCandidateA
class GraphCandidateAB
class GraphCandidateZ
