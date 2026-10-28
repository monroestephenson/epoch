package dev.epoch.core

import java.util.Collections

/** Validate caller-supplied estimates once; statement grading performs one canonical-name lookup. */
object OperationalSizes {
    data class SizeGrade(val severity: OperationalSeverity, val evidence: String)

    fun validateAndNormalize(input: TableSizes?): TableSizes? {
        if (input == null) return null
        require(input.schemaVersion == 1) { "Unsupported table-size schemaVersion=${input.schemaVersion}; supported=1." }
        val normalized = sortedMapOf<String, TableSize>()
        val originals = mutableMapOf<String, String>()
        for ((name, size) in input.tables.toSortedMap()) {
            val key = qualified(name)
                ?: throw IllegalArgumentException("Table-size key '$name' must contain exactly one nonempty schema and table name: schema.table.")
            require(size.estimatedRows == null || size.estimatedRows >= 0) {
                "Table-size estimatedRows for '$name' must be nonnegative or null."
            }
            require(size.totalBytes == null || size.totalBytes >= 0) {
                "Table-size totalBytes for '$name' must be nonnegative or null."
            }
            val previous = originals.putIfAbsent(key, name)
            require(previous == null) {
                "Table-size keys '$previous' and '$name' both normalize to '$key'."
            }
            normalized[key] = size
        }
        return input.copy(tables = Collections.unmodifiableMap(normalized))
    }

    /** Severity grades an existing risk; it never changes an operational RISKY verdict to SAFE. */
    fun grade(relation: OperationalRelation, sizes: TableSizes?): SizeGrade {
        if (relation.preExisting != true) return SizeGrade(OperationalSeverity.HIGH,
            "Size lookup unavailable: preExisting=${relation.preExisting}; production baseline identity is not established for ${relation.name}. " +
                "Both supplied row and byte estimates are required to lower severity.")
        val key = relation.baselineName?.let(::qualified)
            ?: return SizeGrade(OperationalSeverity.HIGH,
                "Size lookup unavailable: no valid original baseline name is established for ${relation.name}. " +
                    "Both supplied row and byte estimates are required to lower severity.")
        val size = sizes?.tables?.get(key)
        val rows = size?.estimatedRows
        val bytes = size?.totalBytes
        val severity = when {
            rows == null || bytes == null || rows < 0 || bytes < 0 -> OperationalSeverity.HIGH
            rows <= LOW_ROWS && bytes <= LOW_BYTES -> OperationalSeverity.LOW
            rows <= MEDIUM_ROWS && bytes <= MEDIUM_BYTES -> OperationalSeverity.MEDIUM
            else -> OperationalSeverity.HIGH
        }
        val reason = when {
            rows != null && rows < 0 || bytes != null && bytes < 0 -> "Invalid negative estimates cannot lower severity; validate the input before verification."
            rows == null || bytes == null -> "Both supplied estimates are required to lower severity."
            severity == OperationalSeverity.LOW -> "Rows <= $LOW_ROWS and bytes <= $LOW_BYTES."
            severity == OperationalSeverity.MEDIUM -> "Rows <= $MEDIUM_ROWS and bytes <= $MEDIUM_BYTES."
            else -> "At least one estimate exceeds the medium-size limit (rows=$MEDIUM_ROWS, bytes=$MEDIUM_BYTES)."
        }
        return SizeGrade(severity, "Size lookup baselineName=$key; estimatedRows=${rows ?: "unknown"}; totalBytes=${bytes ?: "unknown"}; " +
            "severity=$severity. $reason Operational risk is unchanged.")
    }

    private fun qualified(name: String): String? {
        val parts = name.split('.')
        if (parts.size != 2 || parts.any(String::isEmpty)) return null
        return parts.joinToString(".", transform = ::postgresIdentifier)
    }

    private const val LOW_ROWS = 10_000L
    private const val LOW_BYTES = 16L * 1024 * 1024
    private const val MEDIUM_ROWS = 1_000_000L
    private const val MEDIUM_BYTES = 1024L * 1024 * 1024
}
