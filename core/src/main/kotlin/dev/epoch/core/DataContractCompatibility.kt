package dev.epoch.core

/**
 * Compares admitted, directly mapped new values with actual old mapped decoders.
 * This is separate from SQL/schema compatibility and does not execute application code.
 */
class DataContractCompatibility {
    fun assess(old: DatabaseContract, new: DatabaseContract, finalSchema: SchemaSnapshot,
               validOldQueryIds: Set<String>, validNewQueryIds: Set<String>): CompatibilityReport {
        val oldContract = normalize(old)
        val newContract = normalize(new)
        val oldAccepted = if (supportedFormat(oldContract)) validOldQueryIds - duplicateIds(oldContract) else emptySet()
        val newAccepted = if (supportedFormat(newContract)) validNewQueryIds - duplicateIds(newContract) else emptySet()
        val findings = mutableListOf<Finding>()
        fun add(verdict: Verdict, rule: String, objectName: String, message: String, evidence: String,
                readers: List<String> = emptyList(), writers: List<String> = emptyList()) {
            findings += Finding(verdict, rule, objectName, message, evidence,
                (readers.map { "old/$it" } + writers.map { "new/$it" }).distinct().sorted(), assessment,
                when (verdict) {
                    Verdict.BREAKING -> "Keep new stored values within the old reader's supported domain until old releases are retired."
                    Verdict.UNKNOWN -> "Provide supported reader/writer provenance or an independent deterministic proof for this data path."
                    Verdict.SAFE -> null
                })
        }
        fun unknown(rule: String, name: String, message: String, evidence: String,
                    readers: List<String> = emptyList(), writers: List<String> = emptyList()) =
            add(Verdict.UNKNOWN, rule, name, message, evidence, readers, writers)
        for ((label, contract) in listOf("old" to oldContract, "new" to newContract)) {
            if (contract.schemaVersion != 1 || contract.dialect != "PostgreSQL") unknown("DATA_CONTRACT_UNSUPPORTED", label,
                "The data-domain contract format or dialect is unsupported.", "schemaVersion=${contract.schemaVersion}; dialect=${contract.dialect}")
            if (!contract.coverage.complete || contract.unresolvedQueries.isNotEmpty() ||
                contract.accessPaths.any { it.status == AccessPathStatus.UNRESOLVED }) unknown("DATA_CONTRACT_COVERAGE_UNPROVEN", label,
                "Not every declared access path has a supported data-domain description.",
                "coverage.complete=${contract.coverage.complete}; unresolved=${contract.unresolvedQueries.map { it.id }.sorted()}; notes=${contract.coverage.notes.sorted()}")
            if (contract.scope != VerificationScope.STRUCTURAL_CONTRACT) unknown("DATA_WHOLE_APPLICATION_UNPROVEN", label,
                "Whole-application behavioral equivalence is not established by mapped domains.", "scope=${contract.scope}")
            val ids = contract.entities.map { it.id } + contract.queries.map { it.id }
            if (ids.distinct().size != ids.size) unknown("DATA_AMBIGUOUS_PROVENANCE", label,
                "Duplicate contract identifiers make reader or writer provenance ambiguous.", "Duplicate IDs=${ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()}")
        }
        val oldEntities = uniqueEntities(oldContract)
        val newEntities = uniqueEntities(newContract)
        val newQueries = newContract.queries.filter { it.operation == Operation.INSERT || it.operation == Operation.UPDATE }
        val newTargets = newQueries.mapNotNull(::target).toSet()
        val readers = mutableListOf<Reader>()
        val oldReadQueries = oldContract.queries.filter { it.operation == Operation.SELECT }
        val oldReadTables = oldReadQueries.flatMap { it.tables + it.mappedReads.map { read -> read.column.table } }.toSet()
        // Physical names are not a proof of disjoint effects through views, inheritance or user code.
        val allNewWrites = newContract.queries.filter { it.operation != Operation.SELECT }
        for (query in newContract.queries) {
            if (!validQuery(query, newAccepted) || query.source != QuerySource.HIBERNATE_MAPPING ||
                query.operation == Operation.SELECT && !query.mappedReadCoverageComplete) unknown("DATA_WRITE_EFFECT_UNPROVEN", query.id,
                "The statement's complete direct database effects are not established by the supported mapped model.",
                "source=${query.source}; operation=${query.operation}; dependenciesComplete=${query.dependenciesComplete}; acceptedFinalPlan=${query.id in newAccepted}; SQL=${query.sql}",
                writers = listOf(query.id))
        }
        if (allNewWrites.isNotEmpty()) for (name in oldReadTables.sorted()) {
            val table = finalSchema.tables[name] ?: continue
            if ((table.kind != "table" && (table.view?.backingTable == null || table.view.backingTable in newTargets)) ||
                table.relationSecurity?.inheritance == true) unknown("DATA_READ_EFFECT_UNPROVEN", name,
                "An old view or inherited relation may expose data written through another physical relation.",
                "kind=${table.kind}; backing=${table.view?.backingTable}; inheritance=${table.relationSecurity?.inheritance}",
                oldReadQueries.filter { name in it.tables }.map { it.id }, allNewWrites.map { it.id })
        }
        if (oldReadQueries.isNotEmpty()) for (query in allNewWrites) {
            val relation = target(query)?.let(finalSchema.tables::get)
            if (relation == null || relation.kind != "table" || relation.relationSecurity?.let {
                    it.userTriggers || it.userRules || it.rowSecurity || it.inheritance
                } != false || opaqueStoredEffects(relation, query, finalSchema)) unknown("DATA_WRITE_EFFECT_UNPROVEN", query.id,
                "The new writer has unestablished or indirect stored-value effects.",
                "target=${target(query)}; relation kind=${relation?.kind}; security=${relation?.relationSecurity}; SQL=${query.sql}", writers = listOf(query.id))
        }
        for (query in oldReadQueries) {
            val relevant = (!query.mappedReadCoverageComplete && newContract.queries.any { it.operation != Operation.SELECT }) ||
                !query.dependenciesComplete || query.tables.any { it in newTargets } ||
                query.mappedReads.any { it.column.table in newTargets }
            if (!relevant) continue
            if (!validQuery(query, oldAccepted) || !query.mappedReadCoverageComplete) unknown(
                "DATA_READ_PROVENANCE_UNPROVEN", query.id, "The old query's complete mapped decoder set is not established.",
                "acceptedFinalPlan=${query.id in oldAccepted}; dependenciesComplete=${query.dependenciesComplete}; mappedReadCoverageComplete=${query.mappedReadCoverageComplete}; SQL=${query.sql}", listOf(query.id))
            // Positive individual bindings remain useful even when another read path is unsupported.
            if (!validQuery(query, oldAccepted)) continue
            query.mappedReads.distinct().forEach { binding ->
                val entity = oldEntities[binding.entityId]
                val column = entity?.takeIf { it.table == binding.column.table }?.columns?.singleOrNull { it.name == binding.column.column }
                if (column == null || finalSchema.tables[binding.column.table]?.columns?.containsKey(binding.column.column) != true) {
                    unknown("DATA_READ_PROVENANCE_UNPROVEN", display(binding.column), "An old decoded column cannot be linked to its final physical mapping.",
                        "mapping=${binding.entityId}; column=${display(binding.column)}", listOf(query.id))
                } else readers += Reader(query, entity, column, binding.column)
            }
        }
        if (oldReadQueries.isEmpty() && oldContract.entities.any { Operation.SELECT in it.operations }) unknown(
            "DATA_READ_PROVENANCE_UNPROVEN", "old", "Mappings declare reads without actual decoded SQL provenance.", "No old SELECT template establishes a mapped reader.")
        if (newQueries.isEmpty() && newContract.entities.any { entity -> entity.operations.any { it == Operation.INSERT || it == Operation.UPDATE } }) unknown(
            "DATA_WRITE_PROVENANCE_UNPROVEN", "new", "Mappings declare writes without actual generated SQL provenance.", "No new INSERT or UPDATE template establishes a producer.")

        val readersByReference = readers.groupBy { it.reference }
        val readersByTable = readers.groupBy { it.reference.table }
        val writers = mutableListOf<Writer>()
        for (query in newQueries) {
            val table = target(query)
            if (table != null && table !in oldReadTables && table !in readersByTable &&
                standardSingleTargetWrite(query, newEntities, newAccepted)) continue
            if (!validQuery(query, newAccepted) || query.source != QuerySource.HIBERNATE_MAPPING ||
                query.mappedEntityIds.isEmpty() || table == null || query.mappedEntityIds.any { it !in newEntities }) {
                unknown("DATA_WRITE_PROVENANCE_UNPROVEN", query.id, "The new statement lacks supported direct mapped producer provenance.",
                    "acceptedFinalPlan=${query.id in newAccepted}; source=${query.source}; target=$table; mapping IDs=${query.mappedEntityIds}; SQL=${query.sql}", writers = listOf(query.id))
                continue
            }
            val mappings = query.mappedEntityIds.mapNotNull(newEntities::get).filter { it.table == table }
            for (reference in query.writeColumns.distinct()) {
                if (reference.table != table) continue
                val applicable = mappings.mapNotNull { entity -> entity.columns.singleOrNull { it.name == reference.column }?.let { entity to it } }
                for ((entity, column) in applicable) {
                    val allowed = query.operation in entity.operations && if (query.operation == Operation.INSERT)
                        column.insertable && !column.generated else column.updatable
                    if (allowed) writers += Writer(query, entity, column, reference)
                }
                if (applicable.isEmpty() && query.discriminatorWrites.none { it.column == reference } && reference in readersByReference) unknown(
                    "DATA_WRITE_PROVENANCE_UNPROVEN", display(reference), "A new assigned column has no supported mapped value domain.", query.sql,
                    writers = listOf(query.id))
            }
            // Omission is still a write. Without a supported value model it cannot silently disappear.
            if (query.operation == Operation.INSERT) for (read in readersByTable[table].orEmpty()) {
                if (query.writeColumns.none { it == read.reference } && query.discriminatorWrites.none { it.column == read.reference }) {
                    val oldSameOmission = oldContract.queries.any { previous ->
                        previous.id in oldAccepted && previous.operation == Operation.INSERT && previous.source == QuerySource.HIBERNATE_MAPPING &&
                            target(previous) == table && previous.explicitColumnList && previous.writeColumns.none { it == read.reference } &&
                            previous.mappedEntityIds.any { id -> oldEntities[id]?.columns?.any { it.name == read.column.name && it.generated } == true }
                    }
                    val physical = finalSchema.tables[table]?.columns?.get(read.reference.column)
                    if (!(oldSameOmission && read.column.generated && physical?.nullable == false && physical.generated.isNullOrEmpty() &&
                            (!physical.identity.isNullOrEmpty() || physical.defaultExpression != null))) unknown(
                        "DATA_IMPLICIT_WRITE_UNPROVEN", display(read.reference), "The new INSERT omits an old decoded column whose resulting value domain is not established.",
                        "Default=${physical?.defaultExpression}; identity=${physical?.identity}; generated=${physical?.generated}; SQL=${query.sql}", listOf(read.query.id), listOf(query.id))
                }
            }
        }

        val writersByReference = writers.groupBy { it.reference }
        for (read in readers.distinctBy { Triple(it.query.id, it.entity.id, it.reference) }) {
            val matching = writersByReference[read.reference].orEmpty().groupBy { it.entity.id to it.column }
            for ((_, group) in matching.toSortedMap(compareBy<Pair<String, ColumnContract>> { it.first }.thenBy { it.second.name })) {
                val writer = group.first()
                val readerIds = listOf(read.query.id, read.entity.id)
                val writerIds = (group.map { it.query.id } + writer.entity.id).distinct().sorted()
                val objectName = display(read.reference)
                val oldColumn = read.column
                val newColumn = writer.column
                val physical = finalSchema.tables[read.reference.table]?.columns?.get(read.reference.column)
                if (!ordinaryStoredValues(finalSchema.tables[read.reference.table]) || physical?.typeAcceptsImplicitNull != true ||
                    !physical.generated.isNullOrEmpty()) {
                    unknown("DATA_WRITE_EFFECT_UNPROVEN", objectName,
                        "Physical stored-value semantics are not established for a direct mapped-domain proof.",
                        "type=${physical?.type}; ordinary relation=${ordinaryStoredValues(finalSchema.tables[read.reference.table])}; generated=${physical?.generated}", readerIds, writerIds)
                    continue
                }
                if (!oldColumn.readDomainComplete || !newColumn.inputDomainComplete) {
                    val converter = oldColumn.converterClass != null || newColumn.converterClass != null
                    unknown(if (converter) "DATA_CONVERTER_SEMANTICS_UNPROVEN" else "DATA_TYPE_MAPPING_UNPROVEN", objectName,
                        "The old decoder or new producer has unsupported conversion semantics.",
                        "old readDomainComplete=${oldColumn.readDomainComplete}; new inputDomainComplete=${newColumn.inputDomainComplete}; " +
                            "old converter=${oldColumn.converterClass}:${oldColumn.converterBytecodeSha256}; new converter=${newColumn.converterClass}:${newColumn.converterBytecodeSha256}", readerIds, writerIds)
                    continue
                }
                var proven = true
                fun candidate(value: String?, rule: String, message: String, reason: String) {
                    val evidence = group.map { it.query to DataWriteDomain.assess(newContract, it.query, read.reference, value, finalSchema) }
                    val allowed = evidence.firstOrNull { it.second.accepted == true }
                    when {
                        allowed != null -> {
                            add(Verdict.BREAKING, rule, objectName, message,
                                "$reason; writer=${allowed.first.id}; ${allowed.second.evidence}", readerIds, writerIds)
                            proven = false
                        }
                        evidence.all { it.second.accepted == false } -> Unit // The final schema prevents this candidate from reaching an old reader.
                        else -> {
                            unknown("DATA_WRITE_ACCEPTANCE_UNPROVEN", objectName,
                                "A new candidate is outside the old read domain, but its complete stored-row admission is not established.",
                                "$reason; ${evidence.map { "${it.first.id}: ${it.second.evidence}" }.distinct().sorted().joinToString("; ")}", readerIds, writerIds)
                            proven = false
                        }
                    }
                }
                val oldEnum = enumDomain(oldColumn)
                val newEnum = enumDomain(newColumn)
                if (oldEnum != null && newEnum != null && (!enumStorageKnown(physical.type, newEnum.keys + oldEnum.keys, finalSchema) ||
                        !enumReaderRepresentationKnown(oldColumn, newEnum.keys))) {
                    unknown("DATA_TYPE_MAPPING_UNPROVEN", objectName,
                        "The final physical type may transform finite enum values before the old decoder observes them.",
                        "type=${physical.type}; new relational values=${newEnum.keys.sorted()}", readerIds, writerIds)
                    proven = false
                } else if (oldEnum != null && newEnum != null) {
                    for ((value, constant) in newEnum.toSortedMap()) {
                        val oldConstant = oldEnum[value]
                        if (oldConstant == null) {
                            if (oldColumn.enumEncoding == EnumEncoding.EXPLICIT) {
                                unknown("DATA_CONVERTER_SEMANTICS_UNPROVEN", objectName,
                                    "An unknown explicit enum code may decode to NULL; its old declared use is not established.",
                                    "New stored code=$value; old accepted codes=${oldEnum.keys.sorted()}; new constant=$constant.", readerIds, writerIds)
                                proven = false
                            } else candidate(value, "DATA_ENUM_VALUE_UNREADABLE", "A newly writable enum value is outside the old finite decoder.",
                                "New constant=$constant stores '$value'; old encoding=${oldColumn.enumEncoding}; old accepted values=${oldEnum.keys.sorted()}")
                        } else if (oldConstant != constant) candidate(value, "DATA_ENUM_VALUE_REINTERPRETED",
                            "The same stored enum value means a different logical constant to the old release.",
                            "Stored '$value' means new=$constant and old=$oldConstant; old encoding=${oldColumn.enumEncoding}; new encoding=${newColumn.enumEncoding}")
                    }
                } else if (oldEnum != null || newEnum != null || !equivalentScalar(oldColumn, newColumn)) {
                    unknown("DATA_TYPE_MAPPING_UNPROVEN", objectName, "The logical mapped type or codec changed without a supported read-domain inclusion proof.",
                        "old logical=${oldColumn.logicalJavaType}, JDBC=${oldColumn.typeName}, enum=${oldColumn.enumEncoding}; new logical=${newColumn.logicalJavaType}, JDBC=${newColumn.typeName}, enum=${newColumn.enumEncoding}", readerIds, writerIds)
                    proven = false
                }
                if (oldColumn.declaredNonNull == true && permitsMappedNull(newColumn)) candidate(null, "DATA_NULLABILITY_RELAXED",
                    "The new writer admits NULL outside the old declared non-null read domain.",
                    "old declaredNonNull=true; new declaredNonNull=false with no Hibernate null restriction. This is a declared-domain violation, not a claim that all field hydration throws.")
                else if (oldColumn.declaredNonNull != false && !excludesMappedNull(newColumn) &&
                    !DataWriteDomain.excludesNull(read.reference, finalSchema)) {
                    unknown("DATA_NULL_DOMAIN_UNPROVEN", objectName, "The new writer's complete NULL domain or old NULL acceptance is unavailable.",
                        "old declaredNonNull=${oldColumn.declaredNonNull}; new declaredNonNull=${newColumn.declaredNonNull}; new Hibernate nullable=${newColumn.hibernateNullable}", readerIds, writerIds)
                    proven = false
                }
                if (proven) add(Verdict.SAFE, "DATA_DOMAIN_PRESERVED", objectName,
                    "Supported new mapped values remain within the old decoded domain.",
                    "Direct known codecs; finite enum interpretation or equivalent scalar domain; final-schema constraints exclude any rejected candidates; old mapping=${read.entity.id}; new mapping=${writer.entity.id}.", readerIds, writerIds)
            }
        }

        for (oldQuery in oldReadQueries.filter { validQuery(it, oldAccepted) }) {
            for (read in oldQuery.polymorphicReads) {
                val root = oldEntities[read.rootEntityId]
                val hierarchy = root?.hierarchy
                val column = hierarchy?.discriminator
                if (column == null) {
                    if (newQueries.any { target(it) == root?.table }) unknown("DATA_POLYMORPHIC_READ_UNPROVEN", root?.table ?: read.rootEntityId,
                        "The old polymorphic decoder has no complete physical discriminator model.",
                        "hierarchy=$hierarchy; reader=${oldQuery.id}", listOf(oldQuery.id, read.rootEntityId))
                    continue
                }
                for (query in newQueries.filter { validQuery(it, newAccepted) && it.operation == Operation.INSERT }) {
                    if (target(query) == column.table && query.discriminatorWrites.none { it.column == column }) unknown(
                        "DATA_WRITE_PROVENANCE_UNPROVEN", display(column), "The new INSERT has no established physical discriminator value.",
                        "SQL=${query.sql}; mapping IDs=${query.mappedEntityIds}", listOf(oldQuery.id, read.rootEntityId), listOf(query.id))
                    for (write in query.discriminatorWrites.filter { it.column == column }) {
                        val entity = newEntities[write.entityId]
                        val next = entity?.hierarchy
                        val readersIds = listOf(oldQuery.id, read.rootEntityId)
                        val writersIds = listOf(query.id, write.entityId)
                        if (hierarchy.strategy != "SINGLE_TABLE" || !hierarchy.complete || next?.strategy != "SINGLE_TABLE" ||
                            !next.complete || !next.concrete || next.entityValue != write.value || query.source != QuerySource.HIBERNATE_MAPPING ||
                            query.mappedEntityIds.none { it == write.entityId } || Operation.INSERT !in entity.operations) {
                            unknown("DATA_POLYMORPHIC_READ_UNPROVEN", display(column), "The physical discriminator decoder or concrete subtype producer is incomplete.",
                                "old hierarchy=$hierarchy; new hierarchy=$next; stored discriminator=${write.value}", readersIds, writersIds)
                        } else if (finalSchema.tables[column.table]?.columns?.get(column.column)?.type?.let {
                                enumStorageKnown(it, hierarchy.acceptedValues.toSet() + write.value, finalSchema)
                            } != true || !scalarReaderRepresentationKnown(hierarchy.discriminatorType, setOf(write.value))) {
                            unknown("DATA_TYPE_MAPPING_UNPROVEN", display(column), "Discriminator storage or JDBC extraction may change the declared scalar representation.",
                                "old JDBC=${hierarchy.discriminatorType}; old accepted=${hierarchy.acceptedValues.sorted()}; new literal=${write.value}", readersIds, writersIds)
                        } else if (write.value in hierarchy.acceptedValues) add(Verdict.SAFE, "DATA_DOMAIN_PRESERVED", display(column),
                            "The new concrete subtype uses a discriminator known to the old reader.",
                            "New stored discriminator=${write.value}; old accepted=${hierarchy.acceptedValues.sorted()}", readersIds, writersIds)
                        else if (!read.unrestricted) unknown("DATA_POLYMORPHIC_READ_UNPROVEN", display(column),
                            "A conditional old polymorphic reader is not proven to encounter the new subtype.",
                            "New discriminator=${write.value}; old unrestricted=false; old accepted=${hierarchy.acceptedValues.sorted()}", readersIds, writersIds)
                        else {
                            val admission = DataWriteDomain.assess(newContract, query, column, write.value, finalSchema)
                            if (admission.accepted == true) add(Verdict.BREAKING, "DATA_DISCRIMINATOR_UNREADABLE", display(column),
                                "A new concrete subtype can store a discriminator rejected by the old unrestricted root decoder.",
                                "New discriminator=${write.value}; old accepted=${hierarchy.acceptedValues.sorted()}; ${admission.evidence}", readersIds, writersIds)
                            else if (admission.accepted == null) unknown("DATA_WRITE_ACCEPTANCE_UNPROVEN", display(column),
                                "The new discriminator is unknown to the old reader, but complete row admission is not established.", admission.evidence, readersIds, writersIds)
                            else add(Verdict.SAFE, "DATA_DOMAIN_PRESERVED", display(column), "The final schema excludes the new unsupported discriminator.",
                                admission.evidence, readersIds, writersIds)
                        }
                    }
                }
            }
        }
        for ((tableName, table) in finalSchema.tables.toSortedMap()) {
            for ((constraintName, constraint) in table.constraints.toSortedMap()) {
                if (constraint.type != "f" || tableName !in oldReadTables) continue
                val affected = newContract.queries.filter { query -> target(query) == constraint.referencedTable &&
                    (query.operation == Operation.DELETE && constraint.onDelete in setOf("n", "d", "SET NULL", "SET DEFAULT") ||
                        query.operation == Operation.UPDATE && constraint.onUpdate in setOf("c", "n", "d", "CASCADE", "SET NULL", "SET DEFAULT")) }
                if (affected.isNotEmpty()) unknown("DATA_WRITE_EFFECT_UNPROVEN", "$tableName.$constraintName",
                    "A new referenced-row operation can indirectly change old decoded values through a foreign key action.",
                    constraint.definition, writers = affected.map { it.id })
            }
        }
        if (findings.isEmpty()) {
            if (oldReadQueries.isEmpty() && oldContract.entities.isEmpty() || newContract.queries.isEmpty() && newContract.entities.isEmpty())
                unknown("DATA_EMPTY_CONTRACT", "contract", "An empty data contract cannot establish compatibility.", "Old SELECT count=${oldReadQueries.size}; new statements=${newContract.queries.size}")
            else if (newQueries.any { target(it) == null || target(it) in oldReadTables })
                unknown("DATA_READ_PROVENANCE_UNPROVEN", "contract", "No supported mapped data-domain comparison was established for overlapping reads and writes.", "Relevant writers=${newQueries.map { it.id }.sorted()}")
            else add(Verdict.SAFE, "DATA_DOMAIN_PRESERVED", "contract", "No declared new data-producing operation intersects an old read path.",
                "Complete declared dependency sets contain no overlapping INSERT/UPDATE target and old SELECT relation.")
        }
        val ordered = findings.distinct().sortedWith(compareBy<Finding> { verdictOrder(it.verdict) }.thenBy { it.ruleId }
            .thenBy { it.objectName }.thenBy { it.affectedContractIds.joinToString() }.thenBy { it.evidence })
        val verdict = CompatibilityEngine.aggregate(ordered.map { it.verdict })
        return CompatibilityReport(applicationVersion = "${old.applicationVersion} <- ${new.applicationVersion}",
            scope = VerificationScope.STRUCTURAL_CONTRACT, verdict = verdict,
            migrations = FindingEpisodes.annotate(listOf(MigrationReport(assessment, verdict, ordered))), limitations = listOf(
                "New-writes-old-reads compares supported direct mapped data domains on the final deployment schema, separately from schema compatibility.",
                "A catalog row-domain witness is not execution against current row contents; uniqueness occupancy, application validation, arbitrary converters, and general behavioral equivalence are outside this proof.",
                "Only actual decoded reader and generated producer provenance is accepted. Unsupported or incomplete relevant paths remain UNKNOWN.",
            ), ruleSummary = ordered.groupBy { it.ruleId to it.verdict }.map { (key, values) ->
                RuleSummary(key.first, key.second, migrationCount = values.size)
            }.sortedWith(compareBy<RuleSummary> { verdictOrder(it.verdict) }.thenBy { it.ruleId }))
    }

