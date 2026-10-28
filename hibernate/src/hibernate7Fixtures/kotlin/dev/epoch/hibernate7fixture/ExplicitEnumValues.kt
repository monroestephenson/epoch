package dev.epoch.hibernate7fixture

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumeratedValue
import jakarta.persistence.EnumType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

enum class StringCode(@field:EnumeratedValue val code: String) {
    ZEBRA("z-code"), ALPHA("a-code"), MIDDLE("m-code")
}

enum class IntegerCode(@field:EnumeratedValue val code: Int) {
    ZEBRA(17), ALPHA(3), MIDDLE(29)
}

enum class NativeOrderedStatus { ZEBRA, ALPHA, MIDDLE }

/** JPA 3.2 codes deliberately differ from both enum names and declaration ordinals. */
@Entity @Table(name = "explicit_enum_values")
open class ExplicitEnumValues {
    @field:Id open var id: Long = 0
    @field:Enumerated(EnumType.STRING) @field:Column(name = "string_code")
    open var stringCode: StringCode = StringCode.ZEBRA
    @field:Enumerated(EnumType.ORDINAL) @field:Column(name = "integer_code")
    open var integerCode: IntegerCode = IntegerCode.ZEBRA
    @field:Enumerated(EnumType.ORDINAL) @field:JdbcTypeCode(SqlTypes.NAMED_ORDINAL_ENUM) @field:Column(name = "native_ordered")
    open var nativeOrdered: NativeOrderedStatus = NativeOrderedStatus.ZEBRA
}
