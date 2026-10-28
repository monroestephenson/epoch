package dev.epoch.hibernate

import dev.epoch.associationfixture.JavaAssociationEntity
import dev.epoch.core.DatabaseContract
import dev.epoch.core.Operation
import jakarta.persistence.*
import org.hibernate.annotations.NotFound
import org.hibernate.annotations.NotFoundAction
import org.hibernate.annotations.JoinFormula
import org.hibernate.annotations.JavaType
import org.hibernate.type.descriptor.java.LongJavaType
import org.junit.jupiter.api.Test
import java.io.File
import java.io.Serializable
import java.nio.file.Path
import kotlin.test.*

class AssociationNullabilityMetadataTest {
    @Test fun `direct owning association declarations follow exact physical key columns in both runtimes`() {
        val types = listOf(AssociationParent::class.java, AssociationOwner::class.java, InheritedAssociationOwner::class.java)
        for (classpath in runtimes(types)) {
            val contract = extract(types, classpath)
            assertTrue(contract.coverage.complete, contract.toString())
            val required = column(contract, "association_owner", "renamed_required_fk")
            assertEquals(true, required.declaredNonNull)
            assertEquals(true, required.hibernateNullable, "Keep raw Hibernate nullability separate from the Kotlin declaration")
            assertEquals(false, column(contract, "association_owner", "nullable_fk").declaredNonNull)
            assertEquals(true, column(contract, "inherited_association_owner", "inherited_fk").declaredNonNull)
            assertFalse(required.inputDomainComplete,
                "Reference declaration is not exhaustive proof of transient/deleted/early-insert JDBC bindings")
            assertTrue(column(contract, "association_owner", "label").inputDomainComplete,
                "Association uncertainty must not invalidate an unrelated direct scalar")
            val writes = contract.queries.filter { it.targetTable == "public.association_owner" && it.operation == Operation.INSERT }
            assertTrue(writes.isNotEmpty(), contract.toString())
            assertTrue(writes.all { query -> query.writeColumns.any { it.column == "renamed_required_fk" } }, writes.toString())
        }
    }

    @Test fun `Java CLASS retained association annotations are factual but unannotated references stay unknown`() {
        val types = listOf(JavaAssociationEntity::class.java)
        for (classpath in runtimes(types)) {
            val contract = extract(types, classpath)
            assertEquals(true, column(contract, "java_association", "required_fk").declaredNonNull)
            assertEquals(false, column(contract, "java_association", "nullable_fk").declaredNonNull)
            assertNull(column(contract, "java_association", "unspecified_fk").declaredNonNull)
            assertFalse(column(contract, "java_association", "required_fk").inputDomainComplete)
            assertTrue(JavaAssociationEntity::class.java.getField("required").annotations.none {
                it.annotationClass.java.name == "org.jetbrains.annotations.NotNull"
            }, "The annotation fixture must require classfile metadata")
        }
    }

    @Test fun `natural composite ignored-missing and inverse relations do not borrow object nullability`() {
        val groups = listOf(
            listOf(NaturalAssociationParent::class.java, NaturalAssociationOwner::class.java),
            listOf(CompositeAssociationParent::class.java, CompositeAssociationOwner::class.java),
            listOf(AssociationParent::class.java, IgnoredAssociationOwner::class.java),
            listOf(InverseAssociationParent::class.java, InverseAssociationOwner::class.java),
        )
        for (types in groups) for (classpath in runtimes(types)) {
            val contract = extract(types, classpath)
            for ((table, names) in mapOf(
                "natural_association_owner" to listOf("parent_code"),
                "composite_association_owner" to listOf("parent_first", "parent_second"),
                "ignored_association_owner" to listOf("parent_id"),
                "inverse_association_owner" to listOf("parent_id"),
            )) contract.entities.filter { it.table == "public.$table" }.forEach { entity ->
                entity.columns.filter { it.name in names }.forEach { value ->
                    assertNull(value.declaredNonNull, "$table.${value.name}: non-null reference is not this leaf's domain")
                    assertFalse(value.inputDomainComplete, "$table.${value.name}")
                }
            }
            // The inverse relation must not attach its nullable object declaration to its parent's identifier.
            contract.entities.singleOrNull { it.table == "public.inverse_association_parent" }?.let { entity ->
                assertTrue(entity.columns.none { it.name == "parent_id" }, entity.toString())
            }
        }
    }

    @Test fun `conflicting scalar and association declarations remain unknown on their shared column`() {
        val types = listOf(AssociationParent::class.java, SharedAssociationOwner::class.java)
        for (classpath in runtimes(types)) {
            val contract = extract(types, classpath)
            assertNull(column(contract, "shared_association_owner", "parent_id").declaredNonNull,
                "A nullable writable scalar cannot borrow a read-only non-null association's declaration")
            assertEquals(true, column(contract, "shared_association_owner", "id").declaredNonNull)
        }
    }

