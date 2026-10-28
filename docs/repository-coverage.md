# Repository and DAO coverage

Entity CRUD is only one part of an application's database contract. Repository discovery is enabled by default and scans compiled Java/Kotlin classes for database-access call sites. Supply the application's compiled roots and the packages containing its repositories and DAOs:

```json
{
  "applicationVersion": "release-n-minus-1",
  "classpath": ["../build/classes/kotlin/main", "../build/classes/java/main", "../lib/application-dependencies.jar"],
  "scanPackages": ["com.example.domain"],
  "repositoryPackages": ["com.example.repository", "com.example.dao"],
  "discoverRepositories": true
}
```

The classpath must contain the app's actual Hibernate runtime and its dependencies when using Hibernate 7. Missing class directories are recorded as notes: Gradle commonly lists output directories for a language that a module does not use. Missing classes needed by a mapping or query still produce a failure or an unresolved source.

Entities and repositories may live in separate application JARs. Epoch keeps its ASM bytecode-analysis components together while isolating the application's Hibernate runtime, so an application supplying only ASM core does not split the scanner's internal type family. `Hibernate7JarDiscoveryTest` covers separate Kotlin entity/repository JARs with actual Hibernate 7.4.9, including that dependency collision. Framework bootstrap services still need compatible application dependencies; JAR discovery alone does not establish that every application can bootstrap.

[Public application validation](public-application-validation.md) records extraction from the released Keycloak 26.8.1 JARs with Hibernate 7.4.9, including retained entities, discovered queries, unresolved coverage, artifact pins, and an opt-in reproduction recipe.

`repositoryPackages` selects the application packages independently of entity packages. With no repository package selector, the entity scan packages are used when supplied. Unscoped class directories can be scanned in full; JARs require a package selector so the scanner does not mistake dependency internals for application repository methods. Unavailable or unscoped roots produce an explicit unresolved inventory entry. Add all application modules containing database access to the descriptor.

Setting `discoverRepositories: false` explicitly selects the narrower entity/descriptor contract. It is useful for a finite mapping fixture or a deliberate adapter contract, but provides no repository discovery guarantee.

## What is discovered

The scanner uses ASM bytecode data flow. It retains literal strings, constant fields, supported constant concatenation, and constant JVM string-concatenation templates without running application code. Kotlin literal `trimIndent()` and `trimMargin()` calls are normalized, including an explicit margin prefix or the compiled default-argument call. A nearby string constant is never assumed to be a query argument.

When control-flow branches establish different constant strings, the scanner enumerates up to 16 alternatives at that call site. A single finite operand can be combined with constant string fragments. Combining multiple finite operands remains unresolved when their branch correlations are unavailable: a Cartesian product could invent SQL that the application never executes and cause a false `BREAKING` verdict. Unknown templates, unsupported string builders, and variant sets larger than 16 retain their individual unresolved class/method/line locations.

| Access mechanism | Evidence retained |
| --- | --- |
| JPA/Hibernate `createQuery`, `createSelectionQuery`, `createMutationQuery` | Fixed HQL is passed through Hibernate's actual translation infrastructure. Unsupported plans remain unresolved. |
| `createNativeQuery` and fixed JDBC/`JdbcTemplate` SQL | Native SQL templates are retained and checked by PostgreSQL. Unsupported dependency or result semantics remain unresolved. |
| Spring Data `@Query` and `@NativeQuery` | Fixed HQL/native annotations and explicit count queries are discovered. Expression-language substitutions, custom query rewriters, and composed query annotations remain unresolved. |
| Spring Data derived methods | The application's `PartTree` parser supplies predicates/property paths for a supported subset. Supported null/non-null equality variants are enumerated, with up to four nullable parameters (16 variants). Unsupported predicates, projections, excessive variant counts, or unavailable framework APIs are explicit unresolved entries. |
| Panache shorthand | The application's `PanacheJpaUtil` expands supported fixed queries when the entity and helper API can be resolved. Inherited framework owners and generic entity bindings are resolved through supplied ancestor headers; application overrides remain unresolved. Fixed `find(...).list()`/`.stream()` chains retain their query origin. Sort arguments and projection, paging, filter, or lock modifiers remain unresolved; their unmodified base is not asserted as an executed variant. Ambiguous entity types, parameter-dependent shorthand, and unsupported helpers remain unresolved. |
| Criteria, Querydsl, jOOQ, stored procedures, and dynamic SQL | Recognized access sites are individually recorded as unresolved. Test observations can add concrete SQL evidence without establishing exhaustive coverage. |

This is an inventory of recognized paths in the selected compiled scope. Reflection, runtime generation, unscanned modules, custom persistence frameworks, and arbitrary interprocedural string construction are not proven covered.

Spring named-query lookup takes precedence over method-name derivation. Registered JPA/Hibernate named queries are linked to their extracted contract entries. External `META-INF/jpa-named-queries.properties` definitions and XML mapping metadata that prevent establishing this precedence produce unresolved entries; the scanner does not substitute a derived query. Composed query annotations are inspected through the application classloader even when their annotation class lies outside the selected repository packages.

## Methods, variants, and coverage

