package dev.epoch.readwideningfixture

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity @Table(name = "read_wide_int")
open class ReadWideInt {
    @field:Id open var id: Long = 1
    open var amount: Int = 0
}

@Entity @Table(name = "read_wide_long")
open class ReadWideLong {
    @field:Id open var id: Long = 1
    open var amount: Long = 0
}

@Entity @Table(name = "read_wide_string")
open class ReadWideString {
    @field:Id open var id: Long = 1
    open var label: String = "old"
}
