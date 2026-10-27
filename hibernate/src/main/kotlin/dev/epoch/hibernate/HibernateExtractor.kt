package dev.epoch.hibernate

import dev.epoch.core.*
import org.hibernate.LockOptions
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.LoadQueryInfluencers
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.query.sqm.tree.select.SqmSelectStatement
import org.hibernate.query.sql.internal.ParameterParser
import org.hibernate.query.sql.internal.ParameterRecognizerImpl
import org.hibernate.sql.ast.spi.AbstractSqlAstWalker
import org.hibernate.sql.ast.tree.expression.ColumnReference as SqlColumnReference
import org.hibernate.sql.ast.tree.expression.JdbcParameter
import org.hibernate.sql.ast.tree.expression.SelfRenderingExpression
import org.hibernate.sql.ast.tree.from.NamedTableReference
import org.hibernate.sql.ast.tree.predicate.SelfRenderingPredicate
import org.hibernate.sql.exec.spi.JdbcParameterBinder
import org.hibernate.sql.exec.spi.JdbcParameterBindings
import org.hibernate.sql.model.PreparableMutationOperation
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.JDBCType

/** Hibernate 6.6/7 adapter. No connection provider, credentials, or production execution is needed. */
class HibernateExtractor {
    fun extract(request: ExtractionRequest): DatabaseContract {
        IsolatedHibernateRuntime.extractIfRequired(request)?.let { return it }
        ClasspathInspection.checkHibernateVersion(request.classpath)
        request.entityOverrides.forEach { (name, override) ->
            require(override.operations == null || override.operations.isNotEmpty()) {
                "Entity override $name has an empty operation set; use resultMappingOnly or exclude instead"
            }
        }
        val names = (request.entityClasses + ClasspathInspection.entities(request.classpath, request.scanPackages)).distinct().sorted()
        val previousLoader = Thread.currentThread().contextClassLoader
        val loader = URLClassLoader(request.classpath.map { Path.of(it).toUri().toURL() }.toTypedArray(), previousLoader)
        try {
            Thread.currentThread().contextClassLoader = loader
            val failures = mutableListOf<UnresolvedQuery>()
            val excluded = mutableListOf<String>()
            val classes = names.mapNotNull { name ->
                if (request.entityOverrides[name]?.exclude == true) { excluded += name; return@mapNotNull null }
                try {
                    val type = Class.forName(name, false, loader)
                    if (entityOverride(request, type)?.exclude == true) { excluded += name; null } else type
                } catch (error: Throwable) {
                    BootstrapRecovery.rethrowFatal(error)
                    if (!request.keepGoing) throw error
                    failures += UnresolvedQuery(name, "Entity class could not be loaded: ${BootstrapRecovery.describe(error)}")
                    null
                }
            }
            val discovery = if (request.discoverRepositories) RepositoryDiscovery.discover(request.classpath,
                request.repositoryPackages.ifEmpty { request.scanPackages }, loader, classes.map { it.name }) else null
            val discoveredRequest = request.copy(hqlQueries = discovery?.hqlQueries.orEmpty() + request.hqlQueries,
                nativeQueries = discovery?.nativeQueries.orEmpty() + request.nativeQueries)
            val result = try { bootstrap(classes, discoveredRequest) }
            catch (error: Throwable) {
                BootstrapRecovery.rethrowFatal(error)
                if (!request.keepGoing) throw error
                BootstrapRecovery.recover(classes, discoveredRequest, error,
                    bootstrap = { bootstrap(it, discoveredRequest) },
                    bootstrapPruned = { retained, associations -> bootstrap(retained, discoveredRequest, associations) })
            }
            val observations = request.runtimeSqlFiles.flatMap { SqlCollector.read(Path.of(it)) }
            val observedQueries = mutableListOf<QueryContract>()
            val runtimeFailures = mutableListOf<UnresolvedQuery>()
            for ((sql, records) in observations.groupBy { it.sql }.toSortedMap()) {
                try {
                    val matching = result.queries.filter { it.sql == sql }.map { it.parameterTypes }.distinct().singleOrNull()
                    observedQueries += SqlCollector.query(sql).copy(parameterTypes = matching.orEmpty(),
                        observationFrames = records.flatMap { it.callSites }.distinct().sorted())
                } catch (failure: IllegalArgumentException) {
                    runtimeFailures += UnresolvedQuery("runtime.unsupported", "Observed SQL is unsupported: ${failure.message}")
                }
            }
            val unresolved = (result.unresolvedQueries + failures + discovery?.unresolved.orEmpty() + runtimeFailures)
                .distinct().sortedWith(compareBy({ it.id }, { it.reason }))
            val staticMatches = observedQueries.count { observed -> result.queries.any { it.sql == observed.sql } }
            val notes = result.coverage.notes + excluded.sorted().map { "Explicitly excluded from this contract: $it" } +
                request.classpath.filter { !java.nio.file.Files.exists(Path.of(it)) }.distinct().sorted().map { "Warning: ignored nonexistent classpath entry: $it" } +
                (discovery?.notes ?: listOf("Explicit entity/descriptor scope; repository discovery disabled")) +
                if (observations.isNotEmpty()) listOf(
                    "Runtime capture cross-check: $staticMatches distinct SQL templates match static extraction exactly; ${observedQueries.size - staticMatches} have no exact static match.",
                    "Runtime-observed SQL and call-site frames are factual observations; they do not resolve unenumerated query sources or establish exhaustive coverage.") else emptyList()
            val links = discovery?.accessPaths.orEmpty().flatMap { path -> path.queryIds.map { it to path.id } }.groupBy({ it.first }, { it.second })
            val paths = discovery?.accessPaths.orEmpty().map { path ->
                val missing = path.queryIds.filter { id -> result.queries.none { it.id == id } }
                if (missing.isNotEmpty()) path.copy(status = AccessPathStatus.UNRESOLVED,
                    reason = "Discovered query could not be translated: " + missing.joinToString { id ->
                        result.unresolvedQueries.filter { it.id == id }.joinToString { it.reason }.ifBlank { id }
                    }) else path
            }
            val runtimePaths = observedQueries.flatMap { query -> query.observationFrames.map { frame ->
                val className = frame.substringBefore('#')
                val method = frame.substringAfter('#', "unknown").substringBefore('(')
                AccessPathCoverage("runtime-frame:$frame", className, method, AccessPathStatus.OBSERVED,
                    queryIds = listOf(query.id), mechanism = "STATEMENT_INSPECTOR_STACK",
                    reason = "Observed stack frame only; method overload and exhaustive query coverage are not established",
                    line = frame.substringAfterLast(':', "").removeSuffix(")").toIntOrNull()?.takeIf { it > 0 })
            } }.groupBy { it.id }.map { (_, values) -> values.first().copy(queryIds = values.flatMap { it.queryIds }.distinct().sorted()) }
            val allUnresolved = (unresolved + paths.filter { it.status == AccessPathStatus.UNRESOLVED }.map {
                UnresolvedQuery(it.id, it.reason ?: "Discovered access path is unresolved")
            }).distinct().sortedWith(compareBy({ it.id }, { it.reason }))
            return result.copy(unresolvedQueries = allUnresolved, queries = (result.queries.map { query ->
                query.copy(accessPathIds = links[query.id].orEmpty().distinct().sorted())
            } + observedQueries).sortedBy { it.id }, accessPaths = (paths + runtimePaths).sortedBy { it.id },
                coverage = result.coverage.copy(complete = result.coverage.complete && allUnresolved.isEmpty() && observations.isEmpty(), notes = notes))
        } finally {
            Thread.currentThread().contextClassLoader = previousLoader
            loader.close()
        }
    }

