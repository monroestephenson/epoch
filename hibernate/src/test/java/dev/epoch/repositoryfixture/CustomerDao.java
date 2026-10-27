package dev.epoch.repositoryfixture;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;

/** Methods are inspected, never invoked or initialized by discovery. */
public class CustomerDao {
    static { if (Boolean.parseBoolean("true")) throw new IllegalStateException("DAO must not initialize during scanning"); }

    public void fixed(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c where c.email = :email");
    }

    public void nativeColumn(EntityManager manager) {
        manager.createNativeQuery("select legacy_email from repository_customer where id = ?1");
    }

    public void dynamic(EntityManager manager, String query) {
        System.out.println("select c from RepositoryCustomer c");
        manager.createQuery(query);
    }

    public void differentBranches(EntityManager manager, boolean condition) {
        String query;
        if (condition) query = "select c from RepositoryCustomer c";
        else query = "select c from RepositoryCustomer c where c.id = :id";
        manager.createQuery(query);
    }

    public void sameBranches(EntityManager manager, boolean condition) {
        String query;
        if (condition) query = "select c from RepositoryCustomer c";
        else query = "select c from RepositoryCustomer c";
        manager.createQuery(query);
    }

    public void concatenation(EntityManager manager) {
        String prefix = "select c";
        manager.createQuery(prefix + " from RepositoryCustomer c");
    }

    public void valueOf(EntityManager manager) {
        manager.createNativeQuery("select " + String.valueOf(true) + ", '" + String.valueOf('A') + "'");
    }

    public void jdbc(Connection connection) throws SQLException {
        PreparedStatement statement = connection.prepareStatement("delete from repository_customer where id = ?");
        statement.setLong(1, 4L);
        statement.executeUpdate();
    }

    public void externalPrepared(PreparedStatement statement) throws SQLException {
        statement.executeQuery();
    }

    public void callable(Connection connection) throws SQLException {
        connection.prepareCall("{call change_customer(?)}");
    }

    public void pagination(EntityManager manager) {
        manager.createQuery("select c from RepositoryCustomer c").setFirstResult(3).setMaxResults(5);
    }

    public void criteria(EntityManager manager) {
        manager.createQuery(manager.getCriteriaBuilder().createQuery(RepositoryCustomer.class));
    }

    public void named(EntityManager manager) {
        manager.createNamedQuery("RepositoryCustomer.named");
    }
}
