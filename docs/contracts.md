# Descriptors and database contracts

## Extraction descriptor

`extract` consumes an `ExtractionRequest` JSON document and loads already compiled application classes. It can discover annotated entities in selected packages; it does not compile the application or discover every repository/query source.

```json
{
  "applicationVersion": "orders-1.0.0",
  "entityClasses": [],
  "scanPackages": ["com.example.orders.entity"],
  "classpath": ["../build/classes/kotlin/main", "../build/resources/main", "../lib/application-dependencies.jar"],
  "hibernateProperties": {
    "hibernate.default_schema": "public",
    "hibernate.type.preferred_uuid_jdbc_type": "UUID"
  },
  "entityOverrides": {
    "com.example.orders.entity.CustomerProjection": {"operations": ["SELECT"]},
    "com.example.orders.entity.NativeResult": {"resultMappingOnly": true},
    "com.example.orders.entity.UnusedEntity": {"exclude": true}
  },
  "hqlQueries": {"Customer.byEmail": "select c from Customer c where c.email = :email"},
  "nativeQueries": {"Customer.nativeById": "select id, email from customers where id = ?"},
  "nativeQueryParameterTypes": {"Customer.nativeById": ["bigint"]},
  "finiteHqlVariants": {},
  "unresolvedDynamicQueries": {},
  "keepGoing": false
}
```

Replace the illustrative classes and paths with your application's artifacts. Classpath entries are individual directories or JARs, resolved relative to the descriptor file. Include mapping dependencies, embeddables, converters, and naming strategies. `scanPackages` inspects class-file annotations without initializing the classes. It scans only the supplied classpath entries and combines discovered entities with explicitly listed `entityClasses`.

| Field | Meaning |
| --- | --- |
| `entityClasses` | Fully qualified annotated entity names; defaults to `[]`. Supply classes, scan packages, or both. |
| `scanPackages` | Package roots to scan recursively for `jakarta.persistence.Entity`; requires an explicit classpath. |
| `classpath` | JARs/class directories, relative to the descriptor; defaults to `[]`. |
| `applicationVersion` | Release identifier retained in the contract/report; defaults to `unspecified`. |
| `physicalNamingStrategy`, `implicitNamingStrategy` | Optional fully qualified Hibernate naming strategy classes. |
| `hibernateProperties` | String-to-string Hibernate settings, including type preferences and default schema. Offline safeguards override or reject connection, schema-generation, validation, and dialect settings. |
| `entityOverrides` | Class name or JPA entity name to an operation/scope override, described below. |
| `hqlQueries` | Stable query ID to a fixed SELECT HQL query. |
| `nativeQueries` | Stable query ID to native SQL retained as incomplete structural evidence. |
| `nativeQueryParameterTypes` | Query ID or registered named-native-query name to ordered PostgreSQL/JDBC parameter type names. |
| `finiteHqlVariants` | Source ID to a finite list of fixed SELECT HQL alternatives. Exhaustiveness is the caller's assertion. |
| `unresolvedDynamicQueries` | Source ID to the reason its query variants cannot be enumerated. |
| `keepGoing` | Continue independent mapping groups after bootstrap failures and record failures as unresolved; defaults to `false`. The CLI's `--keep-going` enables it too. |

The adapter uses **Hibernate 6.6.13.Final**. It checks Hibernate artifacts on the supplied classpath before bootstrap and rejects a different or unidentifiable version with a clear error. It does not isolate and run Hibernate 7, and it does not silently substitute its bundled version for an incompatible supplied runtime. Include the application's actual dependencies so this check can detect a mismatch; removing an incompatible Hibernate JAR is not a compatibility fix.

Extraction uses the PostgreSQL **16.8** dialect, disables JDBC metadata access and schema generation, and sets `jakarta.persistence.validation.mode=none`. It obtains physical mappings, static mutation SQL, single-ID loader SQL, and translated HQL through Hibernate APIs. Registered named HQL and native queries are included. Synthetic Hibernate discriminator placeholders do not become physical columns. Formula selectables are omitted from physical dependencies and recorded as unresolved where their SQL dependencies remain unproven.

## Entity overrides and partial extraction

`operations` accepts a nonempty subset of `SELECT`, `INSERT`, `UPDATE`, and `DELETE`. Immutable Hibernate entities default to `SELECT`; override this explicitly if the intended contract must include other operations. Excluding an operation narrows the declared scope and is recorded in coverage notes; it is not proof that the application never performs that operation.

`resultMappingOnly: true` retains a class in Hibernate bootstrap for query result mappings but omits entity CRUD and table-dependency claims. Named queries still carry their own evidence and limitations. `exclude: true` removes the class from bootstrap. Associations to an excluded entity remain errors or unresolved dependencies; Epoch does not prune them to manufacture completeness.

Without `--keep-going`, a failed bootstrap or missing compiled class is a runtime failure. With it, Epoch isolates failures across association/inheritance components using a bounded retry budget. Failed components and anything left after the retry budget become unresolved entries. Successful independent groups can still produce useful SQL, but partial extraction always has incomplete coverage. A global Hibernate version mismatch still fails before bootstrap. Fatal VM failures are not treated as recoverable mapping errors.

## Contract schema version 1

