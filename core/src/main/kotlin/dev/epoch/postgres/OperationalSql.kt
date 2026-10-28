package dev.epoch.postgres

import dev.epoch.core.OperationalActionKind
import dev.epoch.core.postgresIdentifier
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.create.index.CreateIndex
import net.sf.jsqlparser.statement.create.table.CreateTable

/** Intent only: a supported token shape is never sufficient without catalog corroboration. */
internal data class OperationalIntent(
    val kind: OperationalActionKind,
    val table: String? = null,
    val detail: String? = null,
    val replacement: String? = null,
    val modifier: String? = null,
    val concurrent: Boolean = false,
    val notValid: Boolean = false,
    val ifNotExists: Boolean = false,
    val complete: Boolean = false,
)
internal data class OperationalSql(
    val intents: List<OperationalIntent>,
    val commandTag: String?,
    val timeoutSetting: String? = null,
) {
    val tables get() = intents.mapNotNull { it.table }.distinct()

    companion object {
        fun inspect(sql: String): OperationalSql {
            return try {
            val tokens = tokens(sql)
            if (tokens.isEmpty()) return unknown()
            var offset = 0
            fun take(word: String): Boolean = tokens.getOrNull(offset)?.word(word) == true && run { offset++; true }
            if (take("SET")) {
                val scope = if (take("LOCAL")) "LOCAL" else { take("SESSION"); "SESSION" }
                if (tokens.getOrNull(offset)?.word("LOCK_TIMEOUT") == true) {
                    offset++
                    val separator = take("TO") || take("=")
                    val rest = tokens.drop(offset)
                    if (separator && rest.size == 1 && (rest[0].literal || rest[0].text.all(Char::isDigit)))
                        return OperationalSql(listOf(OperationalIntent(OperationalActionKind.SETTINGS_CHANGE, complete = true)), "SET", scope)
                }
                return unknown("SET")
            }
            if (tokens.first().word("RESET")) return unknown("RESET")
            if (take("CREATE")) {
                take("UNLOGGED")
                if (take("TABLE")) {
                    val parsed = parse(sqlText(tokens)) as? CreateTable ?: return unknown("CREATE TABLE")
                    val complete = parsed.select == null && parsed.likeTable == null &&
                        tokens.none { it.word("INHERITS") || it.word("PARTITION") || it.word("OF") || it.word("TEMPORARY") || it.word("TEMP") }
                    return OperationalSql(listOf(OperationalIntent(OperationalActionKind.CREATE_TABLE,
                        parsed.table.fullyQualifiedName, ifNotExists = parsed.isIfNotExists, complete = complete)), "CREATE TABLE")
                }
                take("UNIQUE")
                if (take("INDEX")) {
                    val concurrentToken = tokens.getOrNull(offset)?.takeIf { it.word("CONCURRENTLY") }
                    if (concurrentToken != null) offset++
                    val normalized = sqlText(tokens.filterNot { it === concurrentToken })
                    val parsed = parse(normalized) as? CreateIndex ?: return unknown("CREATE INDEX")
                    return OperationalSql(listOf(OperationalIntent(OperationalActionKind.CREATE_INDEX,
                        parsed.table.fullyQualifiedName, detail = parsed.index.name,
                        concurrent = concurrentToken != null, ifNotExists = parsed.isUsingIfNotExists, complete = true)), "CREATE INDEX")
                }
                return unknown(tokens.getOrNull(1)?.text?.uppercase()?.let { "CREATE $it" })
            }
            offset = 0
            if (take("ALTER") && take("TABLE")) {
                if (take("IF")) { if (!take("EXISTS")) return unknown("ALTER TABLE") }
                take("ONLY")
                val name = qualified(tokens, offset) ?: return unknown("ALTER TABLE")
                offset = name.second
                val parts = splitClauses(tokens.drop(offset)) ?: return unknown("ALTER TABLE")
                return OperationalSql(parts.map { alter(name.first, it) }, "ALTER TABLE")
            }
            return unknown(tokens.firstOrNull()?.text?.uppercase())
            } catch (_: Exception) { unknown() }
        }

        private fun parse(sql: String) = try { CCJSqlParserUtil.parse(sql) } catch (_: Exception) { null }
        /** Preserve adjacent operators while removing PostgreSQL's nested comments before AST parsing. */
        private fun sqlText(tokens: List<Token>) = buildString {
            var previousEnd = -1
            for (token in tokens) {
                if (isNotEmpty() && previousEnd != token.start) append(' ')
                append(token.text)
                previousEnd = token.end
            }
        }
        private fun unknown(tag: String? = null) = OperationalSql(listOf(OperationalIntent(OperationalActionKind.UNKNOWN)), tag)
        private fun alter(table: String, t: List<Token>): OperationalIntent {
            fun intent(kind: OperationalActionKind, detail: String? = null, replacement: String? = null,
                       modifier: String? = null, complete: Boolean = true, notValid: Boolean = false) =
                OperationalIntent(kind, table, detail, replacement, modifier, notValid = notValid, complete = complete)
            fun at(i: Int, word: String) = t.getOrNull(i)?.word(word) == true
            fun id(i: Int) = t.getOrNull(i)?.identifier()
            if (at(0, "VALIDATE") && at(1, "CONSTRAINT") && t.size == 3 && id(2) != null)
                return intent(OperationalActionKind.VALIDATE_CONSTRAINT, id(2))
            if (at(0, "ALTER")) {
                val i = if (at(1, "COLUMN")) 2 else 1
                val column = id(i) ?: return intent(OperationalActionKind.UNKNOWN, complete = false)
                val tail = t.drop(i + 1)
                fun words(vararg expected: String) = tail.size == expected.size && tail.zip(expected.toList()).all { (a, b) -> a.word(b) }
                if (words("SET", "NOT", "NULL")) return intent(OperationalActionKind.SET_NOT_NULL, column)
                if (words("DROP", "NOT", "NULL")) return intent(OperationalActionKind.METADATA_CHANGE, column, modifier = "DROP_NOT_NULL")
                if (words("DROP", "DEFAULT")) return intent(OperationalActionKind.METADATA_CHANGE, column, modifier = "DROP_DEFAULT")
                if (tail.firstOrNull()?.word("TYPE") == true && tail.size > 1)
                    return intent(OperationalActionKind.ALTER_COLUMN_TYPE, column,
                        complete = tail.none { it.word("USING") || it.word("COLLATE") })
                if (tail.getOrNull(0)?.word("SET") == true && tail.getOrNull(1)?.word("DEFAULT") == true && tail.size > 2)
                    return intent(OperationalActionKind.METADATA_CHANGE, column, modifier = "SET_DEFAULT")
            }
            if (at(0, "RENAME")) {
                if (at(1, "TO") && t.size == 3 && id(2) != null)
                    return intent(OperationalActionKind.METADATA_CHANGE, replacement = id(2), modifier = "RENAME_TABLE")
                val i = if (at(1, "COLUMN") || at(1, "CONSTRAINT")) 2 else 1
                if (at(i + 1, "TO") && t.size == i + 3 && id(i) != null && id(i + 2) != null)
                    return intent(OperationalActionKind.METADATA_CHANGE, id(i), id(i + 2),
                        if (at(1, "CONSTRAINT")) "RENAME_CONSTRAINT" else "RENAME_COLUMN")
            }
            if (at(0, "ADD")) {
                if (at(1, "CONSTRAINT") && id(2) != null) {
                    val notValid = t.takeLast(2).let { it.size == 2 && it[0].word("NOT") && it[1].word("VALID") }
                    if (at(3, "CHECK")) return intent(OperationalActionKind.ADD_CHECK, id(2), notValid = notValid)
                    if (at(3, "FOREIGN") && at(4, "KEY")) return intent(OperationalActionKind.ADD_FOREIGN_KEY, id(2), notValid = notValid)
                }
                val i = if (at(1, "COLUMN")) 2 else 1
                if (id(i) != null && t.size > i + 1 && !at(i, "CONSTRAINT") && !at(i, "PRIMARY") && !at(i, "UNIQUE"))
                    return intent(OperationalActionKind.METADATA_CHANGE, id(i), modifier = "ADD_COLUMN")
            }
            return intent(OperationalActionKind.UNKNOWN, complete = false)
        }

        private data class Token(val text: String, val start: Int, val end: Int, val quoted: Boolean = false, val literal: Boolean = false) {
            fun word(value: String) = !quoted && !literal && text.equals(value, true)
            fun identifier(): String? = when {
                literal -> null
                quoted -> postgresIdentifier(text.substring(1, text.length - 1).replace("\"\"", "\""))
                text.firstOrNull()?.let { it.isLetter() || it == '_' } == true && text.all { it.isLetterOrDigit() || it in "_$" } -> postgresIdentifier(text.lowercase())
                else -> null
            }
        }
        private fun qualified(t: List<Token>, offset: Int): Pair<String, Int>? {
            if (t.getOrNull(offset)?.identifier() == null) return null
            var end = offset + 1
            if (t.getOrNull(end)?.word(".") == true) {
                if (t.getOrNull(end + 1)?.identifier() == null) return null
                end += 2
            }
            return t.subList(offset, end).joinToString("") { it.text } to end
        }
        private fun splitClauses(t: List<Token>): List<List<Token>>? {
            var depth = 0
            var start = 0
            val result = mutableListOf<List<Token>>()
            for ((i, token) in t.withIndex()) {
                if (token.word("(")) depth++
                if (token.word(")")) depth--
                if (depth < 0) return null
                if (token.word(",") && depth == 0) { result += t.subList(start, i); start = i + 1 }
            }
            if (depth != 0 || start == t.size) return null
            result += t.subList(start, t.size)
            return result
        }
        /** PostgreSQL comments/quotes delimit tokens; keywords inside literals never become modifiers. */
        private fun tokens(sql: String): List<Token> {
            val result = mutableListOf<Token>()
            var i = 0
            while (i < sql.length) {
                if (sql[i].isWhitespace()) { i++; continue }
                if (sql.startsWith("--", i)) { i = sql.indexOf('\n', i + 2).let { if (it < 0) sql.length else it + 1 }; continue }
                if (sql.startsWith("/*", i)) {
                    var depth = 1; i += 2
                    while (i < sql.length && depth > 0) when {
                        sql.startsWith("/*", i) -> { depth++; i += 2 }
                        sql.startsWith("*/", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                    require(depth == 0); continue
                }
                val start = i
                if (sql[i] == '\'' || sql[i] == '"') {
                    val quote = sql[i++]
                    var closed = false
                    while (i < sql.length) {
                        if (sql[i++] == quote) {
                            if (i < sql.length && sql[i] == quote) i++ else { closed = true; break }
                        }
                    }
                    require(closed)
                    result += Token(sql.substring(start, i), start, i, quoted = quote == '"', literal = quote == '\'')
                } else if (sql[i].isLetterOrDigit() || sql[i] == '_') {
                    while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] in "_$")) i++
                    result += Token(sql.substring(start, i), start, i)
                } else {
                    i++
                    result += Token(sql.substring(start, i), start, i)
                }
            }
            if (result.lastOrNull()?.word(";") == true) result.removeAt(result.lastIndex)
            require(result.none { it.word(";") }) // Dollar-quoted/procedural bodies are intentionally unsupported.
            return result
        }
    }
}
