package dev.epoch.hibernate

import dev.epoch.core.ColumnReference
import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.expression.operators.relational.*
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.delete.ParenthesedDelete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.insert.ParenthesedInsert
import net.sf.jsqlparser.statement.select.*
import net.sf.jsqlparser.statement.update.ParenthesedUpdate
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.util.TablesNamesFinder

/** Lexical dependency collection for WITH and nested SELECT scopes. No SQL is executed. */
internal object ScopedSqlDependencies {
    /** Keep the established flat-query collector; never send lexical SQL back to its global aliases. */
    fun required(statement: Statement): Boolean {
        var needed = false
        val detector = object : TablesNamesFinder<Void>() {
            private var selects = 0
            override fun <S : Any?> visit(item: WithItem<*>, context: S): Void? { needed = true; return null }
            override fun <S : Any?> visit(table: Table, context: S): Void? {
                // Relation column aliases rename by catalog position even without WITH.
                // The flat collector must not turn those output names into physical columns.
                if (!table.alias?.aliasColumns.isNullOrEmpty()) needed = true
                return super.visit(table, context)
            }
            override fun <S : Any?> visit(select: ParenthesedSelect, context: S): Void? { needed = true; return null }
            override fun <S : Any?> visit(select: LateralSubSelect, context: S): Void? { needed = true; return null }
            override fun <S : Any?> visit(select: SetOperationList, context: S): Void? { needed = true; return null }
            override fun <S : Any?> visit(item: ParenthesedFromItem, context: S): Void? { needed = true; return null }
            override fun <S : Any?> visit(select: PlainSelect, context: S): Void? {
                if (++selects > 1 || !select.withItemsList.isNullOrEmpty()) { needed = true; selects--; return null }
                return try {
                    val result = super.visit(select, context)
                    // TablesNamesFinder omits expression-bearing clauses that the established
                    // flat collector reads explicitly; nested aliases there also need scoping.
                    select.orderByElements.orEmpty().forEach { it.expression.accept(this, context) }
                    select.groupBy?.groupByExpressionList?.forEach { it.accept(this, context) }
                    select.distinct?.onSelectItems.orEmpty().forEach { it.expression.accept(this, context) }
                    result
                } finally { selects-- }
            }
        }
        detector.getTables(statement)
        return needed
    }

    fun inspect(statement: Statement, defaultSchema: String, nativeSql: Boolean): SqlDependencies.Dependencies =
        Collector(defaultSchema, nativeSql).inspect(statement)

    private sealed interface Binding
    private class Physical(val table: String) : Binding
    private class Virtual(val outputs: Map<String, Set<ColumnReference>>?, val positions: List<Set<ColumnReference>>? = null) : Binding
    private class Scope(val ctes: Map<String, Virtual>, val outer: Scope?) {
        val aliases = linkedMapOf<String, Binding>()
        // Retain two aliases of the same physical table as two relation occurrences.
        val relations = mutableListOf<Binding>()
        val ambiguousAliases = mutableSetOf<String>()
    }

    private class Collector(private val defaultSchema: String, private val nativeSql: Boolean) {
        private val tables = sortedSetOf<String>()
        private val columns = linkedSetOf<ColumnReference>()
        private val writes = linkedSetOf<ColumnReference>()
        private val reasons = sortedSetOf<String>()
        private var target: String? = null
        private var explicit = true
        private var depth = 0
        private var visited = 0
        private val schema = if (nativeSql) "public" else defaultSchema

        fun inspect(statement: Statement): SqlDependencies.Dependencies {
            statement(statement, emptyMap(), null, true)
            val order = compareBy<ColumnReference>({ it.table }, { it.column })
            return SqlDependencies.Dependencies(tables.toList(), columns.sortedWith(order), writes.sortedWith(order),
                target, reasons.isEmpty(), explicit, reasons.takeIf { it.isNotEmpty() }?.joinToString("; "))
        }

        private fun unsupported(reason: String) { reasons += reason }
        private fun enter(): Boolean {
            if (depth >= 128 || ++visited > 50_000) { unsupported("SQL lexical traversal exceeded its supported depth or node budget"); return false }
            depth++
            return true
        }

