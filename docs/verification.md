# Verification and its boundaries

Epoch asks a bounded question: do the old SQL and mappings in this explicit contract retain their supported structural dependencies after each migration? It does not prove equivalence of arbitrary programs or SQL behavior.

## Database workflow

1. Start a disposable PostgreSQL 16.8 container.
2. Construct the baseline using Flyway, or restore a validated baseline cache into the fresh container.
3. Inspect baseline health once, including old SQL validity; retain preexisting findings without pending-migration attribution.
4. Apply pending versioned migrations in Flyway version order, taking a catalog snapshot and checking the old contract at every step.
5. Compare every step to the original baseline, remove duplicated baseline findings from migration results, and aggregate compatibility and any explicitly accepted-baseline gate.

Catalog inspection provides the actual schema after PostgreSQL executes a migration. SQL parsing is a conservative classifier for unsupported migration effects; it is not a replacement for executing the migration. The SQL planner uses PostgreSQL `PREPARE` and `EXPLAIN` with declared parameter types and null argument values; it does not use `EXPLAIN ANALYZE` or run the application DML. PostgreSQL protocol Describe also checks declared result labels and types without executing the operation. Successful planning does not establish accepted values or result semantics.

Baseline and pending directories must be different. Flyway validates migration names and checksums and owns migration ordering; placeholder replacement is disabled. Use self-contained SQL migrations for this workflow. Automatic Flyway SQL lifecycle callbacks are disabled; discovered callback files produce `UNKNOWN` because their effects are not verified. When there are no pending migrations, verification checks the unchanged baseline rather than silently returning success without a contract check.

The current SQL effect classifier recognizes straightforward structural additions/renames, table/index creation, and index drops. Other effects, including table/column drops and type rewrites with possible data loss, receive an additional `UNKNOWN` finding. A known broken dependency still wins over that unknown finding in the aggregate verdict.

Reports place preexisting problems in `baseline.findings` with `migration: null`; they are not repeated for every pending migration. The overall compatibility verdict still includes baseline health. A reviewed accepted-baseline policy can change the separate migration gate, but it does not turn unproven compatibility into proof.

The baseline must reproduce the schema against which the old contract was valid. A mapping or query that was already invalid at baseline cannot support a `SAFE` conclusion. The disposable database starts without a copy of production data; data-specific failures and effects require separate tests with representative data.

## Verdicts

| Change or evidence | Result within the supported model |
| --- | --- |
| Add a nullable column with no default/generated expression, while old operations use explicit columns | Can be `SAFE`. |
| Drop or rename a table/column used by the old contract | `BREAKING`; the old SQL name is gone. |
| Add a `NOT NULL` column without default/identity/generated expression that old inserts omit | `BREAKING`. |
| SQL valid at baseline becomes invalid after a migration | A breaking SQL finding when the PostgreSQL failure establishes incompatibility. |
| Change a referenced type or nullability | `UNKNOWN` unless another check establishes a break. |
| Add/change constraints, indexes, defaults, or generation behavior | `UNKNOWN` where supported rules cannot prove equivalent accepted inputs or behavior. |
| Change result shape for positional SQL or `SELECT *` | Cannot be assumed safe. |
| Data-changing or procedural migration effects | `UNKNOWN`, even if table/column definitions are unchanged. |
| Incomplete query dependencies, unresolved query sources, or observations alone | `UNKNOWN`. |

These are rules for the declared scope, not assurances about every side effect of a DDL statement. Independent safe findings do not cancel unknown or breaking findings. The report retains intermediate failures even if a subsequent migration restores the original schema.

## Unsupported or conservative areas

- **Native SQL:** explicit descriptor parameter types enable PostgreSQL preparation and result inspection. Complete dependency and behavioral proofs are still unavailable; named native queries remain structurally unresolved.
- **Dynamic queries:** only explicitly supplied fixed HQL variants can be translated. Unenumerated branches, collection-valued parameters, dynamic mutation plans, and runtime-dependent predicates remain unresolved.
- **Hibernate features:** collection operations, generated identifier allocation, filters/restrictions, formulas, custom SQL, column transformers, and unsupported mutation or query plans require additional analysis. Recording their names is not proof of compatibility.
- **Database behavior:** user functions, procedures, triggers, views, row-level security policies, sequences, defaults, generated expressions, and constraint implications can change behavior beyond the structural model. Existing user-defined behavior can prevent a safe conclusion when the schema changes. Behavior objects owned by extensions are excluded using `pg_depend.deptype = 'e'`; merely installing pgcrypto or pg_trgm does not create thousands of generic behavior findings. User-defined functions that call extensions remain visible. Extension additions/removals/version changes are recorded separately and conservatively yield `UNKNOWN`.
- **Identifiers:** schema and table names must be representable unambiguously as `schema.table`; names containing embedded dots are unsupported and produce `UNKNOWN`.
- **Type and hydration semantics:** successful SQL preparation does not prove every bound value is accepted, every row can be hydrated, or every application expectation is preserved.
- **Operational migration safety:** lock duration, online index creation, deployment ordering, concurrency, permissions, migration runtime, storage pressure, and rollback policy are outside this check.
- **Application coverage:** no whole-application guarantee follows from entity discovery or a test run. Additional JDBC paths, application authorization assumptions, and custom query-generation code must be accounted for separately.

`UNKNOWN` is an explicit limit of the evidence. Resolve it through a more precise contract, added implementation support, or separate behavioral review/tests. It is not an instruction to turn off the rule.

## Reproducibility

Retain the old compiled application artifacts, extraction descriptor, generated contract, baseline migrations, pending migrations, and report together. Preserve the Gradle wrapper and pinned dependencies. Inferred PostgreSQL parameter types are fixed at baseline, so a migrated column cannot silently redefine the old binding contract. Retain accepted-baseline policies and their justifications alongside reports when used. Use the same PostgreSQL version when reproducing a result, and review the contract whenever application mappings, query definitions, or naming strategies change.

This tool accepts no arbitrary target database connection. Migrations still execute SQL in the disposable database, so use trusted migrations and a Docker daemon appropriate for CI work.
