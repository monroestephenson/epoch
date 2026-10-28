package dev.epoch.cli.descriptorfixture.auditunit

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "descriptor_audit")
open class AuditUnitEntity {
    @Id
    open var id: Long = 0
    open var action: String? = null
}