        private fun statement(statement: Statement, inheritedCtes: Map<String, Virtual>, outer: Scope?, top: Boolean): Virtual {
            if (!enter()) return Virtual(null)
            try {
                return when (statement) {
                    is ParenthesedInsert -> statement(statement.insert, inheritedCtes, outer, top)
                    is ParenthesedUpdate -> statement(statement.update, inheritedCtes, outer, top)
                    is ParenthesedDelete -> statement(statement.delete, inheritedCtes, outer, top)
                    is Select -> select(statement, inheritedCtes, outer)
                    is Insert -> {
                        val ctes = withItems(statement.withItemsList, inheritedCtes, outer)
                        val scope = Scope(ctes, outer)
                        val physical = physical(statement.table)
                        physical?.let { bind(scope, statement.table, it); if (top) target = it.table }
                        if (statement.columns.isNullOrEmpty()) { explicit = false; unsupported("Positional INSERT output is not enumerated") }
                        statement.columns.orEmpty().forEach { column -> physical?.let {
                            val ref = ColumnReference(it.table, SqlDependencies.identifier(column.columnName)); columns += ref; if (top) writes += ref
                        } }
                        if (nativeSql && (statement.select !is Values || statement.conflictAction != null || statement.conflictTarget != null))
                            unsupported("Native INSERT source/conflict variants are not enumerated")
                        statement.select?.let { select(it, ctes, null) }
                        statement.returningClause.orEmpty().forEach { expression(it.expression, scope) }
                        if (statement.conflictAction != null || statement.conflictTarget != null || !statement.duplicateUpdateSets.isNullOrEmpty())
                            unsupported("INSERT conflict effects are not enumerated")
                        Virtual(null)
                    }
                    is Update -> {
                        val ctes = withItems(statement.withItemsList, inheritedCtes, outer)
                        val scope = Scope(ctes, outer)
                        val physical = physical(statement.table)
                        physical?.let { bind(scope, statement.table, it); if (top) target = it.table }
                        from(statement.fromItem, scope)
                        joins(statement.startJoins, scope)
                        joins(statement.joins, scope)
                        statement.updateSets.orEmpty().forEach { assignment ->
                            assignment.columns.orEmpty().forEach { column -> physical?.let {
                                val ref = ColumnReference(it.table, SqlDependencies.identifier(column.columnName)); columns += ref; if (top) writes += ref
                            } }
                            assignment.values.orEmpty().filterNot(::isDefault).forEach { expression(it, scope) }
                        }
                        expression(statement.where, scope)
                        statement.returningClause.orEmpty().forEach { expression(it.expression, scope) }
                        Virtual(null)
                    }
                    is Delete -> {
                        val ctes = withItems(statement.withItemsList, inheritedCtes, outer)
                        val scope = Scope(ctes, outer)
                        physical(statement.table)?.let { bind(scope, statement.table, it); if (top) target = it.table }
                        statement.usingList.orEmpty().forEach { from(it, scope) }
                        joins(statement.joins, scope)
                        expression(statement.where, scope)
                        statement.returningClause.orEmpty().forEach { expression(it.expression, scope) }
                        Virtual(null)
                    }
                    else -> { unsupported("Unsupported statement ${statement.javaClass.simpleName}"); Virtual(null) }
                }
            } finally { depth-- }
        }

        private fun withItems(items: List<WithItem<*>>?, inherited: Map<String, Virtual>, outer: Scope?): Map<String, Virtual> {
            if (items.isNullOrEmpty()) return inherited
            val visible = LinkedHashMap(inherited)
            val recursive = items.any { it.isRecursive }
            val declared = items.map { it.alias?.name?.let(SqlDependencies::identifier) }
            if (recursive) {
                unsupported("Recursive CTE output lineage is not exhaustively enumerated")
                declared.filterNotNull().forEach { visible[it] = Virtual(null) }
            }
            val seen = mutableSetOf<String>()
            items.forEachIndexed { index, item ->
                val name = declared[index]
                val duplicate = name != null && !seen.add(name)
                if (name == null || duplicate) unsupported("CTE names are absent or ambiguous")
                val body = item.parenthesedStatement
                if (body !is ParenthesedSelect) unsupported("Writable CTE effects cannot be represented as an ordinary outer read")
                // Nonrecursive bodies see earlier CTE declarations but not their own name.
                val output = if (body != null) statement(body, visible.toMap(), outer, false) else Virtual(null).also {
                    unsupported("CTE body is absent")
                }
                val aliases = item.withItemList.orEmpty().map { output ->
                    (output.expression as? Column)?.takeIf { it.table?.fullyQualifiedName.isNullOrBlank() }
                        ?.columnName?.let(SqlDependencies::identifier)
                }
                val renamed = if (aliases.isEmpty()) output else {
                    val positions = output.positions
                    if (aliases.any { it == null } || aliases.distinct().size != aliases.size || positions == null || aliases.size != positions.size) {
                        unsupported("CTE output aliases do not have unambiguous direct lineage"); Virtual(null)
                    } else Virtual(aliases.mapIndexed { position, alias -> requireNotNull(alias) to positions[position] }.toMap(), positions)
                }
                if (name != null) visible[name] = if (recursive || duplicate) Virtual(null) else renamed
            }
            return visible
        }

