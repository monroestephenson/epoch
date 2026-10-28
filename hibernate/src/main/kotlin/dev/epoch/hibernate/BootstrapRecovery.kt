package dev.epoch.hibernate

import dev.epoch.core.*
import java.lang.reflect.*

/** Bounded association pruning, followed by isolation of complete dependency components. */
internal object BootstrapRecovery {
    fun recover(classes: List<Class<*>>, request: ExtractionRequest, initialFailure: Throwable,
                bootstrap: (List<Class<*>>) -> DatabaseContract,
                bootstrapPruned: ((List<Class<*>>, Map<String, Set<String>>) -> DatabaseContract)? = null): DatabaseContract {
        rethrowFatal(initialFailure)
        if (bootstrapPruned != null) recoverWithinGraph(classes, initialFailure, bootstrapPruned)?.let { return it }
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

    /** XML transient overrides apply only to this extraction bootstrap; every removed access path is unresolved. */
    private fun recoverWithinGraph(classes: List<Class<*>>, failure: Throwable,
                                   bootstrap: (List<Class<*>>, Map<String, Set<String>>) -> DatabaseContract): DatabaseContract? {
        var budget = 32
        val attempted = mutableSetOf<Set<String>>()
        val pending = ArrayDeque<Pair<Set<Class<*>>, Throwable>>()
        pending.add(emptySet<Class<*>>() to failure)
        while (pending.isNotEmpty() && budget > 0) {
            val (removed, previous) = pending.removeFirst()
            if (removed.size >= classes.size || removed.size > 8) continue
            val messages = generateSequence(previous) { it.cause }.take(12).mapNotNull { it.message }.joinToString("; ")
            val remaining = classes.filter { it !in removed }
            val named = remaining.filter {
                Regex("(?<![\\p{javaJavaIdentifierPart}.])${Regex.escape(it.name)}(?![\\p{javaJavaIdentifierPart}])")
                    .containsMatchIn(messages)
            }.sortedBy { it.name }
            val candidates = named.ifEmpty { if (removed.isEmpty()) remaining.sortedBy { it.name } else emptyList() }
            for (candidate in candidates) {
                if (budget-- <= 0) break
                // Inheritance cannot be detached safely by making an association transient.
                val omitted = removed + classes.filter { candidate.isAssignableFrom(it) }
                if (omitted.size > 8) continue
                if (!attempted.add(omitted.map { it.name }.toSet())) continue
                val retained = classes.filter { it !in omitted }
                if (retained.isEmpty()) continue
                try {
                    val associations = prune(retained, omitted) ?: continue
                    val contract = bootstrap(retained, associations.overrides)
                    val issues = omitted.sortedBy { it.name }.map { UnresolvedQuery(it.name,
                        "Entity excluded after failed bootstrap: ${describe(previous)}") } +
                        associations.paths.map { path -> UnresolvedQuery("${path.owner.name}.${path.property}",
                            "Association declared by ${path.declaring.name}.${path.property} targets excluded entities ${path.targets.filter { it in omitted }.map { it.name }.sorted()}; omitted only in partial extraction, so its SQL and behavior remain unresolved") }
                    return contract.copy(coverage = contract.coverage.copy(complete = false,
                        notes = contract.coverage.notes + "Partial graph extraction: excluded entities and association XML overrides are explicitly unresolved; original application classes were not modified."),
                        unresolvedQueries = (contract.unresolvedQueries + issues).distinct().sortedWith(compareBy({ it.id }, { it.reason })))
                } catch (next: Throwable) {
                    rethrowFatal(next)
                    // Try all single exclusions before combinations; a diagnostic may name healthy callers too.
                    pending.add(omitted to next)
                }
            }
        }
        return null
    }

    private data class AssociationPath(val owner: Class<*>, val declaring: Class<*>, val property: String,
                                       val targets: Set<Class<*>>)
    private data class AssociationPruning(val overrides: Map<String, Set<String>>, val paths: List<AssociationPath>)

    /** A mapped-superclass override applies to all its retained descendants, not just one concrete owner. */
    private fun prune(remaining: List<Class<*>>, removed: Set<Class<*>>): AssociationPruning? {
        val paths = remaining.sortedBy { it.name }.flatMap(::associationPaths)
        val cut = paths.filter { path -> path.targets.any { it in removed } }
        val overrides = cut.groupBy { it.declaring.name }.mapValues { (_, entries) -> entries.map { it.property }.toSortedSet() }.toSortedMap()
        if (paths.any { path -> path.property in overrides[path.declaring.name].orEmpty() &&
                (path.targets.isEmpty() || path.targets.any { it !in removed }) }) return null
        return AssociationPruning(overrides, cut.distinct().sortedWith(compareBy({ it.owner.name }, { it.property }, { it.declaring.name })))
    }

    private fun associationPaths(owner: Class<*>): List<AssociationPath> = buildList {
        var current: Class<*>? = owner
        val bindings = mutableMapOf<TypeVariable<*>, Type>()
        fun targets(type: Type, visited: Set<Type> = emptySet()): Set<Class<*>> {
            if (type in visited) return emptySet()
            val seen = visited + type
            return when (type) {
                is Class<*> -> setOf(type)
                is ParameterizedType -> type.actualTypeArguments.flatMap { targets(it, seen) }.toSet()
                is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap { targets(it, seen) }.toSet()
                is GenericArrayType -> targets(type.genericComponentType, seen)
                is TypeVariable<*> -> bindings[type]?.let { targets(it, seen) } ?: emptySet()
                else -> emptySet()
            }
        }
        fun addAssociation(declaring: Class<*>, property: String, member: AnnotatedElement, type: Type) {
            val annotations = member.annotations.filter { it.annotationClass.java.name in ASSOCIATIONS }
            if (annotations.isEmpty()) return
            val explicit = annotations.mapNotNull { annotation ->
                (annotation.annotationClass.java.getMethod("targetEntity").invoke(annotation) as? Class<*>)
                    ?.takeUnless { it == Void.TYPE || it == Void::class.java }
            }.toSet()
            add(AssociationPath(owner, declaring, property, explicit.ifEmpty { targets(type) }))
        }
        while (current != null && current != Any::class.java) {
            val declaring = current
            if (declaring.annotations.any { it.annotationClass.java.name in MAPPED_TYPES }) {
                declaring.declaredFields.sortedBy { it.name }.forEach { field ->
                    addAssociation(declaring, field.name, field, field.genericType)
                }
                declaring.declaredMethods.filter { it.parameterCount == 0 && !it.isBridge && !it.isSynthetic }
                    .sortedBy { it.name }.forEach { method ->
                        val stem = when {
                            method.name.startsWith("get") -> method.name.removePrefix("get")
                            method.name.startsWith("is") -> method.name.removePrefix("is")
                            else -> return@forEach
                        }
                        addAssociation(declaring, java.beans.Introspector.decapitalize(stem), method, method.genericReturnType)
                    }
            }
            val parent = declaring.genericSuperclass
            if (parent is ParameterizedType) {
                val raw = parent.rawType as? Class<*>
                raw?.typeParameters?.zip(parent.actualTypeArguments)?.forEach { (variable, value) -> bindings[variable] = value }
            }
            current = declaring.superclass
        }
    }

    private val ASSOCIATIONS = setOf("jakarta.persistence.ManyToOne", "jakarta.persistence.OneToOne", "jakarta.persistence.OneToMany", "jakarta.persistence.ManyToMany")
    private val MAPPED_TYPES = setOf("jakarta.persistence.Entity", "jakarta.persistence.MappedSuperclass")

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
