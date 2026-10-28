# Verification refinements

This iteration addresses the second feedback report, which was based on synthetic scenarios and initial application runs. The application's 119-migration history replay was not supplied to this repository, so the regression suite does not establish accuracy on that history.

## Evidence and verdicts

PostgreSQL planning determines whether a concrete old SQL statement is structurally accepted. Catalog rules additionally inspect accepted inputs, generated values, result mappings, and database behavior. A successful `PREPARE` does not prove that a new constraint accepts all old inputs, or that a compatibility view preserves all behavior.

Views are present in the relation catalog. Replacing a table with a view no longer produces a false missing-table `BREAKING` finding when PostgreSQL accepts the old SQL. A full direct projection of the original renamed table can now receive `SAFE` under the [transparent-view proof](verification.md#compatibility-views); other view changes remain `UNKNOWN` unless a concrete incompatibility is established.

Input-domain rules use explicit mapped nullability, length, precision/scale, and supported enum database values. A concrete counterexample requires that the baseline accepted that input. Existing CHECK/domain restrictions that make this implication uncertain prevent an unsupported proof. Unsupported expressions remain `UNKNOWN`.

Successful boundary samples alone cannot establish `SAFE`: finite samples do not cover arbitrary string, numeric, row-interaction, uniqueness, or referential-integrity domains. Sampling must never silently replace that proof obligation.

Optional `--execute-boundaries` tests supported single-row, explicit-column `INSERT ... VALUES (?, ...)` templates. It synthesizes deterministic null, string-length, numeric, enum, and duplicate-row candidates, using existing synthetic-baseline FK targets where available. A candidate becomes a witness only after PostgreSQL actually accepts it on the baseline and rejects it after migration. Every probe rolls back; generated/identity values, nonconstant defaults, custom database behavior, deferred constraints, and unsupported SQL/types are excluded. Passing or skipped probes never remove an `UNKNOWN` finding. Probe counts and skip reasons are recorded in report limitations.

## Changes to the supported model

| Feedback | Supported behavior and limits |
| --- | --- |
| Unrelated drops and owned sequences | Unreferenced table/column drops can be structurally safe, including their own indexes and owned sequences. Referenced operations and incoming referential dependencies are still checked. |
| Generic migration-effect noise | Supported structural SQL is classified against actual before/after catalogs. DML, procedural SQL, unexplained effects, and a destructive drop/recreate sequence still require separate behavioral verification. |
| Required column with constant default | Supported literal defaults preserve old omitted-column inserts. Volatile defaults and generated expressions remain subject to additional checks. |
| Non-unique indexes | Plain index removal is structurally safe. Creation is safe for read-only dependencies or supported bounded key domains. A new btree index over unbounded text can reject an old accepted large value, even without uniqueness; unsupported key bounds remain `UNKNOWN`, and an executed witness can establish `BREAKING`. Expression, predicate, and custom index semantics are conservative. Unique creation remains unproven without input evidence; a removed unique index is checked against old `ON CONFLICT` SQL. |
| New foreign keys | A new referencing table affects declared deletes and referenced-key updates. Read-only operations and updates to other columns are not reported as affected writes. |
| Type widening | Supported varchar and numeric/integer domain inclusions are recognized for writes. Read-result type and hydration compatibility is checked separately. |
| Nullability and narrowing | Supported mapped null, maximum-length, and numeric-domain exclusions produce `BREAKING` with the affected contract entries. No-op catalog changes do not manufacture a schema finding. |
| Enum mappings | Supported enum values are retained in the contract. Simple supported CHECK membership constraints can establish an excluded old value. Arbitrary CHECK expressions remain `UNKNOWN`. |
| Defaults and identities | Dropping a default/identity required by an old omitted non-null column is a concrete incompatibility. Supported identifier generation is represented as a dependency. |

These outcomes remain scoped to the declared structural contract. Dropping data outside that contract can be structurally safe while still requiring a separate application/data-retention review.

## Rollout and rollback workflows

Repeat `--new-contract` alongside the old `--contract` to check both directions in one disposable PostgreSQL run:

```bash
db-compat verify \
  --contract release-n-minus-1.json \
  --new-contract release-n.json \
  --baseline db/baseline \
  --migrations db/pending \
  --report rollout-report.json
```

`oldOnNew` checks every pending migration state. `newOnOld` checks the new contract against a retained copy of the original schema, after validating that contract against the final migrated schema. This does not execute or certify down migrations. The report retains each direction's baseline health and verdict.

`verify-release` reads migration blobs from Git without checking out or resetting either revision:

```bash
db-compat verify-release \
  --repository . \
  --base-ref v1.4.0 \
  --target-ref v1.5.0 \
  --migration-path src/main/resources/db/migration \
  --contract release-1.4.0.json \
  --new-contract release-1.5.0.json \
  --report release-report.json
```

Existing migration files must remain unchanged. The command records exact Git commits and delegates migration ordering/execution to Flyway. Supply the retained release contracts explicitly; Git refs alone do not reconstruct compiled application SQL contracts.

## Extraction fidelity

Physical table/column attribution comes from Hibernate mappings and generated SQL aliases, including JOINED inheritance and secondary tables. Supported element and many-to-many collection operations include their collection-table dependencies. Unsupported collection strategies remain unresolved.

Contract identifiers use PostgreSQL's UTF-8-aware 63-byte identifier limit. Missing classpath entries produce diagnostic coverage notes rather than making otherwise valid classpaths unusable. Missing classes and mapping failures still require unresolved coverage; extraction success alone does not imply completeness.

Repository discovery is enabled by default. If its application classpath/scope is not available, that coverage is unresolved. Setting `discoverRepositories: false` declares a narrower mapping/query contract; it does not certify that an application's repositories have been discovered.

When a query omits parameter type declarations, PostgreSQL can resolve supported unambiguous column/cast contexts, including UUID, text, and PostgreSQL enum columns. The resolved baseline types are reused after migration. An unconstrained `SELECT ?` remains ambiguous; incorrect explicit parameter counts are still rejected. Type inference does not establish exhaustive native-query discovery or behavioral equivalence.

Consult [the contract documentation](contracts.md) for exact supported Hibernate versions, generator strategies, recovery behavior, and adapter limitations. A runtime-version mismatch is never silently treated as compatible.

## Runtime, performance, and cache diagnostics

Flyway uses a non-transactional PostgreSQL advisory lock so its lock connection does not hold a transaction snapshot that blocks `CREATE INDEX CONCURRENTLY`. A non-transactional migration still needs the Flyway SQL sidecar:

```properties
# V123__index.sql.conf
executeInTransaction=false
```

`--migration-timeout-seconds` defaults to 120 and `--global-timeout-seconds` defaults to 1800. Runtime failures, database disconnection, cancellation/timeouts, validation failures, and migration execution failures exit 3. A rejected migration is not presented as a completed `UNKNOWN` compatibility check. Failure messages identify the stage, migration when available, and underlying cause.

Planning is reused only when declared dependencies are unchanged. Incomplete dependencies or database-side behavior take the conservative path. Per-migration `replannedQueries` and `reusedQueries` expose the decision; cached SQL failures remain visible while an incompatible intermediate state persists. Catalog and input-domain checks still run.

Baseline cache reports include `cacheReason` explaining misses, unusable archives, checksum mismatches, restore errors, or catalog mismatches. Restores always target fresh owned databases. Rebuilding a corrupt cache does not accept a changed migration history or reuse pending migration data.

## CI output and operational review

Reports sort findings by severity, with `BREAKING` first. Unknown findings include the missing evidence or a mitigation to help resolve them. `--sarif report.sarif` exports SARIF 2.1.0; `--migration-source-prefix` provides the repository-relative migration directory. Catalog findings are migration-wide evidence, so their file-start location is an anchor, not a claim to identify the exact causal SQL line.

Operational review is separate:

```bash
db-compat lint --migrations db/pending --report operational-lint.json
```

The lint report contains review hints, not a compatibility verdict or a proof of lock duration, rewrite cost, or online deployment safety. Default application logging stays quiet; `--verbose` enables additional diagnostic output and stack traces.

## Regression coverage

The real PostgreSQL tests cover compatibility views; unrelated cascaded dependencies; literal versus volatile defaults; indexes and `ON CONFLICT`; precise FK targets; nullability, string/numeric narrowing and widening; enum membership; omitted defaults/identities; no-op ALTERs; dependency-based plan reuse; concurrent index creation; runtime failures/timeouts; and both verification directions. Hibernate fixtures cover JOINED/secondary-table attribution, element and many-to-many collections, and identity generation.

These regressions complement the original migration scenarios. They do not claim exhaustive coverage of application SQL, arbitrary PostgreSQL procedural behavior, or the unavailable production migration history.
