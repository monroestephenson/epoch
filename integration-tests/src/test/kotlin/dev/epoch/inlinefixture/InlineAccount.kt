package dev.epoch.inlinefixture

import io.quarkus.hibernate.orm.panache.PanacheRepository
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.NamedQuery
import jakarta.persistence.Table

@Entity
@Table(name = "inline_accounts")
@NamedQuery(name = "InlineAccount.byStatus", query = "select a from InlineAccount a where a.status = :status")
open class InlineAccount {
    @field:Id open var id: Long = 0
    open var status: String? = null
}

/** Compiled normally by Kotlin; none of these methods is invoked during extraction. */
class InlineAccountDao(private val entityManager: EntityManager) {
    fun inlineHql(id: Long): List<*> = entityManager
        .createQuery("select a from InlineAccount a where a.id = :id")
        .setParameter("id", id).resultList

    fun inlineNative(id: Long): List<*> = entityManager
        .createNativeQuery("select legacy_note from inline_accounts where id = ?1")
        .setParameter(1, id).resultList

    fun annotationDeclared(status: String): List<*> = entityManager
        .createNamedQuery("InlineAccount.byStatus")
        .setParameter("status", status).resultList

    fun multiline(status: String): List<*> = entityManager.createQuery(
        """
            select a from InlineAccount a
            where a.status = :status
        """.trimIndent()
    ).setParameter("status", status).resultList

    fun marginLiteral(status: String): List<*> = entityManager.createQuery(
        """
            #select a from InlineAccount a
            #where a.status = :status
        """.trimMargin("#")
    ).setParameter("status", status).resultList

    fun defaultMarginLiteral(status: String): List<*> = entityManager.createQuery(
        """
            |select a from InlineAccount a
            |where a.status = :status
        """.trimMargin()
    ).setParameter("status", status).resultList

    fun dynamic(sortProperty: String): List<*> = entityManager
        .createQuery("select a from InlineAccount a order by $sortProperty").resultList
}

/** Shorthand expansion uses the published Panache API and its actual PanacheJpaUtil. */
class InlineAccountPanache : PanacheRepository<InlineAccount> {
    fun shorthand(status: String): List<InlineAccount> = list("status = ?1 order by id", status)
}
