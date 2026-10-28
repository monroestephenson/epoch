package dev.epoch.core

/** A view is transparent only when it exposes the same physical relation under its old name. */
object CompatibilityViews {
    data class Normalized(val after: SchemaSnapshot, val provenViews: Map<String, String>)
    data class ExpressionRisk(val table: String, val objectName: String, val kind: String, val expression: String)

    fun provenViewNames(baseline: SchemaSnapshot, after: SchemaSnapshot): Set<String> =
        normalize(baseline, after).provenViews.keys

    /** PREPARE does not evaluate row defaults or constraints, including expressions on other tables. */
    fun expressionRisks(snapshot: SchemaSnapshot, relationNames: Map<String, String> = emptyMap()): List<ExpressionRisk> = buildList {
        snapshot.tables.toSortedMap().forEach { (physicalName, table) ->
            val name = relationNames[physicalName] ?: physicalName
            table.columns.toSortedMap().forEach { (columnName, column) ->
                val expression = column.defaultExpression
                if (expression != null && !safeDefault(expression, column.type, snapshot) &&
                    !(builtinType.matches(column.type) && simpleExpression(expression, if (column.generated.isNullOrEmpty()) TableSchema() else table))) {
                    add(ExpressionRisk(name, "$name.$columnName", if (column.generated.isNullOrEmpty()) "DEFAULT" else "generated column", expression))
                } else if (!column.generated.isNullOrEmpty() && expression == null) {
                    add(ExpressionRisk(name, "$name.$columnName", "generated column", "Generation expression is absent from the catalog facts."))
                }
            }
            table.constraints.toSortedMap().filterValues { it.type == "c" }.forEach { (constraintName, constraint) ->
                val expression = constraint.checkExpression ?: constraint.definition.substringBefore("; validated=").removePrefix("CHECK ").trim()
                val enum = RuleProofs.enumCheck(constraint)
                val supportedEnum = enum != null && table.columns[enum.first]?.type?.let { it == "text" || it.startsWith("character varying") } == true
                if (!supportedEnum && !simpleExpression(expression, table)) {
                    add(ExpressionRisk(name, "$name.$constraintName", "CHECK", expression))
                }
            }
            table.indexes.toSortedMap().forEach { (indexName, definition) ->
                if (indexName !in table.plainIndexes || table.indexMethods[indexName] != "btree" ||
                    table.indexColumns[indexName].isNullOrEmpty() || table.indexColumns[indexName].orEmpty().any {
                        table.columns[it]?.type?.let(builtinType::matches) != true
                    }) add(ExpressionRisk(name, "$name.$indexName", "index expression, predicate, or unsupported index semantics", definition))
            }
        }
    }

