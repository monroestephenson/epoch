package dev.epoch.core

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.operators.relational.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.statement.update.Update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** SQL syntax facts are cached independently of schema snapshots and contract entry IDs. */
class BehaviorQuerySyntax {
    private data class Key(
        val sql: String, val operation: Operation, val target: String?, val tables: Set<String>,
        val writes: Set<ColumnReference>, val parameters: List<String>, val results: List<String>,
        val dependenciesComplete: Boolean,
    )
    private val cache = ConcurrentHashMap<Key, QueryBehaviorFacts>()

    fun inspect(query: QueryContract): QueryBehaviorFacts {
        val key = Key(query.sql, query.operation, query.targetTable,
            (query.tables + listOfNotNull(query.targetTable) + (query.columns + query.writeColumns).map { it.table }).toSet(),
            query.writeColumns.toSet(), query.parameterTypes.toList(), query.resultColumns.mapNotNull { it.typeName }, query.dependenciesComplete)
        return cache.computeIfAbsent(key) { parse(it) }
    }

    private fun parse(key: Key): QueryBehaviorFacts {
        val collector = Collector(key)
        return try {
            collector.statement(CCJSqlParserUtil.parse(key.sql))
            collector.finish()
        } catch (failure: StackOverflowError) {
            collector.problem("SQL parsing exceeded the supported nesting depth.")
            collector.finish()
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted || generateSequence<Throwable>(failure) { it.cause }.take(32)
                    .any { it is InterruptedException || it is java.util.concurrent.CancellationException }) {
                Thread.currentThread().interrupt()
                throw failure
            }
            collector.problem("SQL behavior syntax could not be classified: ${failure.javaClass.simpleName}.")
            collector.finish()
        }
    }

    private class Collector(private val key: Key) {
        private val reasons = sortedSetOf<String>()
        private var dependencyGap = false
        private var cteNames = emptySet<String>()
        private val references = linkedSetOf<BehaviorReference>()
        private val defaults = linkedSetOf<ColumnReference>()
        private val functions = sortedSetOf<String>()
        private val actualTables = sortedSetOf<String>()
        private var assignmentsComplete = key.operation in setOf(Operation.SELECT, Operation.DELETE)
        private var depth = 0
        private var visited = 0

        fun problem(reason: String) { reasons += reason; dependencyGap = true }
        private fun semanticProblem(reason: String) { reasons += reason }

        fun finish(): QueryBehaviorFacts {
            val declared = key.tables.map(::canonicalQualified).toSet()
            if (!declared.containsAll(actualTables)) problem("Actual SQL relations are missing from the declared dependency inventory.")
            key.parameters.forEach { typeReference(it, jdbcHint = true) }
            key.results.filterNot { it.trim().lowercase(Locale.ROOT) in JDBC_RESULT_ALIASES }.forEach { typeReference(it, jdbcHint = true) }
            return QueryBehaviorFacts(
                complete = reasons.isEmpty(), references = references.sortedWith(compareBy({ it.kind.name }, { it.name }))
                    .toCollection(linkedSetOf()),
                defaultColumns = defaults.sortedWith(compareBy({ it.table }, { it.column })).toCollection(linkedSetOf()),
                defaultAssignmentsComplete = assignmentsComplete,
                unresolvedReasons = reasons.toList(), builtinFunctionCalls = functions.toSet(),
                dependencyCoverageComplete = key.dependenciesComplete && !dependencyGap,
            )
        }

        fun statement(statement: Statement) {
            val actualOperation = when (statement) {
                is Select -> Operation.SELECT
                is Insert -> Operation.INSERT
                is Update -> Operation.UPDATE
                is Delete -> Operation.DELETE
                else -> null
            }
            if (actualOperation != key.operation) problem("SQL statement kind does not match a supported declared operation.")
            when (statement) {
                is Select -> select(statement)
                is Insert -> insert(statement)
                is Update -> update(statement)
                is Delete -> {
                    withItems(statement.withItemsList)
                    table(statement.table)
                    statement.usingList.orEmpty().forEach(::table)
                    joins(statement.joins)
                    expression(statement.where)
                    statement.returningClause.orEmpty().forEach { expression(it.expression) }
                    statement.orderByElements.orEmpty().forEach { expression(it.expression) }
                    limit(statement.limit)
                    if (statement.outputClause != null || statement.preferringClause != null || !statement.tables.isNullOrEmpty())
                        problem("Nonstandard DELETE effects are not enumerated.")
                }
                else -> problem("The statement is outside supported read and write SQL syntax.")
            }
        }

        private fun withItems(items: List<WithItem<*>>?, readonly: Boolean = false) {
            if (items.isNullOrEmpty()) return
            if (!readonly || items.any { it.isRecursive }) {
                problem("CTE and potentially writable nested statement effects are not exhaustively enumerated.")
                return
            }
            semanticProblem("CTE semantics remain outside the supported verification model.")
            val declaredHere = mutableSetOf<String>()
            for (item in items) {
                val name = item.alias?.name?.let(::identifier)
                if (name == null || !declaredHere.add(name)) {
                    problem("CTE relation scope has an absent or duplicate declaration.")
                    continue
                }
                val body = item.parenthesedStatement as? ParenthesedSelect
                if (body == null) problem("Writable CTE effects are not exhaustively enumerated.")
                else select(body)
                // Nonrecursive body can see preceding CTEs but not its own declaration.
                cteNames = cteNames + name
            }
        }

        private fun table(table: Table?): String? {
            if (table == null) return null
            if (table.schemaName.isNullOrBlank() && table.databaseName.isNullOrBlank() &&
                identifier(table.name) in cteNames) return null
            val name = qualified(table.fullyQualifiedName)
            if (name == null) problem("The SQL relation identifier cannot be resolved unambiguously.")
            else actualTables += name
            return name
        }

        private fun from(item: FromItem?) {
            when (item) {
                null -> Unit
                is Table -> table(item)
                is ParenthesedSelect -> { semanticProblem("Derived or nested query effects are not exhaustively enumerated."); select(item) }
                else -> problem("The FROM item has unsupported relation or function semantics.")
            }
        }

        private fun joins(joins: List<Join>?) {
            joins.orEmpty().forEach { join ->
                from(join.rightItem)
                join.onExpressions.orEmpty().forEach(::expression)
                if (join.isNatural || !join.usingColumns.isNullOrEmpty() || join.isApply || join.isWindowJoin)
                    problem("Implicit or specialized join dependencies are not fully enumerated.")
            }
        }

        private fun select(select: Select) {
            if (++depth > MAX_DEPTH) { problem("SQL nesting exceeds the supported depth."); depth--; return }
            val previousCtes = cteNames
            try {
                withItems(select.withItemsList, readonly = true)
                when (select) {
                    is PlainSelect -> {
                        from(select.fromItem)
                        joins(select.joins)
                        select.selectItems.orEmpty().forEach { expression(it.expression) }
                        expression(select.where)
                        expression(select.having)
                        select.groupBy?.groupByExpressionList?.forEach(::expression)
                        select.distinct?.onSelectItems.orEmpty().forEach { expression(it.expression) }
                        if (!select.groupBy?.groupingSets.isNullOrEmpty() || !select.windowDefinitions.isNullOrEmpty() ||
                            select.qualify != null || !select.intoTables.isNullOrEmpty() || select.intoTempTable != null ||
                            select.oracleHierarchical != null || select.preferringClause != null ||
                            select.top != null || select.skip != null || select.first != null || !select.lateralViews.isNullOrEmpty())
                            problem("The SELECT contains unsupported data effects or query variants.")
                    }
                    is Values -> select.expressions?.forEach(::expression)
                    is ParenthesedSelect -> { semanticProblem("Nested query effects are not exhaustively enumerated."); this.select(select.select) }
                    else -> problem("Set-operation or specialized SELECT effects are not exhaustively enumerated.")
                }
                select.orderByElements.orEmpty().forEach { expression(it.expression) }
                limit(select.limit)
                select.offset?.let { pagination(it.offset, "OFFSET") }
                select.fetch?.let { fetch ->
                    val value = fetch.expression ?: fetch.fetchJdbcParameter
                    if (value != null) pagination(value, "FETCH") else if (fetch.rowCount < 0) problem("FETCH count is not a nonnegative fixed value.")
                    if (fetch.fetchParameters.orEmpty().any { it.equals("TIES", true) }) problem("FETCH WITH TIES is outside the bounded pagination model.")
                }
                if (select.limitBy != null) problem("LIMIT BY is outside the supported pagination model.")
            } finally { cteNames = previousCtes; depth-- }
        }

        private fun insert(insert: Insert) {
            withItems(insert.withItemsList)
            val target = table(insert.table)
            val columns = insert.columns.orEmpty().map { identifier(it.columnName) }
            val validColumns = columns.all { it != null } && columns.distinct().size == columns.size
            val declaredTarget = key.target?.let(::canonicalQualified)
            if (target == null || declaredTarget != null && target != declaredTarget) problem("The actual INSERT target is not established by the contract.")
            val actualWrites = if (target == null || !validColumns) emptySet() else columns.map { ColumnReference(target, it!!) }.toSet()
            val expectedWrites = key.writes.map { ColumnReference(canonicalQualified(it.table), canonicalIdentifier(it.column)) }.toSet()
            var complete = target != null && validColumns && actualWrites == expectedWrites
            if (insert.isOnlyDefaultValues) {
                complete = complete && columns.isEmpty()
            } else {
                val values = insert.select as? Values
                val source = insert.select as? PlainSelect
                if (columns.isNotEmpty() && source != null && source.withItemsList.isNullOrEmpty() &&
                    source.selectItems.size == columns.size && source.selectItems.none { it.expression is AllColumns || it.expression is AllTableColumns }) {
                    // The target's explicit columns receive one source expression each. Source
                    // relations remain read dependencies; the normal walker rejects opaque code,
                    // writable/nested sources and unsupported variants independently.
                    select(source)
                } else if (columns.isEmpty() || values == null) {
                    complete = false
                    problem("Positional INSERT or unsupported INSERT source does not establish explicit assignment variants.")
                    insert.select?.let(::select)
                } else {
                    val items = values.expressions.orEmpty()
                    val rows: List<List<Expression>> = when {
                        items is ParenthesedExpressionList<*> -> listOf(items.toList())
                        items.all { it is ParenthesedExpressionList<*> } -> items.map { (it as ParenthesedExpressionList<*>).toList() }
                        else -> listOf(items.toList())
                    }
                    if (rows.isEmpty() || rows.any { it.size != columns.size }) complete = false
                    rows.forEach { row -> row.forEachIndexed { index, value ->
                        if (isDefault(value)) {
                            if (target != null && columns.getOrNull(index) != null) defaults += ColumnReference(target, columns[index]!!)
                            else complete = false
                        } else expression(value)
                    } }
                }
            }
            if (insert.conflictAction != null || insert.conflictTarget != null || !insert.duplicateUpdateSets.isNullOrEmpty() ||
                !insert.setUpdateSets.isNullOrEmpty() || insert.outputClause != null) {
                complete = false
                problem("Conflict or nonstandard INSERT write variants are not fully enumerated.")
            }
            insert.returningClause.orEmpty().forEach { expression(it.expression) }
            assignmentsComplete = complete && insert.withItemsList.isNullOrEmpty()
            if (!assignmentsComplete) problem("INSERT assignment/default coverage is incomplete or disagrees with the declared write set.")
        }

        private fun update(update: Update) {
            withItems(update.withItemsList)
            val target = table(update.table)
            val writes = linkedSetOf<ColumnReference>()
            var complete = target != null && (key.target == null || target == canonicalQualified(key.target))
            update.updateSets.orEmpty().forEach { assignment ->
                val columns = assignment.columns.orEmpty()
                val values = assignment.values.orEmpty()
                if (columns.size != values.size || columns.size != 1) complete = false
                columns.forEachIndexed { index, column ->
                    val name = identifier(column.columnName)
                    if (name == null || target == null) complete = false
                    else {
                        val reference = ColumnReference(target, name)
                        if (!writes.add(reference)) complete = false
                        val value = values.getOrNull(index)
                        if (isDefault(value)) defaults += reference else expression(value)
                    }
                }
                if (columns.size != values.size) values.forEach(::expression)
            }
            val expected = key.writes.map { ColumnReference(canonicalQualified(it.table), canonicalIdentifier(it.column)) }.toSet()
            complete = complete && writes.isNotEmpty() && writes == expected
            from(update.fromItem)
            joins(update.joins)
            joins(update.startJoins)
            expression(update.where)
            update.returningClause.orEmpty().forEach { expression(it.expression) }
            update.orderByElements.orEmpty().forEach { expression(it.expression) }
            limit(update.limit)
            if (update.outputClause != null || update.preferringClause != null) problem("Nonstandard UPDATE effects are not enumerated.")
            assignmentsComplete = complete && update.withItemsList.isNullOrEmpty()
            if (!assignmentsComplete) problem("UPDATE assignment/default coverage is incomplete or disagrees with the declared write set.")
        }

        private fun limit(limit: Limit?) {
            if (limit == null) return
            limit.rowCount?.let { pagination(it, "LIMIT") }
            limit.offset?.let { pagination(it, "LIMIT OFFSET") }
            if (!limit.byExpressions.isNullOrEmpty()) problem("LIMIT BY is outside the supported pagination model.")
        }

        private fun pagination(value: Expression?, clause: String) {
            if (!(value is JdbcParameter || value is LongValue && value.value >= 0)) {
                problem("$clause is not a fixed nonnegative count or a single bound JDBC parameter.")
                expression(value)
            }
        }

        private fun expression(value: Expression?) {
            if (value == null) return
            visited++
            if (visited > MAX_EXPRESSIONS || depth >= MAX_DEPTH) {
                problem("SQL expression traversal exceeds its supported bound."); return
            }
            depth++
            try {
                when (value) {
                    is NullValue, is JdbcParameter, is LongValue, is DoubleValue, is StringValue, is BooleanValue -> Unit
                    is Column -> if (isDefault(value)) problem("DEFAULT occurs outside an established assignment position.")
                    is Function -> function(value)
                    is CastExpression -> {
                        value.colDataType?.let { typeReference(it.toString()) }
                        expression(value.leftExpression)
                        problem("Explicit cast behavior requires catalog-resolved coercion evidence.")
                    }
                    is BinaryExpression -> {
                        if (value.javaClass.simpleName !in SUPPORTED_BINARY) problem("Unsupported operator expression ${value.javaClass.simpleName}.")
                        expression(value.leftExpression); expression(value.rightExpression)
                        if (value is LikeExpression) expression(value.escape)
                    }
                    is SignedExpression -> expression(value.expression)
                    is NotExpression -> expression(value.expression)
                    is IsNullExpression -> expression(value.leftExpression)
                    is IsBooleanExpression -> expression(value.leftExpression)
                    is Between -> { expression(value.leftExpression); expression(value.betweenExpressionStart); expression(value.betweenExpressionEnd) }
                    is InExpression -> { expression(value.leftExpression); expression(value.rightExpression) }
                    is CaseExpression -> {
                        expression(value.switchExpression); value.whenClauses.orEmpty().forEach(::expression); expression(value.elseExpression)
                    }
                    is WhenClause -> { expression(value.whenExpression); expression(value.thenExpression) }
                    is ExpressionList<*> -> value.forEach(::expression)
                    is ExistsExpression -> { semanticProblem("EXISTS semantics remain outside the supported verification model."); expression(value.rightExpression) }
                    is Select -> { semanticProblem("Subquery effects are not exhaustively enumerated."); select(value) }
                    else -> problem("Unsupported SQL expression ${value.javaClass.simpleName}.")
                }
            } finally { depth-- }
        }

        private fun function(function: Function) {
            val name = function.name?.lowercase(Locale.ROOT).orEmpty()
            val unqualified = name.substringAfterLast('.')
            val validName = name == unqualified || name == "pg_catalog.$unqualified"
            val arguments = function.parameters.orEmpty()
            val supportedArguments = arguments.all { it is Column || it is JdbcParameter || it is LongValue || it is DoubleValue ||
                it is StringValue || it is BooleanValue || it is NullValue || it is AllColumns && unqualified == "count" }
            val countStar = unqualified == "count" && (function.isAllColumns || arguments.singleOrNull() is AllColumns)
            val supportedArity = when (unqualified) {
                "count" -> countStar || arguments.size == 1
                "coalesce" -> arguments.isNotEmpty()
                "nullif" -> arguments.size == 2
                else -> arguments.size == 1
            }
            if (validName && unqualified in BUILTIN_FUNCTIONS && supportedArguments && supportedArity &&
                (!function.isAllColumns || unqualified == "count") && function.namedParameters == null && function.attribute == null &&
                function.keep == null && function.havingClause == null && function.limit == null && function.orderByElements.isNullOrEmpty()) {
                functions += name
            } else problem("Function call $name lacks a complete supported builtin signature and argument shape.")
            arguments.filterNot { countStar && it is AllColumns }.forEach(::expression)
            function.orderByElements.orEmpty().forEach { expression(it.expression) }
        }

        private fun typeReference(value: String, jdbcHint: Boolean = false) {
            val name = value.trim()
            if (name.isEmpty() || jdbcHint && name.uppercase(Locale.ROOT) in JDBC_TYPES) return
            val normalized = name.replace(Regex("\\s+"), " ")
            references += BehaviorReference(BehaviorReferenceKind.TYPE, normalized)
        }
    }

    companion object {
        private const val MAX_DEPTH = 128
        private const val MAX_EXPRESSIONS = 20_000
        private val BUILTIN_FUNCTIONS = setOf("count", "min", "max", "sum", "avg", "lower", "upper", "length", "char_length", "coalesce", "nullif", "abs")
        private val SUPPORTED_BINARY = setOf("EqualsTo", "NotEqualsTo", "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals",
            "Addition", "Subtraction", "Multiplication", "Division", "Modulo", "AndExpression", "OrExpression", "LikeExpression", "Concat", "IsDistinctExpression")
        private val JDBC_RESULT_ALIASES = setOf("smallserial", "serial2", "serial", "serial4", "bigserial", "serial8")
        private val JDBC_TYPES = setOf("BIGINT", "INTEGER", "SMALLINT", "TINYINT", "DOUBLE", "REAL", "FLOAT", "NUMERIC", "DECIMAL", "BOOLEAN", "BIT",
            "CHAR", "VARCHAR", "LONGVARCHAR", "NCHAR", "NVARCHAR", "LONGNVARCHAR", "BINARY", "VARBINARY", "LONGVARBINARY", "DATE", "TIME", "TIMESTAMP",
            "TIME_WITH_TIMEZONE", "TIMESTAMP_WITH_TIMEZONE", "NULL", "OTHER", "UNKNOWN")
        private fun isDefault(value: Expression?): Boolean = value is Column && value.table?.fullyQualifiedName.isNullOrBlank() &&
            value.columnName.equals("DEFAULT", ignoreCase = true)
        private fun identifier(value: String): String? {
            if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
                val decoded = value.substring(1, value.length - 1).replace("\"\"", "\"")
                return decoded.takeIf { it.isNotEmpty() && '.' !in it }?.let(::postgresIdentifier)
            }
            if (!Regex("[A-Za-z_][A-Za-z_0-9$]*").matches(value)) return null
            return postgresIdentifier(value.lowercase(Locale.ROOT))
        }
        private fun qualified(value: String): String? {
            val parts = value.split('.')
            if (parts.size !in 1..2) return null
            val normalized = parts.map { identifier(it) ?: return null }
            return if (normalized.size == 1) "public.${normalized.single()}" else normalized.joinToString(".")
        }
        private fun canonicalIdentifier(value: String) = postgresIdentifier(value)
        private fun canonicalQualified(value: String) = value.split('.').joinToString(".", transform = ::postgresIdentifier)
    }
}
