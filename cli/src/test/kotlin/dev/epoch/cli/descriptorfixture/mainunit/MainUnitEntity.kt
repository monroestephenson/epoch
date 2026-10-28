package dev.epoch.cli.descriptorfixture.mainunit

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "descriptor_main")
open class MainUnitEntity {
    @Id
    open var id: Long = 0
    open var label: String? = null
}
