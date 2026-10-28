package dev.epoch.jarfixture.entity

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** Epoch-owned fixture compiled against Hibernate 7/JPA 3.2 and packaged independently. */
@Entity
@Table(name = "jar_customers")
open class JarCustomer {
    @field:Id
    open var id: Long = 0
    open var email: String = ""
}
