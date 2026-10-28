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
    val apiInventory: DatabaseApiInventory? = null,
    val hqlQueryPagination: Map<String, QueryPagination> = emptyMap(),
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
        val failedMethodSources = sortedSetOf<String>()
        var inspectedInvocations = 0
        var recognizedInvocations = 0
        val notes = mutableListOf<String>()
        val paths = mutableListOf<AccessPathCoverage>()
        val apiInvocations = mutableListOf<DatabaseApiInvocation>()
        val methodReferenceIds = sortedSetOf<String>()
        val ownerGaps = sortedMapOf<String, MutableSet<String>>()
        val ownerGapReasons = sortedMapOf<String, MutableSet<String>>()
        val hql = sortedMapOf<String, String>()
        val hqlPagination = sortedMapOf<String, QueryPagination>()
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
        val headers = RepositoryHeaders(nodes, classpath.map { Path.of(it) }, loader, IsolatedHibernateRuntime::sharedClass, failure = { source, reason, error ->
            if (error != null) diagnostics.capture(source, reason, error)
            else unresolved += UnresolvedQuery(source, reason)
        })
        val classifier = DatabaseApiClassifier(headers)
        val domains = RepositoryDomains(headers)
        val metadata = MetadataInventory(nodes, loader, diagnostics, headers)
        val namedQueries = metadata.namedQueries(entityClasses)
        val propertyQueries = metadata.namedQueryProperties()
        val xmlMappingsPresent = loader.getResources("META-INF/orm.xml").hasMoreElements()
        for (owner in nodes.values) {
            val className = owner.name.replace('/', '.')
            val hierarchy = headers.hierarchy(owner.name)
            if (hierarchy.gaps.isNotEmpty()) {
                ownerGaps.getOrPut(owner.name) { sortedSetOf() } += "$className#<hierarchy>"
                ownerGapReasons.getOrPut(owner.name) { sortedSetOf() }.addAll(hierarchy.gaps)
            }
            val repository = hierarchy.nodes.values.any { node ->
                node.name == "org/springframework/data/repository/Repository" || "org/springframework/data/repository/Repository" in node.interfaces
            }
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
                    val entity = runCatching { domains.resolve(RepositoryType.ClassType(owner.name)).className
                        ?.let { Class.forName(it.replace('/', '.'), false, loader) }?.takeIf { type -> type.annotations.any {
                            it.annotationClass.java.name in setOf("jakarta.persistence.Entity", "javax.persistence.Entity") } } }.getOrNull()
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
                // A direct API method reference hides its future invocation in a bootstrap handle.
                // Inventory it as a located unexpanded access path, not as an ordinary executed call.
                var methodReferenceLine: Int? = null
                method.instructions.toArray().forEachIndexed { index, instruction ->
                    if (instruction is LineNumberNode) methodReferenceLine = instruction.line
                    if (instruction is InvokeDynamicInsnNode && instruction.bsm.owner == "java/lang/invoke/LambdaMetafactory") {
                        instruction.bsmArgs.forEachIndexed handles@{ handleIndex, argument ->
                            val handle = argument as? org.objectweb.asm.Handle ?: return@handles
                            val opcode = when (handle.tag) {
                                Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC
                                Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL
                                Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE
                                Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL
                                else -> return@handles
                            }
                            val id = "$className#$methodName:method-reference:$index:$handleIndex"
                            val resolved = try { classifier.classify(MethodInsnNode(opcode, handle.owner, handle.name, handle.desc, handle.isInterface)) }
                            catch (failure: Throwable) {
                                val reason = diagnostics.capture(id, "Database API method reference could not be classified", failure)
                                ApiResolution(null, listOf(reason))
                            }
                            if (resolved.call?.queryCapable == true || resolved.ancestryGaps.isNotEmpty()) {
                                methodReferenceIds += id
                                val reason = "Database API method reference ${handle.owner.replace('/', '.')}.${handle.name}${handle.desc} has no resolved invocation arguments or execution paths"
                                record(id, null, "DATABASE_METHOD_REFERENCE", reason = reason, line = methodReferenceLine)
                                ownerGaps.getOrPut(handle.owner) { sortedSetOf() } += id
                                ownerGapReasons.getOrPut(handle.owner) { sortedSetOf() }.addAll(resolved.ancestryGaps + reason)
                            }
                        }
                    }
                }
                val sites = InvocationSites.index(owner.name, method)
                val calls = mutableMapOf<MethodInsnNode, DatabaseCall>()
                fun classify(instruction: MethodInsnNode, receiver: ConstantValue? = null): DatabaseCall? {
                    val id = sites.getValue(instruction).id
                    val resolution = try { classifier.classifyReceivers(instruction, receiver?.referenceTypes) }
                    catch (failure: Throwable) {
                        val reason = diagnostics.capture(id, "Database API owner could not be classified", failure)
                        ApiResolution(null, listOf(reason))
                    }
                    if (resolution.ancestryGaps.isNotEmpty()) {
                        ownerGaps.getOrPut(instruction.owner) { sortedSetOf() } += id
                        ownerGapReasons.getOrPut(instruction.owner) { sortedSetOf() }.addAll(resolution.ancestryGaps)
                    }
                    return resolution.call
                }
                sites.keys.sortedBy { sites.getValue(it).ordinal }.forEach { instruction -> classify(instruction)?.let { calls[instruction] = it } }
                val linearControlFlow = method.tryCatchBlocks.isEmpty() && method.instructions.toArray().none {
                    it is JumpInsnNode || it is LookupSwitchInsnNode || it is TableSwitchInsnNode }
                val origins = QueryOrigins(sites, calls, linearControlFlow)
                var analysisFailure: String? = null
                val frames = try { Analyzer(ConstantInterpreter(constants, owner.name, method, headers, classifier, origins, sites)).analyze(owner.name, method) }
                catch (failure: Throwable) {
                    analysisFailure = diagnostics.capture("$className#$methodName:analysis", "Bytecode data flow could not be analyzed", failure)
                    failedMethods++
                    failedMethodSources += "$className#$methodName:analysis"
                    null
                }
                fun receiver(frame: Frame<ConstantValue>?, instruction: MethodInsnNode): ConstantValue? {
                    if (instruction.opcode == Opcodes.INVOKESTATIC) return null
                    val count = Type.getArgumentTypes(instruction.desc).size
                    return frame?.takeIf { it.stackSize > count }?.getStack(frame.stackSize - count - 1)
                }
                val neutralBindings = mutableSetOf<MethodInsnNode>()
                method.instructions.toArray().forEachIndexed { index, instruction ->
                    val frame = frames?.get(index) ?: return@forEachIndexed
                    if (instruction is MethodInsnNode) {
                        val value = receiver(frame, instruction)
                        classify(instruction, value)?.let { calls[instruction] = it }
                        val count = Type.getArgumentTypes(instruction.desc).size
                        val values = (0 until count).mapNotNull { n -> frame.takeIf { it.stackSize >= count }?.getStack(frame.stackSize - count + n) }
                        val neutral = calls[instruction]?.role == DatabaseApiRole.BINDING &&
                            scalarBinding(instruction, values, calls[instruction]?.family)
                        if (neutral) neutralBindings += instruction
                        origins.observeCall(instruction, value?.origins, values.map { it.origins }, neutral,
                            values.singleOrNull()?.constant as? Int)
                    } else if (instruction is InvokeDynamicInsnNode) {
                        val count = Type.getArgumentTypes(instruction.desc).size
                        if (frame.stackSize >= count) (0 until count).forEach { position ->
                            origins.escape(frame.getStack(frame.stackSize - count + position).origins,
                                "$className#$methodName:dynamic-escape:$index",
                                "Query is captured by an invokedynamic call whose alias effects are not tracked")
                        }
                    } else if (instruction.opcode in setOf(Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.AASTORE, Opcodes.ARETURN) && frame.stackSize > 0) {
                        origins.escape(frame.getStack(frame.stackSize - 1).origins, "$className#$methodName:escape:$index",
                            "Query leaves local analysis through a field assignment, array store or method return")
                    }
                }
                // Factories publish first so a terminal may refer to any incoming finite origin, including loop back-edges.
                val instructions = sites.keys.sortedWith(compareBy<MethodInsnNode>(
                    { if (calls[it]?.role == DatabaseApiRole.FACTORY) 0 else 1 }, { sites.getValue(it).ordinal }))
                for (instruction in instructions) {
                    inspectedInvocations++
                    val site = sites.getValue(instruction)
                    val index = site.instructionIndex
                    val sourceLine = site.line
                    val id = site.id
                    val call = calls[instruction] ?: continue
                    recognizedInvocations++
                    val neutralBinding = instruction in neutralBindings
                    apiInvocations += DatabaseApiInvocation(id, className, methodName, site.ordinal, sourceLine,
                        instruction.owner.replace('/', '.'), instruction.name, instruction.desc, call.family, call.role,
                        call.queryCapable && !neutralBinding, unresolvedReason = call.reason)
                    if (call.role == DatabaseApiRole.LIFECYCLE_OR_METADATA || neutralBinding) continue
                    val mechanism = call.translation ?: "${call.family}_${call.role}"
                    val arguments = Type.getArgumentTypes(instruction.desc)
                    val stringArgument = arguments.indexOfFirst { it.className == "java.lang.String" }
                    val frame = frames?.get(index)
                    val queryValue = if (frame != null && stringArgument >= 0) {
                        val position = frame.stackSize - arguments.size + stringArgument
                        if (position >= 0) frame.getStack(position) else null
                    } else null
                    val queries = queryValue?.constants?.takeIf { it.all { value -> value is String } }?.map { it as String }?.sorted()
                    val receiverValue = receiver(frame, instruction)
                    val effectFailure = origins.limitations(id).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.siteId}: ${it.reason}" }
                    val queryFailure = analysisFailure ?: call.reason ?: effectFailure ?: queryValue?.unresolvedReason
                    when (mechanism) {
                        "PANACHE_TERMINAL", "QUERY_TERMINAL" -> {
                            val producerQueries = paths.associate { it.id to if (it.status != AccessPathStatus.UNRESOLVED) it.queryIds else emptyList() }
                            val linked = origins.terminal(receiverValue?.origins, producerQueries,
                                if (mechanism == "PANACHE_TERMINAL") "PANACHE_QUERY" else "ORM_QUERY")
                            if (queryFailure == null && linked.unresolved.isEmpty())
                                paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC,
                                    linked.sourceQueryIds, mechanism = mechanism, line = sourceLine)
                            else record(id, null, mechanism, reason = queryFailure ?: linked.unresolved.joinToString("; "), line = sourceLine)
                        }
                        "JPA_HQL" -> {
                            recordVariants(id, queries, mechanism, reason = queryFailure, line = sourceLine)
                            val position = paths.indexOfLast { it.id == id }
                            val path = paths[position]
                            val states = origins.paginationVariants(id)
                            if (path.status == AccessPathStatus.STATIC && states.any { !it.isEmpty() }) {
                                val variants = path.queryIds.flatMap { sourceId ->
                                    val hqlText = hql.remove(sourceId) ?: error("Missing HQL source $sourceId")
                                    states.map { state ->
                                        val variantId = if (state.isEmpty()) sourceId else sourceId + state.suffix()
                                        hql[variantId] = hqlText
                                        if (!state.isEmpty()) hqlPagination[variantId] = state
                                        variantId
                                    }
                                }.distinct().sorted()
                                paths[position] = path.copy(queryIds = variants,
                                    reason = "Enumerated ${states.size} fixed pagination states at local executions through Hibernate QueryOptions")
                            }
                        }
                        "QUERY_PAGINATION" -> {
                            val producerQueries = paths.associate { it.id to if (it.status != AccessPathStatus.UNRESOLVED) it.queryIds else emptyList() }
                            val linked = origins.terminal(receiverValue?.origins, producerQueries, "ORM_QUERY")
                            if (queryFailure == null && origins.supportsPagination(id) && linked.unresolved.isEmpty())
                                paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC,
                                    linked.sourceQueryIds, mechanism = mechanism, line = sourceLine)
                            else record(id, null, mechanism, reason = queryFailure ?: linked.unresolved.joinToString("; ")
                                .ifBlank { "Pagination is outside the supported fixed local HQL model" }, line = sourceLine)
                        }
                        "JPA_NAMED" -> if (queries != null && queries.all(String::isNotBlank) && queryFailure == null) {
                            paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.DECLARED, queries, mechanism = mechanism, line = sourceLine)
                        } else record(id, null, mechanism, reason = queryFailure, line = sourceLine)
                        "JPA_NATIVE", "JDBC_SQL" -> recordVariants(id, queries, mechanism, nativeSql = true, reason = queryFailure, line = sourceLine)
                        "JDBC_PREPARED_EXECUTION" -> {
                            val receiver = frame?.takeIf { it.stackSize > arguments.size }
                                ?.let { it.getStack(it.stackSize - arguments.size - 1).constants }
                                ?.takeIf { it.all { value -> value is PreparedSql } }?.map { it as PreparedSql }
                            val sources = receiver?.map { sql -> paths.firstOrNull { it.id == sql.originId }
                                ?.queryIds.orEmpty().filter { native[it] == sql.sql } }
                            if (!sources.isNullOrEmpty() && sources.all { it.isNotEmpty() }) paths += AccessPathCoverage(id, className, methodName, AccessPathStatus.STATIC,
                                sources.flatten().distinct().sorted(), mechanism = mechanism, line = sourceLine)
                            else record(id, null, mechanism, reason = "Prepared statement execution has no locally established constant SQL source; declare its producer's complete SQL variants", line = sourceLine)
                        }
                        "PANACHE" -> {
                            val dynamicSort = arguments.any { it.className == "io.quarkus.panache.common.Sort" }
                            if (dynamicSort || effectFailure != null || call.reason != null) {
                                record(id, null, "PANACHE_VARIANTS", reason = if (dynamicSort)
                                    "Panache Sort arguments generate ordering/column dependencies that have not been enumerated; the unsorted base is not asserted as an executed query variant"
                                else call.reason ?: effectFailure ?: "Panache variants are unresolved", line = sourceLine)
                                continue
                            }
                            val entity = runCatching {
                                val called = headers.read(instruction.owner).node
                                val entityOwner = called?.takeIf { node -> (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
                                    .any { it.desc in setOf("Ljakarta/persistence/Entity;", "Ljavax/persistence/Entity;") } }?.name
                                val domain = entityOwner ?: receiverValue?.referenceTypes?.singleOrNull()?.let { domains.resolve(it).className }
                                domain?.let { Class.forName(it.replace('/', '.'), false, loader) }
                            }.getOrElse { error -> diagnostics.capture(id, "Panache domain resolution failed", error); null }
                            val fixed = queries ?: listOf("").takeIf { arguments.isEmpty() && instruction.name in setOf("findAll", "listAll", "deleteAll", "count") }
                            val expanded = if (fixed == null) Result.failure(IllegalArgumentException("Panache query is not a compile-time constant"))
                                else if (entity == null) Result.failure(IllegalArgumentException("Panache entity type cannot be resolved from the compiled repository/entity hierarchy"))
                                else runCatching { fixed.map { expandPanache(instruction.name, entity, it, loader).getOrThrow() } }
                            recordVariants(id, expanded.getOrNull(), mechanism,
                                reason = queryFailure ?: expanded.exceptionOrNull()?.let { diagnostics.capture(id, "Panache query expansion unavailable", it) }, line = sourceLine)
                        }
                        else -> record(id, null, mechanism, reason = queryFailure ?: "$mechanism database-access call ${instruction.owner.replace('/', '.')}.${instruction.name} is not exhaustively translated; declare finite SQL/HQL variants or instrument runtime SQL", line = sourceLine)
                    }
                }
            }
        }
        ownerGaps.forEach { (owner, ids) -> unresolved += UnresolvedQuery("repository-owner:${owner.replace('/', '.')}",
            "Database API ancestry could not be established: " + ownerGapReasons[owner].orEmpty().joinToString("; "), affectedIds = ids.toList()) }
        val pathsById = paths.associateBy { it.id }
        val invocationIds = apiInvocations.map { it.id }.toSet()
        notes += "Repository scan inventory: ${nodes.size} readable classes, ${failedClasses.size} unreadable class sources, ${failedArchives.size} unreadable archives, $failedMethods methods with failed data flow; inspected $inspectedInvocations method-call instructions and recognized $recognizedInvocations database API call sites."
        notes += "API inventory includes inherited owners and known unsupported API methods; raw invocation, query-capable path and declaration counts remain distinct. Missing ancestors, reflection and unscanned bodies prevent whole-application completeness."
        return DiscoveredQueries(hql, native, (unresolved + diagnostics.unresolved()).distinct().sortedBy { it.id },
            paths.map { it.copy(reason = diagnostics.resolve(it.reason)) }.sortedBy { it.id }, notes.distinct(),
            DatabaseApiInventory(invocations = apiInvocations.map { invocation -> invocation.copy(
                accessPathIds = listOfNotNull(pathsById[invocation.id]?.id),
                unresolvedReason = pathsById[invocation.id]?.takeIf { it.status == AccessPathStatus.UNRESOLVED }?.reason ?: invocation.unresolvedReason,
            ) }.sortedBy { it.id }, selectedPackages = packages.distinct().sorted(), scannedClasses = nodes.keys.map { it.replace('/', '.') },
                failedSources = (failedClasses + failedArchives + failedMethodSources).toList(), inspectedMethodInvocations = inspectedInvocations,
                declarationAccessPathIds = paths.filter { it.id !in invocationIds && it.id !in methodReferenceIds }.map { it.id }.sorted(),
                methodReferenceAccessPathIds = methodReferenceIds.toList(),
                ownerGaps = ownerGaps.map { (owner, ids) -> DatabaseApiOwnerGap(owner.replace('/', '.'), ids.toList(), ownerGapReasons[owner].orEmpty().joinToString("; ")) },
                completeWithinDeclaredScope = scannedRoots > 0 && unscopedJars == 0 && failedClasses.isEmpty() && failedArchives.isEmpty() && failedMethods == 0 && ownerGaps.isEmpty(),
                limitations = listOf("Counts cover selected compiled bodies and known database API families; reflective/generated access and external unscanned bodies remain outside this inventory.")), hqlPagination)
    }

    private fun annotationValues(annotation: AnnotationNode): Map<String, Any?> = annotation.values.orEmpty().chunked(2).associate { it[0] as String to it[1] }

    private const val SPRING_NATIVE_QUERY = "Lorg/springframework/data/jpa/repository/NativeQuery;"
    private val SPRING_QUERY_ANNOTATIONS = setOf("Lorg/springframework/data/jpa/repository/Query;", SPRING_NATIVE_QUERY)

    /** Read annotation and entity metadata from the effective application loader without initializing classes. */
    private class MetadataInventory(private val scanned: Map<String, ClassNode>, private val loader: ClassLoader,
                                    private val diagnostics: ExtractionDiagnostics, private val headers: RepositoryHeaders) {
        private fun node(name: String): ClassNode? = headers.read(name).node

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

    /** Only established scalar bindings preserve a fixed query shape; collection/type-specific overloads remain unresolved. */
    private fun scalarBinding(call: MethodInsnNode, values: List<ConstantValue>, family: String?): Boolean {
        val arguments = Type.getArgumentTypes(call.desc)
        if (family == "JDBC" && call.name.startsWith("set") && arguments.size >= 2 && arguments.first().sort == Type.INT) return true
        if (call.name != "setParameter" || values.size != 2 || Type.getArgumentTypes(call.desc).size != 2) return false
        val value = values.last()
        if (value.basic.type?.sort in setOf(Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE)) return true
        return value.referenceTypes?.takeIf { it.isNotEmpty() }?.all { it.name in setOf("java/lang/String", "java/lang/Boolean", "java/lang/Byte",
            "java/lang/Character", "java/lang/Short", "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double",
            "java/math/BigDecimal", "java/math/BigInteger", "java/util/UUID") } == true
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

private data class ConstantValue(val basic: BasicValue, val constants: Set<Any>? = null, val unresolvedReason: String? = null,
                                 val origins: Set<QueryOrigin>? = null,
                                 val referenceTypes: Set<RepositoryType.ClassType>? = RepositoryDomains.descriptor(basic.type)) : Value {
    val constant: Any? get() = constants?.singleOrNull()
    override fun getSize() = basic.size
}

private data class PreparedSql(val sql: String, val originId: String)

private class ConstantInterpreter(private val fields: Map<String, Any>, private val owner: String, private val method: MethodNode,
                                  private val headers: RepositoryHeaders, private val classifier: DatabaseApiClassifier,
                                  private val origins: QueryOrigins, private val sites: Map<MethodInsnNode, InvocationSite>) : Interpreter<ConstantValue>(Opcodes.ASM9) {
    private val base = object : BasicInterpreter(Opcodes.ASM9) {}
    override fun newValue(type: Type?) = base.newValue(type)?.let { ConstantValue(it, referenceTypes = RepositoryDomains.descriptor(type)) }
    override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): ConstantValue {
        if (isInstanceMethod && local == 0) return ConstantValue(requireNotNull(base.newValue(type)), referenceTypes = setOf(RepositoryType.ClassType(owner)))
        val arguments = Type.getArgumentTypes(method.desc)
        var position = if (isInstanceMethod) 1 else 0
        val index = arguments.indices.firstOrNull { index -> val matches = position == local; position += arguments[index].size; matches }
        val declared = index?.let { RepositoryDomains.parameterTypes(method.signature)?.getOrNull(it) }
        val references = when (declared) { is RepositoryType.ClassType -> setOf(declared); null -> RepositoryDomains.descriptor(type); else -> null }
        return ConstantValue(requireNotNull(base.newValue(type)), referenceTypes = references)
    }
    private fun fieldTypes(instruction: FieldInsnNode): Set<RepositoryType.ClassType>? {
        val declaration = headers.fieldDeclaration(instruction.owner, instruction.name, instruction.desc)
        return RepositoryDomains.fieldType(declaration?.signature, instruction.desc)
    }
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
        val basic = base.newOperation(instruction)
        val types = when (instruction) {
            is FieldInsnNode -> fieldTypes(instruction)
            is TypeInsnNode -> RepositoryDomains.descriptor(Type.getObjectType(instruction.desc))
            is LdcInsnNode -> if (instruction.cst is String) setOf(RepositoryType.ClassType("java/lang/String")) else RepositoryDomains.descriptor(basic.type)
            else -> RepositoryDomains.descriptor(basic.type)
        }
        return ConstantValue(basic, constant?.let(::setOf), referenceTypes = types)
    }
    override fun copyOperation(instruction: AbstractInsnNode, value: ConstantValue) = value
    override fun unaryOperation(instruction: AbstractInsnNode, value: ConstantValue) =
        base.unaryOperation(instruction, value.basic)?.let {
            when {
                instruction.opcode == Opcodes.CHECKCAST -> ConstantValue(it, value.constants, value.unresolvedReason, value.origins,
                    castTypes(value, (instruction as TypeInsnNode).desc))
                instruction is FieldInsnNode && instruction.opcode == Opcodes.GETFIELD -> ConstantValue(it, referenceTypes = fieldTypes(instruction))
                else -> ConstantValue(it)
            }
        }
    private fun castTypes(value: ConstantValue, target: String): Set<RepositoryType.ClassType>? {
        val known = value.referenceTypes
        return if (!known.isNullOrEmpty() && known.all { type -> headers.hierarchy(type.name).let { it.complete && target in it.nodes } }) known
        else RepositoryDomains.descriptor(Type.getObjectType(target))
    }
    override fun binaryOperation(instruction: AbstractInsnNode, left: ConstantValue, right: ConstantValue) =
        base.binaryOperation(instruction, left.basic, right.basic)?.let { ConstantValue(it) }
    override fun ternaryOperation(instruction: AbstractInsnNode, a: ConstantValue, b: ConstantValue, c: ConstantValue) =
        base.ternaryOperation(instruction, a.basic, b.basic, c.basic)?.let { ConstantValue(it) }
    override fun naryOperation(instruction: AbstractInsnNode, values: MutableList<out ConstantValue>): ConstantValue? {
        val basic = base.naryOperation(instruction, values.map { it.basic }) ?: return null
        val called = (instruction as? MethodInsnNode)?.let { call -> classifier.classifyReceivers(call,
            values.firstOrNull()?.takeIf { call.opcode != Opcodes.INVOKESTATIC }?.referenceTypes).call }
        val produced = (instruction as? MethodInsnNode)?.let { origins.factory(it, called) }
        val references = when (instruction) {
            is MethodInsnNode -> RepositoryDomains.descriptor(Type.getReturnType(instruction.desc))
            is InvokeDynamicInsnNode -> RepositoryDomains.descriptor(Type.getReturnType(instruction.desc))
            else -> RepositoryDomains.descriptor(basic.type)
        }
        val queryOrigins = produced?.let(::setOf) ?: (instruction as? MethodInsnNode)
            ?.takeIf { origins.returnsReceiver(it) }?.let { values.firstOrNull()?.origins }
        val evaluated: Evaluation = when {
            instruction is MethodInsnNode && called?.translation == "JDBC_SQL" && instruction.name == "prepareStatement" ->
                evaluate(values.drop(1).take(1)) { (it.singleOrNull() as? String)?.let { sql -> PreparedSql(sql, sites.getValue(instruction).id) } }
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
        return ConstantValue(basic, evaluated.constants, evaluated.reason, queryOrigins, references)
    }
    override fun returnOperation(instruction: AbstractInsnNode, value: ConstantValue, expected: ConstantValue) = base.returnOperation(instruction, value.basic, expected.basic)
    override fun merge(left: ConstantValue, right: ConstantValue): ConstantValue {
        if (left == right) return left
        val basic = base.merge(left.basic, right.basic)
        val constants = if (left.constants == null || right.constants == null) null else (left.constants + right.constants).takeIf { it.size <= MAX_VARIANTS }
        val origins = if (left.origins == null || right.origins == null) null else (left.origins + right.origins).takeIf { it.size <= MAX_VARIANTS }
        val types = if (left.referenceTypes == null || right.referenceTypes == null) null else (left.referenceTypes + right.referenceTypes).takeIf { it.size <= MAX_VARIANTS }
        val exceeded = left.constants != null && right.constants != null && constants == null
        return ConstantValue(basic, constants, if (exceeded) "Query string alternatives exceed the $MAX_VARIANTS-variant bytecode bound"
            else listOfNotNull(left.unresolvedReason, right.unresolvedReason).sorted().firstOrNull(), origins, types)
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