        private fun select(select: Select, inheritedCtes: Map<String, Virtual>, outer: Scope?): Virtual {
            if (!enter()) return Virtual(null)
            try {
                val ctes = withItems(select.withItemsList, inheritedCtes, outer)
                if (select is ParenthesedSelect) return select(select.select, ctes, outer)
                if (select is SetOperationList) {
                    unsupported("Set-operation output lineage is not enumerated")
                    select.selects.orEmpty().forEach { select(it, ctes, outer) }
                    return Virtual(null)
                }
                val scope = Scope(ctes, outer)
                if (select is Values) {
                    select.expressions.orEmpty().filterNot(::isDefault).forEach { expression(it, scope) }
                    unsupported("VALUES output lineage is not enumerated")
                    return Virtual(null)
                }
                if (select !is PlainSelect) { unsupported("Unsupported SELECT body ${select.javaClass.simpleName}"); return Virtual(null) }
                // Establish every local relation before visiting the projection.
                from(select.fromItem, scope)
                joins(select.joins, scope)
                val output = linkedMapOf<String, Set<ColumnReference>>()
                val positions = mutableListOf<Set<ColumnReference>>()
                var direct = true
                select.selectItems.orEmpty().forEach { item ->
                    val lineage = expression(item.expression, scope)
                    val name = item.alias?.name?.let(SqlDependencies::identifier)
                        ?: (item.expression as? Column)?.columnName?.let(SqlDependencies::identifier)
                    if (name == null || lineage == null || output.containsKey(name) || item.expression !is Column) {
                        direct = false
                    } else { output[name] = lineage; positions += lineage }
                }
                expression(select.where, scope)
                expression(select.having, scope)
                fun clause(value: Expression, outputAliases: Boolean) {
                    val name = (value as? Column)?.takeIf { it.table?.fullyQualifiedName.isNullOrBlank() }
                        ?.columnName?.let(SqlDependencies::identifier)
                    if (name != null && name in output) {
                        if (outputAliases) columns += output.getValue(name) else unsupported("Input/output alias precedence requires catalog evidence")
                    } else expression(value, scope)
                }
                select.orderByElements.orEmpty().forEach { clause(it.expression, true) }
                select.groupBy?.groupByExpressionList?.forEach { clause(it, false) }
                select.distinct?.onSelectItems.orEmpty().forEach { clause(it.expression, false) }
                if (select.groupBy != null || select.distinct != null || select.having != null) {
                    unsupported("Grouped/distinct CTE output is outside direct projection lineage")
                }
                if (select.limit != null || select.limitBy != null || select.offset != null || select.fetch != null ||
                    !select.windowDefinitions.isNullOrEmpty() || select.qualify != null || select.oracleHierarchical != null ||
                    !select.intoTables.isNullOrEmpty() || select.intoTempTable != null || select.top != null || select.skip != null || select.first != null ||
                    select.forMode != null || !select.lateralViews.isNullOrEmpty()) unsupported("SELECT modifiers are not enumerated in scoped dependency extraction")
                if (!direct) unsupported("CTE/nested projection has no complete direct column lineage")
                return if (direct) Virtual(output, positions) else Virtual(null)
            } finally { depth-- }
        }