The JSON model is defined by [`Model.kt`](../core/src/main/kotlin/dev/epoch/core/Model.kt). Enum values are case-sensitive.

| Top-level field | Meaning |
| --- | --- |
| `schemaVersion` | Integer format version; currently `1`. |
| `applicationVersion` | Identity of the old application release. |
| `dialect` | `PostgreSQL`; other dialects are unsupported. |
| `scope` | `STRUCTURAL_CONTRACT`. `WHOLE_APPLICATION` remains `UNKNOWN`. |
| `entities` | Mapped table/column dependencies and declared operations. |
| `queries` | SQL templates with explicit dependency and type information. |
| `coverage` | Completeness within the declared scope, mechanisms, and qualifications. |
| `unresolvedQueries` | Stable source IDs and reasons coverage remains unresolved. |

Entity entries contain a unique `id`, a schema-qualified `table`, `columns`, and an operation set. A column records its physical `name`, optional `typeName` and `nullable`, and `insertable`, `updatable`, and `generated` flags. `generatedIdentifier` records a generator description; it does not prove allocation semantics.

Query entries record:

- `id`, `operation`, and `sql` with JDBC `?` placeholders rather than captured parameter values.
- `tables`, `columns`, and `writeColumns`, using physical names. Optional `targetTable` disambiguates writes such as `INSERT ... SELECT` that also read other tables.
- Ordered `parameterTypes` and optional `resultColumns` with labels/types. The declared parameter count must match JDBC placeholders. `DOUBLE` maps to `double precision`; `TINYINT` maps to `smallint`. PostgreSQL resolves `OTHER` from query/column context when possible, including UUID, JSON, and custom types. Ambiguous types remain `UNKNOWN` and need an explicit declaration. Inferred baseline types are frozen when checking later schemas.
- `source`: `HIBERNATE_MAPPING`, `NAMED_HQL`, `NAMED_NATIVE`, `STATIC`, `RUNTIME_OBSERVED`, or `DYNAMIC_VARIANT`.
- `verificationStatus`: `EXTRACTED`, `OBSERVED`, or `UNSUPPORTED`.
- `dependenciesComplete`, `explicitColumnList`, `exactResultShape`, and optional `variant`.

Native parameter declarations allow PostgreSQL preparation and type checking. They do **not** establish complete native-query dependencies, hydration, or behavioral equivalence; these remain unresolved. An old native statement that PostgreSQL accepts at baseline and rejects after migration can still supply concrete breaking evidence.

Entity/query IDs must be unique within a contract. Keep them stable across releases. Multiple input contracts are namespaced as `contract[1]/...`, `contract[2]/...`, and so on, in input order. Their application versions and scopes are retained in coverage notes.

## What completeness means

`coverage.complete` applies to the **declared** contract, not every database access in the application. `coverage.mechanisms` and `coverage.notes` record how evidence was obtained and its scope. A global flag cannot override an incomplete query, observed-only evidence, unresolved source, or unsupported scope. An empty contract cannot prove compatibility.

Do not change completeness flags or delete unresolved entries just to obtain `SAFE`. Package scanning discovers entities; it does not discover repository methods, Criteria builders, arbitrary JDBC, or unlisted runtime query sources. Finite variants must include every relevant fixed alternative; a nonempty list does not establish that application control flow has no other variants.

`dev.epoch.hibernate.SqlCollector` implements `StatementInspector`. Install it with `hibernate.session_factory.statement_inspector` in integration tests. `statements()` returns sorted distinct SQL, `queries()` creates observed incomplete entries, and `clear()` resets capture. It does not collect bind values or prove exhaustive coverage. Reports mention this limitation when the contract actually contains observed SQL.

## Report and accepted-baseline schemas

A report contains `schemaVersion`, `applicationVersion`, `scope`, `verdict`, `baseline`, ordered `migrations`, `ruleSummary`, and `limitations`. `baseline` contains health `verdict`, `findings`, a deterministic `fingerprint`, and optional `cacheStatus`/`cacheKey`. Baseline findings have `migration: null`; they appear once and are not attributed to pending migrations. With no pending migrations, `migrations` is empty and the overall verdict reflects baseline health.

Each migration contains its version/filename, delta verdict, and findings. Each finding has `ruleId`, `objectName`, `message`, `evidence`, `affectedContractIds`, optional migration attribution, and optional mitigation. `ruleSummary` groups counts by rule and verdict: `baselineCount`, `migrationCount`, and `acceptedBaselineCount`.

The overall `verdict` includes baseline health. Explicit baseline acceptance adds `gateVerdict` and `acceptedBaseline` without changing that compatibility verdict. For example, an accepted unresolved baseline may yield `verdict: UNKNOWN` and `gateVerdict: SAFE` when no new migration issue is found. This is a reviewed policy gate, not a new proof of compatibility.

An acceptance file has `schemaVersion: 1`, `baselineFingerprint`, and `findings`, each with a finding `fingerprint` and a required nonblank `justification`. The fingerprint binds acceptance to the contract, baseline files/settings, catalog, and baseline evidence. Stale, unknown, duplicated, or unjustified entries are rejected. Pending-migration findings cannot be accepted through this file. See [CI usage](ci.md) for the generation and review workflow.
