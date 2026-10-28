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
| Remove a referenced table/column name without a supported compatible replacement | `BREAKING`; the old SQL name is gone. |
| Replace a renamed table with a proven transparent, updatable compatibility view | Can be `SAFE`; backing defaults, constraints, generation and types still undergo the normal rules. |
| A replacement view cannot support a baseline-valid old write, even with trigger support included | `BREAKING`, with the affected operation and PostgreSQL capability evidence. |
| Add a `NOT NULL` column that old inserts necessarily omit as NULL | `BREAKING` when catalog type and write-behavior facts establish rejection; otherwise `UNKNOWN`. |
| SQL valid at baseline becomes invalid after a migration | A breaking SQL finding when the PostgreSQL failure establishes incompatibility. |
| Tighten mapped nullable inputs or narrow a mapped length/precision domain | `BREAKING` when the supported baseline input proof establishes an excluded value; otherwise `UNKNOWN`. |
| Widen supported write types | Can be `SAFE`; old read mappings are checked separately. |
| Change a referenced column's collation | `UNKNOWN` (`COLUMN_COLLATION_CHANGED`); comparison and ordering semantics require a separate proof. |
| Add a nullable or required column with a supported constant default | Can be `SAFE` for old explicit-column inserts; newly imposed constraints still receive their own checks. |
| Add a foreign key on a new table or columns that old inserts omit as proven NULL | Can be `SAFE` when no declared DELETE or referenced-key UPDATE is affected. Defaults, generation, domains, or partial-NULL composite keys require separate proof. |
| Drop non-unique indexes, or add supported indexes with proven bounded keys | Can be structurally `SAFE`; unbounded B-tree keys can reject oversized writes and remain `UNKNOWN` without stronger evidence. |
| Change constraints, defaults, or generation behavior | Input proofs, omitted-value dependencies, and SQL planning determine concrete failures; unsupported implications remain `UNKNOWN`. |
| Change result shape for positional SQL or `SELECT *` | Cannot be assumed safe. |
| Data-changing or procedural migration effects | `UNKNOWN`, even if table/column definitions are unchanged. |
| Incomplete query dependencies, unresolved query sources, or observations alone | `UNKNOWN`. |

These are rules for the declared scope, not assurances about every side effect of a DDL statement. Independent safe findings do not cancel unknown or breaking findings. The report retains intermediate failures even if a subsequent migration restores the original schema.

## Expand-phase proofs

Constant defaults do not waive newly added constraints. For example, an old `INSERT INTO accounts(id) VALUES (?)` starts supplying `region_id=0` implicitly after adding that column with `DEFAULT 0`; a new foreign key can reject it even though the old SQL does not name `region_id`. `CONSTRAINT_CHANGED` includes the affected INSERT IDs when those implicit inputs are not proven compatible. Supply a compatible default, retain the old accepted inputs, or establish the additional constraint separately.

The omitted-NULL exemption requires positive catalog evidence: no column default, identity, generated expression, type-level default or domain restriction, and no opaque table write behavior. Every component of a composite foreign key must meet this proof; one NULL does not establish safety for `MATCH FULL`. Missing type or relation facts remain unknown. `OMITTED_COLUMN_VALUE_UNPROVEN` explains this boundary for newly added nullable columns, including domains whose own NOT NULL constraint is absent from the column's `attnotnull` flag. Extension ownership suppresses generic behavior noise; it does not erase a table's actual trigger/rule facts or a type's domain semantics.

`ExpansionCompatibilityTest` exercises constant defaults, varchar-to-text widening, serial identifier dependencies, and new nullable foreign keys against PostgreSQL. Its rejection witnesses demonstrate FK and domain failures that successful planning alone misses. `VerificationRefinementTest` covers unrelated drops and their dependent objects, supported index changes, new-table sequences, and bounded widening. Integer widening with old readers and unbounded B-tree keys retain their separate hydration and input-size limits; these are not unconditional SAFE rules.