        private fun from(item: FromItem?, scope: Scope) {
            when (item) {
                null -> Unit
                is Table -> {
                    val cte = if (item.schemaName.isNullOrBlank()) scope.ctes[SqlDependencies.identifier(item.name)] else null
                    if (cte != null) bind(scope, item, cte) else physical(item)?.let { bind(scope, item, it) }
                }
                is ParenthesedSelect -> {
                    unsupported("Derived/lateral relation completeness is not yet established")
                    val output = select(item, scope.ctes, scope.takeIf { item is LateralSubSelect })
                    bindVirtual(scope, item.alias?.name, output)
                }
                is ParenthesedFromItem -> {
                    unsupported("Parenthesized join-group output lineage is not enumerated")
                    val nested = Scope(scope.ctes, null)
                    from(item.fromItem, nested)
                    joins(item.joins, nested)
                    if (item.alias != null) bindVirtual(scope, item.alias.name, Virtual(null))
                    else {
                        nested.aliases.forEach { (name, binding) -> alias(scope, name, binding) }
                        scope.ambiguousAliases += nested.ambiguousAliases
                        scope.ambiguousAliases.forEach(scope.aliases::remove)
                        scope.relations += nested.relations
                    }
                }
                is TableFunction -> {
                    unsupported("Table function output and hidden dependencies are not enumerated")
                    expression(item.function, scope)
                    bindVirtual(scope, item.alias?.name, Virtual(null))
                }
                else -> { unsupported("Unsupported FROM item ${item.javaClass.simpleName}"); bindVirtual(scope, item.alias?.name, Virtual(null)) }
            }
        }

        private fun joins(items: List<Join>?, scope: Scope) {
            items.orEmpty().forEach { join ->
                from(join.rightItem, scope)
                if (join.isNatural || !join.usingColumns.isNullOrEmpty()) unsupported("Implicit join column resolution requires catalog evidence")
                join.onExpressions.orEmpty().forEach { expression(it, scope) }
            }
        }

        private fun physical(table: Table): Physical? {
            if (!table.databaseName.isNullOrBlank()) {
                unsupported("Cross-database relation qualification is outside the supported identity model"); return null
            }
            val name = SqlDependencies.identifier(table.name)
            val namespace = table.schemaName?.takeIf { it.isNotBlank() }?.let(SqlDependencies::identifier) ?: SqlDependencies.identifier(schema)
            if ('.' in name || '.' in namespace || name.isBlank() || namespace.isBlank()) {
                unsupported("Physical relation identity cannot be flattened unambiguously"); return null
            }
            if (nativeSql && table.schemaName.isNullOrBlank() && SqlDependencies.identifier(defaultSchema) != "public")
                unsupported("Native unqualified relations do not inherit the ORM default schema")
            val physical = "$namespace.$name"
            tables += physical
            return Physical(physical)
        }

        private fun bind(scope: Scope, table: Table, binding: Binding) {
            val alias = table.alias?.name?.let(SqlDependencies::identifier)
            val keys = if (alias != null) listOf(alias) else listOfNotNull(SqlDependencies.identifier(table.name),
                (binding as? Physical)?.table)
            val effective = if (!table.alias?.aliasColumns.isNullOrEmpty()) {
                unsupported("Relation column aliases require catalog/output position facts that are not established")
                // Preserve known physical relations, but no column on this renamed output
                // may be guessed as an identically named physical column. Other aliases
                // retain their independent established references.
                Virtual(null)
            } else binding
            scope.relations += effective
            keys.distinct().forEach { key -> alias(scope, key, effective) }
        }
        private fun alias(scope: Scope, key: String, binding: Binding) {
            if (key in scope.ambiguousAliases || scope.aliases.putIfAbsent(key, binding) != null) {
                scope.ambiguousAliases += key
                scope.aliases.remove(key)
                unsupported("Relation aliases collide")
            }
        }
        private fun bindVirtual(scope: Scope, name: String?, binding: Virtual) {
            if (name == null) { unsupported("Derived relation alias is absent"); return }
            scope.relations += binding
            alias(scope, SqlDependencies.identifier(name), binding)
        }

        private fun column(column: Column, scope: Scope): Set<ColumnReference>? {
            val name = SqlDependencies.identifier(column.columnName)
            val qualifier = column.table?.fullyQualifiedName?.takeIf { it.isNotBlank() }
            val binding = if (qualifier == null) {
                if (scope.aliases[name] is Physical || name in scope.ambiguousAliases) {
                    // PostgreSQL can resolve an unqualified relation name as a whole-row
                    // composite value. A same-named scalar column takes precedence, but its
                    // existence cannot be established from SQL text alone.
                    unsupported("An unqualified relation-name expression may be a whole-row value"); return null
                }
                if (scope.outer != null && scope.relations.any { it is Physical }) {
                    unsupported("An unqualified correlated column cannot be assigned without catalog column visibility"); return null
                }
                if (scope.relations.size == 1) scope.relations.single()
                else if (scope.relations.all { it is Virtual && it.outputs != null })
                    scope.relations.filter { name in (it as Virtual).outputs.orEmpty() }.singleOrNull()
                else null
            } else {
                val key = if (column.table.schemaName.isNullOrBlank()) SqlDependencies.identifier(column.table.name)
                    else SqlDependencies.qualified(qualifier, schema)
                if (key in scope.ambiguousAliases) { unsupported("Relation aliases collide"); return null }
                var found = scope.aliases[key]
                if (found == null) {
                    var outer = scope.outer
                    while (outer != null && found == null) {
                        if (key in outer.ambiguousAliases) { unsupported("Relation aliases collide"); return null }
                        found = outer.aliases[key]; outer = outer.outer
                    }
                    if (found != null) unsupported("Correlated query lineage is retained but not claimed exhaustive")
                }
                found
            }
            val result = when (binding) {
                is Physical -> setOf(ColumnReference(binding.table, name))
                is Virtual -> binding.outputs?.get(name)
                null -> null
            }
            if (result == null) unsupported("A column has ambiguous, virtual, or unresolved lexical lineage")
            else columns += result
            return result
        }

