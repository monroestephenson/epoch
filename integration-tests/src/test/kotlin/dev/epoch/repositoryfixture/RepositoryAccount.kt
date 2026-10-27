package dev.epoch.repositoryfixture

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity(name = "RepositoryAccount")
@Table(name = "accounts")
open class RepositoryAccount {
    @field:Id open var id: Long = 0
    @field:Column(name = "label") open var label: String? = null
}

/** Never instantiated: the extractor reads these fixed SQL/HQL sources from compiled bytecode. */
class AccountRepository(private val entityManager: EntityManager) {
    fun legacyLookup(id: Long): List<*> = entityManager
        .createNativeQuery("SELECT legacy_column FROM accounts WHERE id = ?1")
        .setParameter(1, id)
        .resultList

    fun bulkDelete(id: Long): Int = entityManager
        .createQuery("delete from RepositoryAccount a where a.id = :id")
        .setParameter("id", id)
        .executeUpdate()
}