## Compatibility views

`TRANSPARENT_COMPATIBILITY_VIEW` recognizes a narrow rename pattern:

```sql
ALTER TABLE accounts RENAME TO accounts_next;
CREATE VIEW accounts AS SELECT * FROM accounts_next;
```

The view must directly expose every backing column, in order, under the same names. The backing relation must be the original baseline table, established through live catalog identity, with unchanged column collations, and PostgreSQL must report automatic support for the old CRUD operations and writable columns. The proof excludes filters, joins, expressions, options, view-specific defaults, inheritance, user triggers/rules, and row-level security. Owners must agree, and explicit table or column ACLs remain outside this narrow proof. The effective search path must put `pg_catalog` first when both snapshots are read; otherwise custom operators or types could invalidate the interpretation of deparsed expressions. Missing search-path evidence remains unsupported. A same-shaped replacement table does not establish that the original data was retained.

Comparison uses the backing table's actual defaults, nullability, generated identifiers, constraints, indexes and sequence ownership. PostgreSQL JDBC's `smallserial`/`serial`/`bigserial` result aliases are normalized to their physical integer types using its storage-type and generation metadata; identifier generation is still checked separately against the catalog. These still receive the ordinary compatibility checks. Only the proven view's relation-kind/definition noise and its supported `CREATE VIEW` effect are removed; other migration effects, including DML, remain visible. `RELATION_RENAME_EXPRESSION_UNPROVEN` retains `UNKNOWN` for unmodeled stored expressions that can observe the name change, including defaults or CHECKs on other tables. For example, an unchanged `pg_get_serial_sequence('accounts','id')` default can return NULL once `accounts` names a view. Keep such expressions independent of the changed relation name or establish their behavior separately. Runtime relation identifiers are excluded from report and cache fingerprints.

`VIEW_WRITE_UNSUPPORTED` identifies an old write that the replacement view cannot perform, using `pg_relation_is_updatable` with trigger support included and a baseline-valid contract entry. Column capability checks use `pg_column_is_updatable`; `VIEW_COLUMN_WRITE_UNPROVEN` remains `UNKNOWN` because an unsupported column flag alone does not prove failure for an explicit `DEFAULT` assignment. Successful planning alone does not prove equivalence for filtered, computed, trigger-backed, or otherwise opaque views, which remain `UNKNOWN` where no concrete failure is established. Preserve the original relation and a direct compatible projection during the rollout, or provide separate behavioral evidence for unsupported view logic.

## Unsupported or conservative areas