        private fun expression(value: Expression?, scope: Scope): Set<ColumnReference>? {
            if (value == null) return emptySet()
            if (!enter()) return null
            try {
                fun combine(vararg values: Expression?): Set<ColumnReference>? {
                    val parts = values.map { expression(it, scope) }
                    return if (parts.any { it == null }) null else parts.filterNotNull().flatten().toSet()
                }
                return when (value) {
                    is Column -> column(value, scope)
                    is JdbcParameter, is JdbcNamedParameter, is LongValue, is DoubleValue, is StringValue,
                    is BooleanValue, is NullValue, is DateValue, is TimeValue, is TimestampValue -> emptySet()
                    is AllColumns, is AllTableColumns -> { explicit = false; unsupported("Wildcard outputs have no enumerated physical columns"); null }
                    is BinaryExpression -> {
                        if (value.javaClass.simpleName !in binary) unsupported("Unsupported operator ${value.javaClass.simpleName}")
                        combine(value.leftExpression, value.rightExpression)
                    }
                    is SignedExpression -> expression(value.expression, scope)
                    is NotExpression -> expression(value.expression, scope)
                    is IsNullExpression -> expression(value.leftExpression, scope)
                    is Between -> combine(value.leftExpression, value.betweenExpressionStart, value.betweenExpressionEnd)
                    is InExpression -> combine(value.leftExpression, value.rightExpression)
                    is ExistsExpression -> expression(value.rightExpression, scope)
                    is ExpressionList<*> -> {
                        val parts = value.map { expression(it, scope) }
                        if (parts.any { it == null }) null else parts.filterNotNull().flatten().toSet()
                    }
                    is ParenthesedSelect -> { unsupported("Nested subquery effects are not exhaustively enumerated"); select(value, scope.ctes, scope); null }
                    is Select -> { unsupported("Nested subquery effects are not exhaustively enumerated"); select(value, scope.ctes, scope); null }
                    is Function -> {
                        if (nativeSql && value.name.lowercase() !in functions) unsupported("Native function may conceal database dependencies")
                        val parts = value.parameters.orEmpty().map { expression(it, scope) }
                        if (value.isAllColumns || value.keep != null || value.orderByElements != null || value.namedParameters != null)
                            unsupported("Function modifiers are not exhaustively enumerated")
                        if (parts.any { it == null }) null else parts.filterNotNull().flatten().toSet()
                    }
                    is CastExpression -> { unsupported("Cast behavior is outside direct scoped lineage"); expression(value.leftExpression, scope) }
                    is CaseExpression -> {
                        unsupported("CASE output lineage is outside the initial direct projection model")
                        val parts = listOf(value.switchExpression, value.elseExpression) + value.whenClauses.orEmpty()
                        parts.forEach { expression(it, scope) }; null
                    }
                    is WhenClause -> combine(value.whenExpression, value.thenExpression)
                    else -> { unsupported("Unsupported scoped expression ${value.javaClass.simpleName}"); null }
                }
            } finally { depth-- }
        }

        private fun isDefault(value: Expression) = value is Column && value.table?.fullyQualifiedName.isNullOrBlank() && value.columnName.equals("DEFAULT", true)
        private val functions = setOf("count", "min", "max", "sum", "avg", "lower", "upper", "length", "char_length", "coalesce", "nullif", "abs")
        private val binary = setOf("EqualsTo", "NotEqualsTo", "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals", "AndExpression", "OrExpression",
            "Addition", "Subtraction", "Multiplication", "Division", "Modulo", "Concat", "LikeExpression", "IsDistinctExpression")
    }
}
