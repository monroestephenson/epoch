package dev.epoch.core

/** PostgreSQL truncates identifiers to NAMEDATALEN - 1 UTF-8 bytes, on a character boundary. */
fun postgresIdentifier(value: String): String {
    if (value.toByteArray(Charsets.UTF_8).size <= 63) return value
    val out = StringBuilder()
    var bytes = 0
    val points = value.codePoints().iterator()
    while (points.hasNext()) {
        val point = points.nextInt()
        val text = String(Character.toChars(point))
        val size = text.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 63) break
        out.append(text)
        bytes += size
    }
    return out.toString()
}
