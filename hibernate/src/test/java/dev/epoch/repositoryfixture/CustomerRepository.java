package dev.epoch.repositoryfixture;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.QueryRewriter;
import org.springframework.data.repository.Repository;
import dev.epoch.externalqueryfixture.ExternalQuery;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends Repository<RepositoryCustomer, Long> {
    List<RepositoryCustomer> findByEmailAndId(String email, Long id);
    Optional<RepositoryCustomer> findById(long id);
    List<RepositoryCustomer> findByEmailContaining(String email);
    List<RepositoryCustomer> findByEmailNot(String email);
    List<RepositoryCustomer> findByEmailAndEmailAndEmailAndEmailAndEmail(String a, String b, String c, String d, String e);
    List<EmailProjection> findByEmail(String email);
    List<RepositoryCustomer> findByEmailIsNotNull();
    List<String> findByEmailIsNull();
    List<RepositoryCustomer> findByEmailNotNull();

    @ExternalQuery
    List<RepositoryCustomer> findByEmailEquals(String email);

    @Query("select c from RepositoryCustomer c where c.email = :email")
    List<RepositoryCustomer> explicit(String email);

    @Query(value = "select legacy_email from repository_customer where id = ?1", nativeQuery = true)
    List<String> legacy(long id);

    @NativeQuery(value = "select modern_native_email from repository_customer where id = ?1", countQuery = "select count(*) from repository_customer")
    List<String> modernNative(long id);

    @Query(value = "select email from repository_customer", nativeQuery = true, queryRewriter = LegacyRewriter.class)
    List<String> rewritten();

    @NativeQuery(value = "select email from repository_customer", queryRewriter = LegacyRewriter.class)
    List<String> nativeRewritten();

    class LegacyRewriter implements QueryRewriter {
        public String rewrite(String query, Sort sort) { return query.replace("email", "legacy_rewritten_email"); }
    }

    @Query(value = "select c from RepositoryCustomer c", countQuery = "select count(c) from RepositoryCustomer c")
    Page<RepositoryCustomer> paged(Pageable pageable);

    @EntityGraph(attributePaths = {"email"})
    @Query("select c from RepositoryCustomer c")
    List<RepositoryCustomer> graph();

    interface EmailProjection { String getEmail(); }
}
