# Public application validation

Epoch was exercised against the released JARs of [Keycloak 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/), using the application's actual **Hibernate 7.4.9.Final** runtime. Two extractions with identical inputs completed with exit code `0` and produced byte-identical contracts. This validates extraction from a public application; it does not establish whole-application coverage or verify Keycloak's production migrations.

The [opt-in reproduction recipe](public-application-reproduction.md) resolves the official dependencies, inventories the released annotated entities, and repeats extraction without vendoring application JARs or sources into Epoch. It requires no application database or credentials.

## Inputs and observed results

The [Keycloak parent POM](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-parent/26.8.1/keycloak-parent-26.8.1.pom) imports [Quarkus BOM 3.40.1](https://repo.maven.apache.org/maven2/io/quarkus/platform/quarkus-bom/3.40.1/quarkus-bom-3.40.1.pom), which pins Hibernate 7.4.9.Final. The supplied classpath contained 188 JARs: model-jpa's transitive runtime dependencies plus the RESTEasy modules supplied by the published Quarkus server. It was not a complete augmented Quarkus server/deployment distribution.

All 75 annotated entity classes declared by the released model-jpa sources were retained. No entity override, exclusion, or package pruning was used. Repository discovery scanned `org.keycloak` in the supplied JARs.

| Result | Count |
| --- | ---: |
| Declared entity classes retained | 75 of 75 |
| Additional collection mapping entries | 24 |
| Total mapping entries | 99 |
| SQL templates | 761 |
| Hibernate mapping SQL / named HQL / static templates | 400 / 351 / 10 |
| Complete / incomplete SQL dependency sets | 696 / 65 |
| Discovered access paths | 606 |
| Declared / static / unresolved access paths | 307 / 10 / 289 |
| Unresolved diagnostic records | 444 |

The scan recorded 4,345 readable classes, no unreadable class sources or archives, and no failed method data-flow analyses. It inspected 135,728 invocation instructions and recognized 606 database API call sites. These paths are neither 606 unique methods nor an exhaustive denominator for every possible database operation.

The contract explicitly records `STRUCTURAL_CONTRACT` and `coverage.complete=false`. Successful extraction means the contract was written; it is not a `SAFE` compatibility verdict.

## Remaining coverage limits

The 444 diagnostic records retain 439 distinct unresolved source IDs. Their largest families are Criteria API calls (145), opaque HQL expressions or aliases (64), pagination/locking/hint variants (61), nonconstant query strings (39), unknown prepared-statement SQL producers (37), collection parameter cardinalities (30), collection persister variants (28), and composite identifier allocation semantics (26).

The remaining records include dynamic mutations, HQL translation/parser failures, database-specific native setup statements, ambiguous native dependencies, and the declared validation boundaries. In particular, unsupported unrestricted-delete translation remains unresolved for two event-store methods. These paths were not dropped to improve the result.

This run supplied no runtime SQL capture and did not reproduce Quarkus augmentation, external entity providers, or every framework query variant. No PostgreSQL verification was performed for this public application. Keycloak uses Liquibase; its production migration sequence and data were not verified through Epoch's Flyway adapter. The repository's separate PostgreSQL integration tests do not change that boundary.

## Reproducibility and defects exposed

Both runs used `extract --descriptor … --output … --keep-going --verbose`, a 240-second process timeout, and `JAVA_OPTS=-Xmx768m`. Their full contract SHA-256 was `365bd84af254a108443b02ad83abdfb970200b5ec86a0b1d4a5f441a9b84d3c9`. Contracts retain input paths, so a different workspace should compare its two local outputs rather than expect the same full-file hash.

Pinned artifact SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| [keycloak-model-jpa 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/keycloak-model-jpa-26.8.1.jar) | `7cf54f8f24c182b9ef79242dd34b15768a14296a630466ecf7989b75aa5618cb` |
| [model-jpa sources 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/keycloak-model-jpa-26.8.1-sources.jar) | `6fa2a29edbd0cb86d4b05f1f8298569e11664e18c1184e590d1d032ea762ea9a` |
| [keycloak-services 26.8.1](https://repo.maven.apache.org/maven2/org/keycloak/keycloak-services/26.8.1/keycloak-services-26.8.1.jar) | `eef91c0d261559393e829231ab44e99d06ec380bd331ee106a2ad8c05cf32da1` |
| [hibernate-core 7.4.9.Final](https://repo.maven.apache.org/maven2/org/hibernate/orm/hibernate-core/7.4.9.Final/hibernate-core-7.4.9.Final.jar) | `0e460f689c4c642c040182a844ad844316775198f330b9888db392de32f3b520` |

The reproduction recipe records every resolved coordinate and artifact checksum. The source JAR is checked against the pinned hash before generating the entity inventory.

The initial run exposed an isolated-classloader service-resource defect: Epoch's parent Jackson Kotlin module was discovered through the application's separately loaded Jackson API, preventing all entity bootstraps. The loader now respects service ownership. Discovery also incorrectly treated an unavailable, unrelated REST annotation as a Spring query annotation on a nonrepository class. Both have focused regressions. The final public runs contain neither the bootstrap failure nor systemic class-inspection failures; unresolved query coverage remains explicit.
