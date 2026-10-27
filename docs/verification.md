# Verification and its boundaries

Epoch asks a bounded question: do the old SQL and mappings in this explicit contract retain their supported structural dependencies after each migration? It does not prove equivalence of arbitrary programs or SQL behavior.

## Database workflow

1. Start a disposable PostgreSQL 16.8 container.
2. Construct the baseline using Flyway, or restore a validated baseline cache into the fresh container.
3. Inspect baseline health once, including old SQL validity; retain preexisting findings without pending-migration attribution.
4. Apply pending versioned migrations in Flyway version order, taking a catalog snapshot and checking the old contract at every step.
5. Compare every step to the original baseline, remove duplicated baseline findings from migration results, and aggregate compatibility and any explicitly accepted-baseline gate.

Catalog inspection provides the actual schema after PostgreSQL executes a migration. SQL parsing is a conservative classifier for unsupported migration effects; it is not a replacement for executing the migration. The SQL planner uses PostgreSQL `PREPARE` and `EXPLAIN` with baseline-resolved parameter types and null argument values. It does not use `EXPLAIN ANALYZE`. Optional `--execute-boundaries` separately executes supported INSERT witnesses inside rolled-back transactions; samples can prove failures but cannot establish safety. PostgreSQL protocol Describe also checks declared result labels and types without executing the operation. Successful planning does not establish accepted values or result semantics.

Baseline and pending directories must be different. Flyway validates migration names and checksums and owns migration ordering; placeholder replacement is disabled. Use self-contained SQL migrations for this workflow. Automatic Flyway SQL lifecycle callbacks are disabled; discovered callback files produce `UNKNOWN` because their effects are not verified. When there are no pending migrations, verification checks the unchanged baseline rather than silently returning success without a contract check.

The SQL effect classifier recognizes supported structural operations and reconciles them with actual catalog differences. Fully modeled unrelated drops, metadata changes, and no-op ALTERs do not receive a generic unsupported finding. Arbitrary DML, procedural statements, unmodeled type rewrites, and destructive drop/recreate sequences retain `UNKNOWN`. A known broken dependency still wins over unknown findings in the aggregate verdict.

Reports place preexisting problems in `baseline.findings` with `migration: null`; they are not repeated for every pending migration. The overall compatibility verdict still includes baseline health. A reviewed accepted-baseline policy can change the separate migration gate, but it does not turn unproven compatibility into proof.

The baseline must reproduce the schema against which the old contract was valid. A mapping or query that was already invalid at baseline cannot support a `SAFE` conclusion. The disposable database starts without a copy of production data; data-specific failures and effects require separate tests with representative data.

## Verdicts

| Change or evidence | Result within the supported model |
| --- | --- |
| Add a nullable column with no default/generated expression, while old operations use explicit columns | Can be `SAFE`. |
| Drop or rename a table/column used by the old contract | `BREAKING`; the old SQL name is gone. |
| Add a `NOT NULL` column without default/identity/generated expression that old inserts omit | `BREAKING`. |
| SQL valid at baseline becomes invalid after a migration | A breaking SQL finding when the PostgreSQL failure establishes incompatibility. |
| Tighten mapped nullable inputs or narrow a mapped length/precision domain | `BREAKING` when the supported baseline input proof establishes an excluded value; otherwise `UNKNOWN`. |
| Widen supported write types | Can be `SAFE`; old read mappings are checked separately. |
| Add a required column with a supported constant default | Can be `SAFE` for old explicit-column inserts. |
| Drop non-unique indexes, or add supported indexes with proven bounded keys | Can be structurally `SAFE`; unbounded B-tree keys can reject oversized writes and remain `UNKNOWN` without stronger evidence. |
| Change constraints, defaults, or generation behavior | Input proofs, omitted-value dependencies, and SQL planning determine concrete failures; unsupported implications remain `UNKNOWN`. |
| Change result shape for positional SQL or `SELECT *` | Cannot be assumed safe. |
| Data-changing or procedural migration effects | `UNKNOWN`, even if table/column definitions are unchanged. |
| Incomplete query dependencies, unresolved query sources, or observations alone | `UNKNOWN`. |

These are rules for the declared scope, not assurances about every side effect of a DDL statement. Independent safe findings do not cancel unknown or breaking findings. The report retains intermediate failures even if a subsequent migration restores the original schema.

## Unsupported or conservative areas

