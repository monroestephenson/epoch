> Historical record for the first feedback revision. For current Hibernate 7 support, repository discovery, and verification changes, see [verification refinements](feedback-round2.md) and [repository coverage](repository-coverage.md).

# Feedback changes and remaining boundaries

This table maps the reported issues to the implementation and documents the limits that still apply. It is not a claim that every real-application mapping or migration is supported.

| # | Change | Boundary |
| ---: | --- | --- |
| 1 | Top-level CLI catches unexpected `Throwable` failures, including linkage errors, and returns `3`; cause chains are printed. | `1` remains the compatibility `BREAKING` code. |
| 2 | Synthetic discriminator placeholders and formula selectables are excluded from physical columns. | Real formula dependencies remain unresolved where unsupported. |
| 3 | Extension-owned behavior objects are filtered using `pg_depend.deptype = 'e'`. | User-defined behavior remains visible; extension version/set changes are tracked separately. |
| 4 | JDBC aliases map to PostgreSQL types; `OTHER` is inferred from PostgreSQL statement/column context and frozen at baseline. | Ambiguous inference remains `UNKNOWN` and requires an explicit type. |
| 5 | Runtime-observation limitation appears only when the input contains observed SQL. | Other scope limitations remain applicable. |
| 6 | Baseline health appears once, with no pending-migration version/filename attribution. | The overall compatibility verdict still includes baseline debt. |
| 7 | Supplied Hibernate runtime versions are checked before bootstrap and mismatches fail clearly. | This adapter supports 6.6.13.Final; executing an application's Hibernate 7 in isolation is not implemented. |
| 8 | Bean Validation is disabled for extraction. | Application dependencies required by mappings still belong on the classpath. |
| 9 | Testcontainers upgraded to 2.0.5; cause chains, required images, and optional Ryuk disabling are documented. | The current development host is Docker 28.4.0; this does not certify an Engine 29 run. |
| 10 | Extraction uses PostgreSQL dialect version 16.8, matching verification. | Other database versions need their own support/validation. |
| 11 | `hibernateProperties` supplies application type/schema and other settings. | Offline connection, schema-generation, validation, and dialect safeguards remain enforced. |
| 12 | Repeated `verify --contract` verifies several persistence units with one baseline application. | Inputs must share the same database baseline; IDs are namespaced in input order. |
| 13 | `scanPackages` discovers annotated entities from compiled class directories and JARs. | Package scanning does not discover arbitrary runtime database-access paths. |
| 14 | `--keep-going` isolates failed entities and their incoming associations, including inherited and explicit-target mappings. | Recovery is bounded; unsupported splits and every omission remain unresolved, and partial extraction is always incomplete. |
| 15 | Entity overrides support operation sets, result-mapping-only classes, and exclusion; immutable mappings default to reads. | Overrides narrow the declared scope and do not prove absence of omitted application operations. |
| 16 | `nativeQueryParameterTypes` supplies types for explicit and named native queries. | Preparation does not establish full native-query dependencies or behavioral safety. |
| 17 | Verification help describes contract/report paths and the new options. | Use each command's `--help` for exact syntax. |
| 18 | Baseline health, justified acceptance files, rule summaries, and bounded terminal detail reduce duplicate noise. | Acceptance changes a separate CI gate, never the original compatibility verdict or new migration findings. |
| 19 | Optional checksummed baseline dumps avoid replaying historical migrations; logging defaults to warnings/errors. | Cache hits still start a fresh container and restore a baseline; `--verbose` enables INFO logs and traces. |
