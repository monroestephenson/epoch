package dev.epoch.hibernate

import dev.epoch.core.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipFile
import java.util.Properties

data class DiscoveredQueries(
    val hqlQueries: Map<String, String> = emptyMap(),
    val nativeQueries: Map<String, String> = emptyMap(),
    val unresolved: List<UnresolvedQuery> = emptyList(),
    val accessPaths: List<AccessPathCoverage> = emptyList(),
    val notes: List<String> = emptyList(),
)

/** Bounded bytecode data flow retains only established constants or finite string alternatives. */
object RepositoryDiscovery {
    fun discover(classpath: List<String>, packages: List<String>, loader: ClassLoader, entityClasses: List<String> = emptyList()): DiscoveredQueries {
        val nodes = sortedMapOf<String, ClassNode>()
        val unresolved = mutableListOf<UnresolvedQuery>()
        val diagnostics = ExtractionDiagnostics()
        val failedClasses = sortedSetOf<String>()
        val failedArchives = sortedSetOf<String>()
        var failedMethods = 0
        var inspectedInvocations = 0
        var recognizedInvocations = 0
        val notes = mutableListOf<String>()
        val paths = mutableListOf<AccessPathCoverage>()
        val hql = sortedMapOf<String, String>()
        val native = sortedMapOf<String, String>()
        val prefixes = packages.distinct().sorted().map { name ->
            require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) { "Invalid repository package: $name" }
            name.replace('.', '/') + "/"
        }
        fun selected(name: String) = name.endsWith(".class") && name != "module-info.class" &&
            (prefixes.isEmpty() || prefixes.any(name::startsWith))
        fun read(location: String, bytes: () -> ByteArray) {
            try {
                val node = ClassNode()
                ClassReader(bytes()).accept(node, ClassReader.SKIP_FRAMES)
                val previous = nodes.putIfAbsent(node.name, node)
                if (previous != null) notes += "Duplicate repository class uses first classpath definition: ${node.name.replace('/', '.')}"
            } catch (error: Throwable) {
                val id = "repository-discovery:$location"
                diagnostics.capture(id, "Cannot inspect compiled class", error)
                failedClasses += id
            }
        }
        var scannedRoots = 0
        var unscopedJars = 0
        for (entry in classpath.distinct()) {
            val path = Path.of(entry)
            if (!Files.exists(path)) continue
            if (Files.isDirectory(path)) {
                scannedRoots++
                Files.walk(path).use { files ->
                    files.filter { Files.isRegularFile(it) && selected(path.relativize(it).toString().replace('\\', '/')) }
                        .sorted().forEach { file -> read(path.relativize(file).toString()) { Files.readAllBytes(file) } }
                }
                notes += "Repository bytecode root: $entry; packages=${packages.ifEmpty { listOf("all classes in directory") }}"
            } else if (prefixes.isEmpty()) {
                unscopedJars++
            } else {
                scannedRoots++
                try {
                    JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jar ->
                        jar.versionedStream().use { entries ->
                            entries.filter { !it.isDirectory && selected(it.name) }.sorted(compareBy { it.name })
                                .forEach { item -> read("$entry!/${item.name}") { jar.getInputStream(item).use { it.readBytes() } } }
                        }
                    }
                    notes += "Repository bytecode root: $entry; packages=$packages"
                } catch (error: Throwable) {
                    val id = "repository-discovery:$entry"
                    diagnostics.capture(id, "Cannot inspect classpath archive", error)
                    failedArchives += id
                }
            }
        }
        if (scannedRoots == 0 || unscopedJars > 0) unresolved += UnresolvedQuery("repository-discovery:scope",
            if (scannedRoots == 0) "No compiled application roots were scanned. Supply classpath and repositoryPackages, or explicitly disable repository discovery for an entity-only contract."
            else "$unscopedJars classpath archives were not scanned without a package selector. Set repositoryPackages to the application's repository/DAO packages; dependency internals are outside the application inventory.")
        notes += "Repository coverage is limited to the supplied compiled roots and selected packages; reflection, generated classes and external application modules are not an exhaustive application inventory."

        val constants = nodes.values.flatMap { owner -> owner.fields.mapNotNull { field ->
            field.value?.takeIf { field.access and (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) == (Opcodes.ACC_STATIC or Opcodes.ACC_FINAL) }
                ?.let { "${owner.name}.${field.name}:${field.desc}" to it }
        } }.toMap()
        val metadata = MetadataInventory(nodes, loader, diagnostics)
        val namedQueries = metadata.namedQueries(entityClasses)
        val propertyQueries = metadata.namedQueryProperties()
        val xmlMappingsPresent = loader.getResources("META-INF/orm.xml").hasMoreElements()
        for (owner in nodes.values) {
            val className = owner.name.replace('/', '.')
            val repository = isSpringRepository(owner, nodes, loader)
            for (method in owner.methods.sortedWith(compareBy({ it.name }, { it.desc }))) {
                val methodName = method.name + method.desc
                val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
                fun record(id: String, query: String?, mechanism: String, nativeSql: Boolean = false, reason: String? = null, line: Int? = null) {
                    val problem = reason ?: query?.takeIf { it.isBlank() || it.contains("#{") }?.let { "Query is empty or contains unresolved expression-language substitution" }
                    if (query != null && problem == null) {
                        (if (nativeSql) native else hql)[id] = query
                        paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC, listOf(id), mechanism = mechanism, line = line)
                    } else {
                        val explanation = problem ?: "Query string is not a compile-time constant on every incoming control-flow path"
                        if (!diagnostics.attach(id, explanation)) unresolved += UnresolvedQuery(id, explanation)
                        paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.UNRESOLVED, reason = explanation, mechanism = mechanism, line = line)
                    }
                }
                fun recordVariants(id: String, queries: List<String>?, mechanism: String, nativeSql: Boolean = false,
                                   reason: String? = null, line: Int? = null) {
                    val fixed = queries?.distinct()?.sorted()
                    if (fixed.isNullOrEmpty() || fixed.size == 1 || reason != null || fixed.any { it.isBlank() || it.contains("#{") }) {
                        record(id, fixed?.singleOrNull(), mechanism, nativeSql, reason ?: fixed?.takeIf {
                            it.any { query -> query.isBlank() || query.contains("#{") }
                        }?.let { "A finite query variant is empty or contains unresolved expression-language substitution" }, line)
                        return
                    }
                    val queryIds = fixed.mapIndexed { variant, query ->
                        "$id.variant.$variant".also { (if (nativeSql) native else hql)[it] = query }
                    }
                    paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC, queryIds,
                        reason = "Enumerated ${fixed.size} constant query variants from compiled control flow", mechanism = mechanism, line = line)
                }
                val queryAnnotations = annotations.filter { it.desc in SPRING_QUERY_ANNOTATIONS }
                val queryAnnotation = queryAnnotations.singleOrNull()
                val customQueryAnnotation = annotations.any {
                    it.desc !in SPRING_QUERY_ANNOTATIONS && metadata.isQueryExtension(it.desc, unreadableMayBeQuery = repository)
                }
                if (queryAnnotation != null) {
                    val attributes = metadata.annotationDefaults(queryAnnotation.desc).orEmpty() + annotationValues(queryAnnotation)
                    val rewriter = attributes["queryRewriter"] as? Type
                    val rewriterFailure = when {
                        metadata.annotationDefaults(queryAnnotation.desc) == null -> "Cannot inspect the supplied Spring query annotation's default behavior"
                        rewriter != null && rewriter.className != "org.springframework.data.jpa.repository.QueryRewriter\$IdentityQueryRewriter" ->
                            "Custom Spring QueryRewriter ${rewriter.className} can replace this SQL/HQL; its rewritten query variants are unresolved"
                        else -> null
                    }
                    val nativeSql = queryAnnotation.desc == SPRING_NATIVE_QUERY || attributes["nativeQuery"] == true
                    val declaredName = (attributes["name"] as? String)?.takeIf(String::isNotBlank)
                    if (rewriterFailure == null && (attributes["value"] as? String).isNullOrBlank() && declaredName != null) {
                        paths += AccessPathCoverage("$className#$methodName:annotation", className, methodName,
                            AccessPathStatus.DECLARED, listOf(declaredName), mechanism = "SPRING_NAMED_QUERY")
                    } else record("$className#$methodName:annotation", attributes["value"] as? String, "SPRING_QUERY", nativeSql, rewriterFailure)
                    (attributes["countQuery"] as? String)?.takeIf(String::isNotBlank)?.let {
                        record("$className#$methodName:countQuery", it, "SPRING_COUNT_QUERY", nativeSql, rewriterFailure)
                    }
                } else if (customQueryAnnotation || queryAnnotations.size > 1) {
                    record("$className#$methodName:annotation", null, "SPRING_QUERY_EXTENSION",
                        reason = "Stored procedures, composed/unreadable query annotations, or conflicting query annotations require framework-specific resolution; derived-method inference cannot substitute for them")
                } else if (repository && method.access and Opcodes.ACC_ABSTRACT != 0 && method.name !in setOf("<init>", "<clinit>")) {
                    val id = "$className#$methodName:derived"
                    val entity = repositoryEntity(owner, nodes, loader)
                    val defaultName = entity?.let { "${it.simpleName}.${method.name}" }
                    val entityNamed = entity?.let { metadata.namedQueries(listOf(it.name)) }.orEmpty()
                    if (defaultName != null && defaultName in propertyQueries) {
                        record("$className#$methodName:named", null, "SPRING_NAMED_QUERY",
                            reason = "META-INF/jpa-named-queries.properties declares $defaultName; this external named query must be resolved through the configured Spring lookup strategy before deriving a query")
                    } else if (defaultName != null && defaultName in namedQueries + entityNamed) {
                        paths += AccessPathCoverage("$className#$methodName:named", className, methodName,
                            AccessPathStatus.DECLARED, listOf(defaultName), mechanism = "SPRING_NAMED_QUERY")
                    } else if (xmlMappingsPresent || "*" in propertyQueries) {
                        record("$className#$methodName:named", null, "SPRING_NAMED_QUERY",
                            reason = "XML mappings or unreadable named-query properties are present; Spring named-query precedence cannot be established before method-name derivation")
                    } else {
                    val derived = if (entity == null) Result.failure(IllegalArgumentException("Repository entity type is not a resolved concrete class"))
                        else deriveSpringQuery(method.name, entity, loader)
                    record(id, derived.getOrNull(), "SPRING_DERIVED", reason = derived.exceptionOrNull()?.let { diagnostics.capture(id, "Spring query expansion unavailable", it) })
                    if (derived.isSuccess) {
                        val variants = nullableSpringVariants(derived.getOrThrow(), method)
                        if (variants == null)
                            record("$className#$methodName:null-variants", null, "SPRING_VARIANTS",
                                reason = "Spring equality/inequality null variants exceed the 16-variant bound or use runtime special parameters; declare the finite variant set explicitly")
                        else if (variants.size > 1) {
                            val queryIds = variants.mapIndexed { variant, query ->
                                (if (variant == 0) id else "$id.nullMask.$variant").also { hql[it] = query }
                            }
                            val position = paths.indexOfLast { it.id == id }
                            paths[position] = paths[position].copy(queryIds = queryIds,
                                reason = "Enumerated ${variants.size} null/non-null equality predicate variants through Spring's parsed property structure")
                        }
                        val returned = Type.getReturnType(method.desc)
                        val ordinaryEntityResult = returned.className == entity?.name ||
                            (returned.className in setOf("java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable", "java.util.Optional") &&
                                method.signature?.contains("L${entity?.name?.replace('.', '/')};") == true)
                        val countResult = method.name.startsWith("count") && returned.className in setOf("long", "int", "java.lang.Long", "java.lang.Integer")
                        if (!ordinaryEntityResult && !countResult) record("$className#$methodName:projection", null, "SPRING_VARIANTS",
                            reason = "Repository return type ${returned.className} is not an established plain entity/count result; projection and wrapper-generated query variants remain unresolved")
                    }
                    }
                }
                if (queryAnnotation != null || repository) {
                    val modifiers = buildList {
                        if (Type.getArgumentTypes(method.desc).any { it.className in setOf("org.springframework.data.domain.Pageable", "org.springframework.data.domain.Sort", "org.springframework.data.domain.ScrollPosition", "java.lang.Class") })
                            add("runtime pagination, sorting, scrolling or projection parameters")
                        if (annotations.any { it.desc in setOf("Lorg/springframework/data/jpa/repository/EntityGraph;", "Lorg/springframework/data/jpa/repository/Lock;") })
                            add("entity graph or lock mode")
                    }
                    if (modifiers.isNotEmpty()) record("$className#$methodName:variants", null, "SPRING_VARIANTS",
                        reason = "Base query extraction does not enumerate ${modifiers.joinToString()}; supply explicit finite variants or runtime observations (observations remain non-exhaustive)")
                }
                if (method.instructions.size() == 0) continue
                var analysisFailure: String? = null
                val frames = try { Analyzer(ConstantInterpreter(constants)).analyze(owner.name, method) }
                catch (failure: Throwable) {
                    analysisFailure = diagnostics.capture("$className#$methodName:analysis", "Bytecode data flow could not be analyzed", failure)
                    failedMethods++
                    null
                }
                var callIndex = 0
                var sourceLine: Int? = null
                val panacheModifiers = method.instructions.toArray().filterIsInstance<MethodInsnNode>()
                    .any { mechanism(it, nodes) == "PANACHE_VARIANTS" }
                method.instructions.toArray().forEachIndexed { index, instruction ->
                    if (instruction is LineNumberNode) sourceLine = instruction.line
                    if (instruction !is MethodInsnNode) return@forEachIndexed
                    inspectedInvocations++
                    val mechanism = mechanism(instruction, nodes) ?: return@forEachIndexed
                    recognizedInvocations++
                    val id = "$className#$methodName:${callIndex++}"
                    val arguments = Type.getArgumentTypes(instruction.desc)
                    val stringArgument = arguments.indexOfFirst { it.className == "java.lang.String" }
                    val frame = frames?.get(index)
                    val queryValue = if (frame != null && stringArgument >= 0) {
                        val position = frame.stackSize - arguments.size + stringArgument
                        if (position >= 0) frame.getStack(position) else null
                    } else null
                    val queries = queryValue?.constants?.takeIf { it.all { value -> value is String } }?.map { it as String }?.sorted()
                    val queryFailure = analysisFailure ?: queryValue?.unresolvedReason
                    when (mechanism) {
                        "JPA_HQL" -> recordVariants(id, queries, mechanism, reason = queryFailure, line = sourceLine)
                        "JPA_NAMED" -> if (queries != null && queries.all(String::isNotBlank) && queryFailure == null) {
                            paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.DECLARED, queries, mechanism = mechanism, line = sourceLine)
                        } else record(id, null, mechanism, reason = queryFailure, line = sourceLine)
                        "JPA_NATIVE", "JDBC_SQL" -> recordVariants(id, queries, mechanism, nativeSql = true, reason = queryFailure, line = sourceLine)
                        "JDBC_PREPARED_EXECUTION" -> {
                            val receiver = frame?.takeIf { it.stackSize > arguments.size }
                                ?.let { it.getStack(it.stackSize - arguments.size - 1).constants }
                                ?.takeIf { it.all { value -> value is PreparedSql } }?.map { it as PreparedSql }
                            val sources = receiver?.map { sql -> native.entries.firstOrNull {
                                it.key.startsWith("$className#$methodName:") && it.value == sql.sql
                            }?.key }
                            if (!sources.isNullOrEmpty() && sources.all { it != null }) paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC,
                                sources.filterNotNull().distinct().sorted(), mechanism = mechanism, line = sourceLine)
                            else record(id, null, mechanism, reason = "Prepared statement execution has no locally established constant SQL source; declare its producer's complete SQL variants", line = sourceLine)
                        }
                        "PANACHE" -> {
                            val dynamicSort = arguments.any { it.className == "io.quarkus.panache.common.Sort" }
                            if (dynamicSort || panacheModifiers) {
                                record(id, null, "PANACHE_VARIANTS", reason = if (dynamicSort)
                                    "Panache Sort arguments generate ordering/column dependencies that have not been enumerated; the unsorted base is not asserted as an executed query variant"
                                else "This method applies Panache projection, paging, filtering or locking modifiers; the unmodified query is not asserted as an executed variant", line = sourceLine)
                                return@forEachIndexed
                            }
                            val entity = panacheEntity(owner, instruction.owner, nodes, loader)
                            val fixed = queries ?: listOf("").takeIf { arguments.isEmpty() && instruction.name in setOf("findAll", "listAll", "deleteAll", "count") }
                            val expanded = if (fixed == null) Result.failure(IllegalArgumentException("Panache query is not a compile-time constant"))
                                else if (entity == null) Result.failure(IllegalArgumentException("Panache entity type cannot be resolved from the compiled repository/entity hierarchy"))
                                else runCatching { fixed.map { expandPanache(instruction.name, entity, it, loader).getOrThrow() } }
                            recordVariants(id, expanded.getOrNull(), mechanism,
                                reason = queryFailure ?: expanded.exceptionOrNull()?.let { diagnostics.capture(id, "Panache query expansion unavailable", it) }, line = sourceLine)
                        }
                        else -> record(id, null, mechanism, reason = "$mechanism database-access call ${instruction.owner.replace('/', '.')}.${instruction.name} is not exhaustively translated; declare finite SQL/HQL variants or instrument runtime SQL", line = sourceLine)
                    }
                }
            }
        }
        notes += "Repository scan inventory: ${nodes.size} readable classes, ${failedClasses.size} unreadable class sources, ${failedArchives.size} unreadable archives, $failedMethods methods with failed data flow; inspected $inspectedInvocations method-call instructions and recognized $recognizedInvocations database API call sites."
        notes += "Recognized API calls are not a complete database-access denominator: unrecognized inherited owners, helper APIs, reflection, and unreadable bytecode remain outside this count; method percentages describe only discovered access paths."
        return DiscoveredQueries(hql, native, (unresolved + diagnostics.unresolved()).distinct().sortedBy { it.id },
            paths.map { it.copy(reason = diagnostics.resolve(it.reason)) }.sortedBy { it.id }, notes.distinct())
    }

    private fun mechanism(call: MethodInsnNode, nodes: Map<String, ClassNode>): String? {
        val owner = call.owner
        if (call.name == "createNamedQuery" && (owner.startsWith("jakarta/persistence/") || owner.startsWith("javax/persistence/") || owner.startsWith("org/hibernate/"))) return "JPA_NAMED"
        if (call.name in setOf("createStoredProcedureQuery", "createNamedStoredProcedureQuery") &&
            (owner.startsWith("jakarta/persistence/") || owner.startsWith("javax/persistence/"))) return "JPA_PROCEDURE"
        if (call.name in setOf("createQuery", "createSelectionQuery", "createMutationQuery") &&
            (owner.startsWith("jakarta/persistence/") || owner.startsWith("javax/persistence/") || owner.startsWith("org/hibernate/")))
            return if (Type.getArgumentTypes(call.desc).firstOrNull()?.className == "java.lang.String") "JPA_HQL" else "CRITERIA"
        if (call.name in setOf("createNativeQuery", "createSQLQuery") &&
            (owner.startsWith("jakarta/persistence/") || owner.startsWith("javax/persistence/") || owner.startsWith("org/hibernate/"))) return "JPA_NATIVE"
        if ((owner.startsWith("jakarta/persistence/") || owner.startsWith("javax/persistence/") || owner.startsWith("org/hibernate/")) &&
            call.name in setOf("setFirstResult", "setMaxResults", "setLockMode", "setHint", "setEntityGraph", "applyGraph", "setFetchSize", "enableFilter", "setParameterList")) return "QUERY_VARIANTS"
        if (owner == "java/sql/Connection" && call.name == "prepareCall") return "JDBC_PROCEDURE"
        if (owner in setOf("java/sql/PreparedStatement", "java/sql/CallableStatement") && call.name.startsWith("execute")) return "JDBC_PREPARED_EXECUTION"
        if ((owner == "java/sql/Connection" && call.name == "prepareStatement") ||
            (owner.startsWith("java/sql/Statement") && call.name.startsWith("execute")) ||
            (owner.startsWith("org/springframework/jdbc/core/") && call.name in setOf("query", "queryForList", "queryForObject", "queryForMap", "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute"))) return "JDBC_SQL"
        if ((owner.startsWith("io/quarkus/hibernate/orm/panache/") || hasPanacheHierarchy(nodes[owner], nodes)) &&
            call.name in setOf("project", "page", "range", "filter", "withLock", "withHint", "withCountQuery", "enableFilter", "disableFilter")) return "PANACHE_VARIANTS"
        if ((owner.startsWith("io/quarkus/hibernate/orm/panache/") || hasPanacheHierarchy(nodes[owner], nodes)) &&
            call.name in setOf("find", "list", "stream", "count", "update", "delete", "findAll", "listAll", "deleteAll")) return "PANACHE"
        if ((owner.startsWith("jakarta/persistence/criteria/") || owner.startsWith("javax/persistence/criteria/")) && call.name in setOf("createQuery", "createCriteriaUpdate", "createCriteriaDelete")) return "CRITERIA"
        if (owner.startsWith("com/querydsl/") && call.name in setOf("fetch", "fetchOne", "fetchFirst", "fetchResults", "fetchCount", "execute", "iterate")) return "QUERYDSL"
        if (owner.startsWith("org/jooq/") && (call.name.startsWith("fetch") || call.name in setOf("execute", "batch", "query", "resultQuery"))) return "JOOQ"
        return null
    }

    private fun annotationValues(annotation: AnnotationNode): Map<String, Any?> = annotation.values.orEmpty().chunked(2).associate { it[0] as String to it[1] }

    private const val SPRING_NATIVE_QUERY = "Lorg/springframework/data/jpa/repository/NativeQuery;"
    private val SPRING_QUERY_ANNOTATIONS = setOf("Lorg/springframework/data/jpa/repository/Query;", SPRING_NATIVE_QUERY)

    /** Read annotation and entity metadata from the effective application loader without initializing classes. */
    private class MetadataInventory(private val scanned: Map<String, ClassNode>, private val loader: ClassLoader,
                                    private val diagnostics: ExtractionDiagnostics) {
        private val external = mutableMapOf<String, ClassNode?>()
        private fun node(name: String): ClassNode? = scanned[name] ?: external.getOrPut(name) {
            try {
                loader.getResourceAsStream("$name.class")?.use { input ->
                    ClassNode().also { ClassReader(input).accept(it, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
                }
            } catch (error: Throwable) {
                diagnostics.capture("repository-header:${name.replace('/', '.')}", "Cannot inspect repository metadata header", error)
                null
            }
        }
        fun annotationDefaults(descriptor: String): Map<String, Any?>? =
            node(descriptor.removePrefix("L").removeSuffix(";"))?.methods?.filter { it.annotationDefault != null }
                ?.associate { it.name to it.annotationDefault }

        fun isQueryExtension(descriptor: String, unreadableMayBeQuery: Boolean,
                             seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (descriptor in SPRING_QUERY_ANNOTATIONS || descriptor == "Lorg/springframework/data/jpa/repository/query/Procedure;") return true
            if (!seen.add(descriptor) || descriptor.startsWith("Ljava/lang/annotation/") || descriptor.startsWith("Lkotlin/")) return false
            // A missing HTTP/cache annotation is not evidence of database access. On a known Spring
            // repository it can still change query lookup, so it must block fabricated derivation.
            // Readable composed query annotations remain recognized on every scanned class.
            val type = node(descriptor.removePrefix("L").removeSuffix(";")) ?: return unreadableMayBeQuery
            return (type.visibleAnnotations.orEmpty() + type.invisibleAnnotations.orEmpty()).any {
                isQueryExtension(it.desc, unreadableMayBeQuery, seen)
            }
        }

        fun namedQueries(classes: List<String>): Set<String> = buildSet {
            val candidates = scanned.values + classes.mapNotNull { node(it.replace('.', '/')) }
            fun visit(annotation: AnnotationNode) {
                val simple = annotation.desc.substringAfterLast('/').removeSuffix(";")
                if (simple in setOf("NamedQuery", "NamedNativeQuery", "NamedStoredProcedureQuery") &&
                    (annotation.desc.startsWith("Ljakarta/persistence/") || annotation.desc.startsWith("Ljavax/persistence/") ||
                        annotation.desc.startsWith("Lorg/hibernate/annotations/"))) {
                    (annotationValues(annotation)["name"] as? String)?.let(::add)
                }
                if (simple in setOf("NamedQueries", "NamedNativeQueries", "NamedStoredProcedureQueries"))
                    (annotationValues(annotation)["value"] as? List<*>)?.filterIsInstance<AnnotationNode>()?.forEach(::visit)
            }
            candidates.forEach { type -> (type.visibleAnnotations.orEmpty() + type.invisibleAnnotations.orEmpty()).forEach(::visit) }
        }

        fun namedQueryProperties(): Set<String> = buildSet {
            val resources = loader.getResources("META-INF/jpa-named-queries.properties")
            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                try { resource.openStream().use { input -> addAll(Properties().apply { load(input) }.stringPropertyNames()) } }
                catch (error: Throwable) {
                    diagnostics.capture("repository-named-queries:$resource", "Cannot inspect named query properties", error)
                    add("*")
                }
            }
        }
    }

    private fun isSpringRepository(node: ClassNode, nodes: Map<String, ClassNode>, loader: ClassLoader, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (!seen.add(node.name)) return false
        if (node.interfaces.any { it.startsWith("org/springframework/data/") && it.endsWith("Repository") }) return true
        return node.interfaces.any { parent -> nodes[parent]?.let { isSpringRepository(it, nodes, loader, seen) } ?: runCatching {
            Class.forName("org.springframework.data.repository.Repository", false, loader).isAssignableFrom(Class.forName(parent.replace('/', '.'), false, loader))
        }.getOrDefault(false) }
    }

    private fun repositoryEntity(node: ClassNode, nodes: Map<String, ClassNode>, loader: ClassLoader): Class<*>? =
        genericEntity(node, loader) ?: node.interfaces.firstNotNullOfOrNull { nodes[it]?.let { parent -> genericEntity(parent, loader) } }

    /** Concrete first generic arguments only; type-variable substitution remains explicitly unresolved. */
    private fun genericEntity(node: ClassNode, loader: ClassLoader): Class<*>? {
        val signature = node.signature ?: return null
        val names = Regex("<(?:\\+)?L([^;<]+);").findAll(signature).map { it.groupValues[1] }
        return names.firstNotNullOfOrNull { name -> runCatching { Class.forName(name.replace('/', '.'), false, loader) }.getOrNull()
            ?.takeIf { type -> type.annotations.any { it.annotationClass.java.name in setOf("jakarta.persistence.Entity", "javax.persistence.Entity") } } }
    }

    private fun hasPanacheHierarchy(node: ClassNode?, nodes: Map<String, ClassNode>, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (node == null || !seen.add(node.name)) return false
        return (node.interfaces + listOfNotNull(node.superName)).any {
            it.startsWith("io/quarkus/hibernate/orm/panache/") || hasPanacheHierarchy(nodes[it], nodes, seen)
        }
    }

    private fun panacheEntity(caller: ClassNode, calledOwner: String, nodes: Map<String, ClassNode>, loader: ClassLoader): Class<*>? {
        val target = nodes[calledOwner]
        if (target != null && (target.visibleAnnotations.orEmpty() + target.invisibleAnnotations.orEmpty()).any { it.desc == "Ljakarta/persistence/Entity;" })
            return runCatching { Class.forName(target.name.replace('/', '.'), false, loader) }.getOrNull()
        return genericEntity(caller, loader)
    }

    private fun expandPanache(method: String, entity: Class<*>, query: String, loader: ClassLoader): Result<String> = runCatching {
        require(!query.startsWith("#")) { "Panache named-query indirection requires explicit named-query registration" }
        require(!query.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) { "Single-property Panache shorthand depends on runtime parameter cardinality; declare its finite expanded query" }
        val helper = Class.forName("io.quarkus.panache.hibernate.common.runtime.PanacheJpaUtil", false, loader)
        val helperName = when (method) { "count" -> "createCountQuery"; "update" -> "createUpdateQuery"; "delete", "deleteAll" -> "createDeleteQuery"; else -> "createFindQuery" }
        val expansion = helper.methods.singleOrNull { it.name == helperName && it.parameterTypes.toList() == listOf(Class::class.java, String::class.java, Int::class.javaPrimitiveType) }
            ?: error("Supplied Panache runtime has no supported $helperName(Class,String,int) expansion API")
        expansion.invoke(null, entity, query, 0) as String
    }

    /** Spring owns parsing of names/property paths; only the explicit finite entity-selection subset is rendered. */
    private fun deriveSpringQuery(methodName: String, entity: Class<*>, loader: ClassLoader): Result<String> = runCatching {
        val parser = Class.forName("org.springframework.data.repository.query.parser.PartTree", false, loader)
        val tree = parser.getConstructor(String::class.java, Class::class.java).newInstance(methodName, entity)
        fun invoke(target: Any, name: String) = target.javaClass.getMethod(name).invoke(target)
        require(invoke(tree, "isExistsProjection") != true) { "Spring exists projection uses runtime limit/result mapping; its variants are unsupported" }
        require(invoke(tree, "isLimiting") != true) { "Spring Top/First query variants require explicit pagination evidence" }
        val entityName = entity.annotations.firstOrNull { it.annotationClass.java.name in setOf("jakarta.persistence.Entity", "javax.persistence.Entity") }
            ?.let { it.annotationClass.java.getMethod("name").invoke(it) as? String }?.takeIf(String::isNotBlank) ?: entity.simpleName
        var parameter = 0
        fun bind() = "?${++parameter}"
        val disjunctions = (tree as Iterable<*>).map { group ->
            (group as Iterable<*>).joinToString(" and ", "(", ")") { item ->
                val part = requireNotNull(item)
                require(invoke(part, "shouldIgnoreCase").toString() == "NEVER") { "Case-folding Spring derived queries require explicit query translation" }
                val property = invoke(part, "getProperty")!!
                val path = property.javaClass.getMethod("toDotPath").invoke(property) as String
                require(!path.contains('.')) { "Spring nested property joins require the framework's exact join/selection variants; declare their generated HQL" }
                val propertyType = property.javaClass.getMethod("getType").invoke(property) as Class<*>
                require(!Collection::class.java.isAssignableFrom(propertyType) && !Map::class.java.isAssignableFrom(propertyType)) {
                    "Spring collection-valued derived properties require explicit cardinality and join variants"
                }
                val column = "e.$path"
                when ((invoke(part, "getType") as Enum<*>).name) {
                    "SIMPLE_PROPERTY" -> "$column = ${bind()}"
                    "NEGATING_SIMPLE_PROPERTY" -> "$column <> ${bind()}"
                    "IS_NULL" -> "$column is null"
                    "IS_NOT_NULL" -> "$column is not null"
                    "TRUE" -> "$column = true"
                    "FALSE" -> "$column = false"
                    "LESS_THAN", "BEFORE" -> "$column < ${bind()}"
                    "LESS_THAN_EQUAL" -> "$column <= ${bind()}"
                    "GREATER_THAN", "AFTER" -> "$column > ${bind()}"
                    "GREATER_THAN_EQUAL" -> "$column >= ${bind()}"
                    "BETWEEN" -> "$column between ${bind()} and ${bind()}"
                    else -> error("Unsupported Spring derived predicate ${invoke(part, "getType")}; supply its exact finite HQL variants")
                }
            }
        }
        val count = invoke(tree, "isCountProjection") == true
        val delete = invoke(tree, "isDelete") == true
        require(!delete) { "Spring derived delete queries select entities and invoke entity removal callbacks; one bulk DELETE would not preserve that contract" }
        val distinct = invoke(tree, "isDistinct") == true
        require(!count || !distinct) { "Distinct-count Spring projection requires exact generated query evidence" }
        val select = if (count) "select count(e)" else "select ${if (distinct) "distinct " else ""}e"
        val sort = invoke(tree, "getSort") as Iterable<*>
        val orders = sort.map { item ->
            val order = requireNotNull(item)
            require(invoke(order, "isIgnoreCase") != true) { "Case-folding Spring sorting is unsupported" }
            val property = invoke(order, "getProperty").toString()
            require(!property.contains('.')) { "Spring nested-property sorting requires exact framework join variants" }
            "e.$property ${invoke(order, "getDirection").toString().lowercase()}"
        }
        "$select from $entityName e" + (if (disjunctions.isEmpty()) "" else " where ${disjunctions.joinToString(" or ")}") +
            (if (orders.isEmpty()) "" else " order by ${orders.joinToString(", ")}")
    }

    /** Spring's simple equality predicates select IS [NOT] NULL based on the bound argument. */
    private fun nullableSpringVariants(query: String, method: MethodNode): List<String>? {
        val parameters = Type.getArgumentTypes(method.desc)
        val predicate = Regex("(e\\.[\\p{L}\\p{N}_$.]+) (=|<>) \\?(\\d+)")
        val predicates = predicate.findAll(query).toList()
        if (predicates.isEmpty()) return listOf(query)
        if (parameters.any { it.className in setOf("org.springframework.data.domain.Pageable", "org.springframework.data.domain.Sort", "org.springframework.data.domain.ScrollPosition", "java.lang.Class") }) return null
        val nullableSlots = predicates.map { it.groupValues[3].toInt() }.distinct().filter { slot ->
            parameters.getOrNull(slot - 1)?.sort in setOf(Type.OBJECT, Type.ARRAY)
        }.sorted()
        if (nullableSlots.size > 4) return null
        return (0 until (1 shl nullableSlots.size)).map { mask ->
            val nullSlots = nullableSlots.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val expanded = predicate.replace(query) { match ->
                if (match.groupValues[3].toInt() in nullSlots)
                    "${match.groupValues[1]} is ${if (match.groupValues[2] == "<>") "not " else ""}null"
                else match.value
            }
            val binding = Regex("\\?(\\d+)")
            val slots = binding.findAll(expanded).map { it.groupValues[1].toInt() }.distinct().sorted().toList()
            binding.replace(expanded) { match -> "?${slots.indexOf(match.groupValues[1].toInt()) + 1}" }
        }.distinct()
    }
}

