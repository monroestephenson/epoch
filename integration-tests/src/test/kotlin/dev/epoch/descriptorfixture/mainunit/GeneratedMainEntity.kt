package dev.epoch.descriptorfixture.mainunit

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "generated_main")
open class GeneratedMainEntity {
    @Id
    open var id: Long = 0
    open var label: String? = null
}
