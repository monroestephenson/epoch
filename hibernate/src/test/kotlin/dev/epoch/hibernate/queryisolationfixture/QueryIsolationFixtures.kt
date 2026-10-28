package dev.epoch.hibernate.queryisolationfixture

import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity(name = "QueryIsolationRecord")
@Table(name = "query_isolation_records")
open class QueryIsolationRecord {
    @field:Id open var id: Long = 0
    open var label: String = ""
}

class QueryIsolationRepository(private val entityManager: EntityManager) {
    fun malformed(): List<*> = entityManager.createQuery(
        "select r.labelThatDoesNotExist from QueryIsolationRecord r",
    ).resultList

    fun healthy(): List<*> = entityManager.createQuery(
        "select r.label from QueryIsolationRecord r where r.id = :id",
    ).setParameter("id", 1L).resultList
}
