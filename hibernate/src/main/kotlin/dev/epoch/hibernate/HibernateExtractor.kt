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
import org.hibernate.query.sqm.internal.QuerySqmImpl
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

/** Hibernate 6.6 adapter. No connection provider, credentials, or production execution is needed. */
class HibernateExtractor {
    fun extract(request: ExtractionRequest): DatabaseContract {
        ClasspathInspection.checkHibernateVersion(request.classpath)
        request.entityOverrides.forEach { (name, override) ->
            require(override.operations == null || override.operations.isNotEmpty()) {
                "Entity override $name has an empty operation set; use resultMappingOnly or exclude instead"
            }
        }
        val names = (request.entityClasses + ClasspathInspection.entities(request.classpath, request.scanPackages)).distinct().sorted()
        require(names.isNotEmpty()) { "No entities found; supply entityClasses or scanPackages with explicit classpath entries" }
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
            val result = if (classes.isEmpty()) DatabaseContract(applicationVersion = request.applicationVersion)
            else try { bootstrap(classes, request) }
            catch (error: Throwable) {
                BootstrapRecovery.rethrowFatal(error)
                if (!request.keepGoing) throw error
                BootstrapRecovery.recover(classes, request, error) { bootstrap(it, request) }
            }
            val unresolved = (result.unresolvedQueries + failures).distinct().sortedWith(compareBy({ it.id }, { it.reason }))
            val notes = result.coverage.notes + excluded.sorted().map { "Explicitly excluded from this contract: $it" }
            return result.copy(unresolvedQueries = unresolved,
                coverage = result.coverage.copy(complete = result.coverage.complete && unresolved.isEmpty(), notes = notes))
        } finally {
            Thread.currentThread().contextClassLoader = previousLoader
            loader.close()
        }
    }

    private fun bootstrap(classes: List<Class<*>>, request: ExtractionRequest): DatabaseContract {
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
            val factory = sources.buildMetadata().buildSessionFactory() as SessionFactoryImplementor
            factory.use { return extractFactory(it, request) }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    /** For tests/adapters already bootstrapping Hibernate with all application-specific settings. */
    fun extractFactory(factory: SessionFactoryImplementor, request: ExtractionRequest): DatabaseContract {
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
                selectables[qualified(s.containingTableExpression) to identifier(s.selectionExpression)] = s
                if (s.customReadExpression?.let { it != org.hibernate.sql.Template.TEMPLATE + "." + s.selectionExpression } == true || s.customWriteExpression?.let { it != "?" } == true)
                    unresolved += UnresolvedQuery(p.entityName, "Custom column transformer requires additional SQL dependency and behavioral analysis")
            }
            p.identifierMapping.forEachSelectable(consume)
            p.forEachSelectable(consume)
            p.discriminatorMapping?.takeIf { it.hasPhysicalColumn() || it.isFormula }?.forEachSelectable(consume)
            val generatedId = p.generator?.javaClass?.name?.takeUnless { it.endsWith(".Assigned") }
            if (generatedId != null && Operation.INSERT in operations) unresolved += UnresolvedQuery(p.entityName, "Generated identifier strategy $generatedId is recorded but sequence/allocation semantics are not verified")
            if ((p.entityMetamodel.isDynamicInsert && Operation.INSERT in operations) || (p.entityMetamodel.isDynamicUpdate && Operation.UPDATE in operations))
                unresolved += UnresolvedQuery(p.entityName, "Dynamic insert/update produces unenumerated SQL variants")
            if (p.hasWhereRestrictions() || p.hasFilterForLoadByKey() || factory.definedFilterNames.isNotEmpty())
                unresolved += UnresolvedQuery(p.entityName, "Hibernate filters/restrictions require explicitly enumerated configurations")
            if (p.propertyTypes.any { it.isCollectionType })
                unresolved += UnresolvedQuery(p.entityName, "Collection CRUD and collection loader variants are not yet extracted")
            if ((p.hasInsertGeneratedProperties() && Operation.INSERT in operations) || (p.hasUpdateGeneratedProperties() && Operation.UPDATE in operations))
                unresolved += UnresolvedQuery(p.entityName, "Database-generated property retrieval and generation semantics are not exhaustively extracted")
            if (p.optimisticLockStyle().isAllOrDirty)
                unresolved += UnresolvedQuery(p.entityName, "ALL/DIRTY optimistic locking creates runtime-dependent predicates")
            if (p.mappedClass.annotations.any { it.annotationClass.java.name in CUSTOM_SQL_ANNOTATIONS || it.annotationClass.java.name.startsWith("org.hibernate.annotations.SQL") })
                unresolved += UnresolvedQuery(p.entityName, "Custom Hibernate SQL requires a separate declared query contract")
            selectables.entries.groupBy { it.key.first }.toSortedMap().forEach { (table, entries) ->
                entities += EntityContract(
                    id = if (p.tableSpan == 1) p.entityName else "${p.entityName}@$table",
                    table = table,
                    columns = entries.map { (key, s) -> ColumnContract(
                        name = key.second,
                        typeName = jdbcName(s.jdbcMapping),
                        nullable = s.isNullable,
                        insertable = s.isInsertable,
                        updatable = s.isUpdateable,
                        generated = generatedId != null && key.second in p.identifierColumnNames.map(::identifier),
                    ) }.sortedBy { it.name },
                    operations = operations,
                    generatedIdentifier = generatedId,
                )
            }
        }
        val allColumns = entities.groupBy { it.table }.mapValues { (_, values) -> values.flatMap { it.columns }.distinctBy { it.name } }
        for (raw in persisters) {
            val p = raw as? AbstractEntityPersister ?: continue
            val override = entityOverride(request, p.mappedClass)
            if (override?.resultMappingOnly == true || override?.exclude == true) continue
            val operations = operations(request, p)
            fun capture(id: String, operation: Operation, sql: String, tables: List<String>, binders: List<JdbcParameterBinder>) {
                val cols = tables.flatMap { table -> allColumns[table].orEmpty().map { ColumnReference(table, it.name) } }.distinct()
                val writes = tables.flatMap { table -> allColumns[table].orEmpty().filter {
                    when (operation) { Operation.INSERT -> it.insertable && !it.generated; Operation.UPDATE -> it.updatable; else -> false }
                }.map { ColumnReference(table, it.name) } }.distinct()
                queries += QueryContract(id, operation, sql, tables.distinct().sorted(), cols, writes,
                    parameterTypes = parameterTypes(binders), source = QuerySource.HIBERNATE_MAPPING,
                    dependenciesComplete = tables.all { it in allColumns }, explicitColumnList = true,
                    targetTable = if (operation == Operation.SELECT) null else tables.singleOrNull())
            }
            if (Operation.SELECT in operations) try {
                val influencers = LoadQueryInfluencers(factory)
                val plan = SingleIdEntityLoaderStandardImpl<Any>(p, influencers).resolveLoadPlan(LockOptions.NONE, influencers, factory)
                capture("${p.entityName}.findById", Operation.SELECT, plan.jdbcSelect.sqlString,
                    plan.jdbcSelect.affectedTableNames.map(::qualified), plan.jdbcSelect.parameterBinders)
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
        factory.openSession().use { session ->
            fun hql(id: String, text: String, source: QuerySource, variant: String? = null) {
                try {
                    val q = session.createQuery(text, Any::class.java) as QuerySqmImpl<*>
                    val sqm = q.sqmStatement as? SqmSelectStatement<*> ?: error("Only SELECT HQL is supported")
                    val translation = factory.queryEngine.sqmTranslatorFactory.createSelectTranslator(
                        sqm, q.queryOptions, q.domainParameterXref, q.parameterBindings, q.loadQueryInfluencers, factory, true,
                    ).translate()
                    val ast = translation.sqlAst
                    val deps = DependencyWalker()
                    ast.accept(deps)
                    val jdbc = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory.buildSelectTranslator(factory, ast)
                        .translate(JdbcParameterBindings.NO_BINDINGS, q.queryOptions)
                    val complete = deps.complete && deps.columns.all { it.first in deps.tables }
                    queries += QueryContract(id, Operation.SELECT, jdbc.sqlString,
                        tables = deps.tables.values.distinct().sorted(),
                        columns = deps.columns.mapNotNull { (alias, column) -> deps.tables[alias]?.let { ColumnReference(it, column) } }.distinct().sortedWith(compareBy({ it.table }, { it.column })),
                        parameterTypes = parameterTypes(jdbc.parameterBinders), source = source,
                        dependenciesComplete = complete, variant = variant)
                    if (!complete) unresolved += UnresolvedQuery(id, "HQL includes opaque SQL expressions or unresolved table aliases")
                    if (q.parameterMetadata.registrations.any { it.allowsMultiValuedBinding() })
                        unresolved += UnresolvedQuery(id, "Collection-valued HQL parameter cardinalities must be explicitly enumerated")
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
                    queries += QueryContract(id, SqlOperationClassifier.operation(recognizer.adjustedSqlString), recognizer.adjustedSqlString, source = source,
                        parameterTypes = request.nativeQueryParameterTypes[id].orEmpty(), dependenciesComplete = false, explicitColumnList = false)
                } catch (e: Exception) {
                    unresolved += UnresolvedQuery(id, "Native SQL parsing/classification failed: ${e.message}")
                }
                unresolved += UnresolvedQuery(id, if (id in request.nativeQueryParameterTypes)
                    "Native SQL has declared parameter types for PostgreSQL preparation; its structural dependencies and behavioral contract remain unproven"
                else "Native SQL is retained for PostgreSQL preparation, but its dependencies, parameter/result types, and behavioral contract are not proven")
            }
            named.visitNativeQueryMementos { native(it.registrationName, it.sqlString, QuerySource.NAMED_NATIVE) }
            request.nativeQueries.toSortedMap().forEach { (id, text) -> native(id, text, QuerySource.STATIC) }
            named.visitCallableQueryMementos { unresolved += UnresolvedQuery(it.registrationName, "Stored procedures are unsupported") }
        }
        return DatabaseContract(applicationVersion = request.applicationVersion, entities = entities.sortedBy { it.id },
            queries = queries.sortedBy { it.id }, coverage = Coverage(complete = unresolved.isEmpty(),
                mechanisms = listOf("Hibernate 6.6 runtime mapping metamodel", "Hibernate static mutation plans", "Hibernate single-id loader SQL", "Hibernate SQM/SQL AST translation"),
                notes = listOf("Scoped to declared entities and query definitions only; repository scanning, arbitrary JDBC, criteria builders and unlisted runtime query sources are outside this contract.",
                    "Generated SQL covers the default single-id loader and static entity mutations only; entity graphs, alternate lock modes, batch loaders, pagination, and repository CRUD query variants are not enumerated.",
                    "Finite variants are user-declared exhaustive sets, not inferred application coverage.",
                    "Generated identifier allocation, collection operations, dynamic mutations, filters, custom SQL, formulas and native semantics are unsupported when present.") + scopeNotes),
            unresolvedQueries = unresolved.distinct().sortedWith(compareBy({ it.id }, { it.reason })))
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
        private fun identifier(value: String): String = value.removeSurrounding("\"").replace("\"\"", "\"")
        private fun qualified(value: String): String = if ('.' in value) value.split('.').joinToString(".", transform = ::identifier) else "public.${identifier(value)}"
        private fun entityOverride(request: ExtractionRequest, type: Class<*>): EntityOverride? =
            request.entityOverrides[type.name] ?: request.entityOverrides[type.getAnnotation(jakarta.persistence.Entity::class.java)?.name?.ifBlank { type.simpleName } ?: type.simpleName]
        private fun operations(request: ExtractionRequest, p: AbstractEntityPersister): Set<Operation> =
            entityOverride(request, p.mappedClass)?.operations ?: if (p.isMutable) Operation.entries.toSet() else setOf(Operation.SELECT)
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
        private fun parameterTypes(binders: List<JdbcParameterBinder>): List<String> = binders.map {
            val parameter = it as? JdbcParameter
            parameter?.expressionType?.getJdbcMapping(0)?.let(::jdbcName) ?: "UNKNOWN"
        }
    }
}
