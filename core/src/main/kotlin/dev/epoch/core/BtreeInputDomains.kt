package dev.epoch.core

import java.util.Locale

/** Universal bounds for direct declared writes; unlike row witnesses, successful samples are not evidence here. */
internal class BtreeInputDomains(
    contract: DatabaseContract,
    private val validBaselineIds: Set<String>,
    private val queryFacts: (QueryContract) -> QueryBehaviorFacts,
) {
    data class OperationProof(
        val contractId: String,
        val operation: Operation,
        /** null denotes an unchanged existing tuple, not an unbounded new tuple. */
        val maximumTupleBytes: Long?,
        val evidence: List<String>,
    )

    data class Assessment(
        val proven: Boolean,
        val proofs: List<OperationProof>,
        val reasons: List<String>,
        val unprovenContractIds: List<String>,
    ) {
        fun summary(): String {
            val bounded = proofs.mapNotNull { it.maximumTupleBytes }
            val unchanged = proofs.count { it.maximumTupleBytes == null }
            val details = if (reasons.isEmpty()) "" else "; gaps=" + reasons.take(12).joinToString("; ") +
                if (reasons.size > 12) "; ${reasons.size - 12} further distinct domain gaps" else ""
            return "Direct operation proofs=${proofs.size}; unchanged tuples=$unchanged; maximum bounded tuple bytes=${bounded.maxOrNull() ?: "none"}; " +
                "supported ceiling=2000 bytes on PostgreSQL 8192-byte pages; unproven contract IDs=${unprovenContractIds.size}$details"
        }
    }

    private val entities = contract.entities.groupBy { it.id }
    private val entitiesByTable = contract.entities.groupBy { it.table }
    private val writers = contract.queries.filter { it.operation in writeOperations }
    private val queriesByTarget = writers.groupBy(::target)
    private val ambiguousQueryIds = contract.queries.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys

    fun assess(table: String, index: String, snapshot: SchemaSnapshot,
               writeEffectsKnown: (QueryContract) -> Boolean): Assessment {
        val reasons = linkedSetOf<String>()
        val rejected = linkedSetOf<String>()
        val proofs = mutableListOf<OperationProof>()
        fun gap(id: String, reason: String) { rejected += id; reasons += reason }
        fun result() = Assessment(reasons.isEmpty(), proofs.sortedWith(compareBy({ it.contractId }, { it.operation.name })),
            reasons.sorted(), rejected.sorted())
        val schema = snapshot.tables[table]
        val columns = schema?.indexColumns?.get(index).orEmpty()
        if (schema == null || schema.kind != "table" || schema.indexMethods[index] != "btree" ||
            schema.uniqueIndexes[index] != false || index !in schema.plainIndexes || columns.isEmpty() ||
            columns.any { it !in schema.columns } || schema.indexValidity[index] != true || schema.indexReadiness[index] != true) {
            gap("$table.$index", "A complete valid/ready plain nonunique B-tree and its full key/INCLUDE column inventory are required.")
            return result()
        }
        val security = schema.relationSecurity
        if (security == null || security.userTriggers || security.userRules || security.rowSecurity || security.inheritance ||
            snapshot.expressionSearchPath.firstOrNull() != "pg_catalog") {
            gap(table, "Ordinary relation writes and builtin expression resolution are not established.")
            return result()
        }
        val queries = queriesByTarget[table].orEmpty()
        for (entity in entitiesByTable[table].orEmpty()) for (operation in entity.operations.intersect(writeOperations)) {
            val linked = queries.filter { it.operation == operation && it.source == QuerySource.HIBERNATE_MAPPING && entity.id in it.mappedEntityIds }
            if (entity.id !in validBaselineIds || entities[entity.id]?.size != 1 || linked.isEmpty())
                gap(entity.id, "Declared $operation has no unique baseline-valid mapping and actual linked generated SQL.")
        }
        for (query in queriesByTarget[null].orEmpty()) if (table in query.tables ||
            (query.columns + query.writeColumns).any { it.table == table })
            gap(query.id, "A potentially relevant write has no established physical target.")

        for (query in queries.sortedBy { it.id }) {
            if (query.id !in validBaselineIds || query.id in ambiguousQueryIds || !query.dependenciesComplete ||
                query.verificationStatus != VerificationStatus.EXTRACTED || query.sql.isBlank() ||
                query.targetTable != table || query.writeColumns.any { it.table != table || it.column !in schema.columns } ||
                query.operation == Operation.INSERT && !query.explicitColumnList ||
                query.operation == Operation.UPDATE && query.writeColumns.isEmpty()) {
                gap(query.id, "A direct write lacks baseline acceptance, exact target/column provenance, or exhaustive SQL extraction.")
                continue
            }
            val facts = queryFacts(query)
            if (!facts.complete || !facts.defaultAssignmentsComplete || !writeEffectsKnown(query)) {
                gap(query.id, "The write's expressions, DEFAULT assignments, or operation-local effects are not completely supported.")
                continue
            }
            val written = query.writeColumns.map { it.column }.toSet()
            if (query.operation == Operation.UPDATE && columns.none { it in written } &&
                columns.all { schema.columns.getValue(it).generated.isNullOrEmpty() }) {
                proofs += OperationProof(query.id, query.operation, null,
                    listOf("Valid/ready index; every key and INCLUDE value is unchanged by the complete ordinary UPDATE."))
                continue
            }
            var bytes = 32L
            val evidence = mutableListOf<String>()
            var complete = true
            for (name in columns) {
                val physical = schema.columns.getValue(name)
                val fixed = RuleProofs.btreeColumnBytes(physical.type)
                val explicitDefault = ColumnReference(table, name) in facts.defaultColumns
                val size = if (fixed != null) {
                    evidence += "$name: physical ${physical.type} bounds the stored datum at $fixed bytes"
                    fixed
                } else if (query.operation == Operation.INSERT && name !in written || explicitDefault) {
                    implicitBytes(physical)?.also { evidence += "$name: supported implicit value bounds the datum at $it bytes" }
                } else if (name in written) {
                    finiteBytes(query, table, name, physical)?.also { evidence += "$name: direct finite enum domain bounds the datum at $it bytes" }
                } else null // A retained unbounded component cannot borrow a mapping's bound for historical rows.
                if (size == null) {
                    gap(query.id, "$table.$name has no complete ${if (name !in written && query.operation == Operation.UPDATE) "retained-value" else "stored-value"} bound for ${query.operation}.")
                    complete = false
                } else {
                    // Individual bounds are capped before arithmetic; the sum never approaches Long overflow.
                    bytes += size + 8
                    if (bytes > 2000) {
                        gap(query.id, "The complete key/INCLUDE tuple exceeds the supported 2000-byte upper bound.")
                        complete = false
                        break
                    }
                }
            }
            if (complete) proofs += OperationProof(query.id, query.operation, bytes, evidence)
        }
        if (queries.isEmpty()) gap(table, "No actual direct writer SQL establishes the declared finite input-domain proof.")
        return result()
    }

    private fun finiteBytes(query: QueryContract, table: String, name: String, physical: ColumnSchema): Long? {
        if (query.source != QuerySource.HIBERNATE_MAPPING || query.mappedEntityIds.isEmpty() || !textStorage(physical.type) ||
            physical.typeAcceptsImplicitNull != true || !physical.generated.isNullOrEmpty()) return null
        val linked = query.mappedEntityIds.map { id ->
            if (id !in validBaselineIds) return null
            entities[id]?.singleOrNull() ?: return null
        }.filter { it.table == table }
        if (linked.isEmpty()) return null
        val values = mutableListOf<String>()
        for (entity in linked) {
            if (query.operation !in entity.operations) return null
            val column = entity.columns.singleOrNull { it.name == name } ?: return null
            if (!column.inputDomainComplete || column.generated || column.enumValues.isEmpty() || column.converterClass != null ||
                column.typeName?.lowercase(Locale.ROOT) !in stringJdbcTypes ||
                query.operation == Operation.INSERT && !column.insertable || query.operation == Operation.UPDATE && !column.updatable) return null
            values += column.enumValues
        }
        return values.maxOfOrNull { value -> stringBytes(value) ?: return null }?.let { it + 8 }
    }

    private fun implicitBytes(column: ColumnSchema): Long? {
        if (!textStorage(column.type) || column.typeAcceptsImplicitNull != true ||
            !column.identity.isNullOrEmpty() || !column.generated.isNullOrEmpty()) return null
        val expression = column.defaultExpression?.trim() ?: return 0
        if (Regex("NULL(?:::(?:text|character varying|varchar))?", RegexOption.IGNORE_CASE).matches(expression)) return 0
        if (!RuleProofs.constantDefault(expression, column.type)) return null
        val literal = Regex("^'((?:[^']|'')*)'(?:::(?:text|character varying(?:\\(\\d+\\))?|varchar(?:\\(\\d+\\))?))?$")
            .matchEntire(expression)?.groupValues?.get(1)?.replace("''", "'") ?: return null
        return stringBytes(literal)?.let { it + 8 }
    }

    private fun stringBytes(value: String): Long? {
        if (value.length > 2000 || '\u0000' in value || value.indices.any { index ->
                val char = value[index]
                char.isHighSurrogate() && (index == value.lastIndex || !value[index + 1].isLowSurrogate()) ||
                    char.isLowSurrogate() && (index == 0 || !value[index - 1].isHighSurrogate())
            }) return null
        return value.toByteArray(Charsets.UTF_8).size.toLong().takeIf { it <= 2000 }
    }

    private fun textStorage(type: String) = Regex("(?:text|character varying(?:\\(\\d+\\))?|varchar(?:\\(\\d+\\))?)").matches(type)
    private fun target(query: QueryContract): String? = query.targetTable ?: query.writeColumns.map { it.table }.distinct().singleOrNull()
    private companion object {
        val writeOperations = setOf(Operation.INSERT, Operation.UPDATE)
        val stringJdbcTypes = setOf("varchar", "nvarchar", "longvarchar", "longnvarchar", "text", "character varying")
    }
}