    private data class Reader(val query: QueryContract, val entity: EntityContract, val column: ColumnContract, val reference: ColumnReference)
    private data class Writer(val query: QueryContract, val entity: EntityContract, val column: ColumnContract, val reference: ColumnReference)

    companion object {
        private val assessment = MigrationInfo("contract", "<old-vs-new-contract>")
        private val primitives = setOf("byte", "short", "int", "long", "float", "double", "boolean", "char")
        private val boxes = mapOf("byte" to "java.lang.Byte", "short" to "java.lang.Short", "int" to "java.lang.Integer", "long" to "java.lang.Long",
            "float" to "java.lang.Float", "double" to "java.lang.Double", "boolean" to "java.lang.Boolean", "char" to "java.lang.Character")
        private val scalars = boxes.values.toSet() + setOf("java.lang.String", "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.OffsetDateTime", "java.time.LocalTime", "java.time.OffsetTime",
            "java.time.Instant", "java.util.Date", "java.sql.Date", "java.sql.Timestamp", "java.sql.Time", "[B")
        private fun equivalentScalar(old: ColumnContract, new: ColumnContract): Boolean {
            val a = boxes[old.logicalJavaType] ?: old.logicalJavaType
            val b = boxes[new.logicalJavaType] ?: new.logicalJavaType
            return a != null && a == b && a in scalars && old.typeName?.uppercase() == new.typeName?.uppercase() &&
                old.converterClass == null && new.converterClass == null
        }
        private fun enumDomain(column: ColumnContract): Map<String, String>? {
            if (column.enumEncoding == null || column.enumConstants.isEmpty() || column.enumConstants.size != column.enumRelationalValues.size ||
                column.enumConstants.distinct().size != column.enumConstants.size || column.enumRelationalValues.distinct().size != column.enumRelationalValues.size) return null
            if (column.enumEncoding == EnumEncoding.NAME && column.enumConstants != column.enumRelationalValues) return null
            if (column.enumEncoding == EnumEncoding.ORDINAL && column.enumRelationalValues != column.enumConstants.indices.map(Int::toString)) return null
            return column.enumRelationalValues.zip(column.enumConstants).toMap()
        }
        private fun excludesMappedNull(column: ColumnContract) = column.declaredNonNull == true || column.hibernateNullable == false ||
            column.nullable == false || column.logicalJavaType in primitives
        private fun permitsMappedNull(column: ColumnContract) = column.declaredNonNull == false && !excludesMappedNull(column)
        private fun duplicateIds(contract: DatabaseContract) = (contract.entities.map { it.id } + contract.queries.map { it.id }).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        private fun uniqueEntities(contract: DatabaseContract): Map<String, EntityContract> {
            val duplicates = duplicateIds(contract)
            return contract.entities.groupBy { it.id }.filter { (id, values) -> values.size == 1 && id !in duplicates }.mapValues { it.value.single() }
        }
        private fun supportedFormat(contract: DatabaseContract) = contract.schemaVersion == 1 && contract.dialect == "PostgreSQL"
        private fun ordinaryStoredValues(table: TableSchema?) = table?.kind == "table" && table.relationSecurity?.let {
            !it.userTriggers && !it.userRules && !it.rowSecurity && !it.inheritance
        } == true
        private fun opaqueStoredEffects(table: TableSchema, query: QueryContract, snapshot: SchemaSnapshot): Boolean {
            if (query.operation == Operation.DELETE) return false
            if (table.columns.values.any { !it.generated.isNullOrEmpty() }) return true
            if (table.constraints.values.any { constraint -> constraint.type == "c" && run {
                val nonNull = RuleProofs.notNullCheck(constraint)
                val enum = RuleProofs.enumCheck(constraint)
                when {
                    nonNull != null -> table.columns[nonNull]?.let { it.typeAcceptsImplicitNull == true && RuleProofs.scalarNullTest(it.type, snapshot) } != true
                    enum != null -> snapshot.expressionSearchPath.firstOrNull() != "pg_catalog" || table.columns[enum.first]?.let {
                        it.collation == "pg_catalog.default" && Regex("(?:text|character varying(?:\\(\\d+\\))?|varchar(?:\\(\\d+\\))?)").matches(it.type)
                    } != true
                    else -> true
                }
            } }) return true
            if (table.indexes.keys.any { it !in table.plainIndexes }) return true
            return query.operation == Operation.INSERT && table.columns.any { (name, column) ->
                column.defaultExpression != null && query.writeColumns.none { it.column == name } &&
                    !RuleProofs.constantDefault(column.defaultExpression, column.type) &&
                    !RuleProofs.sequenceDefault(column.defaultExpression, column.type, snapshot)
            }
        }
        private fun standardSingleTargetWrite(query: QueryContract, entities: Map<String, EntityContract>, accepted: Set<String>): Boolean {
            val target = target(query) ?: return false
            if (!validQuery(query, accepted) || query.source != QuerySource.HIBERNATE_MAPPING || query.mappedEntityIds.isEmpty()) return false
            val mappings = query.mappedEntityIds.map { entities[it] ?: return false }
            if (mappings.any { it.table != target || query.operation !in it.operations }) return false
            return query.writeColumns.all { reference -> reference.table == target &&
                (query.discriminatorWrites.any { it.column == reference } || mappings.any { entity ->
                    entity.columns.any { it.name == reference.column && it.inputDomainComplete }
                }) }
        }
        private fun enumStorageKnown(type: String, values: Set<String>, snapshot: SchemaSnapshot): Boolean {
            if (snapshot.expressionSearchPath.firstOrNull() != "pg_catalog") return false
            if (type in snapshot.enumTypes || "public.$type" in snapshot.enumTypes) return true
            if (type in setOf("text", "character varying", "varchar")) return true
            Regex("(?:character varying|varchar)\\((\\d+)\\)").matchEntire(type)?.let { match ->
                val maximum = match.groupValues[1].toIntOrNull() ?: return false
                // PostgreSQL may silently truncate excess spaces; that changes relational meaning.
                return values.none { value -> value.codePointCount(0, value.length) > maximum &&
                    value.substring(value.offsetByCodePoints(0, maximum)).all { it == ' ' } }
            }
            return type in setOf("smallint", "integer", "bigint") && values.all { it.toBigIntegerOrNull()?.toString() == it }
        }
        private fun scalarReaderRepresentationKnown(type: String?, values: Set<String>) =
            type?.uppercase() !in setOf("TINYINT", "SMALLINT", "INTEGER", "INT", "BIGINT", "BYTE", "SHORT", "LONG") ||
                values.all { it.toBigIntegerOrNull()?.toString() == it }
        private fun enumReaderRepresentationKnown(column: ColumnContract, values: Set<String>): Boolean {
            if (column.enumEncoding == EnumEncoding.NAME && values.any { it.trim { char -> char <= ' ' } != it }) return false
            return scalarReaderRepresentationKnown(column.typeName, values) &&
                (column.enumEncoding != EnumEncoding.ORDINAL || values.all { it.toBigIntegerOrNull()?.toString() == it })
        }
        private fun validQuery(query: QueryContract, accepted: Set<String>) = query.id in accepted && query.sql.isNotBlank() &&
            query.dependenciesComplete && query.verificationStatus == VerificationStatus.EXTRACTED
        private fun target(query: QueryContract) = query.targetTable ?: query.tables.singleOrNull().takeIf { query.operation != Operation.SELECT }
        private fun display(column: ColumnReference) = "${column.table}.${column.column}"
        private fun postgresRelation(value: String) = value.split('.').joinToString(".", transform = ::postgresIdentifier)
        private fun verdictOrder(verdict: Verdict) = when (verdict) { Verdict.BREAKING -> 0; Verdict.UNKNOWN -> 1; Verdict.SAFE -> 2 }
        private fun normalize(contract: DatabaseContract): DatabaseContract {
            fun reference(value: ColumnReference) = ColumnReference(postgresRelation(value.table), postgresIdentifier(value.column))
            return contract.copy(entities = contract.entities.map { entity ->
                entity.copy(table = postgresRelation(entity.table), columns = entity.columns.map { it.copy(name = postgresIdentifier(it.name)) },
                    hierarchy = entity.hierarchy?.let { it.copy(discriminator = it.discriminator?.let(::reference)) })
            }, queries = contract.queries.map { query ->
                query.copy(tables = query.tables.map(::postgresRelation), columns = query.columns.map(::reference), writeColumns = query.writeColumns.map(::reference),
                    targetTable = query.targetTable?.let(::postgresRelation), mappedReads = query.mappedReads.map { it.copy(column = reference(it.column)) },
                    discriminatorWrites = query.discriminatorWrites.map { it.copy(column = reference(it.column)) })
            })
        }
    }
}