    fun normalize(baseline: SchemaSnapshot, after: SchemaSnapshot): Normalized {
        // Bare type/operator names identify builtins only when PostgreSQL resolves pg_catalog first.
        if (baseline.expressionSearchPath.firstOrNull() != "pg_catalog" || after.expressionSearchPath.firstOrNull() != "pg_catalog") {
            return Normalized(after, emptyMap())
        }
        val candidates = baseline.tables.toSortedMap().mapNotNull { (name, old) ->
            val replacement = after.tables[name] ?: return@mapNotNull null
            val view = replacement.view ?: return@mapNotNull null
            val backingName = view.backingTable ?: return@mapNotNull null
            val backing = after.tables[backingName] ?: return@mapNotNull null
            if (!qualified(name) || !qualified(backingName) || backingName in baseline.tables ||
                old.kind != "table" || replacement.kind != "view" || backing.kind != "table" ||
                old.relationOid == null || old.relationOid != backing.relationOid ||
                !view.completeDirectProjection || view.options.isNotEmpty() ||
                view.updatableMask and 28 != 28 || view.updatableMaskWithTriggers and 28 != 28 ||
                !clean(old.relationSecurity) || !clean(replacement.relationSecurity) || !clean(backing.relationSecurity) ||
                old.relationSecurity?.owner != replacement.relationSecurity?.owner ||
                old.relationSecurity?.owner != backing.relationSecurity?.owner ||
                old.columns.any { (column, original) -> column in backing.columns && original.collation != backing.columns[column]?.collation } ||
                replacement.columns.values.any { it.defaultExpression != null || !it.identity.isNullOrEmpty() || !it.generated.isNullOrEmpty() } ||
                orderedColumns(replacement) != orderedColumns(backing) ||
                backing.columns.filterValues { it.generated.isNullOrEmpty() }.keys.any {
                    it !in view.updatableColumns || it !in view.updatableColumnsWithTriggers
                }
            ) return@mapNotNull null
            name to backingName
        }.toMap()
        // Removing a physical duplicate must never merge distinct old application relations.
        val duplicated = candidates.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val proven = candidates.filterValues { it !in duplicated }.toSortedMap()
        if (proven.isEmpty()) return Normalized(after, emptyMap())
        val oldNames = proven.entries.associate { (old, backing) -> backing to old }
        val effective = after.tables.filterKeys { it !in oldNames }.mapValues { (name, current) ->
            val physical = proven[name]?.let(after.tables::getValue) ?: current
            val previous = baseline.tables[name]
            physical.copy(
                constraints = physical.constraints.mapValues { (constraintName, constraint) ->
                    val referenced = constraint.referencedTable
                    val oldName = oldNames[referenced]
                    if (constraint.type != "f" || referenced == null || oldName == null) constraint
                    else constraint.copy(
                        referencedTable = oldName,
                        definition = rewriteTarget(constraint.definition, "REFERENCES", referenced, oldName,
                            previous?.constraints?.get(constraintName)?.definition),
                    )
                },
                indexes = physical.indexes.mapValues { (indexName, definition) ->
                    val backing = proven[name]
                    if (backing == null) definition else rewriteTarget(definition, "ON", backing, name,
                        previous?.indexes?.get(indexName))
                },
            )
        }.toSortedMap()
        return Normalized(after.copy(
            tables = effective,
            behaviors = after.behaviors.filterKeys { key -> proven.keys.none { key == "view:$it" } },
            sequenceOwners = after.sequenceOwners.mapValues { (_, owner) ->
                oldNames[owner.table]?.let { owner.copy(table = it) } ?: owner
            },
        ), proven)
    }

    private fun qualified(name: String) = name.split('.').let { it.size == 2 && it.all(String::isNotEmpty) }
    private fun clean(security: RelationSecurity?) = security != null && security.owner.isNotBlank() &&
        !security.explicitPrivileges && !security.rowSecurity && !security.userTriggers &&
        !security.userRules && !security.inheritance

    private fun orderedColumns(table: TableSchema) = table.columns.entries.sortedBy { it.value.ordinal }
        .map { Triple(it.key, it.value.type, it.value.collation) }

