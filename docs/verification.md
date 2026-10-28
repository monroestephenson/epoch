# Verification and its boundaries

Epoch asks a bounded question: do the old SQL and mappings in this explicit contract retain their supported structural dependencies after each migration? It does not prove equivalence of arbitrary programs or SQL behavior.

## Database workflow

1. Start a disposable PostgreSQL 16.8 container.
2. Construct the baseline using Flyway, or restore a validated baseline cache into the fresh container.
3. Inspect baseline health once, including old SQL validity; retain preexisting findings without pending-migration attribution.
4. Apply pending versioned migrations in Flyway version order, observe each actual SQL statement's operational facts, and take a catalog snapshot/check the old contract at every step.
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
| Tighten declared nullable inputs or narrow a mapped length/precision domain | `BREAKING` when direct mapping and catalog evidence establish an old accepted value that the target rejects; otherwise `UNKNOWN`. |
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

## Write-domain proofs

NOT NULL and a supported `CHECK (column IS NOT NULL)` share the same input reasoning. A direct writable mapping with `declaredNonNull=false` can establish `MAPPED_NULL_REJECTED` only when Hibernate does not already reject NULL and the baseline database accepts it. `OMITTED_NULL_REJECTED` uses a separate proven omitted-NULL INSERT witness. `NON_NULL_INPUT_PRESERVED` establishes SAFE only when every relevant writer is covered; a native query or an unrelated generated statement cannot borrow a member's non-null declaration. Generated SQL uses explicit `mappedEntityIds` to establish its source mapping. Missing declarations, converters, column transformers, custom mutation SQL, dynamic mutations, and generated property values retain their own proof limits.

A validated baseline non-null CHECK can prove that later SET NOT NULL is redundant (`REDUNDANT_NOT_NULL`). An unvalidated CHECK does not establish that existing rows satisfy it. In particular, a newly added NOT VALID CHECK can reject an UPDATE of an unrelated column on a preexisting NULL row. The empty verification database is not evidence that those rows cannot exist. Preserve the old accepted NULL domain during rollout, or first establish the non-null invariant for all affected writes and rows.

CHECK equivalence is limited to supported scalar types. A PostgreSQL composite such as `ROW(NULL, 1)` can pass physical NOT NULL yet fail `IS NOT NULL`, which also examines its fields. An exact existing validated CHECK is separate evidence; a physical NOT NULL flag alone does not establish the composite CHECK.

An entity's INSERT and UPDATE can have different input behavior. A proven non-null UPDATE does not cover an INSERT that omits the same column; each omission needs its own default or NULL proof. Removing a column default also does not establish breakage when a domain still supplies a value, or when the old default already made the omitted INSERT fail.

`MAPPED_TYPE_NARROWED` intersects supported logical input bounds with the baseline database's accepted range. An explicit unbounded string declaration is distinct from missing length metadata; a finite witness longer than the new limit can prove rejection. A Java Byte stored as BIGINT does not acquire BIGINT's full logical input range. `ENUM_VALUE_REJECTED` and `ENUM_CHECK_ACCEPTS_MAPPED_VALUES` use actual relational values, not guessed Java names, and account for baseline restrictions and supported comparison semantics. A custom collation can make two different labels compare equal, so a literal set difference alone cannot prove CHECK rejection there. Keep the old accepted values or bounds until old writers are retired.

Dormant mapping drift remains baseline health: a missing physical column produces `INVALID_BASELINE_COLUMN`, with no pending-migration attribution. It cannot support a fabricated new failure or terminate the rest of verification.

## New writes and old readers

Supply `--new-contract new.json` alongside the old contract to assess three independent directions:

| Direction | Question |
| --- | --- |
| `old-on-new` | Does the old contract remain compatible at every pending migration state? |
| `new-on-old` | Can the new contract operate on the retained original schema? |
| `new-writes-old-reads` | On the final schema, do supported values written by the new release remain readable by the old release? |

