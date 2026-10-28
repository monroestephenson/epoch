package dev.epoch.postgres

import dev.epoch.core.TableSchema
import java.util.Locale

/** Accepts only PostgreSQL's deparsed, complete direct-column SELECT from one relation. */
internal object DirectViewProjection {
    fun complete(definition: String, backingName: String, view: TableSchema, backing: TableSchema): Boolean {
        val source = backingName.split('.')
        if (source.size != 2 || source.any(String::isEmpty) || backing.kind != "table") return false
        val projected = view.columns.entries.sortedBy { it.value.ordinal }
        val original = backing.columns.entries.sortedBy { it.value.ordinal }
        if (projected.isEmpty() || projected.map { it.key } != original.map { it.key } ||
            projected.zip(original).any { (a, b) -> a.value.type != b.value.type || a.value.collation != b.value.collation }) return false
        val tokens = tokenize(definition) ?: return false
        var position = 0
        fun keyword(value: String): Boolean {
            val token = tokens.getOrNull(position) ?: return false
            if (token.quoted || token.value != value) return false
            position++
            return true
        }
        fun symbol(value: String): Boolean {
            val token = tokens.getOrNull(position) ?: return false
            if (token.identifier || token.value != value) return false
            position++
            return true
        }
        fun identifier(): String? {
            val token = tokens.getOrNull(position) ?: return null
            if (!token.identifier) return null
            position++
            return token.value
        }
        fun reference(): List<String>? {
            val parts = mutableListOf(identifier() ?: return null)
            while (symbol(".")) parts += identifier() ?: return null
            return parts
        }
        if (!keyword("select")) return false
        val references = mutableListOf(reference() ?: return false)
        while (symbol(",")) references += reference() ?: return false
        if (!keyword("from")) return false
        val from = reference() ?: return false
        if (from != source && from != listOf(source.last())) return false
        val alias = when {
            keyword("as") -> identifier() ?: return false
            tokens.getOrNull(position)?.identifier == true -> identifier()
            else -> null
        }
        symbol(";")
        if (position != tokens.size || references.size != original.size) return false
        return references.zip(original).all { (reference, column) ->
            reference.last() == column.key && when (reference.size) {
                1 -> true
                2 -> reference.first() == (alias ?: source.last())
                3 -> alias == null && reference.dropLast(1) == source
                else -> false
            }
        }
    }

    private data class Token(val value: String, val identifier: Boolean, val quoted: Boolean = false)

    /** Strings, comments, operators, parentheses and every other SQL construct are rejected. */
    private fun tokenize(sql: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var position = 0
        while (position < sql.length) {
            val char = sql[position]
            when {
                char.isWhitespace() -> position++
                char in ".,;" -> { tokens += Token(char.toString(), false); position++ }
                char == '"' -> {
                    position++
                    val value = StringBuilder()
                    var closed = false
                    while (position < sql.length) {
                        val next = sql[position++]
                        if (next != '"') value.append(next)
                        else if (sql.getOrNull(position) == '"') { value.append('"'); position++ }
                        else { closed = true; break }
                    }
                    if (!closed || value.isEmpty()) return null
                    tokens += Token(value.toString(), true, true)
                }
                char == '_' || char.isLetter() -> {
                    val start = position++
                    while (position < sql.length && (sql[position] == '_' || sql[position] == '$' || sql[position].isLetterOrDigit())) position++
                    tokens += Token(sql.substring(start, position).lowercase(Locale.ROOT), true)
                }
                else -> return null
            }
        }
        return tokens
    }
}
