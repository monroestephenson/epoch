package dev.epoch.scanfixture

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity @Table(name = "scanned_customers")
open class ScannedCustomer {
    @field:Id open var id: Long = 0
    open var label: String = ""
}
