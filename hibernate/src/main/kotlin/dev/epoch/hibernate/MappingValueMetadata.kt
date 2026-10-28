package dev.epoch.hibernate

import dev.epoch.core.EnumEncoding
import dev.epoch.core.EnumMode
import org.hibernate.metamodel.mapping.JdbcMapping
import org.hibernate.type.descriptor.converter.spi.BasicValueConverter
import org.hibernate.type.descriptor.converter.spi.JpaAttributeConverter
import org.hibernate.type.descriptor.java.EnumJavaType
import java.security.MessageDigest

/** Reads actual Hibernate conversion metadata; application AttributeConverter methods are never called here. */
internal object MappingValueMetadata {
    data class Facts(
        val enumMode: EnumMode? = null,
        val enumConstants: List<String> = emptyList(),
        val enumRelationalValues: List<String> = emptyList(),
        val enumEncoding: EnumEncoding? = null,
        val converterClass: String? = null,
        val converterBytecodeSha256: String? = null,
    ) {
        val enumValues: List<String> get() = enumRelationalValues.distinct().sorted()
    }

    private val fingerprints = object : ClassValue<String?>() {
        override fun computeValue(type: Class<*>): String? = ClassBytes.read(type)?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    fun inspect(mapping: JdbcMapping): Facts {
        val converter = mapping.valueConverter
        val converterType = (converter as? JpaAttributeConverter<*, *>)?.converterJavaType?.javaTypeClass
        val javaType = converter?.domainJavaType ?: mapping.javaTypeDescriptor
        val enumType = javaType.javaTypeClass
        val constants = if (enumType.isEnum) enumType.enumConstants.map { it as Enum<*> } else emptyList()
        val facts = Facts(enumConstants = constants.map { it.name }, converterClass = converterType?.name,
            converterBytecodeSha256 = converterType?.let(fingerprints::get))
        if (constants.isEmpty() || javaType.javaClass != EnumJavaType::class.java) return facts
        val jdbcType = mapping.jdbcType.javaClass.name
        val mode = when (jdbcType) {
            in integerJdbcTypes, in nativeOrdinalJdbcTypes -> EnumMode.ORDINAL
            in stringJdbcTypes -> EnumMode.STRING
            else -> return facts
        }
        if (converter == null) {
            val encoding = if (jdbcType in integerJdbcTypes) EnumEncoding.ORDINAL else EnumEncoding.NAME
            return facts.copy(enumMode = mode, enumEncoding = encoding,
                enumRelationalValues = constants.map { if (encoding == EnumEncoding.ORDINAL) it.ordinal.toString() else it.name })
        }
        if (converter.javaClass.name == "org.hibernate.boot.model.process.internal.EnumeratedValueConverter") {
            // This Hibernate implementation reads its already-built finite map, not application converter code.
            @Suppress("UNCHECKED_CAST")
            val known = converter as BasicValueConverter<Any, Any>
            val relational: List<Any?> = constants.map { known.toRelationalValue(it) }
            if (relational.any { it == null || it !is String && it !is Byte && it !is Short && it !is Int }) return facts
            return facts.copy(enumMode = mode, enumEncoding = EnumEncoding.EXPLICIT,
                enumRelationalValues = relational.map { it.toString() })
        }
        return facts
    }

    private val integerJdbcTypes = setOf("TinyIntJdbcType", "TinyIntAsSmallIntJdbcType", "SmallIntJdbcType", "IntegerJdbcType", "BigIntJdbcType")
        .map { "org.hibernate.type.descriptor.jdbc.$it" }.toSet()
    private val stringJdbcTypes = setOf("CharJdbcType", "NCharJdbcType", "VarcharJdbcType", "NVarcharJdbcType", "LongVarcharJdbcType", "LongNVarcharJdbcType", "EnumJdbcType")
        .map { "org.hibernate.type.descriptor.jdbc.$it" }.toSet() + setOf(
            "org.hibernate.dialect.PostgreSQLEnumJdbcType", "org.hibernate.dialect.type.PostgreSQLEnumJdbcType",
        )
    private val nativeOrdinalJdbcTypes = setOf("org.hibernate.type.descriptor.jdbc.OrdinalEnumJdbcType",
        "org.hibernate.dialect.PostgreSQLOrdinalEnumJdbcType", "org.hibernate.dialect.type.PostgreSQLOrdinalEnumJdbcType")
}