- **Native SQL:** supported fixed explicit-column templates use SQL aliases for physical dependencies and explicit types or unambiguous PostgreSQL inference. Unknown types freeze only after PostgreSQL resolves them at baseline. Wildcards, nested scopes, ambiguous aliases, positional inserts, conflict-update variants, and opaque function/sequence calls remain unresolved; simplify or explicitly enumerate those access paths instead of changing completeness flags. `POSTGRES_SQL_REJECTED` supplies `BREAKING` evidence only when PostgreSQL accepted the same contract SQL at baseline. Successful planning does not prove arbitrary native hydration or behavior.
- **SQL dependency coverage:** supported projection, join predicate, filter, `ORDER BY`, `GROUP BY`, `DISTINCT ON`, and DML `RETURNING` references participate in invalidation. Implicit `NATURAL`/`USING` joins, ambiguous grouping aliases, window expressions, pagination expressions, and set-operation scopes remain incomplete and therefore always re-plan. Native SQL uses the verifier's public-schema model; `hibernate.default_schema` does not establish a native search path. Use explicitly qualified native relations for non-public schemas; unqualified names under a non-public ORM default stay unresolved.
- **Dynamic queries:** only explicitly supplied fixed HQL variants can be translated. Unenumerated branches, collection-valued parameters, dynamic mutation plans, and runtime-dependent predicates remain unresolved.
- **Hibernate features:** supported collection-table CRUD and identity/sequence dependencies are extracted. Filters/restrictions, formulas, custom SQL, column transformers, unsupported collection strategies, and other unrecognized plans remain unresolved. Recording a generator name alone does not prove its allocation behavior.
- **Partial bootstrap:** `extract --keep-going` can retain healthy entities in a connected graph by excluding a failed entity and its incoming associations, including inherited properties and explicit association targets. Every omitted entity and concrete property path is unresolved, so `INCOMPLETE_COVERAGE` and `UNRESOLVED_QUERY` prevent a partial contract from becoming SAFE. Recovery is bounded and refuses a shared generic override that would silently remove a healthy association. Fix the original mapping and re-extract to restore complete evidence.
- **Database behavior:** user functions, procedures, triggers, views, row-level security policies, sequences, defaults, generated expressions, and constraint implications can change behavior beyond the structural model. Existing user-defined behavior can prevent a safe conclusion when the schema changes. Views remain usable relations for SQL planning; only the narrowly proven transparent-view case above establishes compatibility through a replacement view. Relevant sequences and enum types have structured dependencies; unrelated owned sequences do not create global noise. Behavior objects owned by extensions are excluded using `pg_depend.deptype = 'e'`; merely installing pgcrypto or pg_trgm does not create thousands of generic behavior findings. User-defined functions that call extensions remain visible. Extension additions/removals/version changes are recorded separately and conservatively yield `UNKNOWN`.
- **Identifiers:** physical names are truncated at PostgreSQL's 63-byte UTF-8 boundary before catalog matching. `PostgresIdentifierIntegrationTest` verifies an entity whose original table name is 67 characters against the actual truncated PostgreSQL catalog name. Schema and table names must be representable unambiguously as `schema.table`; names containing embedded dots are unsupported and produce `UNKNOWN`.
- **Type and hydration semantics:** successful SQL preparation does not prove every bound value is accepted, every row can be hydrated, or every application expectation is preserved.
- **Nullability evidence:** contracts distinguish `declaredNonNull` from raw `hibernateNullable`. Kotlin metadata and explicit Java bytecode annotations describe the mapped logical member; unknown Java/generic declarations remain unknown, and container or association nullability is not copied onto JDBC leaf columns. These facts do not prove arbitrary converter output or replace the actual baseline's accepted-input constraints.
- **Enum and converter evidence:** ordered constants, recognized enum encodings, aligned database values, and converter class/fingerprint facts are recorded separately. Hibernate 7 explicit enum codes are not assumed to be Java names or declaration ordinals. Arbitrary converter semantics remain unsupported; a class-byte fingerprint alone proves neither equivalent behavior nor a breaking format change.
- **Operational migration safety:** production lock duration, concurrency, permissions, storage pressure, and down-migration behavior are outside compatibility proof. A separate optional `lint` command emits operational hints. Verification deadlines and session-level advisory locking keep the disposable run bounded, including supported concurrent index migrations.
- **Application coverage:** no whole-application guarantee follows from entity discovery or a test run. Additional JDBC paths, application authorization assumptions, and custom query-generation code must be accounted for separately.

Repository discovery is enabled by default and records recognized DAO/repository call sites, individually unresolved dynamic paths, and observed test statements. Per-class percentages cover only the discovered method inventory. See [repository coverage](repository-coverage.md) for scan scope, framework subsets, and variant boundaries.

`MappingRefinementTest` verifies physical SQL attribution against PostgreSQL for JOINED inheritance and secondary tables. A child-table DELETE depends only on that table's key; parent audit columns such as `people.created_at` are not attributed to `employees`. The baseline checks both SQL validity and the absence of false missing-column findings.

`UNKNOWN` is an explicit limit of the evidence. Resolve it through a more precise contract, added implementation support, or separate behavioral review/tests. It is not an instruction to turn off the rule.

