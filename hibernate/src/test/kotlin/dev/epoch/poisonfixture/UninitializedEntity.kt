package dev.epoch.poisonfixture

import jakarta.persistence.Entity
import jakarta.persistence.Id

@Entity
open class UninitializedEntity {
    @field:Id open var id: Long = 0
    companion object {
        init { error("Package scanning must never initialize this entity") }
    }
}
