package dev.epoch.writedomainfixture

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.SecondaryTable
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity @Table(name = "required_write")
open class DomainRequiredWrite {
    @field:Id open var id: Long = 1
    open var label: String = "old-value"
}

@Entity @Table(name = "required_check")
open class DomainRequiredCheck {
    @field:Id open var id: Long = 1
    open var label: String = "old-value"
}

@Entity @Table(name = "redundant_null")
open class DomainRedundantNull {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @Table(name = "nullable_write")
open class DomainNullableWrite {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @Table(name = "nullable_check")
open class DomainNullableCheck {
    @field:Id open var id: Long = 1
    open var label: String? = null
}

@Entity @Table(name = "transformed_write")
open class DomainTransformedWrite {
    @field:Id open var id: Long = 1
    @field:ColumnTransformer(write = "coalesce(?, '')")
    open var label: String? = null
}

enum class DomainState { OPEN, CLOSED }

@Entity @Table(name = "enum_write")
open class DomainEnumWrite {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING)
    open var label: DomainState = DomainState.CLOSED
}

@Entity @Table(name = "enum_native")
open class DomainNativeEnumWrite {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(columnDefinition = "domain_state")
    open var label: DomainState = DomainState.CLOSED
}

@Entity @Table(name = "unbounded_text")
open class DomainUnboundedWrite {
    @field:Id open var id: Long = 1
    @field:Column(columnDefinition = "text")
    open var label: String = "123456"
}

enum class DomainCaseState { ACTIVE, active }

@Entity @Table(name = "enum_case")
open class DomainCaseEnumWrite {
    @field:Id open var id: Long = 1
    @field:Enumerated(EnumType.STRING)
    open var label: DomainCaseState = DomainCaseState.active
}

@Entity @Table(name = "optional_primary")
@SecondaryTable(name = "optional_detail", pkJoinColumns = [PrimaryKeyJoinColumn(name = "account_id")])
open class DomainOptionalWrite {
    @field:Id open var id: Long = 1
    open var label: String = "old-value"
    @field:Column(table = "optional_detail")
    open var detail: String? = null
}