The third result is `dataCompatibility` in JSON. Its `<old-vs-new-contract>` step is a contract assessment, not a pending migration. It runs even when there are no pending migrations. All three directions contribute to the combined verdict and CI exit code. Accepted-baseline findings cannot suppress new data-format regressions. Older saved reports with only the two schema directions retain that narrower scope when replayed.

Data comparison uses physical table/column identity across releases, actual generated write provenance, and actual decoded-column provenance from supported Hibernate by-ID loaders. Mentioning an enum in a WHERE clause is not evidence that the old query decodes it. Read-only projections and abstract subtype declarations do not establish new writes. Each SQL template must also be valid against the final schema; successful preparation alone does not prove its values or decoder semantics.

| Rule | Proof and mitigation |
| --- | --- |
| `DATA_ENUM_VALUE_UNREADABLE` | A supported new writer can store a NAME label or ordinal outside the old finite decoder. Keep the common value set until old readers retire. |
| `DATA_ENUM_VALUE_REINTERPRETED` | An accepted stored ordinal now denotes a different logical constant. Preserve the existing encoding; declaration order alone is not the encoding for explicit codes or native enum NAME storage. |
| `DATA_DISCRIMINATOR_UNREADABLE` | A concrete new subtype's actual INSERT stores a scalar discriminator outside an old unrestricted, finite root decoder. Delay writing that subtype until old polymorphic readers retire. Filtered roots, formulas, wildcards, and synthetic discriminators require separate evidence. |
| `DATA_NULLABILITY_RELAXED` | A supported new NULL write violates an old decoded member's declared non-null domain. Preserve the old non-null invariant during rollout. This is a declared-domain violation; Kotlin field hydration does not invariably throw an NPE. |
| `DATA_CONVERTER_SEMANTICS_UNPROVEN` | Converter class/hash facts do not establish codec equivalence or a concrete format break. Supply a supported relational-domain proof; changed and unchanged arbitrary converters can both remain UNKNOWN. |
| `DATA_TYPE_MAPPING_UNPROVEN` | A logical codec/type change lacks a supported readability proof. Retain a compatible encoding or add explicit codec evidence. |
| `DATA_WRITE_ACCEPTANCE_UNPROVEN` | A candidate old-reader failure lacks a positive supported row-domain admission proof. Opaque/cross-column constraints, defaults, custom writes, and other unsupported behavior cannot be ignored. |
| `DATA_READ_PROVENANCE_UNPROVEN`, `DATA_WRITE_PROVENANCE_UNPROVEN`, `DATA_WRITE_EFFECT_UNPROVEN` | Relevant paths lack a supported decoder, writer, or indirect-effect model. Re-extract supported metadata or retain UNKNOWN for those paths. |
| `DATA_READ_EFFECT_UNPROVEN`, `DATA_IMPLICIT_WRITE_UNPROVEN`, `DATA_NULL_DOMAIN_UNPROVEN`, `DATA_POLYMORPHIC_READ_UNPROVEN` | Views/inheritance, omitted values, uncertain NULL domains, or conditional/incomplete discriminator models need additional scoped evidence. They cannot be treated as disjoint or equivalent merely because the explicit SQL still prepares. |
| `DATA_CONTRACT_UNSUPPORTED`, `DATA_CONTRACT_COVERAGE_UNPROVEN`, `DATA_WHOLE_APPLICATION_UNPROVEN`, `DATA_AMBIGUOUS_PROVENANCE`, `DATA_EMPTY_CONTRACT` | Unsupported versions/dialects, incomplete declared coverage, whole-application claims, duplicate identifiers, or empty evidence prevent a complete data proof. Supply a valid, unambiguous contract covering the relevant supported access paths. |
| `DATA_DOMAIN_PRESERVED` | Known admitted new domains and decoder meanings are included in the old supported read domains. This is a scoped data proof, independent of the schema verdicts. |

Final NOT NULL, CHECK, or enum restrictions can exclude a proposed new value and prevent a fabricated old-reader failure. Candidate evidence describes the supported relational row domain; it does not claim to execute an INSERT or model current unique-key occupancy, matching UPDATE rows, or sequence exhaustion. PostgreSQL/Hibernate fixture executions independently demonstrate decoder failures and counterexamples. Unsupported or legacy contracts without the required provenance remain UNKNOWN where relevant. The data direction currently concerns the final schema, not new-release writes against every intermediate migration state.

