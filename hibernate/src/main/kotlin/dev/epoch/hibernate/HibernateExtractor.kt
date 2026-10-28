package dev.epoch.hibernate

import dev.epoch.core.*
import org.hibernate.LockOptions
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.spi.LoadQueryInfluencers
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl
import org.hibernate.metamodel.mapping.SelectableMapping
import org.hibernate.metamodel.mapping.SelectableMappings
import org.hibernate.metamodel.mapping.AttributeMapping
import org.hibernate.metamodel.mapping.BasicValuedModelPart
import org.hibernate.persister.entity.AbstractEntityPersister
import org.hibernate.query.sqm.tree.select.SqmSelectStatement
import org.hibernate.query.sql.internal.ParameterParser
import org.hibernate.query.sql.internal.ParameterRecognizerImpl
import org.hibernate.sql.ast.tree.expression.JdbcParameter
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
            val classDiagnostics = ExtractionDiagnostics()
            val excluded = mutableListOf<String>()
            val classes = names.mapNotNull { name ->
                if (request.entityOverrides[name]?.exclude == true) { excluded += name; return@mapNotNull null }
                try {
                    val type = Class.forName(name, false, loader)
                    if (entityOverride(request, type)?.exclude == true) { excluded += name; null } else type
                } catch (error: Throwable) {
                    BootstrapRecovery.rethrowFatal(error)
                    if (!request.keepGoing) throw error
                    classDiagnostics.capture(name, "Entity class could not be loaded", error)
                    null
                }
            }
            val discovery = if (request.discoverRepositories) RepositoryDiscovery.discover(request.classpath,
                request.repositoryPackages.ifEmpty { request.scanPackages }, loader, classes.map { it.name }) else null
            val discoveredRequest = request.copy(hqlQueries = discovery?.hqlQueries.orEmpty() + request.hqlQueries,
                nativeQueries = discovery?.nativeQueries.orEmpty() + request.nativeQueries,
                hqlQueryPagination = discovery?.hqlQueryPagination.orEmpty().filterKeys { it !in request.hqlQueries } + request.hqlQueryPagination)
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
            val runtimeDiagnostics = ExtractionDiagnostics()
            for ((sql, records) in observations.groupBy { it.sql }.toSortedMap()) {
                QueryExtraction.record(SqlCollector.queryId(sql), "Observed SQL extraction failed", observedQueries, runtimeFailures, runtimeDiagnostics) {
                    val limitations = mutableListOf<UnresolvedQuery>()
                    val matching = result.queries.filter { it.sql == sql }.map { it.parameterTypes }.distinct().singleOrNull()
                    val query = SqlCollector.query(sql, limitations).copy(parameterTypes = matching.orEmpty(),
                        observationFrames = records.flatMap { it.callSites }.distinct().sorted())
                    ExtractedSql(query, limitations)
                }
            }
            val unresolved = (result.unresolvedQueries + classDiagnostics.unresolved() + discovery?.unresolved.orEmpty() + runtimeFailures + runtimeDiagnostics.unresolved())
                .distinct().sortedWith(compareBy({ it.id }, { it.reason }))
            val staticMatches = observedQueries.count { observed -> result.queries.any { it.sql == observed.sql } }
            val notes = result.coverage.notes + excluded.sorted().map { "Explicitly excluded from this contract: $it" } +
                request.classpath.filter { !java.nio.file.Files.exists(Path.of(it)) }.distinct().sorted().map { "Warning: ignored nonexistent classpath entry: $it" } +
                (discovery?.notes ?: listOf("Explicit entity/descriptor scope; repository discovery disabled")) +
                if (observations.isNotEmpty()) listOf(
                    "Runtime capture cross-check: $staticMatches distinct SQL templates match static extraction exactly; ${observedQueries.size - staticMatches} have no exact static match.",
                    "Runtime-observed SQL and call-site frames are factual observations; they do not resolve unenumerated query sources or establish exhaustive coverage.") else emptyList()
            val links = discovery?.accessPaths.orEmpty().flatMap { path -> path.queryIds.map { it to path.id } }.groupBy({ it.first }, { it.second })
            val extractedQueryIds = result.queries.map { it.id }.toHashSet()
            val failuresBySource = result.unresolvedQueries.flatMap { failure ->
                (failure.affectedIds + failure.id).distinct().map { it to failure }
            }.groupBy({ it.first }, { it.second })
            val paths = discovery?.accessPaths.orEmpty().map { path ->
                val missing = path.queryIds.filter { it !in extractedQueryIds }
                if (missing.isNotEmpty()) path.copy(status = AccessPathStatus.UNRESOLVED,
                    reason = "Discovered query could not be translated: " + missing.joinToString { id ->
                        failuresBySource[id].orEmpty().joinToString {
                            if (it.affectedIds.size > 1) ExtractionDiagnostics.reference(it.id) else it.reason
                        }.ifBlank { id }
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
            val pathById = paths.associateBy { it.id }
            val linkedUnresolved = unresolved.map { failure ->
                if (failure.affectedIds.isEmpty()) failure else {
                    val sources = failure.affectedIds.toHashSet()
                    val linked = sources.flatMap { links[it].orEmpty() }.distinct().mapNotNull(pathById::get).filter { path ->
                        val missing = path.queryIds.filterNot(extractedQueryIds::contains)
                        path.status == AccessPathStatus.UNRESOLVED && missing.isNotEmpty() && missing.all { it in sources }
                    }.map { it.id }
                    failure.copy(affectedIds = (sources + linked).sorted())
                }
            }
            val groupsBySource = linkedUnresolved.filter { it.affectedIds.size > 1 }.flatMap { failure ->
                failure.affectedIds.map { it to failure }
            }.groupBy({ it.first }, { it.second })
            val linkedPaths = paths.map { path ->
                val diagnostic = groupsBySource[path.id]?.singleOrNull()
                if (path.status == AccessPathStatus.UNRESOLVED && diagnostic != null)
                    path.copy(reason = ExtractionDiagnostics.reference(diagnostic.id)) else path
            }
            val coveredSources = linkedUnresolved.flatMap { it.affectedIds + it.id }.toSet()
            val allUnresolved = (linkedUnresolved + linkedPaths.filter { it.status == AccessPathStatus.UNRESOLVED && it.id !in coveredSources }.map {
                UnresolvedQuery(it.id, it.reason ?: "Discovered access path is unresolved")
            }).distinct().sortedWith(compareBy({ it.id }, { it.reason }))
            return result.copy(unresolvedQueries = allUnresolved, queries = (result.queries.map { query ->
                query.copy(accessPathIds = links[query.id].orEmpty().distinct().sorted())
            } + observedQueries).sortedBy { it.id }, accessPaths = (linkedPaths + runtimePaths).sortedBy { it.id },
                apiInventory = discovery?.apiInventory,
                coverage = result.coverage.copy(complete = result.coverage.complete && allUnresolved.isEmpty() && observations.isEmpty() &&
                    discovery?.apiInventory?.completeWithinDeclaredScope != false, notes = notes))
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
        require(request.hibernateProperties["hibernate.dialect_resolvers"].isNullOrBlank()) {
            "Offline extraction pins PostgreSQL 16.8 and does not support hibernate.dialect_resolvers; application resolvers are not invoked"
        }
        val settings = mutableMapOf<String, Any>("hibernate.default_schema" to "public")
        settings.putAll(request.hibernateProperties)
        request.physicalNamingStrategy?.let { settings["hibernate.physical_naming_strategy"] = it }
        request.implicitNamingStrategy?.let { settings["hibernate.implicit_naming_strategy"] = it }
        // Legacy product hints take precedence in some supported Hibernate runtimes. Remove them
        // before resolving the dialect from authoritative offline facts through Hibernate's standard resolver.
        listOf("hibernate.dialect", "hibernate.dialect_resolvers", "hibernate.temp.use_jdbc_metadata_defaults",
            "javax.persistence.database-product-name", "javax.persistence.database-product-version",
            "javax.persistence.database-major-version", "javax.persistence.database-minor-version").forEach(settings::remove)
        // Apply these after application properties: extraction must not connect, mutate a schema, or invoke Bean Validation.
        settings.putAll(mapOf(
            "hibernate.boot.allow_jdbc_metadata_access" to false,
            "jakarta.persistence.database-product-name" to "PostgreSQL",
            "jakarta.persistence.database-product-version" to "16.8",
            "jakarta.persistence.database-major-version" to 16,
            "jakarta.persistence.database-minor-version" to 8,
            "hibernate.hbm2ddl.auto" to "none",
            "hibernate.connection.provider_class" to "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl",
            "jakarta.persistence.validation.mode" to "none",
            "javax.persistence.validation.mode" to "none",
            "hibernate.query.startup_check" to false,
        ))
        val registry = ExtractionClassLoaderService.registry(settings)
        try {
            val sources = MetadataSources(registry)
            classes.forEach(sources::addAnnotatedClass)
            if (prunedAssociations.isNotEmpty()) {
                fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                val overrides = prunedAssociations.map { (name, properties) ->
                    val declaring = Class.forName(name, false, Thread.currentThread().contextClassLoader)
                    val element = if (declaring.annotations.any { it.annotationClass.java.name == "jakarta.persistence.MappedSuperclass" })
                        "mapped-superclass" else "entity"
                    if (element == "mapped-superclass") sources.addAnnotatedClass(declaring)
                    val access = recoveryMappingAccess(declaring, classes, properties)
                    Triple(element, name, access to properties)
                }.sortedWith(compareBy({ if (it.first == "mapped-superclass") 0 else 1 }, { it.second }))
                val mappings = "<entity-mappings xmlns=\"https://jakarta.ee/xml/ns/persistence/orm\" version=\"3.1\">" +
                    overrides.joinToString("") { (element, name, attributeMapping) ->
                        val (access, properties) = attributeMapping
                        "<$element class=\"${xml(name)}\" access=\"$access\"><attributes>" + properties.sorted().joinToString("") { property ->
                            "<transient name=\"${xml(property)}\"/>"
                        } + "</attributes></$element>"
                    } + "</entity-mappings>"
                sources.addInputStream(mappings.byteInputStream())
            }
            val metadata = sources.buildMetadata()
            val factory = metadata.buildSessionFactory() as SessionFactoryImplementor
            try {
                val unbounded = BootColumnBounds.unboundedStrings(metadata, request.hibernateProperties["hibernate.default_schema"] ?: "public")
                return extractFactory(factory, request, unbounded)
            } finally { factory.close() }
        } finally { StandardServiceRegistryBuilder.destroy(registry) }
    }

    /** Sparse transient XML must retain the annotation mapping's access strategy, including Hibernate 7's model lookup. */
    private fun recoveryMappingAccess(declaring: Class<*>, classes: List<Class<*>>, properties: Set<String>): String {
        val explicit = declaring.annotations.singleOrNull { it.annotationClass.java.name == "jakarta.persistence.Access" }
            ?.let { it.annotationClass.java.getMethod("value").invoke(it).toString() }
        val identifiers = setOf("jakarta.persistence.Id", "jakarta.persistence.EmbeddedId")
        val mappedTypes = classes.filter { declaring.isAssignableFrom(it) }.flatMap { owner ->
            generateSequence(owner) { it.superclass }.filter { type ->
                type.annotations.any { it.annotationClass.java.name in setOf("jakarta.persistence.Entity", "jakarta.persistence.MappedSuperclass") }
            }.toList()
        }.distinct()
        val access = explicit ?: mappedTypes.flatMap { type ->
            type.declaredFields.filter { field -> field.annotations.any { it.annotationClass.java.name in identifiers } }.map { "FIELD" } +
                type.declaredMethods.filter { method -> method.annotations.any { it.annotationClass.java.name in identifiers } }.map { "PROPERTY" }
        }.distinct().singleOrNull()
        require(access in setOf("FIELD", "PROPERTY")) { "Cannot determine annotation access for partial mapping ${declaring.name}" }
        val associations = setOf("jakarta.persistence.ManyToOne", "jakarta.persistence.OneToOne", "jakarta.persistence.OneToMany", "jakarta.persistence.ManyToMany")
        properties.forEach { property ->
            val fieldAccess = declaring.declaredFields.any { it.name == property && it.annotations.any { a -> a.annotationClass.java.name in associations } }
            val methodAccess = declaring.declaredMethods.any { method -> method.parameterCount == 0 &&
                java.beans.Introspector.decapitalize(method.name.removePrefix("get").removePrefix("is")) == property &&
                method.annotations.any { a -> a.annotationClass.java.name in associations } }
            require(if (access == "FIELD") fieldAccess && !methodAccess else methodAccess && !fieldAccess) {
                "Partial mapping cannot change member-specific access for ${declaring.name}.$property"
            }
        }
        return requireNotNull(access)
    }

    /** For tests/adapters already bootstrapping Hibernate with all application-specific settings. */
    fun extractFactory(factory: SessionFactoryImplementor, request: ExtractionRequest): DatabaseContract =
        extractFactory(factory, request, emptySet())

    private fun extractFactory(factory: SessionFactoryImplementor, request: ExtractionRequest,
                               unbounded: Set<BootColumnBounds.Key>): DatabaseContract {
        // Hibernate 7 changed this public compile-time constant; resolve it from the actual runtime.
        val sqlTemplate = org.hibernate.sql.Template::class.java.getField("TEMPLATE").get(null) as String
        val unresolved = request.unresolvedDynamicQueries.toSortedMap().map { UnresolvedQuery(it.key, it.value) }.toMutableList()
        val diagnostics = ExtractionDiagnostics()
        val entities = mutableListOf<EntityContract>()
        val queries = mutableListOf<QueryContract>()
        val mappingIds = linkedMapOf<String, Map<String, String>>()
        val hierarchyFacts = linkedMapOf<String, HierarchyContract?>()
        val schema = request.hibernateProperties["hibernate.default_schema"] ?: "public"
        val scopeNotes = mutableListOf("Hibernate runtime ${org.hibernate.Version.getVersionString()}; PostgreSQL dialect version ${factory.jdbcServices.dialect.version}.")
        if (!factory.sessionFactoryOptions.isCheckNullability) scopeNotes +=
            "Hibernate nullability checking is disabled; a raw nullable=false mapping alone does not establish input NULL exclusion."
        // Data-domain provenance is additive: missing decoder facts must not erase healthy SQL.
        fun <T> dataMetadata(id: String, phase: String, fallback: T, action: () -> T): T = try { action() }
        catch (error: Throwable) {
            scopeNotes += "$id: ${QueryExtraction.failure(id, phase, error)}"
            fallback
        }
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
            val dynamicInput = Operation.INSERT in operations && p.entityMetamodel.isDynamicInsert ||
                Operation.UPDATE in operations && p.entityMetamodel.isDynamicUpdate
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
            val customMutations = linkedMapOf<String, Pair<Boolean, Boolean>>()
            val optionalTables = mutableSetOf<String>()
            p.forEachMutableTable { table ->
                customMutations[qualified(table.tableName)] = (table.insertCustomSql != null) to (table.updateCustomSql != null)
                if (table.isOptional) optionalTables += qualified(table.tableName)
                // KeyMapping changes from a class to an interface in Hibernate 7.4.
                // Dispatch through its stable superinterface in both runtime generations.
                val keySelectables: SelectableMappings = table.keyMapping
                keySelectables.forEachSelectable { _, selectable ->
                    if (!selectable.isFormula) selectables.putIfAbsent(qualified(table.tableName) to identifier(selectable.selectionExpression), selectable)
                }
            }
            val primitiveColumns = mutableSetOf<Pair<String, String>>()
            val generatedColumns = mutableSetOf<Pair<String, String>>()
            val associationInputColumns = mutableSetOf<Pair<String, String>>()
            val declaredNullability = mutableMapOf<Pair<String, String>, MutableSet<Boolean?>>()
            fun recordDeclaration(attribute: AttributeMapping) {
                // A component/container declaration does not describe every JDBC leaf. A direct
                // owning ManyToOne to one standard primary identifier can preserve the reference
                // declaration, separately from the lifecycle-dependent JDBC input domain.
                val value = if (attribute is BasicValuedModelPart)
                    DeclaredNullability.of(attribute.propertyAccess?.getter?.member) else null
                val association = dataMetadata<AssociationNullability.Fact?>("${p.entityName}.${attribute.attributeName}",
                    "Association declaration metadata unavailable", null) { AssociationNullability.inspect(attribute) }
                // Hibernate's state-array position links this property (including component leaves)
                // to its actual generator. An audit timestamp must not hide unrelated input domains.
                val generated = p.entityMetamodel.generators?.getOrNull(attribute.stateArrayPosition)?.let { generator ->
                    Operation.INSERT in operations && generator.generatesOnInsert() ||
                        Operation.UPDATE in operations && generator.generatesOnUpdate()
                } == true
                attribute.forEachSelectable { _, selectable ->
                    if (!selectable.isFormula) {
                        val key = qualified(p.physicalTableNameForMutation(selectable)) to identifier(selectable.selectionExpression)
                        declaredNullability.getOrPut(key) { linkedSetOf() } +=
                            (association?.takeIf { it.appliesTo(selectable) }?.declaredNonNull ?: value)
                        if (attribute is org.hibernate.metamodel.mapping.EntityAssociationMapping &&
                            (Operation.INSERT in operations && selectable.isInsertable ||
                                Operation.UPDATE in operations && selectable.isUpdateable)) associationInputColumns += key
                        if (generated) generatedColumns += key
                    }
                }
            }
            (p.identifierMapping as? AttributeMapping)?.let(::recordDeclaration)
            p.visitAttributeMappings { attribute ->
                recordDeclaration(attribute)
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
            val tableMappings = selectables.keys.map { it.first }.distinct().sorted().associateWith { table ->
                if (selectables.keys.map { it.first }.distinct().size == 1) p.entityName else "${p.entityName}@$table"
            }
            mappingIds[p.entityName] = tableMappings
            selectables.entries.groupBy { it.key.first }.toSortedMap().forEach { (table, entries) ->
                entities += EntityContract(
                    id = tableMappings.getValue(table),
                    table = table,
                    columns = entries.map { (key, s) ->
                        val valueMetadata = MappingValueMetadata.inspect(s.jdbcMapping)
                        val insertable = s.isInsertable
                        val updatable = s.isUpdateable && key.second !in p.identifierColumnNames.map(::identifier)
                        val custom = customMutations[table]
                        val directInput = inputDomainComplete(s.jdbcMapping, valueMetadata) &&
                            // A non-null entity reference may undergo transient/deleted-reference
                            // nullification or deferred key assignment. Its source declaration alone
                            // cannot prove every SQL binding used by Hibernate's lifecycle.
                            key !in associationInputColumns &&
                            // Mapping nullability is a declaration; disabled runtime checks cannot prove NULL exclusion.
                            (factory.sessionFactoryOptions.isCheckNullability || s.isNullable ||
                                declaredNullability[key]?.singleOrNull() == true || key in primitiveColumns) &&
                            s.customWriteExpression.let { it == null || it.trim() == "?" } && !dynamicInput && key !in generatedColumns &&
                            (Operation.INSERT !in operations || !insertable || custom?.first == false) &&
                            (Operation.UPDATE !in operations || !updatable || custom?.second == false) &&
                            // Optional secondary rows can be skipped/deleted when their values are NULL.
                            !(table in optionalTables && (Operation.INSERT in operations && insertable ||
                                Operation.UPDATE in operations && updatable))
                        ColumnContract(
                        name = key.second,
                        typeName = jdbcName(s.jdbcMapping),
                        nullable = s.isNullable && key !in primitiveColumns,
                        insertable = insertable,
                        updatable = updatable,
                        generated = p.generator is org.hibernate.id.IdentityGenerator && table == qualified(p.identifierTableMapping.tableName) && key.second in p.identifierColumnNames.map(::identifier),
                        length = s.length, precision = s.precision, scale = s.scale,
                        enumValues = valueMetadata.enumValues,
                        logicalJavaType = logicalType(s.jdbcMapping).name,
                        inputDomainComplete = directInput,
                        readDomainComplete = readDomainComplete(s, valueMetadata),
                        stringInputLengthUnrestricted = directInput && unrestrictedStringBinding(s.jdbcMapping),
                        declaredNonNull = declaredNullability[key]?.singleOrNull(),
                        hibernateNullable = s.isNullable,
                        enumMode = valueMetadata.enumMode,
                        enumConstants = valueMetadata.enumConstants,
                        enumRelationalValues = valueMetadata.enumRelationalValues,
                        enumEncoding = valueMetadata.enumEncoding,
                        converterClass = valueMetadata.converterClass,
                        converterBytecodeSha256 = valueMetadata.converterBytecodeSha256,
                        lengthUnbounded = directInput && logicalType(s.jdbcMapping) == String::class.java &&
                            BootColumnBounds.Key(p.entityName, table, key.second) in unbounded,
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
        // Resolve root links only after all physical mapping IDs exist; class-name sorting is not ancestry.
        for (raw in persisters) {
            val p = raw as? AbstractEntityPersister ?: continue
            if (p.entityName !in mappingIds) continue
            val hierarchy = dataMetadata<HierarchyContract?>(p.entityName, "Data hierarchy metadata unavailable", null) {
                HibernateHierarchyMetadata.inspect(p, mappingIds, schema, ::jdbcName)
            }
            hierarchyFacts[p.entityName] = hierarchy
            if (hierarchy != null) {
                val table = hierarchy.discriminator?.table ?: (p.rootEntityDescriptor as? AbstractEntityPersister)
                    ?.identifierTableMapping?.tableName?.let(::qualified)
                val id = mappingIds[p.entityName]?.get(table)
                val index = entities.indexOfFirst { it.id == id }
                if (index >= 0) entities[index] = entities[index].copy(hierarchy = hierarchy)
            }
        }
        for (raw in persisters) {
            val p = raw as? AbstractEntityPersister ?: continue
            val override = entityOverride(request, p.mappedClass)
            if (override?.resultMappingOnly == true || override?.exclude == true) continue
            val operations = operations(request, p)
            fun capture(id: String, operation: Operation, sql: String, tables: List<String>, binders: List<JdbcParameterBinder>,
                        readFacts: HibernateReadMetadata.Facts = HibernateReadMetadata.Facts()): ExtractedSql {
                val deps = SqlDependencies.inspect(sql, request.hibernateProperties["hibernate.default_schema"] ?: "public", sourceId = id)
                val references = deps.tables.ifEmpty { tables }.distinct().sorted()
                // Hibernate knows the mutation target even when its SQL uses an unsupported MERGE
                // form. Retaining provenance does not establish complete dependencies or behavior.
                val target = deps.target ?: tables.singleOrNull().takeIf { operation != Operation.SELECT }
                val origins = mappingIds[p.entityName].orEmpty().filterKeys { table ->
                    if (operation == Operation.SELECT) table in references
                    else table == target && table in tables
                }.values.sorted()
                val query = QueryContract(id, operation, sql, deps.tables.ifEmpty { tables }.distinct().sorted(), deps.columns, deps.writes,
                    parameterTypes = parameterTypes(binders), source = QuerySource.HIBERNATE_MAPPING,
                    dependenciesComplete = deps.complete, explicitColumnList = true,
                    targetTable = target, mappedEntityIds = origins,
                    mappedReads = readFacts.reads, mappedReadCoverageComplete = readFacts.complete,
                    polymorphicReads = if (operation == Operation.SELECT && p.entityName == p.rootEntityName)
                        hierarchyFacts[p.entityName]?.rootEntityId?.let { rootId -> listOf(PolymorphicRead(rootId,
                            readFacts.unrestricted && readFacts.discriminatorSelected &&
                                hierarchyFacts[p.entityName]?.complete == true && !p.needsDiscriminator())) }.orEmpty()
                        else emptyList(),
                    discriminatorWrites = if (operation == Operation.INSERT)
                        dataMetadata(id, "Data discriminator write metadata unavailable", emptyList()) {
                            HibernateHierarchyMetadata.write(p, hierarchyFacts[p.entityName], sql, mappingIds[p.entityName].orEmpty(), schema)
                        } else emptyList())
                return ExtractedSql(query, if (deps.complete) emptyList() else listOf(UnresolvedQuery(id,
                    deps.failureReason ?: "Generated SQL has an unsupported dependency expression or unresolved table alias")))
            }
            if (Operation.SELECT in operations) QueryExtraction.record("${p.entityName}.findById", "Hibernate loader translation failed", queries, unresolved, diagnostics) {
                val influencers = LoadQueryInfluencers(factory)
                val loader = SingleIdEntityLoaderStandardImpl<Any>(p, influencers)
                val resolve = loader.javaClass.methods.single { it.name == "resolveLoadPlan" }
                val plan = (if (resolve.parameterCount == 2) resolve.invoke(loader, LockOptions.NONE, influencers)
                    else resolve.invoke(loader, LockOptions.NONE, influencers, factory))
                val jdbc = HibernateSqlOperations.loadPlanSelect(plan)
                val id = "${p.entityName}.findById"
                val readFacts = dataMetadata(id, "Data read metadata unavailable", HibernateReadMetadata.Facts()) {
                    HibernateReadMetadata.inspect(factory, p, jdbc, influencers, mappingIds[p.entityName].orEmpty(), schema)
                }
                capture(id, Operation.SELECT, jdbc.sqlString,
                    HibernateSqlOperations.affectedTableNames(jdbc).map(::qualified), jdbc.parameterBinders, readFacts)
            }
            for (operation in listOf(Operation.INSERT, Operation.UPDATE, Operation.DELETE)) {
                if (operation !in operations) continue
                val plan = QueryExtraction.attempt("${p.entityName}.$operation", "Hibernate mutation plan extraction failed", unresolved, diagnostics) {
                    val coordinator = when (operation) {
                        Operation.INSERT -> p.insertCoordinator
                        Operation.UPDATE -> p.updateCoordinator
                        Operation.DELETE -> p.deleteCoordinator
                        else -> error("Unexpected mutation operation $operation")
                    }
                    val group = coordinator?.staticMutationOperationGroup
                    val count = group?.numberOfOperations ?: 0
                    if (count == 0) unresolved += UnresolvedQuery("${p.entityName}.$operation", "No exhaustive static Hibernate mutation plan is available")
                    group to count
                } ?: continue
                val (group, count) = plan
                for (i in 0 until count) {
                    val id = "${p.entityName}.${operation.name.lowercase()}.$i"
                    QueryExtraction.record(id, "Hibernate mutation SQL extraction failed", queries, unresolved, diagnostics) {
                        val mutation = group?.getOperation(i) as? PreparableMutationOperation
                        if (mutation == null || mutation.isCallable) ExtractedSql(limitations = listOf(UnresolvedQuery(id,
                            "Non-preparable or callable mutation is unsupported")))
                        else capture(id, operation, mutation.sqlString,
                            listOf(qualified(mutation.tableDetails.tableName)), mutation.parameterBinders)
                    }
                }
            }
        }
        extractCollections(factory, request, entities, queries, unresolved, unbounded, diagnostics)
        val session = factory.openSession()
        try {
            fun hql(id: String, text: String, source: QuerySource, variant: String? = null) {
                QueryExtraction.record(id, "Hibernate fixed HQL translation failed", queries, unresolved, diagnostics) {
                    val q = session.createQuery(text)
                    val pagination = request.hqlQueryPagination[id]
                    require(pagination?.maxResults != 0) {
                        "maxResults=0 can skip SQL execution in Hibernate; no executable SQL template is asserted for this pagination state"
                    }
                    pagination?.firstResult?.let { value -> q.javaClass.getMethod("setFirstResult", Int::class.javaPrimitiveType).invoke(q, value) }
                    pagination?.maxResults?.let { value -> q.javaClass.getMethod("setMaxResults", Int::class.javaPrimitiveType).invoke(q, value) }
                    // QuerySqmImpl was renamed in 7; bridge the stable runtime accessors without binding to its implementation class.
                    fun property(name: String): Any = q.javaClass.getMethod(name).invoke(q)
                    val sqm = property("getSqmStatement")
                    if (pagination != null) {
                        require(sqm is SqmSelectStatement<*>) { "Pagination on mutation HQL has no supported SQL execution" }
                        require(!sqm.containsCollectionFetches()) { "Pagination with a collection fetch can execute in memory; its runtime SQL variant is not established" }
                    }
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
                    val jdbc = HibernateSqlOperations.translate(factory, ast, options)
                    val deps = SqlDependencies.inspect(jdbc.sqlString, request.hibernateProperties["hibernate.default_schema"] ?: "public", sourceId = id)
                    val astDependencies = SqlAstDependencies()
                    ast.accept(astDependencies)
                    val complete = deps.complete && astDependencies.complete
                    val query = QueryContract(id, SqlOperationClassifier.operation(jdbc.sqlString), jdbc.sqlString,
                        tables = deps.tables, columns = deps.columns, writeColumns = deps.writes,
                        parameterTypes = parameterTypes(jdbc.parameterBinders), source = source,
                        dependenciesComplete = complete, variant = pagination?.let { "firstResult=${it.firstResult};maxResults=${it.maxResults}" } ?: variant, targetTable = deps.target)
                    val limitations = mutableListOf<UnresolvedQuery>()
                    if (!complete) limitations += UnresolvedQuery(id, deps.failureReason ?:
                        astDependencies.limitations.takeIf { it.isNotEmpty() }?.joinToString("; ") ?:
                        "HQL includes opaque SQL expressions or unresolved table aliases")
                    val metadata = property("getParameterMetadata") as org.hibernate.query.spi.ParameterMetadataImplementor
                    if (metadata.registrations.any { it.allowsMultiValuedBinding() })
                        limitations += UnresolvedQuery(id, "Collection-valued HQL parameter cardinalities must be explicitly enumerated")
                    ExtractedSql(query, limitations)
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
                QueryExtraction.record(id, "Native SQL parsing/classification failed", queries, unresolved, diagnostics) {
                    val recognizer = ParameterRecognizerImpl()
                    ParameterParser.parse(sql, recognizer)
                    recognizer.complete()
                    val adjusted = recognizer.adjustedSqlString
                    val dependencies = SqlDependencies.inspect(adjusted, request.hibernateProperties["hibernate.default_schema"] ?: "public", nativeSql = true, sourceId = id)
                    val query = QueryContract(id, SqlOperationClassifier.operation(adjusted), adjusted, source = source,
                        tables = dependencies.tables, columns = dependencies.columns, writeColumns = dependencies.writes,
                        targetTable = dependencies.target,
                        parameterTypes = request.nativeQueryParameterTypes[id] ?: List((recognizer.javaClass.getMethod("getParameterList").invoke(recognizer) as? List<*>)?.size ?: 0) { "OTHER" },
                        dependenciesComplete = dependencies.complete, explicitColumnList = dependencies.explicitColumnList)
                    ExtractedSql(query, if (dependencies.complete) emptyList() else listOf(UnresolvedQuery(id,
                        dependencies.failureReason ?: "Native SQL is retained for PostgreSQL preparation, but wildcard/nested/function/conflict variants or ambiguous alias dependencies are not complete; declare a supported explicit-column template or retain this unresolved access path")))
                }
            }
            named.visitNativeQueryMementos { native(it.registrationName, it.sqlString, QuerySource.NAMED_NATIVE) }
            request.nativeQueries.toSortedMap().forEach { (id, text) -> native(id, text, QuerySource.STATIC) }
            named.visitCallableQueryMementos { unresolved += UnresolvedQuery(it.registrationName, "Stored procedures are unsupported") }
        } finally { session.close() }
        val allUnresolved = (unresolved + diagnostics.unresolved()).distinct().sortedWith(compareBy({ it.id }, { it.reason }))
        return DatabaseContract(applicationVersion = request.applicationVersion, entities = entities.sortedBy { it.id },
            queries = queries.sortedBy { it.id }, coverage = Coverage(complete = allUnresolved.isEmpty(),
                mechanisms = listOf("Hibernate ${org.hibernate.Version.getVersionString()} runtime mapping metamodel", "Hibernate static mutation plans", "Hibernate single-id loader SQL", "Hibernate SQM/SQL AST translation"),
                notes = listOf("Scoped to extracted entity operations, supplied query definitions, and inventoried access paths; unsupported or unenumerated paths are unresolved. This does not prove whole-application behavior.",
                    "Generated SQL covers the default single-id loader and static entity mutations only; entity graphs, alternate lock modes, batch loaders and undeclared repository CRUD variants are not enumerated. Literal local HQL pagination is rendered through Hibernate; dynamic/branch-dependent, named/native and Panache pagination remain unresolved.",
                    "Finite variants are user-declared exhaustive sets, not inferred application coverage.",
                    "Identity/serial and non-pooled physical sequence dependencies are checked; pooled/custom allocation, dynamic mutations, filters, custom SQL, formulas and native semantics remain unsupported when present.") + scopeNotes),
            unresolvedQueries = allUnresolved)
    }

    private fun extractCollections(factory: SessionFactoryImplementor, request: ExtractionRequest,
                                   entities: MutableList<EntityContract>, queries: MutableList<QueryContract>,
                                   unresolved: MutableList<UnresolvedQuery>, unbounded: Set<BootColumnBounds.Key>,
                                   diagnostics: ExtractionDiagnostics) {
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
            fun capture(suffix: String, operation: Operation, sql: String, binders: List<JdbcParameterBinder>): ExtractedSql {
                val id = "$role.$suffix"
                val deps = SqlDependencies.inspect(sql, request.hibernateProperties["hibernate.default_schema"] ?: "public", sourceId = id)
                val query = QueryContract(id, operation, sql, deps.tables, deps.columns, deps.writes,
                    parameterTypes = parameterTypes(binders), source = QuerySource.HIBERNATE_MAPPING,
                    dependenciesComplete = deps.complete, targetTable = deps.target,
                    mappedEntityIds = if (if (operation == Operation.SELECT) table in deps.tables else deps.target == table)
                        listOf("$role@collection") else emptyList())
                return ExtractedSql(query, if (deps.complete) emptyList() else listOf(UnresolvedQuery(id,
                    deps.failureReason ?: "Collection SQL dependency resolution is incomplete")))
            }
            QueryExtraction.record("$role.load", "Hibernate collection loader translation failed", collectionQueries, unresolved, diagnostics) {
                val loader = org.hibernate.loader.ast.internal.CollectionLoaderSingleKey(p.attributeMapping, LoadQueryInfluencers(factory), factory)
                val jdbc = HibernateSqlOperations.translate(factory, loader.sqlAst, org.hibernate.query.spi.QueryOptions.NONE)
                capture("load", Operation.SELECT, jdbc.sqlString, jdbc.parameterBinders)
            }
            val writable = QueryExtraction.attempt("$role.mutations", "Hibernate collection write metadata failed", unresolved, diagnostics) {
                p.isMutable && !p.isInverse && (owner == null || operations(request, owner).any { it != Operation.SELECT })
            } == true
            if (writable) {
                val rows = QueryExtraction.attempt("$role.mutations", "Hibernate collection mutation SPI failed", unresolved, diagnostics) {
                    // This protected SPI is the source of Hibernate's actual row mutations; no SQL is synthesized.
                    val method = org.hibernate.persister.collection.BasicCollectionPersister::class.java.getDeclaredMethod("getRowMutationOperations")
                    method.isAccessible = true
                    method.invoke(p) as org.hibernate.persister.collection.mutation.RowMutationOperations
                }
                if (rows != null) for (operation in listOf(Operation.INSERT, Operation.UPDATE, Operation.DELETE)) {
                    val suffix = operation.name.lowercase()
                    QueryExtraction.record("$role.$suffix", "Hibernate collection mutation extraction failed", collectionQueries, unresolved, diagnostics) {
                        val mutation = when (operation) {
                            Operation.INSERT -> if (rows.hasInsertRow()) rows.insertRowOperation else null
                            Operation.UPDATE -> if (rows.hasUpdateRow()) rows.updateRowOperation else null
                            Operation.DELETE -> if (rows.hasDeleteRow()) rows.deleteRowOperation else null
                            else -> error("Unexpected collection mutation $operation")
                        }
                        when {
                            mutation == null -> null
                            mutation.isCallable -> ExtractedSql(limitations = listOf(UnresolvedQuery("$role.$suffix", "Callable collection mutation is unsupported")))
                            else -> capture(suffix, operation, mutation.sqlString, mutation.parameterBinders)
                        }
                    }
                }
                QueryExtraction.record("$role.deleteAll", "Hibernate collection delete-all translation failed", collectionQueries, unresolved, diagnostics) {
                    if (!p.needsRemove()) null else {
                        val ast = p.generateDeleteAllAst(org.hibernate.sql.model.ast.MutatingTableReference(p.collectionTableMapping))
                        val mutation = factory.jdbcServices.jdbcEnvironment.sqlAstTranslatorFactory.buildModelMutationTranslator(ast, factory)
                            .translate(JdbcParameterBindings.NO_BINDINGS, org.hibernate.query.spi.QueryOptions.NONE)
                        capture("deleteAll", Operation.DELETE, mutation.sqlString, mutation.parameterBinders)
                    }
                }
            }
            queries += collectionQueries
            val observedColumns = collectionQueries.flatMap { it.columns }.filter { it.table == table }.map { it.column }.toSet()
            if (observedColumns.any { it !in selectables }) unresolved += UnresolvedQuery(role,
                "Collection SQL includes columns without complete selectable metadata: ${(observedColumns - selectables.keys).sorted()}")
            entities += EntityContract("$role@collection", table, selectables.map { (name, s) ->
                val valueMetadata = MappingValueMetadata.inspect(s.jdbcMapping)
                val directInput = inputDomainComplete(s.jdbcMapping, valueMetadata) &&
                    (factory.sessionFactoryOptions.isCheckNullability || s.isNullable) &&
                    s.customWriteExpression.let { it == null || it.trim() == "?" } &&
                    !(collectionQueries.any { it.operation == Operation.INSERT } && p.collectionTableMapping.insertDetails.customSql != null) &&
                    !(collectionQueries.any { it.operation == Operation.UPDATE } && p.collectionTableMapping.updateDetails.customSql != null)
                ColumnContract(name, jdbcName(s.jdbcMapping), s.isNullable, s.isInsertable, s.isUpdateable,
                    length = s.length, precision = s.precision, scale = s.scale, enumValues = valueMetadata.enumValues,
                    logicalJavaType = logicalType(s.jdbcMapping).name, inputDomainComplete = directInput,
                    readDomainComplete = readDomainComplete(s, valueMetadata),
                    stringInputLengthUnrestricted = directInput && unrestrictedStringBinding(s.jdbcMapping),
                    hibernateNullable = s.isNullable, enumMode = valueMetadata.enumMode,
                    enumConstants = valueMetadata.enumConstants, enumRelationalValues = valueMetadata.enumRelationalValues,
                    enumEncoding = valueMetadata.enumEncoding, converterClass = valueMetadata.converterClass,
                    converterBytecodeSha256 = valueMetadata.converterBytecodeSha256,
                    lengthUnbounded = directInput && logicalType(s.jdbcMapping) == String::class.java &&
                        BootColumnBounds.Key("$role@collection", table, name) in unbounded)
            }.sortedBy { it.name }, collectionQueries.map { it.operation }.toSet().ifEmpty { setOf(Operation.SELECT) })
        }
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
        private fun inputDomainComplete(mapping: org.hibernate.metamodel.mapping.JdbcMapping, facts: MappingValueMetadata.Facts): Boolean {
            if (mapping.valueConverter != null && !knownExplicitEnum(mapping, facts)) return false
            // Recognized standard native PostgreSQL enums live outside descriptor.jdbc. Their finite
            // relational values are established by exact EnumJavaType/JdbcType metadata above.
            if (logicalType(mapping).isEnum) return facts.enumEncoding != null && facts.enumRelationalValues.isNotEmpty()
            // A custom JavaType can change even non-null inputs before the ordinary JDBC binder sees them.
            return mapping.javaTypeDescriptor.javaClass.name in READ_JAVA_TYPES && mapping.jdbcType.javaClass.name in READ_JDBC_TYPES
        }
        private fun unrestrictedStringBinding(mapping: org.hibernate.metamodel.mapping.JdbcMapping): Boolean =
            mapping.valueConverter == null &&
                mapping.javaTypeDescriptor.javaClass == org.hibernate.type.descriptor.java.StringJavaType::class.java &&
                mapping.jdbcType.javaClass in setOf<Class<*>>(org.hibernate.type.descriptor.jdbc.VarcharJdbcType::class.java,
                    org.hibernate.type.descriptor.jdbc.LongVarcharJdbcType::class.java)

        private fun knownExplicitEnum(mapping: org.hibernate.metamodel.mapping.JdbcMapping, facts: MappingValueMetadata.Facts): Boolean =
            mapping.valueConverter?.javaClass?.name == "org.hibernate.boot.model.process.internal.EnumeratedValueConverter" &&
                facts.enumEncoding == EnumEncoding.EXPLICIT && facts.enumConstants.isNotEmpty() &&
                facts.enumConstants.size == facts.enumRelationalValues.size &&
                facts.enumRelationalValues.distinct().size == facts.enumRelationalValues.size

        private fun readDomainComplete(selectable: SelectableMapping, facts: MappingValueMetadata.Facts): Boolean {
            if (selectable.isFormula || !HibernateReadMetadata.directRead(selectable.customReadExpression, selectable.selectionExpression)) return false
            val mapping = selectable.jdbcMapping
            if (mapping.valueConverter != null && !knownExplicitEnum(mapping, facts)) return false
            if (logicalType(mapping).isEnum) return facts.enumEncoding != null && facts.enumRelationalValues.isNotEmpty()
            val javaType = mapping.javaTypeDescriptor.javaClass.name
            if (javaType !in READ_JAVA_TYPES) return false
            return mapping.jdbcType.javaClass.name in READ_JDBC_TYPES
        }
        private val READ_JAVA_TYPES = setOf("StringJavaType", "CharacterJavaType", "LongJavaType", "IntegerJavaType",
            "ShortJavaType", "ByteJavaType", "BooleanJavaType", "DoubleJavaType", "FloatJavaType", "BigDecimalJavaType",
            "BigIntegerJavaType", "UUIDJavaType", "LocalDateJavaType", "LocalDateTimeJavaType", "OffsetDateTimeJavaType",
            "LocalTimeJavaType", "OffsetTimeJavaType", "InstantJavaType", "DateJavaType", "JdbcDateJavaType",
            "JdbcTimestampJavaType", "JdbcTimeJavaType", "PrimitiveByteArrayJavaType")
            .map { "org.hibernate.type.descriptor.java.$it" }.toSet()
        private val READ_JDBC_TYPES = setOf("CharJdbcType", "NCharJdbcType", "VarcharJdbcType", "NVarcharJdbcType",
            "LongVarcharJdbcType", "LongNVarcharJdbcType", "TinyIntJdbcType", "TinyIntAsSmallIntJdbcType", "SmallIntJdbcType",
            "IntegerJdbcType", "BigIntJdbcType", "BooleanJdbcType", "BitJdbcType", "DoubleJdbcType", "FloatJdbcType",
            "RealJdbcType", "DecimalJdbcType", "NumericJdbcType", "UUIDJdbcType", "DateJdbcType", "TimeJdbcType",
            "TimeWithTimeZoneJdbcType", "TimeUtcAsOffsetTimeJdbcType", "TimestampJdbcType", "TimestampWithTimeZoneJdbcType",
            "TimestampUtcAsJdbcTimestampJdbcType", "TimestampUtcAsOffsetDateTimeJdbcType", "BinaryJdbcType", "VarbinaryJdbcType",
            "LongVarbinaryJdbcType").map { "org.hibernate.type.descriptor.jdbc.$it" }.toSet() + setOf(
            "org.hibernate.dialect.PostgreSQLUUIDJdbcType", "org.hibernate.dialect.type.PostgreSQLUUIDJdbcType")

        private fun parameterTypes(binders: List<JdbcParameterBinder>): List<String> = binders.map {
            val parameter = it as? JdbcParameter
            parameter?.expressionType?.getJdbcMapping(0)?.let(::jdbcName) ?: "UNKNOWN"
        }
    }
}