- **Native SQL:** discovered templates use explicit types or unambiguous PostgreSQL inference. Complete dependency and behavioral proofs are not generally available; unsupported native templates remain structurally unresolved even when they can establish a concrete planning failure.
- **Dynamic queries:** only explicitly supplied fixed HQL variants can be translated. Unenumerated branches, collection-valued parameters, dynamic mutation plans, and runtime-dependent predicates remain unresolved.
- **Hibernate features:** supported collection-table CRUD and identity/sequence dependencies are extracted. Filters/restrictions, formulas, custom SQL, column transformers, unsupported collection strategies, and other unrecognized plans remain unresolved. Recording a generator name alone does not prove its allocation behavior.
- **Database behavior:** user functions, procedures, triggers, views, row-level security policies, sequences, defaults, generated expressions, and constraint implications can change behavior beyond the structural model. Existing user-defined behavior can prevent a safe conclusion when the schema changes. Views remain usable relations for SQL planning; replacing a table with a compatibility view does not itself establish behavioral equivalence. Relevant sequences and enum types have structured dependencies; unrelated owned sequences do not create global noise. Behavior objects owned by extensions are excluded using `pg_depend.deptype = 'e'`; merely installing pgcrypto or pg_trgm does not create thousands of generic behavior findings. User-defined functions that call extensions remain visible. Extension additions/removals/version changes are recorded separately and conservatively yield `UNKNOWN`.
- **Identifiers:** schema and table names must be representable unambiguously as `schema.table`; names containing embedded dots are unsupported and produce `UNKNOWN`.
- **Type and hydration semantics:** successful SQL preparation does not prove every bound value is accepted, every row can be hydrated, or every application expectation is preserved.
- **Operational migration safety:** production lock duration, concurrency, permissions, storage pressure, and down-migration behavior are outside compatibility proof. A separate optional `lint` command emits operational hints. Verification deadlines and session-level advisory locking keep the disposable run bounded, including supported concurrent index migrations.
- **Application coverage:** no whole-application guarantee follows from entity discovery or a test run. Additional JDBC paths, application authorization assumptions, and custom query-generation code must be accounted for separately.

Repository discovery is enabled by default and records recognized DAO/repository call sites, individually unresolved dynamic paths, and observed test statements. Per-class percentages cover only the discovered method inventory. See [repository coverage](repository-coverage.md) for scan scope, framework subsets, and variant boundaries.

`UNKNOWN` is an explicit limit of the evidence. Resolve it through a more precise contract, added implementation support, or separate behavioral review/tests. It is not an instruction to turn off the rule.

## Reproducibility

Retain the old compiled application artifacts, extraction descriptor, generated contract, baseline migrations, pending migrations, and report together. Preserve the Gradle wrapper and pinned dependencies. Inferred PostgreSQL parameter types are fixed at baseline, so a migrated column cannot silently redefine the old binding contract. Retain accepted-baseline policies and their justifications alongside reports when used. Use the same PostgreSQL version when reproducing a result, and review the contract whenever application mappings, query definitions, or naming strategies change.

`Hibernate7CompiledFixtureTest` compiles a separate Kotlin fixture against Hibernate 7.1.4/JPA 3.2, then extracts it with actual Hibernate 7.0.10 and 7.1.4 runtimes. Its generic `List<X>` fields include `@ManyToMany` and join-table `@OneToMany`, with no explicit `targetEntity`. The test checks collection SQL and association-query dependencies while confirming that the tool's bundled Hibernate 6.6 runtime remains isolated. Other collection strategies retain the explicit limitations above.

This tool accepts no arbitrary target database connection. Migrations still execute SQL in the disposable database, so use trusted migrations and a Docker daemon appropriate for CI work.

## Scale and execution failures

The planner reuses a query result only when its complete declared dependencies remain unchanged; incomplete dependencies and database behavior take a conservative path. Reports retain intermediate failures and expose per-step `replannedQueries` and `reusedQueries`. Structural/input checks continue at every step.

Flyway's PostgreSQL transactional lock is disabled to avoid holding the snapshot that can block `CREATE INDEX CONCURRENTLY`. Put `executeInTransaction=false` in that migration's `.sql.conf` file. Per-migration and global deadlines default to 120 and 1800 seconds. A database disconnection, migration SQL/validation failure, or deadline expiry throws a runtime failure and exits 3 with the stage/migration; it does not produce a completed `UNKNOWN` report.

The optional baseline cache explains `MISS`, `HIT`, and `REBUILT` through `baseline.cacheReason`. Its schema digest uses logical live-column order, so PostgreSQL restore's removal of historical dropped-column slots does not repeatedly invalidate equivalent dumps.

See [rollout and rollback workflows](feedback-round2.md#rollout-and-rollback-workflows) for `--new-contract` and `verify-release`. Reverse verification uses a retained copy of the old schema; it does not execute down migrations.
