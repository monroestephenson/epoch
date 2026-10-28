package dev.epoch.hibernate.diagnosticvariantfixture

import jakarta.persistence.EntityManager

/** These real compiled branches share one translation failure and retain healthy variants. */
class VariantDiagnosticsRepository(private val entityManager: EntityManager) {
    fun first(fail: Boolean) = entityManager.createQuery(if (fail)
        "select r.missingLabel from QueryIsolationRecord r" else
        "select r.label from QueryIsolationRecord r")

    fun second(fail: Boolean) = entityManager.createQuery(if (fail)
        "select r.missingLabel from QueryIsolationRecord r" else
        "select r.id from QueryIsolationRecord r")
}
