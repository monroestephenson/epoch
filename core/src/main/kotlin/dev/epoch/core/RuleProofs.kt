package dev.epoch.core

/** Deliberately small proof fragments over PostgreSQL catalog definitions, never migration regexes. */
internal object RuleProofs {
    private val builtin = "(?:smallint|integer|bigint|real|double precision|numeric(?:\\(\\d+(?:,\\d+)?\\))?|text|character varying(?:\\(\\d+\\))?|boolean)"
    fun constantDefault(expression: String?, type: String): Boolean {
        if (expression == null || !Regex("^$builtin$").matches(type)) return false
        val value = expression.trim()
        val literal = "(?:[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?|true|false|'(?:[^']|'')*')"
        return Regex("^$literal(?:::$builtin)?$", RegexOption.IGNORE_CASE).matches(value)
    }

    private data class Shape(val family: String, val integerDigits: Int? = null, val fractionDigits: Int = 0, val length: Long? = null)
    private fun shape(value: String): Shape? {
        val type = value.lowercase().trim()
        return when (type) {
            "smallint", "int2" -> Shape("integer", 5)
            "integer", "int", "int4" -> Shape("integer", 10)
            "bigint", "int8" -> Shape("integer", 19)
            "text", "character varying", "varchar" -> Shape("string", length = Long.MAX_VALUE)
            "numeric", "decimal" -> Shape("numeric", Int.MAX_VALUE, Int.MAX_VALUE)
            else -> Regex("(?:character varying|varchar)\\((\\d+)\\)").matchEntire(type)?.let {
                Shape("string", length = it.groupValues[1].toLong())
            } ?: Regex("(?:numeric|decimal)\\((\\d+)(?:,\\s*(\\d+))?\\)").matchEntire(type)?.let {
                val precision = it.groupValues[1].toInt()
                val scale = it.groupValues[2].ifBlank { "0" }.toInt()
                Shape("numeric", precision - scale, scale)
            }
        }
    }

    fun widening(before: String, after: String): Boolean {
        val a = shape(before) ?: return false
        val b = shape(after) ?: return false
        if (a.family != b.family) return false
        return when (a.family) {
            "string" -> b.length!! >= a.length!!
            "integer", "numeric" -> b.integerDigits!! >= a.integerDigits!! && b.fractionDigits >= a.fractionDigits
            else -> false
        }
    }

    fun stableReadRepresentation(before: String, after: String) =
        shape(before)?.family == "string" && shape(after)?.family == "string"

    fun mappedNarrowing(column: ColumnContract, before: String, after: String): String? {
        if (!column.inputDomainComplete) return null
        val a = shape(before) ?: return null
        val b = shape(after) ?: return null
        if (a.family != b.family) return null
        // An enum mapping accepts its finite labels, not every value up to the JDBC/DDL bound.
        if (column.enumValues.isNotEmpty()) {
            if (a.family == "string") {
                val excluded = column.enumValues.filter { it.codePointCount(0, it.length).toLong() <= a.length!! &&
                    it.codePointCount(0, it.length).toLong() > b.length!! }
                return excluded.takeIf { it.isNotEmpty() }?.let { "Mapped enum labels $it fit $before and exceed $after." }
            }
            return null
        }
        if (a.family == "integer" && (column.logicalJavaType == null || column.logicalJavaType in setOf("byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long"))) {
            val mapped = column.typeName?.let(::shape)
            if (mapped?.family == "integer" && mapped.integerDigits!! <= a.integerDigits!! && mapped.integerDigits > b.integerDigits!!) {
                return "Declared mapped integer type ${column.typeName} includes values in $before outside the target $after range."
            }
        }
        if (a.family == "string" && (column.logicalJavaType == null || column.logicalJavaType == "java.lang.String") && column.length != null && column.length > b.length!! && column.length <= a.length!!) {
            return "Declared mapped length ${column.length} is accepted by $before and exceeds target length ${b.length}."
        }
        if (a.family == "numeric" && (column.logicalJavaType == null || column.logicalJavaType == "java.math.BigDecimal") && column.precision != null && column.scale != null) {
            val integer = column.precision - column.scale
            if (integer <= a.integerDigits!! && column.scale <= a.fractionDigits &&
                (integer > b.integerDigits!! || column.scale > b.fractionDigits)) {
                return "Declared mapped precision/scale ${column.precision}/${column.scale} is represented exactly by $before; $after can overflow or round those values."
            }
        }
        return null
    }

