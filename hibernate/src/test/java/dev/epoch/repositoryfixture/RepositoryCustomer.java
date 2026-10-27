package dev.epoch.repositoryfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.Table;

@Entity
@Table(name = "repository_customer")
@NamedQueries({
    @NamedQuery(name = "RepositoryCustomer.named", query = "select c from RepositoryCustomer c where c.id = :id"),
    @NamedQuery(name = "RepositoryCustomer.findByEmailIsNotNull", query = "select c from RepositoryCustomer c where c.id > 100")
})
@NamedNativeQuery(name = "RepositoryCustomer.findByEmailIsNull", query = "select legacy_named_email from repository_customer")
@org.hibernate.annotations.NamedQuery(name = "RepositoryCustomer.findByEmailNotNull", query = "select c from RepositoryCustomer c where c.id > 200")
public class RepositoryCustomer {
    @Id public Long id;
    public String email;
}