PostgreSQL assignment and JDBC/Hibernate decoding can normalize representations: numeric text `01` can decode as ordinal 1, and a NAME enum decoder can trim surrounding whitespace. Raw string differences in such unsupported cases remain UNKNOWN. Arbitrary JavaType/JdbcType implementations cannot borrow a built-in logical type's producer proof. When effective Hibernate null checks are disabled, raw `hibernateNullable=false` alone does not prove that a nullable property cannot write NULL; the contract preserves that raw fact and withholds the unsupported input-domain proof.

## Compatibility views

`TRANSPARENT_COMPATIBILITY_VIEW` recognizes a narrow rename pattern:

```sql
ALTER TABLE accounts RENAME TO accounts_next;
CREATE VIEW accounts AS SELECT * FROM accounts_next;
```

The view must directly expose every backing column, in order, under the same names. The backing relation must be the original baseline table, established through live catalog identity, with unchanged column collations, and PostgreSQL must report automatic support for the old CRUD operations and writable columns. The proof excludes filters, joins, expressions, options, view-specific defaults, inheritance, user triggers/rules, and row-level security. Owners must agree, and explicit table or column ACLs remain outside this narrow proof. The effective search path must put `pg_catalog` first when both snapshots are read; otherwise custom operators or types could invalidate the interpretation of deparsed expressions. Missing search-path evidence remains unsupported. A same-shaped replacement table does not establish that the original data was retained.

Comparison uses the backing table's actual defaults, nullability, generated identifiers, constraints, indexes and sequence ownership. PostgreSQL JDBC's `smallserial`/`serial`/`bigserial` result aliases are normalized to their physical integer types using its storage-type and generation metadata; identifier generation is still checked separately against the catalog. These still receive the ordinary compatibility checks. Only the proven view's relation-kind/definition noise and its supported `CREATE VIEW` effect are removed; other migration effects, including DML, remain visible. `RELATION_RENAME_EXPRESSION_UNPROVEN` retains `UNKNOWN` for unmodeled stored expressions that can observe the name change, including defaults or CHECKs on other tables. For example, an unchanged `pg_get_serial_sequence('accounts','id')` default can return NULL once `accounts` names a view. Keep such expressions independent of the changed relation name or establish their behavior separately. Runtime relation identifiers are excluded from report and cache fingerprints.

`VIEW_WRITE_UNSUPPORTED` identifies an old write that the replacement view cannot perform, using `pg_relation_is_updatable` with trigger support included and a baseline-valid contract entry. Column capability checks use `pg_column_is_updatable`; `VIEW_COLUMN_WRITE_UNPROVEN` remains `UNKNOWN` because an unsupported column flag alone does not prove failure for an explicit `DEFAULT` assignment. Successful planning alone does not prove equivalence for filtered, computed, trigger-backed, or otherwise opaque views, which remain `UNKNOWN` where no concrete failure is established. Preserve the original relation and a direct compatible projection during the rollout, or provide separate behavioral evidence for unsupported view logic.

## Operational migration assessment

The measured operational result is independent of compatibility: `SAFE`, `RISKY`, or `UNKNOWN` within a PostgreSQL model of scans that block ordinary writes and rewrites of preexisting storage. A structurally compatible migration can be operationally RISKY. Known risk dominates UNKNOWN, which dominates SAFE. This does not predict production duration, lock waits, workload interference, permissions, storage capacity, or every deployment hazard.

For each pending statement, Flyway callbacks retain the exact SQL and a one-based statement ordinal within its migration. Epoch samples the same migration connection's autocommit state, granted relation locks before/after execution, newly observed locks, and `lock_timeout`. Catalog identity establishes whether surviving physical storage was rewritten and whether a relation existed at baseline, including through renames. SQL recognition must be corroborated by actual catalog/session observations; an empty diff or lock sample is never sufficient for SAFE. Flyway batching is disabled so samples correspond to individual executed statements. OIDs, storage identifiers, process IDs, timestamps, and durations stay outside the portable operational report.