    @Test fun `join tables formulas and custom identifier codecs do not establish direct reference facts`() {
        val groups = listOf(
            listOf(AssociationParent::class.java, JoinTableAssociationOwner::class.java),
            listOf(AssociationParent::class.java, FormulaAssociationOwner::class.java),
            listOf(CustomAssociationParent::class.java, CustomAssociationOwner::class.java),
        )
        for (types in groups) for (classpath in runtimes(types)) {
            val contract = extract(types, classpath)
            if (JoinTableAssociationOwner::class.java in types) {
                val keys = contract.entities.filter { it.id.startsWith(JoinTableAssociationOwner::class.java.name) }
                    .flatMap { it.columns }.filter { it.name == "parent_id" }
                assertTrue(keys.isNotEmpty(), contract.toString())
                assertTrue(keys.all { it.declaredNonNull == null && !it.inputDomainComplete }, keys.toString())
            }
            if (CustomAssociationOwner::class.java in types) {
                val key = column(contract, "custom_association_owner", "parent_id")
                assertNull(key.declaredNonNull, key.toString())
                assertFalse(key.inputDomainComplete, key.toString())
            }
            if (FormulaAssociationOwner::class.java in types) {
                assertTrue(contract.entities.single { it.table == "public.formula_association_owner" }.columns.none { it.name == "1" })
                assertTrue(contract.unresolvedQueries.any { it.reason.contains("Formula", ignoreCase = true) }, contract.toString())
            }
        }
    }

    private fun extract(types: List<Class<*>>, classpath: List<String>): DatabaseContract =
        HibernateExtractor().extract(ExtractionRequest(entityClasses = types.map { it.name },
            classpath = classpath, discoverRepositories = false))

    private fun column(contract: DatabaseContract, table: String, name: String) =
        contract.entities.single { it.table == "public.$table" }.columns.single { it.name == name }

    private fun runtimes(types: List<Class<*>>): List<List<String>> = listOf(emptyList(),
        requireNotNull(System.getProperty("epoch.hibernate749.classpath")).split(File.pathSeparator) +
            types.map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct())
}

@Entity @Table(name = "association_parent")
open class AssociationParent {
    @field:Id open var id: Long = 0
}

@Entity @Table(name = "association_owner")
open class AssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "renamed_required_fk")
    open lateinit var required: AssociationParent
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "nullable_fk")
    open var optional: AssociationParent? = null
    open var label: String = ""
}

@MappedSuperclass
open class InheritedAssociationBase {
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "inherited_fk")
    open lateinit var required: AssociationParent
}

@Entity @Table(name = "inherited_association_owner")
open class InheritedAssociationOwner : InheritedAssociationBase() { @field:Id open var id: Long = 0 }

@Entity @Table(name = "natural_association_parent")
open class NaturalAssociationParent {
    @field:Id open var id: Long = 0
    @field:Column(unique = true) open var code: String? = null
}

@Entity @Table(name = "natural_association_owner")
open class NaturalAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_code", referencedColumnName = "code")
    open lateinit var parent: NaturalAssociationParent
}

@Embeddable
data class CompositeAssociationKey(var first: Long = 0, var second: Long = 0) : Serializable

@Entity @Table(name = "composite_association_parent")
open class CompositeAssociationParent { @field:EmbeddedId open var id: CompositeAssociationKey = CompositeAssociationKey() }

@Entity @Table(name = "composite_association_owner")
open class CompositeAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumns(value = [JoinColumn(name = "parent_first", referencedColumnName = "first"), JoinColumn(name = "parent_second", referencedColumnName = "second")])
    open lateinit var parent: CompositeAssociationParent
}

@Entity @Table(name = "ignored_association_owner")
open class IgnoredAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne @field:JoinColumn(name = "parent_id") @field:NotFound(action = NotFoundAction.IGNORE)
    open lateinit var parent: AssociationParent
}

@Entity @Table(name = "inverse_association_parent")
open class InverseAssociationParent {
    @field:Id open var id: Long = 0
    @field:OneToOne(mappedBy = "parent") open var owner: InverseAssociationOwner? = null
}

@Entity @Table(name = "inverse_association_owner")
open class InverseAssociationOwner {
    @field:Id open var id: Long = 0
    @field:OneToOne @field:JoinColumn(name = "parent_id") open lateinit var parent: InverseAssociationParent
}

@Entity @Table(name = "shared_association_owner")
open class SharedAssociationOwner {
    @field:Id open var id: Long = 0
    @field:Column(name = "parent_id") open var parentId: Long? = null
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id", insertable = false, updatable = false)
    open lateinit var parent: AssociationParent
}

@Entity @Table(name = "join_table_association_owner")
open class JoinTableAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinTable(name = "association_owner_parent", joinColumns = [JoinColumn(name = "owner_id")],
        inverseJoinColumns = [JoinColumn(name = "parent_id")])
    open lateinit var parent: AssociationParent
}

@Entity @Table(name = "formula_association_owner")
open class FormulaAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinFormula(value = "1", referencedColumnName = "id")
    open lateinit var parent: AssociationParent
}

class AssociationCustomLongJavaType : LongJavaType()

@Entity @Table(name = "custom_association_parent")
open class CustomAssociationParent {
    @field:Id @field:JavaType(AssociationCustomLongJavaType::class) open var id: Long = 0
}

@Entity @Table(name = "custom_association_owner")
open class CustomAssociationOwner {
    @field:Id open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY) @field:JoinColumn(name = "parent_id")
    open lateinit var parent: CustomAssociationParent
}
