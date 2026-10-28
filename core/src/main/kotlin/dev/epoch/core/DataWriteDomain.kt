package dev.epoch.core

import java.math.BigInteger

/**
 * A deliberately small independent-column row-domain proof. This does not execute the INSERT,
 * establish available UNIQUE values in the current data, or establish that an UPDATE finds a row.
 */
internal object DataWriteDomain {
    data class Result(val accepted: Boolean?, val evidence: String)

    private data class Choice(val known: Boolean, val values: List<String?> = emptyList())
    private data class Check(val column: String, val values: Set<String>? = null)

    fun excludesNull(reference: ColumnReference, snapshot: SchemaSnapshot): Boolean {
        val table = snapshot.tables[reference.table] ?: return false
        val column = table.columns[reference.column] ?: return false
        if (!column.nullable) return true
        return table.constraints.values.any { constraint ->
            constraint.type == "c" && check(constraint, table, snapshot)?.let {
                it.column == reference.column && it.values == null
            } == true
        }
    }

    fun assess(contract: DatabaseContract, query: QueryContract, candidate: ColumnReference,
               value: String?, snapshot: SchemaSnapshot): Result {
        val table = snapshot.tables[candidate.table]
            ?: return unknown("Candidate table ${candidate.table} is absent from the final catalog.")
        val physical = table.columns[candidate.column]
            ?: return unknown("Candidate column ${candidate.table}.${candidate.column} is absent from the final catalog.")
        // Exclusion of the stored datum is useful even when another part of the row is unsupported.
        val candidateAcceptance = accepts(physical, value, snapshot)
        if (candidateAcceptance == false) return excluded("Final ${physical.type}${if (!physical.nullable) " NOT NULL" else ""} excludes ${render(value)}.")
        val checks = table.constraints.toSortedMap().mapNotNull { (name, constraint) ->
            if (constraint.type != "c") null else name to check(constraint, table, snapshot)
        }
        for ((name, check) in checks) {
            if (check != null && check.column == candidate.column && !check.accepts(value))
                return excluded("Final CHECK $name excludes ${render(value)}: ${table.constraints.getValue(name).definition}.")
        }
        if (candidateAcceptance == null) return unknown("The final physical domain ${physical.type} has no supported candidate admission proof.")
        if (query.operation != Operation.INSERT) return unknown("A complete row-domain witness is currently supported only for direct ORM INSERTs; ${query.id} is ${query.operation}.")
        if (query.source != QuerySource.HIBERNATE_MAPPING || query.verificationStatus != VerificationStatus.EXTRACTED ||
            !query.dependenciesComplete || !query.explicitColumnList || query.targetTable != candidate.table || query.sql.isBlank())
            return unknown("The candidate has no complete generated INSERT and exact target/column-list provenance.")
        val security = table.relationSecurity
        if (table.kind != "table" || security == null || security.userTriggers || security.userRules || security.rowSecurity || security.inheritance)
            return unknown("The final relation has unsupported or unestablished trigger, rule, security, inheritance, or view semantics.")
        if (snapshot.expressionSearchPath.firstOrNull() != "pg_catalog")
            return unknown("The PostgreSQL builtin expression resolution path is not established.")
        if (table.constraints.values.any { it.type !in setOf("c", "p", "u", "n") })
            return unknown("Foreign-key, exclusion, or other unsupported constraints prevent a whole-row admission proof.")
        if (checks.any { it.second == null })
            return unknown("A CHECK expression is outside the supported independent-column enum/IS NOT NULL grammar.")
        val parsedChecks = checks.map { it.second!! }
        val mappings = query.mappedEntityIds.map { id ->
            contract.entities.singleOrNull { it.id == id }
                ?: return unknown("Generated INSERT mapping $id is absent or ambiguous.")
        }
        if (mappings.isEmpty() || mappings.any { it.table != candidate.table || Operation.INSERT !in it.operations })
            return unknown("The generated INSERT lacks writable mappings for its exact target table.")
        val writes = query.writeColumns.filter { it.table == candidate.table }.map { it.column }.toSet()
        if (query.writeColumns.any { it.table != candidate.table || it.column !in table.columns } || candidate.column !in writes)
            return unknown("The candidate is not an established direct INSERT column.")
        val literals = query.discriminatorWrites.filter { it.column.table == candidate.table }
            .groupBy { it.column.column }
        if (query.discriminatorWrites.any { it.column.table != candidate.table || it.entityId !in query.mappedEntityIds })
            return unknown("A discriminator literal has ambiguous mapping/target provenance.")
        val row = linkedMapOf<String, String?>()
        for ((name, column) in table.columns.toSortedMap()) {
            if (!column.generated.isNullOrEmpty() || !column.identity.isNullOrEmpty())
                return unknown("Generated or identity value ${candidate.table}.$name requires a separate row-domain proof.")
            val columnChecks = parsedChecks.filter { it.column == name }
            val choices = if (name in writes) {
                val literal = literals[name]
                if (literal != null) {
                    if (literal.map { it.value }.distinct().size != 1)
                        return unknown("Conflicting discriminator literals target ${candidate.table}.$name.")
                    Choice(true, listOf(literal.first().value))
                } else {
                    val mapped = mappings.flatMap { it.columns.filter { mapped -> mapped.name == name } }.distinct()
                    if (mapped.size != 1) return unknown("The writable domain of ${candidate.table}.$name is absent or ambiguous.")
                    val fact = mapped.single()
                    if (!fact.inputDomainComplete || !fact.insertable || fact.generated)
                        return unknown("The writable domain of ${candidate.table}.$name has custom, generated, or unsupported semantics.")
                    if (name == candidate.column) {
                        if (!mappingPermits(fact, value)) return unknown("The new mapped input domain does not establish candidate ${render(value)} for ${candidate.table}.$name.")
                        Choice(true, listOf(value))
                    } else choices(fact, columnChecks)
                }
            } else defaultChoice(column)
            if (!choices.known) return unknown("No supported supplied or implicit value is established for ${candidate.table}.$name.")
            val possible = choices.values.filter { proposed -> accepts(column, proposed, snapshot) == true && columnChecks.all { it.accepts(proposed) } }
            if (possible.isEmpty()) return unknown("The supported domain of ${candidate.table}.$name has no established value satisfying its final type and independent CHECKs.")
            val chosen = possible.first()
            if (name == candidate.column && chosen != value)
                return unknown("The generated literal does not supply the candidate stored value for ${candidate.table}.$name.")
            row[name] = chosen
        }
        if (!indexValuesSupported(table, row))
            return unknown("An index expression, method, or key size lacks a supported admission proof for the constructed row domain.")
        return Result(true, "Supported direct INSERT row domain admits ${candidate.table}.${candidate.column}=${render(value)}; " +
            "all required supplied/implicit fields and independent CHECKs have compatible values. " +
            "This is a schema/input-domain witness, not an executed INSERT or a claim about current UNIQUE occupancy.")
    }

