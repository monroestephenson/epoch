# Public application validation

Epoch was exercised against the released JARs of [Keycloak 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/), using the application's actual **Hibernate 7.4.9.Final** runtime. Two extractions with identical inputs completed with exit code `0` and produced byte-identical contracts. The tested production source was `a275c1c`; `852f819` retains that production source and updates an integration test. This validates extraction from a public application; it does not establish whole-application coverage or verify Keycloak's production migrations.

The [opt-in reproduction recipe](public-application-reproduction.md) resolves the official dependencies, inventories the released annotated entities, and repeats extraction without vendoring application JARs or sources into Epoch. It requires no application database or credentials.

## Inputs and observed results

The [Keycloak parent POM](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-parent/26.8.1/keycloak-parent-26.8.1.pom) imports [Quarkus BOM 3.40.1](https://repo.maven.apache.org/maven2/io/quarkus/platform/quarkus-bom/3.40.1/quarkus-bom-3.40.1.pom), which pins Hibernate 7.4.9.Final. The supplied classpath contained 188 JARs: model-jpa's transitive runtime dependencies plus the RESTEasy modules supplied by the published Quarkus server. It was not a complete augmented Quarkus server/deployment distribution.

All 75 annotated entity classes declared by the released model-jpa sources were retained. No entity override, exclusion, or package pruning was used. Repository discovery scanned `org.keycloak` in the supplied JARs.

| Result | Count |
| --- | ---: |
| Declared entity classes retained | 75 of 75 |
| Additional collection mapping entries | 24 |
| Total mapping entries | 99 |
| SQL templates | 759 |
| Hibernate mapping SQL / named HQL / static templates | 400 / 351 / 8 |
| Complete / incomplete SQL dependency sets | 694 / 65 |
| Discovered access paths | 2,878 |
| Declared / static / unresolved access paths | 181 / 195 / 2,502 |
| Unresolved diagnostic records | 2,796 |

The scan recorded 4,345 readable classes, no unreadable class sources or archives, and no failed method data-flow analyses. It inspected 135,728 ordinary method-call instructions and inventoried 3,600 calls in known database API families. Of these, 2,847 are query-capable invocations: 181 link to declared query definitions, 195 to statically extracted SQL, and 2,471 remain unresolved. The other 753 calls comprise 540 binding calls and 213 lifecycle or metadata calls. A further 31 direct database method references are unresolved potential access paths; they are counted separately from ordinary invocations. Together, the query-capable calls and method references account for the 2,878 access paths across 668 methods in 94 classes. Multiple calls can link to one SQL template, so these counts are not interchangeable.

The API inventory also records incomplete owner ancestry for 140 owners, linked to 1,216 distinct call sites. These gaps have unknown database-call extent and are not added to the known-call count as established database invocations. Consequently, `apiInventory.completeWithinDeclaredScope=false` despite the absence of unreadable classes or failed data-flow analyses. The [inventory model](repository-api-inventory.md) covers selected compiled roots and known API families; it is not an exhaustive denominator for every possible database operation.

The contract explicitly records `STRUCTURAL_CONTRACT` and `coverage.complete=false`. Successful extraction means the contract was written; it is not a `SAFE` compatibility verdict.

## Remaining coverage limits

The 2,796 diagnostic records retain 3,837 distinct affected source IDs; 146 records group shared causes. Diagnostic records, affected sources and unresolved access paths are separate inventories, so their totals differ. The larger inventory includes unsupported API calls and ancestry gaps explicitly rather than silently omitting them. It does not indicate a systemic classloading failure: neither run recorded a `ClassCastException` or `ServiceConfigurationError` diagnostic.

Among unresolved access paths, the recorded mechanisms include 1,383 unsupported Criteria builder calls and 70 Criteria query-creation calls, 269 ORM operations, 213 query terminals, 171 other unsupported JPA calls, 133 named-query call sites, 90 binding calls, 61 pagination or other query-variant calls, 37 prepared-statement executions without an established local SQL producer, and the 31 method references. These are mechanism counts, not inferred diagnoses or unique repository methods. Generated ORM SQL and registered named HQL are still extracted; that alone does not establish every invocation's producer, bindings or runtime variants.

Other unresolved records include incomplete native dependencies, query translation limitations, collection and identifier behavior, and the declared validation boundaries. Unsupported paths were retained rather than dropped to improve the result.

This run supplied no runtime SQL capture and did not reproduce Quarkus augmentation, external entity providers, or every framework query variant. No PostgreSQL verification was performed for this public application. Keycloak uses Liquibase; its production migration sequence and data were not verified through Epoch's Flyway adapter. The repository's separate PostgreSQL integration tests do not change that boundary.

## Reproducibility and defects exposed

Both runs used `extract --descriptor … --output … --keep-going --verbose`, a 240-second process timeout, and `JAVA_OPTS=-Xmx768m`. Each contract contained 8,642,559 bytes, with SHA-256 `34ed769530b97f91a4e28b84ec4840d43fa3d8a7968414ef4b290ad4fd9d0500`. Contracts retain input paths, so a different workspace should compare its two local outputs rather than expect the same full-file hash.

Pinned artifact SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| [keycloak-model-jpa 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/keycloak-model-jpa-26.8.1.jar) | `7cf54f8f24c182b9ef79242dd34b15768a14296a630466ecf7989b75aa5618cb` |
| [model-jpa sources 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/keycloak-model-jpa-26.8.1-sources.jar) | `6fa2a29edbd0cb86d4b05f1f8298569e11664e18c1184e590d1d032ea762ea9a` |
| [keycloak-services 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-services/26.8.1/keycloak-services-26.8.1.jar) | `eef91c0d261559393e829231ab44e99d06ec380bd331ee106a2ad8c05cf32da1` |
| [hibernate-core 7.4.9.Final](https://repo.maven.apache.org/maven2/org/hibernate/orm/hibernate-core/7.4.9.Final/hibernate-core-7.4.9.Final.jar) | `0e460f689c4c642c040182a844ad844316775198f330b9888db392de32f3b520` |

The reproduction recipe records every resolved coordinate and artifact checksum. The source JAR is checked against the pinned hash before generating the entity inventory.

The initial run exposed an isolated-classloader service-resource defect: Epoch's parent Jackson Kotlin module was discovered through the application's separately loaded Jackson API, preventing all entity bootstraps. The loader now respects service ownership. Discovery also incorrectly treated an unavailable, unrelated REST annotation as a Spring query annotation on a nonrepository class. Both have focused regressions. The final public runs contain neither the bootstrap failure nor systemic class-inspection failures; unresolved query coverage remains explicit.
