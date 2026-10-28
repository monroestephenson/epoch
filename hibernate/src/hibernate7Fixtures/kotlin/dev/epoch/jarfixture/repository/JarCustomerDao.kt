package dev.epoch.jarfixture.repository

import dev.epoch.jarfixture.entity.JarCustomer
import jakarta.persistence.EntityManager

/** Lives in a second JAR, as a repository module normally would. */
class JarCustomerDao(private val entityManager: EntityManager) {
    fun byEmail(email: String): List<JarCustomer> = entityManager.createQuery(
        "select c from JarCustomer c where c.email = :email", JarCustomer::class.java,
    ).setParameter("email", email).resultList

    fun legacyNote(id: Long): Any? = entityManager.createNativeQuery(
        "select legacy_note from public.jar_customers where id = :id",
    ).setParameter("id", id).singleResult

    fun dynamic(column: String): List<*> = entityManager.createNativeQuery(
        "select $column from public.jar_customers",
    ).resultList
}
