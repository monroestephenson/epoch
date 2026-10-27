package dev.epoch.hibernate

import dev.epoch.core.*
import java.lang.reflect.*

/** Bounded divide-and-conquer over complete association/inheritance components. */
internal object BootstrapRecovery {
    fun recover(classes: List<Class<*>>, request: ExtractionRequest, initialFailure: Throwable,
                bootstrap: (List<Class<*>>) -> DatabaseContract): DatabaseContract {
        rethrowFatal(initialFailure)
        val components = components(classes)
        val completed = mutableListOf<Pair<List<Class<*>>, DatabaseContract>>()
        val unresolved = mutableListOf<UnresolvedQuery>()
        var remainingAttempts = 64
        fun reject(group: List<List<Class<*>>>, reason: String) {
            group.flatten().forEach { unresolved += UnresolvedQuery(it.name, reason) }
        }
        fun visit(group: List<List<Class<*>>>, knownFailure: Throwable? = null) {
            if (group.isEmpty()) return
            val members = group.flatten().sortedBy { it.name }
            val failure = knownFailure ?: if (remainingAttempts-- <= 0) {
                reject(group, "Partial extraction stopped after the 64-attempt bootstrap isolation budget; this component remains unresolved")
                return
            } else try {
                completed += members to bootstrap(members)
                return
            } catch (error: Throwable) { rethrowFatal(error); error }
            if (group.size == 1) reject(group, "Entity dependency component could not bootstrap: ${describe(failure)}")
            else {
                val middle = group.size / 2
                visit(group.take(middle))
                visit(group.drop(middle))
            }
        }
        visit(components, initialFailure)
        val successfulClasses = completed.flatMap { it.first }.distinctBy { it.name }.sortedBy { it.name }
        var combined: DatabaseContract? = null
        if (successfulClasses.isNotEmpty() && successfulClasses.size < classes.size && completed.size > 1) {
            try { combined = bootstrap(successfulClasses) }
            catch (error: Throwable) {
                rethrowFatal(error)
                unresolved += UnresolvedQuery("bootstrap.combined", "Successful subsets could not bootstrap together: ${describe(error)}")
            }
        }
        if (successfulClasses.size == classes.size) unresolved += UnresolvedQuery("bootstrap.combined",
            "The complete entity set failed bootstrap even though isolated subsets succeeded: ${describe(initialFailure)}")
        val contracts = combined?.let(::listOf) ?: completed.map { it.second }
        val entities = contracts.flatMap { it.entities }.distinct().sortedBy { it.id }
        val groupedQueries = contracts.flatMap { it.queries }.groupBy { it.id }
        val queries = groupedQueries.flatMap { (id, values) ->
            if (values.distinct().size == 1) listOf(values.first()) else {
                unresolved += UnresolvedQuery(id, "Partial bootstrap produced different query definitions for the same ID")
                emptyList()
            }
        }.sortedBy { it.id }
        val allUnresolved = (unresolved + contracts.flatMap { it.unresolvedQueries } +
            request.unresolvedDynamicQueries.map { UnresolvedQuery(it.key, it.value) }).distinct().sortedWith(compareBy({ it.id }, { it.reason }))
        return DatabaseContract(applicationVersion = request.applicationVersion, entities = entities, queries = queries,
            coverage = Coverage(false, contracts.flatMap { it.coverage.mechanisms }.distinct().sorted(),
                contracts.flatMap { it.coverage.notes }.distinct() + "Partial extraction: failed dependency components are unresolved; associations/inheritance were not pruned to manufacture completeness."),
            unresolvedQueries = allUnresolved)
    }

    private fun components(classes: List<Class<*>>): List<List<Class<*>>> {
        val byName = classes.associateBy { it.name }
        val adjacency = classes.associate { it.name to mutableSetOf<String>() }
        fun connect(from: String, to: String) {
            if (to in byName && from != to) { adjacency.getValue(from) += to; adjacency.getValue(to) += from }
        }
        fun reference(owner: String, type: Type, visited: MutableSet<Type>) {
            if (!visited.add(type)) return
            when (type) {
                is Class<*> -> { connect(owner, type.name); if (type.isArray) reference(owner, type.componentType, visited) }
                is ParameterizedType -> { reference(owner, type.rawType, visited); type.actualTypeArguments.forEach { reference(owner, it, visited) } }
                is WildcardType -> (type.lowerBounds + type.upperBounds).forEach { reference(owner, it, visited) }
                is GenericArrayType -> reference(owner, type.genericComponentType, visited)
                is TypeVariable<*> -> type.bounds.forEach { reference(owner, it, visited) }
            }
        }
        for (entity in classes) {
            try {
                var current: Class<*>? = entity
                while (current != null && current != Any::class.java) {
                    reference(entity.name, current, mutableSetOf())
                    current.genericSuperclass?.let { reference(entity.name, it, mutableSetOf()) }
                    val elements: List<AnnotatedElement> = current.declaredFields.toList() + current.declaredMethods.toList()
                    for (field in current.declaredFields) reference(entity.name, field.genericType, mutableSetOf())
                    for (method in current.declaredMethods) reference(entity.name, method.genericReturnType, mutableSetOf())
                    for (element in elements) for (annotation in element.annotations) {
                        if (!annotation.annotationClass.java.name.startsWith("jakarta.persistence.")) continue
                        annotation.annotationClass.java.declaredMethods.filter { it.returnType == Class::class.java }.forEach { member ->
                            (member.invoke(annotation) as? Class<*>)?.let { reference(entity.name, it, mutableSetOf()) }
                        }
                    }
                    current = current.superclass
                }
            } catch (error: Throwable) {
                rethrowFatal(error)
                // An unreadable dependency graph cannot safely be split.
                classes.forEach { connect(entity.name, it.name) }
            }
        }
        val unseen = byName.keys.toSortedSet()
        val result = mutableListOf<List<Class<*>>>()
        while (unseen.isNotEmpty()) {
            val queue = ArrayDeque<String>(); queue.add(unseen.first())
            val component = mutableListOf<Class<*>>()
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!unseen.remove(name)) continue
                component += byName.getValue(name)
                adjacency.getValue(name).sorted().forEach(queue::addLast)
            }
            result += component.sortedBy { it.name }
        }
        return result
    }

    fun rethrowFatal(error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        if (error is InterruptedException) { Thread.currentThread().interrupt(); throw error }
    }
    fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message ?: "no additional detail"}"
}