## Reproducibility

Retain the old compiled application artifacts, extraction descriptor, generated contract, baseline migrations, pending migrations, and report together. Preserve the Gradle wrapper and pinned dependencies. Inferred PostgreSQL parameter types are fixed at baseline, so a migrated column cannot silently redefine the old binding contract. Declared result types are also resolved at baseline; subsequent checks compare actual result signatures with that accepted signature, so a newly created same-named type cannot reinterpret an old result declaration. Retain accepted-baseline policies and their justifications alongside reports when used. Use the same PostgreSQL version when reproducing a result, and review the contract whenever application mappings, query definitions, or naming strategies change.

`Hibernate7CompiledFixtureTest` compiles a separate Kotlin fixture against Hibernate 7.4.9/JPA 3.2, then extracts it with actual Hibernate 7.0.10, 7.1.4, and 7.4.9 runtimes. Its generic `List<X>` fields include `@ManyToMany` and join-table `@OneToMany`, with no explicit `targetEntity`. The test checks collection SQL and association-query dependencies while confirming that the tool's bundled Hibernate 6.6 runtime remains isolated. Other collection strategies retain the explicit limitations above.

This tool accepts no arbitrary target database connection. Migrations still execute SQL in the disposable database, so use trusted migrations and a Docker daemon appropriate for CI work.

## Scale and execution failures

The planner reuses a query result only when its complete declared dependencies remain unchanged; incomplete dependencies and database behavior take a conservative path. Reports retain intermediate failures and expose per-step `replannedQueries` and `reusedQueries`. Structural/input checks continue at every step.

Flyway's PostgreSQL transactional lock is disabled to avoid holding the snapshot that can block `CREATE INDEX CONCURRENTLY`. Put `executeInTransaction=false` in that migration's `.sql.conf` file. Per-migration and global deadlines default to 120 and 1800 seconds. A database disconnection, migration SQL/validation failure, or deadline expiry throws a runtime failure and exits 3 with the stage/migration; it does not produce a completed `UNKNOWN` report.

`RuntimeCliPostgresTest` exercises actual PostgreSQL container termination during a pending migration and malformed SQL (`42601`) through the CLI. Both retain `MIGRATION_EXECUTION_FAILED` and the migration filename and return exit 3. `VerificationOperationsTest` separately verifies concurrent index completion and timeout cancellation. These are execution diagnostics, not compatibility findings or evidence that an old contract is broken.

Planning uses a bounded connection pool for independent baseline checks and committed states. Transactional migration checks retain the migration connection to observe uncommitted changes. Incremental invalidation computes each catalog diff once and reuses only plans whose complete dependencies remain unchanged. A cached baseline plan must match the contract, baseline migration key, catalog, and planner format; unsupported or data-dependent evidence is re-checked. Neither parallelism nor caching changes a compatibility rule or turns incomplete coverage into safety.

Progress snapshots carry `complete=false` until verification finishes. An interrupted checkpoint is execution evidence for completed work, not a completed compatibility verdict. Reading it through `report` exits 3, and baseline acceptance cannot be generated from it. The CLI preserves checkpoints on timeouts and termination signals; it does not certify an interrupted migration's final state.

The optional baseline cache records `MISS`, `HIT`, and `REBUILT`; every miss or rebuild includes `baseline.cacheReason`, emitted immediately under `--verbose`. Immutable archive generations and an atomic manifest let valid warm readers restore without acquiring the writer lock. A hit still verifies archive integrity, the restored catalog, and Flyway history. Its schema digest uses logical live-column order, so PostgreSQL restore's removal of historical dropped-column slots does not repeatedly invalidate equivalent dumps.

See [rollout and rollback workflows](feedback-round2.md#rollout-and-rollback-workflows) for `--new-contract` and `verify-release`. Reverse verification uses a retained copy of the old schema; it does not execute down migrations.