    private data class TargetSpan(val name: String, val start: Int, val end: Int)
    private val tokenPattern = Regex("\"(?:[^\"]|\"\")*\"|'(?:[^']|'')*'|[\\p{L}_][\\p{L}\\p{N}_$]*|[^\\s]")
    private val bareIdentifier = Regex("[\\p{L}_][\\p{L}\\p{N}_$]*")
    private val builtinType = Regex("(?:smallint|integer|bigint|real|double precision|numeric(?:\\(\\d+(?:,\\d+)?\\))?|text|character varying(?:\\(\\d+\\))?|boolean|date|uuid|bytea|timestamp(?: with(?:out)? time zone)?|time(?: with(?:out)? time zone)?)")
    private val identifierPattern = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z_0-9$]*)"
    private val integerTypes = setOf("smallint", "integer", "bigint")
    private val expressionTokens = Regex("\"(?:[^\"]|\"\")*\"|'(?:[^']|'')*'|[A-Za-z_][A-Za-z_0-9$]*|\\d+|[+*/%<>=!|&^~?:-]+|\\S")

    private fun safeDefault(expression: String, type: String, snapshot: SchemaSnapshot): Boolean {
        if (RuleProofs.constantDefault(expression, type)) return true
        if (builtinType.matches(type) && Regex("NULL(?:::${Regex.escape(type)})?", RegexOption.IGNORE_CASE).matches(expression.trim())) return true
        if (type !in integerTypes) return false
        val sequence = Regex("(?:pg_catalog\\.)?nextval\\('((?:[^']|'')*)'::(?:pg_catalog\\.)?regclass\\)")
            .matchEntire(expression.trim())?.groupValues?.get(1)?.replace("''", "'") ?: return false
        if (!Regex("$identifierPattern(?:\\.$identifierPattern)?").matches(sequence)) return false
        val parts = Regex(identifierPattern).findAll(sequence).map { identifierValue(it.value) }.toList()
        return (if (parts.size == 1) "public.${parts.single()}" else parts.joinToString(".")) in snapshot.sequences
    }

    /** Positive fragments only: builtin scalar null checks, text literal comparisons, and integer arithmetic/comparisons. */
    private fun simpleExpression(expression: String, table: TableSchema): Boolean {
        val tokens = expressionTokens.findAll(expression.trim()).map { it.value }.toList()
        if (tokens.isEmpty()) return false
        var start = 0
        var end = tokens.size
        while (start < end && tokens[start] == "(" && tokens[end - 1] == ")") {
            var depth = 0
            var enclosesAll = true
            for (index in start until end) {
                if (tokens[index] == "(") depth++
                if (tokens[index] == ")") depth--
                if (depth == 0 && index != end - 1) enclosesAll = false
            }
            if (!enclosesAll) break
            start++
            end--
        }
        val body = tokens.subList(start, end)
        val firstType = body.firstOrNull()?.let { table.columns[identifierValue(it)]?.type }
        if (firstType != null && builtinType.matches(firstType)) {
            val suffix = body.drop(1).map { it.uppercase() }
            if (suffix == listOf("IS", "NULL") || suffix == listOf("IS", "NOT", "NULL")) return true
            if (firstType == "boolean" && suffix in listOf(listOf("IS", "TRUE"), listOf("IS", "FALSE"), listOf("IS", "NOT", "TRUE"), listOf("IS", "NOT", "FALSE"))) return true
            if (firstType == "text" && body.size in setOf(3, 5) && body[1] in setOf("=", "<>", "!=", "<", ">", "<=", ">=") &&
                Regex("'(?:[^']|'')*'").matches(body[2]) &&
                (body.size == 3 || body[3] == "::" && body[4] == "text")) return true
        }
        return tokens.withIndex().all { (index, token) ->
            when {
                token in setOf("(", ")", "+", "-", "*", "/", "%", "=", "<>", "!=", "<", ">", "<=", ">=") -> true
                token.uppercase() in setOf("AND", "OR", "NOT", "IS", "NULL", "TRUE", "FALSE") -> true
                token.toLongOrNull()?.let { it in 0..Int.MAX_VALUE.toLong() } == true -> true
                table.columns[identifierValue(token)]?.type in integerTypes && tokens.getOrNull(index + 1) != "(" -> true
                else -> false
            }
        }
    }

    private fun identifierValue(token: String) = if (token.startsWith('"')) token.removeSurrounding("\"").replace("\"\"", "\"") else token.lowercase()

    /** Only the relation operand changes. Expressions, literals and quoted column names stay exact. */
    private fun rewriteTarget(sql: String, keyword: String, from: String, to: String, baselineSql: String?): String {
        val span = targetSpan(sql, keyword, from.substringBefore('.')) ?: return sql
        if (span.name != from) return sql
        val previous = baselineSql?.let { targetSpan(it, keyword, to.substringBefore('.')) }
        val replacement = if (previous?.name == to) baselineSql.substring(previous.start, previous.end)
        else to.split('.').joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" }
        return sql.replaceRange(span.start, span.end, replacement)
    }

    private fun targetSpan(sql: String, keyword: String, defaultSchema: String): TargetSpan? {
        val tokens = tokenPattern.findAll(sql).toList()
        var index = tokens.indexOfFirst { it.value.equals(keyword, ignoreCase = true) }
        if (index < 0) return null
        index++
        if (keyword == "ON" && tokens.getOrNull(index)?.value.equals("ONLY", ignoreCase = true)) index++
        fun identifier(index: Int): String? = tokens.getOrNull(index)?.value?.let {
            when {
                it.startsWith('"') -> it.removeSurrounding("\"").replace("\"\"", "\"")
                bareIdentifier.matches(it) -> it.lowercase()
                else -> null
            }
        }
        val first = identifier(index) ?: return null
        val start = tokens[index].range.first
        val name = if (tokens.getOrNull(index + 1)?.value == ".") {
            val second = identifier(index + 2) ?: return null
            index += 2
            "$first.$second"
        } else "$defaultSchema.$first"
        if (tokens.getOrNull(index + 1)?.value == ".") return null
        return TargetSpan(name, start, tokens[index].range.last + 1)
    }
}