After an autocommit statement, its locks may already be released. Ordinary index builds still have known write-blocking scan semantics. Index locks remain distinct from table locks. Before-statement `ShareLock`, `ShareRowExclusiveLock`, `ExclusiveLock`, and `AccessExclusiveLock` on old tables can remain held from earlier statements while a later scan runs. Epoch checks these retained locks across the migration session, including locks on other old relations; inspecting only newly acquired locks would miss that risk.

Only `old-on-new` executes the deployment sequence and contributes operational evidence. Reverse schema checks and the new-writes/old-reads assessment do not execute deployment or down migrations. Baseline construction is outside the pending operational window. A completed, known-empty pending window is operational SAFE with an explicit scope note; an older report with missing measurements is unavailable. Failed SQL, broken observation callbacks, lost connections, and interrupted runs remain execution failures (exit 3), with only completed checkpoint evidence retained.

### Operational rules

Every operational finding includes migration identity, statement ordinal, object, rule, evidence, and a mitigation when applicable. RISKY findings also include size-based severity. These rules do not alter structural or data-compatibility findings.

| Rule | Evidence and response |
| --- | --- |
| `OP_TABLE_REWRITE` | RISKY: the same preexisting physical relation has changed storage identity. Use expand/backfill/contract or an explicit rewrite maintenance plan. |
| `OP_NOT_NULL_SCAN` | RISKY: a previously nullable column has no supported prior validated non-null CHECK, so SET NOT NULL validates rows under AccessExclusiveLock. Install a supported CHECK NOT VALID, release strong locks, validate, then set NOT NULL. |
| `OP_INDEX_BUILD_BLOCKS_WRITES` | RISKY: a catalog-confirmed ordinary index build scans old data while blocking writes, even when autocommit leaves no sampled lock afterward. Use supported CREATE INDEX CONCURRENTLY in a nontransactional migration. |
| `OP_CONSTRAINT_VALIDATION_BLOCKS_WRITES` | RISKY: immediate CHECK/FK validation affects old data or referenced relations under its write-blocking validation locks. Separate installation with NOT VALID from later validation. |
| `OP_VALIDATION_UNDER_COMPOUND_LOCK` | RISKY: a recognized scan shares one compound ALTER with a confirmed subcommand requiring a strong table lock. PostgreSQL acquires the strongest required lock before its subcommands; split the work across committed transactions. |
| `OP_VALIDATION_UNDER_RETAINED_LOCK` | RISKY: an old table is already held under a write-blocking lock when a recognized scan/build runs. Release that lock before the scan, usually through separate transactions. This also matters when the scan's target is new. |
| `OP_ONLINE_INDEX_BUILD` | SAFE: a supported concurrent build actually ran nontransactionally, produced a valid/ready index, and had no retained old-table blocking lock. Preserve that execution mode; long-transaction waits and I/O still need production planning. |
| `OP_DEFERRED_CONSTRAINT_VALIDATION` | SAFE: supported NOT VALID installation is catalog-confirmed to skip the initial validation scan. Schedule its later validation separately; this does not assert compatibility with old writers. |
| `OP_NONBLOCKING_VALIDATION` | SAFE: a supported previously unvalidated constraint became valid under normal validation semantics, without an old-table blocking lock held before the scan. Preserve the transaction boundary that made this possible. |
| `OP_VALIDATED_NOT_NULL` | SAFE: a prior supported validated scalar non-null CHECK establishes that SET NOT NULL needs no validation scan. Establish that proof before the statement; a composite-row predicate is insufficient. |
| `OP_NEW_RELATION_OPERATION` | SAFE: a supported operation affects new-window storage, with relevant existing-relation and retained-lock effects accounted for. Do not apply this exemption to opaque SQL merely because its named target is new. |
| `OP_METADATA_ONLY` | SAFE: a supported catalog change, literal lock-timeout setting, or positively established no-op needs no scan/build within the model. Retain the exact supported form; a measured rewrite still makes the statement RISKY. |
| `OP_STATEMENT_UNSUPPORTED` | UNKNOWN: SQL meaning, physical identity, catalog corroboration, or a required scan/effect proof is incomplete. Review the specific gap or add supported evidence; no observed rewrite alone does not prove no scan. |
| `OP_DATABASE_CALLBACK_UNPROVEN` | UNKNOWN: enabled event triggers applicable to this command can execute unmodeled procedural work. Review those callbacks separately; unrelated command-tag restrictions do not automatically contaminate every statement. |

