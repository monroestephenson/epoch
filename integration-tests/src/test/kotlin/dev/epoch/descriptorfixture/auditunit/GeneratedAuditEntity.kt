package dev.epoch.descriptorfixture.auditunit

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "generated_audit")
open class GeneratedAuditEntity {
    @Id
    open var id: Long = 0
    open var action: String? = null
}