private data class ConstantValue(val basic: BasicValue, val constants: Set<Any>? = null, val unresolvedReason: String? = null) : Value {
    val constant: Any? get() = constants?.singleOrNull()
    override fun getSize() = basic.size
}

private data class PreparedSql(val sql: String)

private class ConstantInterpreter(private val fields: Map<String, Any>) : Interpreter<ConstantValue>(Opcodes.ASM9) {
    private val base = object : BasicInterpreter(Opcodes.ASM9) {}
    override fun newValue(type: Type?) = base.newValue(type)?.let { ConstantValue(it) }
    override fun newOperation(instruction: AbstractInsnNode): ConstantValue {
        val constant: Any? = when (instruction) {
            is LdcInsnNode -> instruction.cst.takeIf { it is String || it is Number }
            is IntInsnNode -> if (instruction.opcode in setOf(Opcodes.BIPUSH, Opcodes.SIPUSH)) instruction.operand else null
            is FieldInsnNode -> fields["${instruction.owner}.${instruction.name}:${instruction.desc}"]
            else -> when (instruction.opcode) {
                in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> instruction.opcode - Opcodes.ICONST_0
                Opcodes.LCONST_0 -> 0L
                Opcodes.LCONST_1 -> 1L
                else -> null
            }
        }
        return ConstantValue(base.newOperation(instruction), constant?.let(::setOf))
    }
    override fun copyOperation(instruction: AbstractInsnNode, value: ConstantValue) = value
    override fun unaryOperation(instruction: AbstractInsnNode, value: ConstantValue) =
        base.unaryOperation(instruction, value.basic)?.let {
            if (instruction.opcode == Opcodes.CHECKCAST) ConstantValue(it, value.constants, value.unresolvedReason) else ConstantValue(it)
        }
    override fun binaryOperation(instruction: AbstractInsnNode, left: ConstantValue, right: ConstantValue) =
        base.binaryOperation(instruction, left.basic, right.basic)?.let { ConstantValue(it) }
    override fun ternaryOperation(instruction: AbstractInsnNode, a: ConstantValue, b: ConstantValue, c: ConstantValue) =
        base.ternaryOperation(instruction, a.basic, b.basic, c.basic)?.let { ConstantValue(it) }
    override fun naryOperation(instruction: AbstractInsnNode, values: MutableList<out ConstantValue>): ConstantValue? {
        val basic = base.naryOperation(instruction, values.map { it.basic }) ?: return null
        val evaluated: Evaluation = when {
            instruction is MethodInsnNode && instruction.owner == "java/sql/Connection" && instruction.name == "prepareStatement" ->
                evaluate(values.drop(1).take(1)) { (it.singleOrNull() as? String)?.let(::PreparedSql) }
            instruction is InvokeDynamicInsnNode && instruction.bsm.owner == "java/lang/invoke/StringConcatFactory" ->
                evaluate(values) { concatenate(instruction, it) }
            instruction is MethodInsnNode && instruction.owner == "java/lang/String" && instruction.name == "concat" && values.size == 2 ->
                evaluate(values) { parts -> (parts[0] as? String)?.let { left -> (parts[1] as? String)?.let { left + it } } }
            instruction is MethodInsnNode && instruction.owner == "java/lang/String" && instruction.name == "valueOf" && values.size == 1 ->
                evaluate(values) { parts -> val value = parts.single(); when (Type.getArgumentTypes(instruction.desc).single().sort) {
                    Type.BOOLEAN -> (value as? Number)?.let { (it.toInt() != 0).toString() }
                    Type.CHAR -> (value as? Number)?.toInt()?.toChar()?.toString()
                    else -> value.toString()
                } }
            instruction is MethodInsnNode && instruction.owner in setOf("kotlin/text/StringsKt", "kotlin/text/StringsKt__IndentKt") ->
                normalizeKotlin(instruction, values)
            else -> Evaluation()
        }
        return ConstantValue(basic, evaluated.constants, evaluated.reason)
    }
    override fun returnOperation(instruction: AbstractInsnNode, value: ConstantValue, expected: ConstantValue) = base.returnOperation(instruction, value.basic, expected.basic)
    override fun merge(left: ConstantValue, right: ConstantValue): ConstantValue {
        if (left == right) return left
        val basic = base.merge(left.basic, right.basic)
        if (left.constants == null || right.constants == null)
            return ConstantValue(basic, unresolvedReason = listOfNotNull(left.unresolvedReason, right.unresolvedReason).sorted().firstOrNull())
        val combined = left.constants + right.constants
        return if (combined.size <= MAX_VARIANTS) ConstantValue(basic, combined)
        else ConstantValue(basic, unresolvedReason = "Query string alternatives exceed the $MAX_VARIANTS-variant bytecode bound")
    }