    /** Supported rejection/admission of a stored scalar datum, before whole-row constraints. */
    private fun accepts(column: ColumnSchema, value: String?, snapshot: SchemaSnapshot): Boolean? {
        if (value == null) {
            if (!column.nullable) return false
            return if (column.typeAcceptsImplicitNull == true) true else null
        }
        if (column.typeAcceptsImplicitNull != true) return null
        val enumValues = snapshot.enumTypes[column.type] ?: snapshot.enumTypes["public.${column.type}"]
        if (enumValues != null) return value in enumValues
        val type = column.type.lowercase().trim()
        return when {
            type == "text" || type == "character varying" || type == "varchar" -> stringDatum(value)
            Regex("(?:character varying|varchar)\\((\\d+)\\)").matches(type) -> {
                val limit = Regex("\\d+").find(type)!!.value.toLongOrNull() ?: return null
                if (!stringDatum(value)) false
                else if (value.codePointCount(0, value.length).toLong() <= limit) true
                // PostgreSQL accepts overlength assignment when only spaces are discarded.
                // That stored value differs from this candidate and must be assessed separately.
                else if (value.substring(value.offsetByCodePoints(0, limit.toInt())).all { it == ' ' }) null
                else false
            }
            type in setOf("smallint", "integer", "bigint") -> {
                val bits = when (type) { "smallint" -> 16; "integer" -> 32; else -> 64 }
                val number = value.toBigIntegerOrNull() ?: return false
                number >= BigInteger.ONE.shiftLeft(bits - 1).negate() && number < BigInteger.ONE.shiftLeft(bits - 1)
            }
            type == "boolean" -> value == "true" || value == "false"
            type in setOf("real", "double precision") -> value.toBigDecimalOrNull()?.let { number ->
                if (type == "real") number.toFloat().isFinite() else number.toDouble().isFinite()
            } ?: false
            type == "numeric" || type == "decimal" -> value.toBigDecimalOrNull() != null
            Regex("(?:numeric|decimal)\\((\\d+)(?:,\\s*(\\d+))?\\)").matches(type) -> {
                val match = Regex("(?:numeric|decimal)\\((\\d+)(?:,\\s*(\\d+))?\\)").matchEntire(type)!!
                val precision = match.groupValues[1].toIntOrNull() ?: return null
                val scale = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
                val number = value.toBigDecimalOrNull() ?: return false
                val normalized = number.stripTrailingZeros()
                // Rounding can admit a different stored value. It is neither exact admission
                // nor a rejected write, and cannot eliminate a possible data-format regression.
                if (normalized.signum() == 0) true
                else if (normalized.scale().coerceAtLeast(0) > scale) null
                else normalized.precision() - normalized.scale() <= precision - scale
            }
            type == "uuid" -> Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)
            else -> null
        }
    }

    private fun check(constraint: ConstraintSchema, table: TableSchema, snapshot: SchemaSnapshot): Check? {
        RuleProofs.notNullCheck(constraint)?.let { name ->
            val column = table.columns[name] ?: return null
            if (column.typeAcceptsImplicitNull == true && RuleProofs.scalarNullTest(column.type, snapshot)) return Check(name)
            return null
        }
        val (name, values) = RuleProofs.enumCheck(constraint) ?: return null
        val column = table.columns[name] ?: return null
        if (snapshot.expressionSearchPath.firstOrNull() != "pg_catalog" || column.collation != "pg_catalog.default" ||
            !Regex("(?:text|character varying(?:\\(\\d+\\))?|varchar(?:\\(\\d+\\))?)").matches(column.type)) return null
        return Check(name, values)
    }

    private fun Check.accepts(value: String?) = if (values == null) value != null else value == null || value in values

    private fun mappingPermits(column: ColumnContract, value: String?): Boolean {
        if (value == null) return column.declaredNonNull == false && column.nullable != false &&
            column.hibernateNullable != false && column.logicalJavaType !in primitives
        if (column.enumRelationalValues.isNotEmpty()) return value in column.enumRelationalValues
        if (column.enumValues.isNotEmpty()) return value in column.enumValues
        return when (column.logicalJavaType) {
            "java.lang.String" -> stringDatum(value)
            "char", "java.lang.Character" -> value.length == 1
            "byte", "java.lang.Byte" -> value.toByteOrNull() != null
            "short", "java.lang.Short" -> value.toShortOrNull() != null
            "int", "java.lang.Integer" -> value.toIntOrNull() != null
            "long", "java.lang.Long" -> value.toLongOrNull() != null
            "java.math.BigInteger" -> value.toBigIntegerOrNull() != null
            "java.math.BigDecimal" -> value.toBigDecimalOrNull() != null
            "float", "java.lang.Float" -> value.toFloatOrNull()?.isFinite() == true
            "double", "java.lang.Double" -> value.toDoubleOrNull()?.isFinite() == true
            "boolean", "java.lang.Boolean" -> value == "true" || value == "false"
            "java.util.UUID" -> Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)
            else -> false
        }
    }

    private fun choices(column: ColumnContract, checks: List<Check>): Choice {
        val nullable = mappingPermits(column, null)
        val values: List<String> = when {
            column.enumRelationalValues.isNotEmpty() -> column.enumRelationalValues.distinct().sorted()
            column.enumValues.isNotEmpty() -> column.enumValues.distinct().sorted()
            column.logicalJavaType == "java.lang.String" -> checks.mapNotNull { it.values }.flatten().distinct().sorted().ifEmpty { listOf("") }
            column.logicalJavaType in setOf("char", "java.lang.Character") -> checks.mapNotNull { it.values }.flatten().filter { it.length == 1 }.distinct().sorted().ifEmpty { listOf("x") }
            column.logicalJavaType in numericTypes -> listOf("0")
            column.logicalJavaType in setOf("boolean", "java.lang.Boolean") -> listOf("false", "true")
            column.logicalJavaType == "java.util.UUID" -> listOf("00000000-0000-0000-0000-000000000000")
            else -> return Choice(false)
        }
        return Choice(true, values + if (nullable) listOf(null) else emptyList())
    }

    private fun defaultChoice(column: ColumnSchema): Choice {
        if (column.typeAcceptsImplicitNull != true) return Choice(false)
        val expression = column.defaultExpression ?: return Choice(true, listOf(null))
        if (Regex("NULL(?:::(?:text|character varying|smallint|integer|bigint|boolean))?", RegexOption.IGNORE_CASE).matches(expression.trim()))
            return Choice(true, listOf(null))
        if (!RuleProofs.constantDefault(expression, column.type)) return Choice(false)
        val literal = Regex("^('(?:[^']|'')*'|[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?|true|false)(?:::.+)?$", RegexOption.IGNORE_CASE)
            .matchEntire(expression.trim())?.groupValues?.get(1) ?: return Choice(false)
        val value = if (literal.startsWith('\'')) literal.substring(1, literal.lastIndex).replace("''", "'") else literal.lowercase()
        return Choice(true, listOf(value))
    }

    private fun indexValuesSupported(table: TableSchema, row: Map<String, String?>): Boolean {
        for (index in table.indexes.keys) {
            if (index !in table.plainIndexes || table.indexMethods[index] != "btree") return false
            val columns = table.indexColumns[index].orEmpty()
            if (columns.isEmpty()) return false
            var bytes = 32L
            for (name in columns) {
                if (!row.containsKey(name)) return false
                val value = row[name]
                val type = table.columns[name]?.type ?: return false
                bytes += if (value == null) 8L else when {
                    type == "smallint" -> 10L
                    type in setOf("integer", "real") -> 12L
                    type in setOf("bigint", "double precision") -> 16L
                    type == "boolean" -> 9L
                    type == "uuid" -> 24L
                    type == "text" || type.startsWith("character varying") || type.startsWith("varchar") -> value.toByteArray(Charsets.UTF_8).size.toLong() + 16
                    type.startsWith("numeric") || type.startsWith("decimal") -> value.length.toLong() + 24
                    else -> return false
                }
                if (bytes > 2000) return false
            }
        }
        return true
    }

    private fun stringDatum(value: String): Boolean = '\u0000' !in value && value.indices.none { index ->
        val char = value[index]
        char.isHighSurrogate() && (index == value.lastIndex || !value[index + 1].isLowSurrogate()) ||
            char.isLowSurrogate() && (index == 0 || !value[index - 1].isHighSurrogate())
    }
    private fun render(value: String?) = value?.let { "'${it.replace("'", "''")}'" } ?: "NULL"
    private fun unknown(message: String) = Result(null, message)
    private fun excluded(message: String) = Result(false, message)
    private val primitives = setOf("byte", "short", "int", "long", "float", "double", "boolean", "char")
    private val numericTypes = primitives - setOf("boolean", "char") + setOf("java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.math.BigInteger", "java.math.BigDecimal")
}