Supported metadata changes include ordinary renames, nullable built-in column additions with no default or a supported constant default, supported constant-default changes, DROP DEFAULT, and DROP NOT NULL. Known no-ops are corroborated by prior and resulting catalogs. Supported index/constraint proofs exclude unmodeled expressions, custom types/operators, and other unsupported effects. Procedural `DO`, arbitrary DML, transaction-control SQL, and other unrecognized operations remain UNKNOWN unless measured rewrite/blocking evidence establishes RISKY. Existing-table type changes without a heap rewrite remain UNKNOWN when possible validation or index work is unproven. Partition/inheritance and opaque callback paths retain their limits.

A positive staged pattern uses distinct committed migrations:

```sql
-- V201__install_check.sql
ALTER TABLE accounts ADD CONSTRAINT accounts_email_nn
  CHECK (email IS NOT NULL) NOT VALID;

-- V202__validate_check.sql: runs after V201 commits and releases its lock
ALTER TABLE accounts VALIDATE CONSTRAINT accounts_email_nn;

-- V203__set_not_null.sql: the validated CHECK now supplies the scan-skip proof
ALTER TABLE accounts ALTER COLUMN email SET NOT NULL;
```

Validation must succeed against the supplied baseline data. Combining installation and validation in one transaction retains the installation's strong lock and is operationally RISKY. A single compound ALTER combining validation with another strong-lock subcommand is also RISKY: PostgreSQL acquires the strongest required lock before running the subcommands. These statements still receive independent compatibility checks, which may reject tighter old-writer inputs. For concurrent indexes, keep `executeInTransaction=false` in the migration's `.sql.conf` sidecar; Epoch uses Flyway's session-level advisory lock so its own lock transaction does not obstruct the build.

### Timeout evidence and size grading

Timeout facts record milliseconds, PostgreSQL's reported source, and origin: `VERIFIER`, `MIGRATION_SESSION`, `MIGRATION_LOCAL`, or `UNKNOWN`. A recognized literal SET/SET LOCAL can establish authored scope; zero means no lock timeout. The disposable connection's injected timeout is a test-harness safeguard, not evidence that deployment migrations configure one. Outer observations cannot establish an opaque procedure's internal settings. A timeout never downgrades a known blocking scan or rewrite to SAFE.

