package dev.epoch.paginationfixture;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;

public class PaginationDao {
    public void fixed(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c where c.email = :email")
            .setParameter("email", "fixed@example.test").setFirstResult(3).setMaxResults(5).getResultList();
    }
    public void sequential(EntityManager manager) {
        Query query = manager.createQuery("select c from RepositoryCustomer c");
        query.getResultList();
        query.setMaxResults(2);
        query.getResultList();
    }
    public void offsetOnly(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c").setFirstResult(0).getResultList();
    }
    public void zeroResults(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c").setMaxResults(0).getResultList();
    }
    public void dynamic(EntityManager manager, int size) {
        manager.createQuery("select c from RepositoryCustomer c").setMaxResults(size).getResultList();
    }
    public void conditional(EntityManager manager, boolean condition) {
        Query query = manager.createQuery("select c from RepositoryCustomer c");
        if (condition) query.setMaxResults(5);
        query.getResultList();
    }
    public void nativeQuery(EntityManager manager) {
        manager.createNativeQuery("select id from repository_customer").setMaxResults(5).getResultList();
    }
    public void locked(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c").setMaxResults(5).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
    }
    public Query escaped(EntityManager manager) {
        return manager.createQuery("select c from RepositoryCustomer c").setMaxResults(5);
    }
    public void collectionFetch(EntityManager manager) {
        manager.createQuery("select r from CollectionRecord r join fetch r.tags").setMaxResults(5).getResultList();
    }
}