    private fun bootstrap(classes: List<Class<*>>, request: ExtractionRequest,
                          prunedAssociations: Map<String, Set<String>> = emptyMap()): DatabaseContract {
        val unsafe = request.hibernateProperties.keys.filter { key ->
            key.startsWith("hibernate.connection.") || key.startsWith("jakarta.persistence.jdbc.") ||
                key.startsWith("javax.persistence.jdbc.") || key.startsWith("jakarta.persistence.schema-generation.") ||
                key.startsWith("javax.persistence.schema-generation.") || key == "hibernate.hbm2ddl.auto"
        }
        require(unsafe.isEmpty()) { "Offline extraction does not accept connection or schema-generation properties: ${unsafe.sorted().joinToString()}" }
        require(request.hibernateProperties["hibernate.dialect"].let { it == null || it == "org.hibernate.dialect.PostgreSQLDialect" }) {
            "Extraction uses PostgreSQL 16.8; a different hibernate.dialect requires a separate adapter"
        }
        val settings = mutableMapOf<String, Any>("hibernate.default_schema" to "public")
        settings.putAll(request.hibernateProperties)
        request.physicalNamingStrategy?.let { settings["hibernate.physical_naming_strategy"] = it }
        request.implicitNamingStrategy?.let { settings["hibernate.implicit_naming_strategy"] = it }
        // Apply these after application properties: extraction must not connect, mutate a schema, or invoke Bean Validation.
        settings.putAll(mapOf(
            "hibernate.dialect" to org.hibernate.dialect.PostgreSQLDialect(org.hibernate.dialect.DatabaseVersion.make(16, 8)),
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "hibernate.temp.use_jdbc_metadata_defaults" to false,
            "hibernate.hbm2ddl.auto" to "none",
            "hibernate.connection.provider_class" to "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl",
            "jakarta.persistence.validation.mode" to "none",
            "javax.persistence.validation.mode" to "none",
            "hibernate.query.startup_check" to false,
        ))
        val registry = StandardServiceRegistryBuilder().applySettings(settings).build()
        try {
            val sources = MetadataSources(registry)
            classes.forEach(sources::addAnnotatedClass)
            if (prunedAssociations.isNotEmpty()) {
                fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                val mappings = "<entity-mappings xmlns=\"https://jakarta.ee/xml/ns/persistence/orm\" version=\"3.1\">" +
                    prunedAssociations.toSortedMap().entries.joinToString("") { (name, properties) ->
                        "<entity class=\"${xml(name)}\"><attributes>" + properties.sorted().joinToString("") { property ->
                            "<transient name=\"${xml(property)}\"/>"
                        } + "</attributes></entity>"
                    } + "</entity-mappings>"
                sources.addInputStream(mappings.byteInputStream())
            }
            val factory = sources.buildMetadata().buildSessionFactory() as SessionFactoryImplementor
            try { return extractFactory(factory, request) } finally { factory.close() }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    /** For tests/adapters already bootstrapping Hibernate with all application-specific settings. */
    fun extractFactory(factory: SessionFactoryImplementor, request: ExtractionRequest): DatabaseContract {
        // Hibernate 7 changed this public compile-time constant; resolve it from the actual runtime.
        val sqlTemplate = org.hibernate.sql.Template::class.java.getField("TEMPLATE").get(null) as String
        val unresolved = request.unresolvedDynamicQueries.toSortedMap().map { UnresolvedQuery(it.key, it.value) }.toMutableList()
        val entities = mutableListOf<EntityContract>()
        val queries = mutableListOf<QueryContract>()
        val scopeNotes = mutableListOf("Hibernate runtime ${org.hibernate.Version.getVersionString()}; PostgreSQL dialect version ${factory.jdbcServices.dialect.version}.")
        val persisters = factory.mappingMetamodel.streamEntityDescriptors().toList().sortedBy { it.entityName }
        for (raw in persisters) {
            val p = raw as? AbstractEntityPersister
            if (p == null) {
                unresolved += UnresolvedQuery(raw.entityName, "Unsupported Hibernate persister ${raw.javaClass.name}")
                continue
            }
            val override = entityOverride(request, p.mappedClass)
            if (override?.resultMappingOnly == true || override?.exclude == true) {
                scopeNotes += "Entity ${p.entityName} is retained only as a query result mapping; entity CRUD/table dependencies are outside this contract."
                continue
            }
            val operations = operations(request, p)
            if (operations != Operation.entries.toSet()) scopeNotes += "Entity ${p.entityName} operations are scoped to ${operations.sortedBy { it.name }}."
            val selectables = linkedMapOf<Pair<String, String>, SelectableMapping>()
            val consume: (Int, SelectableMapping) -> Unit = consume@{ _, s ->
                if (s.selectionExpression == "{discriminator}") return@consume
                if (s.isFormula) {
                    unresolved += UnresolvedQuery(p.entityName, "Formula dependency ${s.selectionExpression} cannot be exhaustively resolved")
                    return@consume
                }
                selectables[qualified(p.physicalTableNameForMutation(s)) to identifier(s.selectionExpression)] = s
                if (s.customReadExpression?.let { it != sqlTemplate + "." + s.selectionExpression } == true || s.customWriteExpression?.let { it != "?" } == true)
                    unresolved += UnresolvedQuery(p.entityName, "Custom column transformer requires additional SQL dependency and behavioral analysis")
            }
            p.identifierMapping.forEachSelectable(consume)
            p.forEachSelectable(consume)
            // Secondary/JOINED tables have their own key columns, which do not always appear among attribute selectables.
            p.forEachMutableTable { table ->
                table.keyMapping.forEachSelectable { _, selectable ->
                    if (!selectable.isFormula) selectables.putIfAbsent(qualified(table.tableName) to identifier(selectable.selectionExpression), selectable)
                }
            }
            val primitiveColumns = mutableSetOf<Pair<String, String>>()
            p.visitAttributeMappings { attribute ->
                if (attribute.propertyAccess?.getter?.returnTypeClass?.isPrimitive == true) attribute.forEachSelectable { _, selectable ->
                    if (!selectable.isFormula) primitiveColumns += qualified(p.physicalTableNameForMutation(selectable)) to identifier(selectable.selectionExpression)
                }
            }
            p.discriminatorMapping?.takeIf { it.hasPhysicalColumn() || it.isFormula }?.forEachSelectable(consume)
            val generatedId = p.generator?.javaClass?.name?.takeUnless { it.endsWith(".Assigned") }
            val sequence = (p.generator as? org.hibernate.id.enhanced.SequenceStyleGenerator)?.databaseStructure
            val supportedGenerator = p.generator is org.hibernate.id.IdentityGenerator ||
                (sequence?.isPhysicalSequence == true && sequence.incrementSize == 1)
            if (generatedId != null && !supportedGenerator && Operation.INSERT in operations)
                unresolved += UnresolvedQuery(p.entityName, "Generated identifier strategy $generatedId has unsupported allocation semantics; only identity/serial and physical sequences with allocationSize=1 are checked")
            if ((p.entityMetamodel.isDynamicInsert && Operation.INSERT in operations) || (p.entityMetamodel.isDynamicUpdate && Operation.UPDATE in operations))
                unresolved += UnresolvedQuery(p.entityName, "Dynamic insert/update produces unenumerated SQL variants")
            if (p.hasWhereRestrictions() || p.hasFilterForLoadByKey() || factory.definedFilterNames.isNotEmpty())
                unresolved += UnresolvedQuery(p.entityName, "Hibernate filters/restrictions require explicitly enumerated configurations")
            if ((p.hasInsertGeneratedProperties() && Operation.INSERT in operations) || (p.hasUpdateGeneratedProperties() && Operation.UPDATE in operations))
                unresolved += UnresolvedQuery(p.entityName, "Database-generated property retrieval and generation semantics are not exhaustively extracted")
            if (p.optimisticLockStyle().isAllOrDirty)
                unresolved += UnresolvedQuery(p.entityName, "ALL/DIRTY optimistic locking creates runtime-dependent predicates")
            if (p.mappedClass.annotations.any { it.annotationClass.java.name in CUSTOM_SQL_ANNOTATIONS || it.annotationClass.java.name.startsWith("org.hibernate.annotations.SQL") })
                unresolved += UnresolvedQuery(p.entityName, "Custom Hibernate SQL requires a separate declared query contract")
            selectables.entries.groupBy { it.key.first }.toSortedMap().forEach { (table, entries) ->
                entities += EntityContract(
                    id = if (selectables.keys.map { it.first }.distinct().size == 1) p.entityName else "${p.entityName}@$table",
                    table = table,
                    columns = entries.map { (key, s) -> ColumnContract(
                        name = key.second,
                        typeName = jdbcName(s.jdbcMapping),
                        nullable = s.isNullable && key !in primitiveColumns,
                        insertable = s.isInsertable,
                        updatable = s.isUpdateable && key.second !in p.identifierColumnNames.map(::identifier),
                        generated = p.generator is org.hibernate.id.IdentityGenerator && table == qualified(p.identifierTableMapping.tableName) && key.second in p.identifierColumnNames.map(::identifier),
                        length = s.length, precision = s.precision, scale = s.scale,
                        enumValues = enumValues(s.jdbcMapping),
                        logicalJavaType = logicalType(s.jdbcMapping).name,
                        inputDomainComplete = inputDomainComplete(s.jdbcMapping),
                    ) }.sortedBy { it.name },
                    operations = operations,
                    generatedIdentifier = generatedId,
                    identifierSequence = sequence?.takeIf { it.isPhysicalSequence }?.physicalName?.render()?.let {
                        SqlDependencies.qualified(it, request.hibernateProperties["hibernate.default_schema"] ?: "public")
                    },
                    identifierAllocationSize = sequence?.incrementSize,
                )
            }
        }
        for (raw in persisters) {
            val p = raw as? AbstractEntityPersister ?: continue
            val override = entityOverride(request, p.mappedClass)
            if (override?.resultMappingOnly == true || override?.exclude == true) continue
            val operations = operations(request, p)
            fun capture(id: String, operation: Operation, sql: String, tables: List<String>, binders: List<JdbcParameterBinder>) {
                val deps = SqlDependencies.inspect(sql, request.hibernateProperties["hibernate.default_schema"] ?: "public")
                queries += QueryContract(id, operation, sql, deps.tables.ifEmpty { tables }.distinct().sorted(), deps.columns, deps.writes,
                    parameterTypes = parameterTypes(binders), source = QuerySource.HIBERNATE_MAPPING,
                    dependenciesComplete = deps.complete, explicitColumnList = true,
                    targetTable = deps.target)
                if (!deps.complete) unresolved += UnresolvedQuery(id, "Generated SQL has an unsupported dependency expression or unresolved table alias")
            }
            if (Operation.SELECT in operations) try {
                val influencers = LoadQueryInfluencers(factory)
                val loader = SingleIdEntityLoaderStandardImpl<Any>(p, influencers)
                val resolve = loader.javaClass.methods.single { it.name == "resolveLoadPlan" }
                val plan = (if (resolve.parameterCount == 2) resolve.invoke(loader, LockOptions.NONE, influencers)
                    else resolve.invoke(loader, LockOptions.NONE, influencers, factory)) as org.hibernate.loader.ast.internal.SingleIdLoadPlan<*>
                capture("${p.entityName}.findById", Operation.SELECT, plan.jdbcSelect.sqlString,
                    plan.jdbcSelect.affectedTableNames.map(::qualified), plan.jdbcSelect.parameterBinders)
            } catch (e: ReflectiveOperationException) {
                unresolved += UnresolvedQuery("${p.entityName}.findById", "Hibernate loader SPI accessor failed: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: RuntimeException) {
                unresolved += UnresolvedQuery("${p.entityName}.findById", "Hibernate loader translation failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            val coordinators = listOf(Operation.INSERT to p.insertCoordinator, Operation.UPDATE to p.updateCoordinator, Operation.DELETE to p.deleteCoordinator)
            for ((operation, coordinator) in coordinators) {
                if (operation !in operations) continue
                val group = coordinator?.staticMutationOperationGroup
                if (group == null || group.numberOfOperations == 0) {
                    unresolved += UnresolvedQuery("${p.entityName}.$operation", "No exhaustive static Hibernate mutation plan is available")
                    continue
                }
                for (i in 0 until group.numberOfOperations) {
                    val mutation = group.getOperation(i) as? PreparableMutationOperation
                    if (mutation == null || mutation.isCallable) {
                        unresolved += UnresolvedQuery("${p.entityName}.$operation.$i", "Non-preparable or callable mutation is unsupported")
                    } else capture("${p.entityName}.${operation.name.lowercase()}.$i", operation, mutation.sqlString,
                        listOf(qualified(mutation.tableDetails.tableName)), mutation.parameterBinders)
                }
            }
        }
        extractCollections(factory, request, entities, queries, unresolved)
        val session = factory.openSession()
        try {
            fun hql(id: String, text: String, source: QuerySource, variant: String? = null) {
                try {
                    val q = session.createQuery(text)
                    // QuerySqmImpl was renamed in 7; bridge the stable runtime accessors without binding to its implementation class.
                    fun property(name: String): Any = q.javaClass.getMethod(name).invoke(q)
                    val sqm = property("getSqmStatement")
                    val options = property("getQueryOptions") as org.hibernate.query.spi.QueryOptions
                    val parameters = property("getParameterBindings") as org.hibernate.query.spi.QueryParameterBindings
                    val xref = property("getDomainParameterXref") as org.hibernate.query.sqm.internal.DomainParameterXref
                    val influencers = property("getLoadQueryInfluencers") as LoadQueryInfluencers
                    val creationContext = if (org.hibernate.Version.getVersionString().startsWith("7."))
                        factory.javaClass.getMethod("getSqlTranslationEngine").invoke(factory) as org.hibernate.sql.ast.spi.SqlAstCreationContext
                    else factory as org.hibernate.sql.ast.spi.SqlAstCreationContext
                    val translatorFactory = factory.queryEngine.sqmTranslatorFactory
                    val ast = when (sqm) {
                        is SqmSelectStatement<*> -> translatorFactory.createSelectTranslator(sqm, options, xref, parameters,
                            influencers, creationContext, true).translate().sqlAst
                        is org.hibernate.query.sqm.tree.SqmDmlStatement<*> -> translatorFactory.createMutationTranslator(sqm,
                            options, xref, parameters, influencers, creationContext).translate().sqlAst
                        else -> error("Unsupported Hibernate SQM statement ${sqm.javaClass.name}")
                    }
                    val translator = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory
                    val jdbc = when (ast) {
                        is org.hibernate.sql.ast.tree.select.SelectStatement -> translator.buildSelectTranslator(factory, ast)
                            .translate(JdbcParameterBindings.NO_BINDINGS, options)
                        is org.hibernate.sql.ast.tree.MutationStatement -> translator.buildMutationTranslator(factory, ast)
                            .translate(JdbcParameterBindings.NO_BINDINGS, options)
                        else -> error("Unsupported Hibernate SQL AST ${ast.javaClass.name}")
                    }
                    val deps = SqlDependencies.inspect(jdbc.sqlString, request.hibernateProperties["hibernate.default_schema"] ?: "public")
                    val astDependencies = DependencyWalker()
                    ast.accept(astDependencies)
                    val complete = deps.complete && astDependencies.complete
                    queries += QueryContract(id, SqlOperationClassifier.operation(jdbc.sqlString), jdbc.sqlString,
                        tables = deps.tables, columns = deps.columns, writeColumns = deps.writes,
                        parameterTypes = parameterTypes(jdbc.parameterBinders), source = source,
                        dependenciesComplete = complete, variant = variant, targetTable = deps.target)
                    if (!complete) unresolved += UnresolvedQuery(id, "HQL includes opaque SQL expressions or unresolved table aliases")
                    val metadata = property("getParameterMetadata") as org.hibernate.query.spi.ParameterMetadataImplementor
                    if (metadata.registrations.any { it.allowsMultiValuedBinding() })
                        unresolved += UnresolvedQuery(id, "Collection-valued HQL parameter cardinalities must be explicitly enumerated")
                } catch (e: ReflectiveOperationException) {
                    unresolved += UnresolvedQuery(id, "Hibernate query runtime accessor is unsupported: ${e.javaClass.simpleName}: ${e.message}")
                } catch (e: RuntimeException) {
                    unresolved += UnresolvedQuery(id, "Hibernate could not translate this fixed HQL to a supported SQL template: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            val named = factory.queryEngine.namedObjectRepository
            named.visitSqmQueryMementos { hql(it.registrationName, it.hqlString, QuerySource.NAMED_HQL) }
            request.hqlQueries.toSortedMap().forEach { (id, text) -> hql(id, text, QuerySource.STATIC) }
            request.finiteHqlVariants.toSortedMap().forEach { (id, variants) ->
                if (variants.isEmpty()) unresolved += UnresolvedQuery(id, "Declared finite query source contains no variants")
                variants.distinct().sorted().forEachIndexed { i, text -> hql("$id.variant.$i", text, QuerySource.DYNAMIC_VARIANT, i.toString()) }
            }
            fun native(id: String, sql: String, source: QuerySource) {
                val recognizer = ParameterRecognizerImpl()
                try {
                    ParameterParser.parse(sql, recognizer)
                    recognizer.complete()
                    val adjusted = recognizer.adjustedSqlString
                    val dependencies = SqlDependencies.inspect(adjusted, request.hibernateProperties["hibernate.default_schema"] ?: "public", nativeSql = true)
                    queries += QueryContract(id, SqlOperationClassifier.operation(adjusted), adjusted, source = source,
                        tables = dependencies.tables, columns = dependencies.columns, writeColumns = dependencies.writes,
                        targetTable = dependencies.target,
                        parameterTypes = request.nativeQueryParameterTypes[id] ?: List((recognizer.javaClass.getMethod("getParameterList").invoke(recognizer) as? List<*>)?.size ?: 0) { "OTHER" },
                        dependenciesComplete = dependencies.complete, explicitColumnList = dependencies.explicitColumnList)
                    if (!dependencies.complete) unresolved += UnresolvedQuery(id,
                        "Native SQL is retained for PostgreSQL preparation, but wildcard/nested/function/conflict variants or ambiguous alias dependencies are not complete; declare a supported explicit-column template or retain this unresolved access path")
                } catch (e: Exception) {
                    unresolved += UnresolvedQuery(id, "Native SQL parsing/classification failed: ${e.message}")
                }
            }
            named.visitNativeQueryMementos { native(it.registrationName, it.sqlString, QuerySource.NAMED_NATIVE) }
            request.nativeQueries.toSortedMap().forEach { (id, text) -> native(id, text, QuerySource.STATIC) }
            named.visitCallableQueryMementos { unresolved += UnresolvedQuery(it.registrationName, "Stored procedures are unsupported") }
        } finally { session.close() }
        return DatabaseContract(applicationVersion = request.applicationVersion, entities = entities.sortedBy { it.id },
            queries = queries.sortedBy { it.id }, coverage = Coverage(complete = unresolved.isEmpty(),
                mechanisms = listOf("Hibernate ${org.hibernate.Version.getVersionString()} runtime mapping metamodel", "Hibernate static mutation plans", "Hibernate single-id loader SQL", "Hibernate SQM/SQL AST translation"),
                notes = listOf("Scoped to extracted entity operations, supplied query definitions, and inventoried access paths; unsupported or unenumerated paths are unresolved. This does not prove whole-application behavior.",
                    "Generated SQL covers the default single-id loader and static entity mutations only; entity graphs, alternate lock modes, batch loaders, pagination, and repository CRUD query variants are not enumerated.",
                    "Finite variants are user-declared exhaustive sets, not inferred application coverage.",
                    "Identity/serial and non-pooled physical sequence dependencies are checked; pooled/custom allocation, dynamic mutations, filters, custom SQL, formulas and native semantics remain unsupported when present.") + scopeNotes),
            unresolvedQueries = unresolved.distinct().sortedWith(compareBy({ it.id }, { it.reason })))
    }

    private fun extractCollections(factory: SessionFactoryImplementor, request: ExtractionRequest,
                                   entities: MutableList<EntityContract>, queries: MutableList<QueryContract>,
                                   unresolved: MutableList<UnresolvedQuery>) {
        for (raw in factory.mappingMetamodel.streamCollectionDescriptors().toList().sortedBy { it.role }) {
            val p = raw as? org.hibernate.persister.collection.BasicCollectionPersister
            val owner = raw.ownerEntityPersister as? AbstractEntityPersister
            if (owner != null && entityOverride(request, owner.mappedClass)?.let { it.exclude || it.resultMappingOnly } == true) continue
            if (p == null) {
                unresolved += UnresolvedQuery(raw.role, "One-to-many/custom collection persister variants are not yet extracted")
                continue
            }
            val role = p.role
            val table = qualified(p.tableName)
            val selectables = linkedMapOf<String, SelectableMapping>()
            val consumer: (Int, SelectableMapping) -> Unit = { _, selectable ->
                if (selectable.isFormula) unresolved += UnresolvedQuery(role, "Collection formula dependency is unsupported")
                else if (qualified(selectable.containingTableExpression) == table)
                    selectables[identifier(selectable.selectionExpression)] = selectable
            }
            p.attributeMapping.keyDescriptor.visitKeySelectables(consumer)
            p.attributeMapping.elementDescriptor.forEachSelectable(consumer)
            p.attributeMapping.indexDescriptor?.forEachSelectable(consumer)
            if (p.hasWhereRestrictions() || factory.definedFilterNames.isNotEmpty())
                unresolved += UnresolvedQuery(role, "Collection filters/restrictions require explicit configuration variants")
            val collectionQueries = mutableListOf<QueryContract>()
            fun capture(suffix: String, operation: Operation, sql: String, binders: List<JdbcParameterBinder>) {
                val deps = SqlDependencies.inspect(sql, request.hibernateProperties["hibernate.default_schema"] ?: "public")
                collectionQueries += QueryContract("$role.$suffix", operation, sql, deps.tables, deps.columns, deps.writes,
                    parameterTypes = parameterTypes(binders), source = QuerySource.HIBERNATE_MAPPING,
                    dependenciesComplete = deps.complete, targetTable = deps.target)
                if (!deps.complete) unresolved += UnresolvedQuery("$role.$suffix", "Collection SQL dependency resolution is incomplete")
            }
            try {
                val loader = org.hibernate.loader.ast.internal.CollectionLoaderSingleKey(p.attributeMapping, LoadQueryInfluencers(factory), factory)
                val jdbc = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory.buildSelectTranslator(factory, loader.sqlAst)
                    .translate(JdbcParameterBindings.NO_BINDINGS, org.hibernate.query.spi.QueryOptions.NONE)
                capture("load", Operation.SELECT, jdbc.sqlString, jdbc.parameterBinders)
                val writable = p.isMutable && (owner == null || operations(request, owner).any { it != Operation.SELECT })
                if (writable && !p.isInverse) {
                    // This protected SPI is the source of Hibernate's actual row mutations; no SQL is synthesized.
                    val method = org.hibernate.persister.collection.BasicCollectionPersister::class.java.getDeclaredMethod("getRowMutationOperations")
                    method.isAccessible = true
                    val rows = method.invoke(p) as org.hibernate.persister.collection.mutation.RowMutationOperations
                    val mutations = listOf(
                        Triple("insert", Operation.INSERT, if (rows.hasInsertRow()) rows.insertRowOperation else null),
                        Triple("update", Operation.UPDATE, if (rows.hasUpdateRow()) rows.updateRowOperation else null),
                        Triple("delete", Operation.DELETE, if (rows.hasDeleteRow()) rows.deleteRowOperation else null),
                    )
                    mutations.forEach { (suffix, operation, mutation) ->
                        if (mutation != null) {
                            if (mutation.isCallable) unresolved += UnresolvedQuery("$role.$suffix", "Callable collection mutation is unsupported")
                            else capture(suffix, operation, mutation.sqlString, mutation.parameterBinders)
                        }
                    }
                    if (p.needsRemove()) {
                        val ast = p.generateDeleteAllAst(org.hibernate.sql.model.ast.MutatingTableReference(p.collectionTableMapping))
                        val mutation = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory.buildModelMutationTranslator(ast, factory)
                            .translate(JdbcParameterBindings.NO_BINDINGS, org.hibernate.query.spi.QueryOptions.NONE)
                        capture("deleteAll", Operation.DELETE, mutation.sqlString, mutation.parameterBinders)
                    }
                }
            } catch (failure: ReflectiveOperationException) {
                unresolved += UnresolvedQuery(role, "Collection mutation SPI could not be accessed: ${BootstrapRecovery.describe(failure)}")
            } catch (failure: RuntimeException) {
                unresolved += UnresolvedQuery(role, "Collection SQL translation failed: ${BootstrapRecovery.describe(failure)}")
            }
            queries += collectionQueries
            val observedColumns = collectionQueries.flatMap { it.columns }.filter { it.table == table }.map { it.column }.toSet()
            if (observedColumns.any { it !in selectables }) unresolved += UnresolvedQuery(role,
                "Collection SQL includes columns without complete selectable metadata: ${(observedColumns - selectables.keys).sorted()}")
            entities += EntityContract("$role@collection", table, selectables.map { (name, s) ->
                ColumnContract(name, jdbcName(s.jdbcMapping), s.isNullable, s.isInsertable, s.isUpdateable,
                    length = s.length, precision = s.precision, scale = s.scale, enumValues = enumValues(s.jdbcMapping),
                    logicalJavaType = logicalType(s.jdbcMapping).name, inputDomainComplete = inputDomainComplete(s.jdbcMapping))
            }.sortedBy { it.name }, collectionQueries.map { it.operation }.toSet().ifEmpty { setOf(Operation.SELECT) })
        }
    }

    private class DependencyWalker : AbstractSqlAstWalker() {
        val tables = linkedMapOf<String, String>()
        val columns = linkedSetOf<Pair<String, String>>()
        var complete = true
        override fun visitNamedTableReference(tableReference: NamedTableReference) {
            val key = tableReference.identificationVariable ?: tableReference.tableExpression
            val value = qualified(tableReference.tableExpression)
            if (tables.put(key, value)?.let { it != value } == true) complete = false
        }
        override fun visitColumnReference(columnReference: SqlColumnReference) {
            if (columnReference.isColumnExpressionFormula) complete = false
            else columns += (columnReference.qualifier ?: "") to identifier(columnReference.columnExpression)
        }
        override fun visitSelfRenderingExpression(expression: SelfRenderingExpression) { complete = false }
        override fun visitSelfRenderingPredicate(predicate: SelfRenderingPredicate) { complete = false }
    }

    companion object {
        private val CUSTOM_SQL_ANNOTATIONS = setOf("org.hibernate.annotations.SQLInsert", "org.hibernate.annotations.SQLUpdate", "org.hibernate.annotations.SQLDelete", "org.hibernate.annotations.SQLDeleteAll", "org.hibernate.annotations.Subselect", "org.hibernate.annotations.Loader")
        private fun identifier(value: String): String = SqlDependencies.identifier(value)
        private fun qualified(value: String): String = if ('.' in value) value.split('.').joinToString(".", transform = ::identifier) else "public.${identifier(value)}"
        private fun entityOverride(request: ExtractionRequest, type: Class<*>): EntityOverride? =
            request.entityOverrides[type.name] ?: request.entityOverrides[type.getAnnotation(jakarta.persistence.Entity::class.java)?.name?.ifBlank { type.simpleName } ?: type.simpleName]
        private fun operations(request: ExtractionRequest, p: AbstractEntityPersister): Set<Operation> {
            entityOverride(request, p.mappedClass)?.operations?.let { return it }
            if (!p.isMutable) return setOf(Operation.SELECT)
            val scalarUpdate = p.propertyTypes.indices.any { !p.propertyTypes[it].isCollectionType && p.propertyUpdateability[it] }
            return Operation.entries.toSet().let { if (scalarUpdate) it else it - Operation.UPDATE }
        }
        private fun jdbcName(mapping: org.hibernate.metamodel.mapping.JdbcMapping): String {
            val code = mapping.jdbcType.jdbcTypeCode
            if (code == java.sql.Types.OTHER && mapping.jdbcJavaType.javaTypeClass == java.util.UUID::class.java) return "uuid"
            if (mapping.jdbcType.defaultSqlTypeCode == org.hibernate.type.SqlTypes.JSON) return "OTHER"
            return when (code) {
                java.sql.Types.DOUBLE -> "double precision"
                java.sql.Types.TINYINT -> "smallint"
                java.sql.Types.TIME_WITH_TIMEZONE -> "time with time zone"
                java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp with time zone"
                java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY -> "bytea"
                java.sql.Types.NVARCHAR -> "varchar"
                java.sql.Types.LONGVARCHAR, java.sql.Types.LONGNVARCHAR -> "text"
                else -> try { JDBCType.valueOf(code).name } catch (_: IllegalArgumentException) { "JDBC_$code" }
            }
        }
        private fun logicalType(mapping: org.hibernate.metamodel.mapping.JdbcMapping): Class<*> =
            mapping.valueConverter?.domainJavaType?.javaTypeClass ?: mapping.javaTypeDescriptor.javaTypeClass
        private fun inputDomainComplete(mapping: org.hibernate.metamodel.mapping.JdbcMapping): Boolean {
            if (mapping.valueConverter != null) return false
            if (!mapping.jdbcType.javaClass.name.startsWith("org.hibernate.type.descriptor.jdbc.")) return false
            val type = logicalType(mapping)
            return type.isEnum || type.isPrimitive || type.name in setOf("java.lang.String", "java.lang.Character",
                "java.lang.Long", "java.lang.Integer", "java.lang.Short", "java.lang.Byte", "java.lang.Boolean",
                "java.lang.Double", "java.lang.Float", "java.math.BigDecimal", "java.math.BigInteger",
                "java.util.UUID", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime",
                "java.time.LocalTime", "java.time.OffsetTime", "java.time.Instant", "java.util.Date",
                "java.sql.Date", "java.sql.Timestamp", "java.sql.Time", "[B")
        }
        private fun enumValues(mapping: org.hibernate.metamodel.mapping.JdbcMapping): List<String> {
            val javaType = logicalType(mapping)
            if (!javaType.isEnum) return emptyList()
            val converter = mapping.valueConverter
            // Hibernate's enum JavaType supplies a factual finite Java domain. Convert with Hibernate's own converter.
            return javaType.enumConstants.map { value ->
                if (converter != null) {
                    @Suppress("UNCHECKED_CAST")
                    (converter as org.hibernate.type.descriptor.converter.spi.BasicValueConverter<Any, Any>).toRelationalValue(value).toString()
                } else if (mapping.jdbcType.isInteger) (value as Enum<*>).ordinal.toString() else (value as Enum<*>).name
            }.sorted()
        }
        private fun parameterTypes(binders: List<JdbcParameterBinder>): List<String> = binders.map {
            val parameter = it as? JdbcParameter
            parameter?.expressionType?.getJdbcMapping(0)?.let(::jdbcName) ?: "UNKNOWN"
        }
    }
}