Optional [table-size metadata](ci.md#table-size-estimates) grades RISKY findings as LOW, MEDIUM, or HIGH. Both row and byte estimates are required to lower severity; missing estimates default to HIGH. Renamed old relations use their original baseline names for lookup. Fixture sizes are never substituted for production estimates, and grading does not predict duration or change the verdict. `--fail-on-operational` optionally requires operational SAFE after the compatibility gate passes; see [CI policy](ci.md#operational-ci-gate).

## Unsupported or conservative areas

- **Native SQL:** supported fixed explicit-column templates use SQL aliases for physical dependencies and explicit types or unambiguous PostgreSQL inference. Unknown types freeze only after PostgreSQL resolves them at baseline. Wildcards, nested scopes, ambiguous aliases, positional inserts, conflict-update variants, and opaque function/sequence calls remain unresolved; simplify or explicitly enumerate those access paths instead of changing completeness flags. `POSTGRES_SQL_REJECTED` supplies `BREAKING` evidence only when PostgreSQL accepted the same contract SQL at baseline. Successful planning does not prove arbitrary native hydration or behavior.
- **SQL dependency coverage:** supported projection, join predicate, filter, `ORDER BY`, `GROUP BY`, `DISTINCT ON`, and DML `RETURNING` references participate in invalidation. Implicit `NATURAL`/`USING` joins, ambiguous grouping aliases, window expressions, pagination expressions, and set-operation scopes remain incomplete and therefore always re-plan. Native SQL uses the verifier's public-schema model; `hibernate.default_schema` does not establish a native search path. Use explicitly qualified native relations for non-public schemas; unqualified names under a non-public ORM default stay unresolved.
- **Dynamic queries:** only explicitly supplied fixed HQL variants can be translated. Unenumerated branches, collection-valued parameters, dynamic mutation plans, and runtime-dependent predicates remain unresolved.
- **Hibernate features:** supported collection-table CRUD and identity/sequence dependencies are extracted. Filters/restrictions, formulas, custom SQL, column transformers, unsupported collection strategies, and other unrecognized plans remain unresolved. Recording a generator name alone does not prove its allocation behavior.
- **Partial bootstrap:** `extract --keep-going` can retain healthy entities in a connected graph by excluding a failed entity and its incoming associations, including inherited properties and explicit association targets. Every omitted entity and concrete property path is unresolved, so `INCOMPLETE_COVERAGE` and `UNRESOLVED_QUERY` prevent a partial contract from becoming SAFE. Recovery is bounded and refuses a shared generic override that would silently remove a healthy association. Fix the original mapping and re-extract to restore complete evidence.
- **Query translation failures:** each template has its own recovery boundary. Selection aliases are traversed through their selected expression; cyclic references remain incomplete rather than recursing indefinitely. A failed query retains its source ID/call site and cause, with the original stack available under `--verbose`; healthy siblings remain usable. This does not convert an incomplete contract into SAFE. Correct the failing query or add supported translation/dependency evidence, then re-extract. Cancellation and fatal VM failures still fail the command.
- **Database behavior:** user functions, procedures, triggers, views, row-level security policies, sequences, defaults, generated expressions, and constraint implications can change behavior beyond the structural model. Existing user-defined behavior can prevent a safe conclusion when the schema changes. Views remain usable relations for SQL planning; only the narrowly proven transparent-view case above establishes compatibility through a replacement view. Relevant sequences and enum types have structured dependencies; unrelated owned sequences do not create global noise. Behavior objects owned by extensions are excluded using `pg_depend.deptype = 'e'`; merely installing pgcrypto or pg_trgm does not create thousands of generic behavior findings. User-defined functions that call extensions remain visible. Extension additions/removals/version changes are recorded separately and conservatively yield `UNKNOWN`.
- **Identifiers:** physical names are truncated at PostgreSQL's 63-byte UTF-8 boundary before catalog matching. `PostgresIdentifierIntegrationTest` verifies an entity whose original table name is 67 characters against the actual truncated PostgreSQL catalog name. Schema and table names must be representable unambiguously as `schema.table`; names containing embedded dots are unsupported and produce `UNKNOWN`.
- **Type and hydration semantics:** successful SQL preparation does not prove every bound value is accepted, every row can be hydrated, or every application expectation is preserved.
- **Nullability evidence:** contracts distinguish `declaredNonNull` from raw `hibernateNullable`. Kotlin metadata and explicit Java bytecode annotations describe the mapped logical member; unknown Java/generic declarations remain unknown, and container or association nullability is not copied onto JDBC leaf columns. These facts do not prove arbitrary converter output or replace the actual baseline's accepted-input constraints.
- **Enum and converter evidence:** ordered constants, recognized enum encodings, aligned database values, and converter class/fingerprint facts are recorded separately. Hibernate 7 explicit enum codes are not assumed to be Java names or declaration ordinals. Arbitrary converter semantics remain unsupported; a class-byte fingerprint alone proves neither equivalent behavior nor a breaking format change.
- **Operational migration safety:** the separate measured assessment above covers supported write-blocking scans and storage rewrites. Production lock duration, workload concurrency, permissions, storage pressure, and down-migration behavior remain outside that proof. The optional static `lint` command is unchanged; its hints are separate from measured findings. Verification deadlines bound the disposable run and do not establish a production timeout policy.
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