Each discovered ordinary call site has an identifier based on the class, JVM method signature, and ordinal among all JVM method-call instructions in that method, such as `example.CustomerDao#find(Ljava/lang/String;)Ljava/util/List;:0`. Annotation and framework-derived entries use descriptive suffixes. Multiple constant alternatives use query IDs ending in `.variant.N`, assigned after sorting the query strings; one call-site entry links every variant. Source line numbers are retained when the compiled class contains debug information.

Counting ordinary calls before API classification means recognizing another API does not renumber later sites. This replaces the earlier recognized-database-calls-only numbering, so regenerating an existing contract may change source IDs and invalidate its accepted-baseline fingerprint. A compiler/source change can still change the instruction sequence. Direct API method references are separate unresolved potential paths rather than ordinary invocation counts.

`accessPaths` in the contract and compatibility report contains each call's class, method, status, query IDs, mechanism, optional source line, and unresolved reason. Statuses are `STATIC`, `OBSERVED`, `DECLARED`, and `UNRESOLVED`. Extraction and saved-report rendering show percentages per class over the discovered methods. A method with any unresolved site counts as unresolved; observations do not count as an exhaustive static proof. Default extraction summarizes at most 20 classes without printing every site. `extract --details` and `report --details` print the full inventory retained in JSON.

Repeated extraction failures share a diagnostic with all affected source IDs and a retained exception/cause. `--verbose` emits the original caught trace once per group. Normal extraction bounds diagnostic groups and source samples; normal findings show at most 20 affected IDs with total and omitted counts. `--details` expands them. The unresolved-source count is the distinct union of grouped source IDs, not the smaller number of diagnostic rows. Grouping never improves the coverage verdict.

The optional `apiInventory` makes raw known-family API calls, query-capable extracted/linked ratios, repository declarations, unresolved method references and scan gaps explicit. Existing per-method percentages remain a separate discovered-method view. External ancestor headers and generic bindings resolve inherited owners even outside the selected body-scan packages. Missing or ambiguous ancestry, unreadable classes/archives and failed method data flow make inventory extent incomplete; they cannot be treated as zero database calls. Reflection, unknown frameworks and unscanned bodies remain outside the declared denominator. See [compiled API inventory](repository-api-inventory.md) for exact fields and limits.

Pagination, runtime sorting, entity graphs, locks, batch loading, collection-valued parameters, and framework projections can generate additional SQL. Recognized modifiers are recorded as unresolved variants when they cannot be enumerated. Supply fixed alternatives through `finiteHqlVariants` for a deliberately finite contract, and retain an unresolved entry whenever exhaustiveness is not established. The supported Spring Data derived subset enumerates null/non-null equality branches and renumbers the remaining bind parameters for each HQL variant. More than four nullable equality parameters produces an unresolved variant entry. The presence of one translated base query does not silently prove all other framework variants.

Automatic pagination discovery supports literal nonnegative `setFirstResult` and positive `setMaxResults` calls on locally created HQL queries in straight-line methods with established local execution. Each supported executed state is translated through the application's actual Hibernate query options, including its SQL and parameter binder types. Dynamic or branch-dependent bounds, native/named-query call pagination, Panache page/range, Spring Pageable, collection fetches, escaped queries and other shape modifiers remain unresolved. `maxResults=0` remains unresolved because Hibernate can skip SQL entirely; Epoch does not invent a database operation for that state. An explicit `hqlQueryPagination` declaration is a separate caller-supplied fixed HQL option, not proof of automatic discovery coverage.

## Native parameter inference

An empty `parameterTypes` list allows the verifier to infer parameters from the SQL's PostgreSQL column or explicit-cast context. It prepares the SQL, reads `pg_prepared_statements.parameter_types`, and freezes those baseline types for later schemas. Explicit `nativeQueryParameterTypes` declarations remain available.

This works for supported unambiguous contexts such as `WHERE id = ?` against a UUID column. An unconstrained `SELECT ?` does not establish a type and remains unverified. Named native parameters are normalized by the extractor where supported; a template that cannot be converted safely remains unresolved. Inference is not a proof of accepted application inputs or complete native-query semantics.

## Runtime SQL capture

In integration tests, register Epoch's inspector with the normal Hibernate setting:

```properties
hibernate.session_factory.statement_inspector=dev.epoch.hibernate.SqlCollector
```

Add the Epoch Hibernate adapter as a test dependency. Set the JVM property `-Depoch.sql.capture=build/epoch-observed.jsonl` to persist captured statements. The collector records SQL templates and candidate application stack frames, without JDBC bind values. SQL that the application itself constructs with inline literal values still contains those literals; use test data for capture.

Merge one or more captures during extraction:

```bash
db-compat extract --descriptor descriptor.json \
  --runtime-sql build/epoch-observed.jsonl \
  --output old-contract.json
```

Alternatively, set `runtimeSqlFiles` in the descriptor, with paths relative to that descriptor. Runtime provenance is an observed stack location, not a proof that a framework proxy or asynchronous execution belongs uniquely to one repository method. Captures supplement static evidence; they never eliminate unresolved dynamic paths solely because tests ran successfully.

For a complete deterministic contract, every relevant discovered site and variant needs supported evidence. A coverage percentage describes the inventory's denominator, not a probability that an application is safe.