    private data class Evaluation(val constants: Set<Any>? = null, val reason: String? = null)

    /** Do not invent Cartesian products whose branch correlations have been lost by frame merging. */
    private fun evaluate(values: List<ConstantValue>, operation: (List<Any>) -> Any?): Evaluation {
        if (values.any { it.constants == null }) return Evaluation(reason = values.mapNotNull { it.unresolvedReason }.sorted().firstOrNull())
        if (values.count { it.constants!!.size > 1 } > 1)
            return Evaluation(reason = "Multiple finite string operands require correlated branch analysis; their Cartesian product is not asserted as executable SQL")
        var arguments = listOf(emptyList<Any>())
        for (value in values) arguments = arguments.flatMap { prefix -> value.constants!!.map { prefix + it } }
        val results = linkedSetOf<Any>()
        for (args in arguments) {
            val result = runCatching { operation(args) }.getOrNull() ?: return Evaluation()
            results += result
        }
        return Evaluation(results)
    }

    private fun normalizeKotlin(instruction: MethodInsnNode, values: List<ConstantValue>): Evaluation = when {
        instruction.name == "trimIndent" && instruction.desc == "(Ljava/lang/String;)Ljava/lang/String;" ->
            evaluate(values) { (it.single() as? String)?.trimIndent() }
        instruction.name == "trimMargin" && instruction.desc == "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;" ->
            evaluate(values) { parts -> (parts[0] as? String)?.let { text -> (parts[1] as? String)?.let(text::trimMargin) } }
        instruction.name == "trimMargin\$default" && instruction.desc == "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;" -> {
            val mask = values.getOrNull(2)?.constant as? Int
            when {
                mask == null -> Evaluation()
                mask and 1 != 0 -> evaluate(values.take(1)) { (it.single() as? String)?.trimMargin() }
                else -> evaluate(values.take(2)) { parts -> (parts[0] as? String)?.let { text -> (parts[1] as? String)?.let(text::trimMargin) } }
            }
        }
        else -> Evaluation()
    }

    private fun concatenate(instruction: InvokeDynamicInsnNode, values: List<Any>): String? {
        val argumentTypes = Type.getArgumentTypes(instruction.desc)
        fun value(index: Int): String = when (argumentTypes[index].sort) {
            Type.BOOLEAN -> ((values[index] as Number).toInt() != 0).toString()
            Type.CHAR -> ((values[index] as Number).toInt().toChar()).toString()
            else -> values[index].toString()
        }
        if (instruction.bsm.name == "makeConcat") return values.indices.joinToString("") { value(it) }
        if (instruction.bsm.name != "makeConcatWithConstants") return null
        val recipe = instruction.bsmArgs.firstOrNull() as? String ?: return null
        var argumentIndex = 0
        var constantIndex = 1
        return buildString {
            for (character in recipe) when (character) {
                '\u0001' -> append(value(argumentIndex++))
                '\u0002' -> append(instruction.bsmArgs[constantIndex++])
                else -> append(character)
            }
        }
    }

    companion object { private const val MAX_VARIANTS = 16 }
}