    /** Conservative bound for ordinary btree tuples on PostgreSQL's supported 8 KiB pages. */
    fun boundedBtreeKey(table: TableSchema, index: String): Boolean {
        if (table.indexMethods[index] != "btree") return false
        val columns = table.indexColumns[index].orEmpty()
        if (columns.isEmpty()) return false
        var bytes = 32L // tuple header, alignment and NULL bitmap margin
        for (name in columns) {
            val type = table.columns[name]?.type ?: return false
            val size = when (type) {
                "boolean" -> 1L
                "smallint" -> 2L
                "integer", "real", "date" -> 4L
                "bigint", "double precision", "time without time zone", "timestamp without time zone", "timestamp with time zone" -> 8L
                "uuid", "interval", "time with time zone" -> 16L
                else -> Regex("(?:character varying|varchar|character|char)\\((\\d+)\\)").matchEntire(type)?.let { it.groupValues[1].toLong() * 4 + 8 }
                    ?: Regex("numeric\\((\\d+)(?:,\\d+)?\\)").matchEntire(type)?.let { (it.groupValues[1].toLong() + 3) / 4 * 2 + 12 }
                    ?: return false
            }
            bytes += size + 8 // per-attribute alignment margin, including INCLUDE columns
            if (bytes > 2000) return false
        }
        return true
    }

    /** Supported catalog form: column IN ('a', 'b') or column = ANY (ARRAY['a'::text, 'b'::text]). */
    fun enumCheck(constraint: ConstraintSchema): Pair<String, Set<String>>? {
        if (constraint.type != "c") return null
        var expression = constraint.checkExpression ?: constraint.definition.substringBefore("; validated=").removePrefix("CHECK ").trim()
        expression = unwrap(expression.trim())
        val identifier = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z_0-9]*)"
        val prefix = "\\(?($identifier)\\)?(?:::(?:text|character varying))?"
        val inMatch = Regex("^$prefix\\s+IN\\s*\\((.*)\\)$", RegexOption.IGNORE_CASE).matchEntire(expression)
        val anyMatch = Regex("^$prefix\\s*=\\s*ANY\\s*\\(\\(?ARRAY\\[(.*?)]\\)?(?:::(?:text|character varying)\\[\\])?\\)$", RegexOption.IGNORE_CASE).matchEntire(expression)
        val equalMatch = Regex("^$prefix\\s*=\\s*('(?:[^']|'')*'(?:::(?:text|character varying))?)$", RegexOption.IGNORE_CASE).matchEntire(expression)
        val match = inMatch ?: anyMatch ?: equalMatch ?: return null
        val values = parseStrings(match.groupValues[2]) ?: return null
        val name = match.groupValues[1].removeSurrounding("\"").replace("\"\"", "\"")
        return postgresIdentifier(name) to values.toSet()
    }

    private fun parseStrings(input: String): List<String>? {
        val literal = Regex("\\s*'((?:[^']|'')*)'(?:::(?:text|character varying))?\\s*(,|$)", RegexOption.IGNORE_CASE)
        var offset = 0
        val values = mutableListOf<String>()
        while (offset < input.length) {
            val match = literal.find(input, offset)?.takeIf { it.range.first == offset } ?: return null
            values += match.groupValues[1].replace("''", "'")
            offset = match.range.last + 1
            if (match.groupValues[2].isEmpty()) return values
            if (offset == input.length) return null
        }
        return null
    }

    private fun unwrap(value: String): String {
        var current = value
        while (current.startsWith('(') && current.endsWith(')')) {
            var depth = 0
            var quoted = false
            var balanced = true
            current.forEachIndexed { index, char ->
                if (char == '\'') quoted = !quoted
                if (!quoted) {
                    if (char == '(') depth++
                    if (char == ')') depth--
                    if (depth == 0 && index < current.lastIndex) balanced = false
                }
            }
            if (!balanced || quoted) break
            current = current.substring(1, current.lastIndex).trim()
        }
        return current
    }
}
