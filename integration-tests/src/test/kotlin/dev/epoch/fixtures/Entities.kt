package dev.epoch.fixtures

import jakarta.persistence.*

@Entity(name = "PlainCustomer")
@Table(name = "customers")
open class PlainCustomer {
    @field:Id
    open var id: Long = 0
    @field:Column(name = "email", nullable = false)
    open var email: String = ""
}

@Entity(name = "Customer")
@Table(name = "customers")
@NamedQuery(name = "Customer.byEmail", query = "select c from Customer c where c.email = :email")
@NamedNativeQuery(name = "Customer.nativeByEmail", query = "select id, email from customers where email = :email")
open class Customer {
    @field:Id
    open var id: Long = 0
    @field:Column(name = "email", nullable = false)
    open var email: String = ""
}

@Entity(name = "Purchase")
@Table(name = "purchases")
@NamedQuery(name = "Purchase.customerEmail", query = "select p from Purchase p join p.customer c where c.email = :email")
open class Purchase {
    @field:Id
    open var id: Long = 0
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "customer_id", nullable = false)
    open var customer: Customer? = null
}
