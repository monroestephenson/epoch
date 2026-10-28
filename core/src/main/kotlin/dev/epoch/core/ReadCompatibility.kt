package dev.epoch.core

import java.math.BigInteger

/** Positive decoder proofs for type-only widening; exact declared SQL result contracts stay strict. */
class ReadCompatibility(
    private val contract: DatabaseContract,
    private val baseline: SchemaSnapshot,
    private val target: SchemaSnapshot,
    private val validBaselineIds: Set<String>,
    private val preservedIntegerColumns: Set<ColumnReference> = emptySet(),
) {
    private val duplicateIds = (contract.entities.map { it.id } + contract.queries.map { it.id })
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    private val entities = contract.entities.filter { it.id !in duplicateIds }.associateBy { it.id }
    private val queries = contract.queries.filter { it.id !in duplicateIds }.associateBy { it.id }
    private val readers = contract.queries.filter { it.operation == Operation.SELECT }
    private val writers = contract.queries.filter { it.operation != Operation.SELECT }
    private val entitiesByTable = entities.values.groupBy { relation(it.table) }
    private val writersByTable = writers.groupBy(::queryTarget)
    private val operationsByMapping = writers.flatMap { query -> query.mappedEntityIds.map { it to query.operation } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
    private val readersByColumn = readers.flatMap { query ->
        (query.columns.map { reference(it.table, it.column) } + query.mappedReads.map { reference(it.column.table, it.column.column) })
            .distinct().map { it to query }
    }.groupBy({ it.first }, { it.second })
    private val unknownReadersByTable = readers.filter { !it.dependenciesComplete || !it.explicitColumnList }
        .flatMap { query -> query.tables.map { relation(it) to query } }.groupBy({ it.first }, { it.second })
    private val baselineScope = BehaviorScope(baseline)
    private val targetScope = BehaviorScope(target)
    private val memo = mutableMapOf<ColumnReference, List<String>?>()
    private val writerBounds = mutableMapOf<Pair<ColumnReference, Int>, Boolean>()
    private data class ProducerEffects(val known: Boolean, val references: Set<BehaviorReference> = emptySet())
    private val producerEffects: ProducerEffects by lazy { producerEffects() }

    fun columnCompatible(table: String, column: String): Boolean = columnProof(reference(table, column)) != null
    fun columnEvidence(table: String, column: String): List<String> = columnProof(reference(table, column)).orEmpty()

    /** Positions are actual Hibernate SqlSelection JDBC indexes, never guessed from labels/order. */
    fun resultCompatible(query: QueryContract, before: List<ResultColumn>, after: List<ResultColumn>): Boolean {
        val actual = queries[query.id] ?: return false
        if (query != actual || !supportedReader(actual) || actual.exactResultShape || actual.resultColumns.isNotEmpty() ||
            before.size != after.size || before.isEmpty()) return false
        if (before.indices.any { before[it].name != after[it].name }) return false
        return before.indices.all { index ->
            if (before[index] == after[index]) true else {
                val bindings = actual.mappedReads.filter { it.resultPosition == index + 1 }
                val columns = bindings.map { reference(it.column.table, it.column.column) }.distinct()
                if (bindings.isEmpty() || columns.size != 1) false else {
                    val ref = columns.single()
                    val old = baseline.tables[ref.table]?.columns?.get(ref.column)
                    val next = target.tables[ref.table]?.columns?.get(ref.column)
                    old != null && next != null && resultType(old.type) == resultType(before[index].typeName) &&
                        resultType(next.type) == resultType(after[index].typeName) &&
                        bindings.all { binding -> decoder(binding)?.let { compatibleDecoder(ref, it) } != null } &&
                        columnProof(ref) != null
                }
            }
        }
    }

    private fun columnProof(ref: ColumnReference): List<String>? {
        if (memo.containsKey(ref)) return memo[ref]
        val proof = proveColumn(ref)
        memo[ref] = proof
        return proof
    }

    private fun proveColumn(ref: ColumnReference): List<String>? {
        val old = baseline.tables[ref.table]?.columns?.get(ref.column) ?: return null
        val next = target.tables[ref.table]?.columns?.get(ref.column) ?: return null
        if (!RuleProofs.widening(old.type, next.type) || old.type == next.type ||
            !ordinary(baseline.tables[ref.table]) || !ordinary(target.tables[ref.table])) return null
        val relevant = (readersByColumn[ref].orEmpty() + unknownReadersByTable[ref.table].orEmpty()).distinctBy { it.id }
        val bindings = mutableListOf<Pair<QueryContract, MappedColumnRead>>()
        for (query in relevant) {
            if (!supportedReader(query)) return null
            query.mappedReads.filter { reference(it.column.table, it.column.column) == ref }.forEach { bindings += query to it }
        }
        val declared = entitiesByTable[ref.table].orEmpty().filter { entity -> Operation.SELECT in entity.operations &&
            entity.columns.any { postgresIdentifier(it.name) == ref.column } }
        if (bindings.isEmpty() || declared.any { entity -> bindings.none { it.second.entityId == entity.id } }) return null
        return bindings.map { (query, binding) ->
            val column = decoder(binding) ?: return null
            val evidence = compatibleDecoder(ref, column) ?: return null
            "Reader=${query.id}; mapping=${binding.entityId}; column=${ref.table}.${ref.column}; $evidence"
        }.distinct().sorted()
    }

    private fun decoder(binding: MappedColumnRead): ColumnContract? {
        val entity = entities[binding.entityId]?.takeIf { it.id in validBaselineIds && Operation.SELECT in it.operations } ?: return null
        val ref = reference(binding.column.table, binding.column.column)
        if (relation(entity.table) != ref.table) return null
        val column = entity.columns.singleOrNull { postgresIdentifier(it.name) == ref.column } ?: return null
        if (!column.readDomainComplete || column.converterClass != null || column.enumValues.isNotEmpty() ||
            column.enumEncoding != null || column.enumConstants.isNotEmpty()) return null
        return column
    }

    private fun compatibleDecoder(ref: ColumnReference, column: ColumnContract): String? {
        val old = baseline.tables[ref.table]?.columns?.get(ref.column) ?: return null
        val next = target.tables[ref.table]?.columns?.get(ref.column) ?: return null
        if (!column.readDomainComplete || column.converterClass != null || !RuleProofs.widening(old.type, next.type)) return null
        if (column.logicalJavaType == "java.lang.String" && column.typeName?.uppercase() in stringJdbc &&
            stringType(old.type) && stringType(next.type)) {
            return "Standard String decoder preserves textual values across ${old.type} -> ${next.type}."
        }
        val javaBits = logicalBits(column.logicalJavaType) ?: return null
        val jdbcBits = integerBits(column.typeName) ?: return null
        val beforeBits = integerBits(old.type) ?: return null
        val afterBits = integerBits(next.type) ?: return null
        val decoderBits = minOf(javaBits, jdbcBits)
        if (afterBits <= decoderBits) return "Standard ${column.logicalJavaType}/${column.typeName} decoder covers the entire ${next.type} range."
        if (beforeBits > decoderBits || ref !in preservedIntegerColumns ||
            !writerBounds.getOrPut(ref to decoderBits) { closedIntegerWriters(ref, decoderBits) }) return null
        return "Original ${old.type} values remain unchanged and every declared direct producer preserves the signed $decoderBits-bit range; " +
            "standard ${column.logicalJavaType}/${column.typeName} still decodes them from ${next.type}. This bound is scoped to declared old operations."
    }

    private fun supportedReader(query: QueryContract) = query.id in validBaselineIds && query.id !in duplicateIds &&
        query.operation == Operation.SELECT && query.source == QuerySource.HIBERNATE_MAPPING && query.sql.isNotBlank() &&
        query.verificationStatus == VerificationStatus.EXTRACTED && query.dependenciesComplete && query.mappedReadCoverageComplete &&
        query.mappedEntityIds.isNotEmpty() && query.mappedEntityIds.all { it in entities && it in validBaselineIds }

    /** Compute contract-wide mutation coverage/scopes once, not once for every widened result slot. */
    private fun producerEffects(): ProducerEffects {
        if (!contract.coverage.complete || contract.unresolvedQueries.isNotEmpty() || duplicateIds.isNotEmpty() ||
            contract.accessPaths.any { it.status == AccessPathStatus.UNRESOLVED } ||
            contract.queries.any { !it.dependenciesComplete || it.verificationStatus != VerificationStatus.EXTRACTED }) return ProducerEffects(false)
        // Arbitrary DML can call functions that write another relation. The initial invariant model
        // admits only the extractor's direct mutation templates, not guessed native side effects.
        if (writers.any { it.source != QuerySource.HIBERNATE_MAPPING || it.id !in validBaselineIds || it.mappedEntityIds.isEmpty() }) return ProducerEffects(false)
        val references = linkedSetOf<BehaviorReference>()
        for (entity in entities.values) for (operation in entity.operations.filter { it != Operation.SELECT }) {
            if (entity.id !in validBaselineIds || operation !in operationsByMapping[entity.id].orEmpty()) return ProducerEffects(false)
            if (!ordinary(baseline.tables[relation(entity.table)]) || !ordinary(target.tables[relation(entity.table)])) return ProducerEffects(false)
            for (scope in listOf(baselineScope.forMapping(entity, operation), targetScope.forMapping(entity, operation))) {
                if (scope.uncertainties.isNotEmpty() || scope.writeTransformations.isNotEmpty()) return ProducerEffects(false)
                references += scope.references.filter { it.access in setOf(BehaviorAccess.INSERT, BehaviorAccess.UPDATE, BehaviorAccess.UNKNOWN) }
            }
        }
        return ProducerEffects(true, references)
    }

    /** Establish every operation separately; a finite UPDATE cannot discharge an omitted INSERT. */
    private fun closedIntegerWriters(ref: ColumnReference, bits: Int): Boolean {
        val effects = producerEffects
        if (!effects.known || effects.references.any { it.name == ref.table && (it.column == null || it.column == ref.column) }) return false
        val table = target.tables[ref.table] ?: return false
        if (!ordinary(table) || !ordinary(baseline.tables[ref.table])) return false
        // A FK owned by another table cannot assign this parent's unrelated value. A child-key
        // CASCADE/SET DEFAULT can; absent graph facts must not silently lose that producer.
        if (table.constraints.values.any { constraint -> constraint.type == "f" &&
                (constraint.columns.isEmpty() || ref.column in constraint.columns) &&
                (constraint.onUpdate?.uppercase() !in setOf("A", "R", "N", "NO ACTION", "RESTRICT", "SET NULL") ||
                    constraint.onDelete?.uppercase() !in setOf("A", "R", "C", "N", "NO ACTION", "RESTRICT", "CASCADE", "SET NULL")) }) return false
        val relevant = writersByTable[ref.table].orEmpty()
        val declared = entitiesByTable[ref.table].orEmpty()
        if (relevant.none { it.operation in setOf(Operation.INSERT, Operation.UPDATE) }) return false
        for (query in relevant) {
            if (query.operation == Operation.DELETE) continue
            if (!query.explicitColumnList || query.sql.isBlank() || query.mappedEntityIds.any { it !in entities || it !in validBaselineIds }) return false
            val mappings = query.mappedEntityIds.map { entities.getValue(it) }
            if (mappings.any { relation(it.table) != ref.table || query.operation !in it.operations }) return false
            for (write in query.writeColumns) {
                if (relation(write.table) != ref.table) return false
                val columns = mappings.flatMap { entity -> entity.columns.filter { postgresIdentifier(it.name) == postgresIdentifier(write.column) } }
                if (columns.isEmpty() && query.discriminatorWrites.none { it.column == write }) return false
                if (columns.any { !it.inputDomainComplete }) return false
            }
            val assigned = query.writeColumns.any { reference(it.table, it.column) == ref }
            if (assigned) {
                val columns = mappings.map { entity -> entity.columns.singleOrNull { postgresIdentifier(it.name) == ref.column } ?: return false }
                if (columns.any { column -> !column.inputDomainComplete || column.generated || column.converterClass != null ||
                        column.enumValues.isNotEmpty() || column.enumEncoding != null ||
                        (if (query.operation == Operation.INSERT) !column.insertable else !column.updatable) ||
                        minOf(logicalBits(column.logicalJavaType) ?: return false, integerBits(column.typeName) ?: return false) > bits }) return false
            } else if (query.operation == Operation.INSERT && !boundedOmission(table.columns[ref.column] ?: return false, bits)) return false
        }
        return declared.filter { it.operations.any { op -> op == Operation.INSERT || op == Operation.UPDATE } }.all { entity ->
            entity.operations.filter { it == Operation.INSERT || it == Operation.UPDATE }.all { operation ->
                relevant.any { it.operation == operation && entity.id in it.mappedEntityIds }
            }
        }
    }

    private fun boundedOmission(column: ColumnSchema, bits: Int): Boolean {
        if (column.typeAcceptsImplicitNull != true || !column.identity.isNullOrEmpty() || !column.generated.isNullOrEmpty()) return false
        val value = column.defaultExpression ?: return column.nullable
        if (value.trim().equals("NULL", true)) return column.nullable
        if (target.expressionSearchPath.firstOrNull() != "pg_catalog") return false
        val literal = Regex("^([-+]?\\d+)(?:::(?:smallint|integer|bigint))?$").matchEntire(value.trim()) ?: return false
        val number = literal.groupValues[1].toBigIntegerOrNull() ?: return false
        val half = BigInteger.ONE.shiftLeft(bits - 1)
        return number >= half.negate() && number < half
    }

    private fun queryTarget(query: QueryContract): String? = (query.targetTable ?: query.writeColumns.map { it.table }.distinct().singleOrNull()
        ?: query.tables.singleOrNull())?.let(::relation)

    companion object {
        /** Reuse baseline health, including rejected SQL-to-mapping links, without guessing entity validity from plan IDs. */
        fun establishedBaselineIds(contract: DatabaseContract, snapshot: SchemaSnapshot,
                                   acceptedQueryIds: Set<String>, baselineFindings: List<Finding>): Set<String> {
            val duplicates = (contract.entities.map { it.id } + contract.queries.map { it.id })
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val rejected = baselineFindings.filter { it.ruleId == "BASELINE_SQL_UNVERIFIED" || it.ruleId.startsWith("INVALID_BASELINE_") }
                .flatMap { it.affectedContractIds }.toSet() + duplicates
            val mappingsRejected = contract.queries.filter { it.id in rejected && it.source == QuerySource.HIBERNATE_MAPPING }
                .flatMap { it.mappedEntityIds }.toSet()
            val validMappings = contract.entities.filter { entity -> entity.columns.isNotEmpty() && entity.operations.isNotEmpty() &&
                entity.columns.all { postgresIdentifier(it.name) in snapshot.tables[relation(entity.table)]?.columns.orEmpty() }
            }.map { it.id }
            return (acceptedQueryIds + validMappings) - rejected - mappingsRejected
        }

        private val stringJdbc = setOf("VARCHAR", "NVARCHAR", "LONGVARCHAR", "LONGNVARCHAR", "TEXT", "CHARACTER VARYING")
        private fun ordinary(table: TableSchema?) = table?.kind == "table" && table.relationSecurity?.let {
            !it.userTriggers && !it.userRules && !it.rowSecurity && !it.inheritance
        } == true
        private fun relation(value: String) = value.split('.').joinToString(".", transform = ::postgresIdentifier)
        private fun reference(table: String, column: String) = ColumnReference(relation(table), postgresIdentifier(column))
        private fun logicalBits(type: String?) = when (type) {
            "byte", "java.lang.Byte" -> 8
            "short", "java.lang.Short" -> 16
            "int", "java.lang.Integer" -> 32
            "long", "java.lang.Long" -> 64
            else -> null
        }
        private fun integerBits(type: String?) = when (type?.lowercase()?.trim()) {
            "tinyint" -> 8
            "smallint", "int2" -> 16
            "integer", "int", "int4" -> 32
            "bigint", "int8" -> 64
            else -> null
        }
        private fun stringType(type: String) = Regex("(?:text|varchar(?:\\(\\d+\\))?|character varying(?:\\(\\d+\\))?)").matches(type)
        private fun resultType(type: String?): String? = type?.lowercase()?.trim()?.let { value -> when {
            value in setOf("varchar", "character varying") || value.startsWith("character varying(") || value.startsWith("varchar(") -> "varchar"
            value in setOf("int", "int4", "integer") -> "integer"
            value in setOf("int8", "bigint") -> "bigint"
            value in setOf("int2", "smallint") -> "smallint"
            else -> value
        } }
    }
}
