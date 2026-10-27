package dev.epoch.externalqueryfixture;

import org.springframework.data.jpa.repository.Query;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Query("select c from RepositoryCustomer c where c.id > 500")
public @interface ExternalQuery {}
