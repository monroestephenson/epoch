# Descriptors and database contracts

## Extraction descriptor

`extract` consumes an `ExtractionRequest` JSON document and loads already compiled application classes. It discovers annotated entities and inventories supported repository/database call sites in compiled Java/Kotlin bytecode. It does not compile or start the application. Discovery defaults on; unsupported or dynamic access paths remain unresolved.

```json
{
  "applicationVersion": "orders-1.0.0",
  "entityClasses": [],
  "scanPackages": ["com.example.orders.entity"],
  "repositoryPackages": ["com.example.orders"],
  "discoverRepositories": true,
  "runtimeSqlFiles": [],
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

Replace the illustrative classes and paths with your application's artifacts. Classpath entries are individual directories or JARs, resolved relative to the descriptor file. Include mapping dependencies, embeddables, converters, and naming strategies. Missing classpath directories are ignored with a coverage warning, so ordinary absent Gradle output directories do not abort extraction. `scanPackages` inspects class-file annotations without initializing the classes. It scans only the supplied classpath entries and combines discovered entities with explicitly listed `entityClasses`.

| Field | Meaning |
| --- | --- |
| `entityClasses` | Fully qualified annotated entity names; defaults to `[]`. Supply classes, scan packages, or both. |
| `scanPackages` | Package roots to scan recursively for `jakarta.persistence.Entity`; requires an explicit classpath. |
| `classpath` | JARs/class directories, relative to the descriptor; defaults to `[]`. Include the application's actual Hibernate and transitive runtime dependencies. Missing paths produce warnings. |
| `repositoryPackages` | Package roots for repository/database bytecode inventory. Falls back to `scanPackages`; select application packages that include DAOs and services as well as entities. |
| `discoverRepositories` | Defaults to `true`. An absent/insufficient scan scope is unresolved. Set `false` only for an intentionally limited entity/descriptor contract; this narrowed scope is recorded. |
| `runtimeSqlFiles` | JSONL observation files from `SqlCollector`, resolved relative to the descriptor. Observations supplement discovery without proving exhaustive coverage. |
| `applicationVersion` | Release identifier retained in the contract/report; defaults to `unspecified`. |
| `physicalNamingStrategy`, `implicitNamingStrategy` | Optional fully qualified Hibernate naming strategy classes. |
| `hibernateProperties` | String-to-string Hibernate settings, including type preferences and default schema. Offline safeguards override or reject connection, schema-generation, validation, and dialect settings. |
| `entityOverrides` | Class name or JPA entity name to an operation/scope override, described below. |
| `hqlQueries` | Stable query ID to fixed HQL. SELECT and supported single-table INSERT/UPDATE/DELETE translations use Hibernate's SQL AST; unsupported mutation plans remain unresolved. |
| `nativeQueries` | Stable query ID to native SQL retained as incomplete structural evidence. |
| `nativeQueryParameterTypes` | Query ID or registered named-native-query name to ordered PostgreSQL/JDBC parameter type names. |
| `finiteHqlVariants` | Source ID to a finite list of fixed HQL alternatives. Exhaustiveness is the caller's assertion. |
| `unresolvedDynamicQueries` | Source ID to the reason its query variants cannot be enumerated. |
| `keepGoing` | Isolate failed entities and recover supported associations within mapping graphs; record every omission as unresolved; defaults to `false`. The CLI's `--keep-going` enables it too. |

The bundled runtime is **Hibernate 6.6.13.Final**. When the classpath supplies Hibernate 6.6 or 7.x, Epoch loads the application's Hibernate, JPA API, mapping classes, and dependencies in an isolated classloader. No Hibernate objects cross into the bundled runtime, and missing Hibernate/JPA classes never fall back to bundled classes. The artifact version must agree with the runtime's own version; conflicting or incomplete runtimes fail clearly. Version-sensitive SPI accessors bridge changes in Hibernate 7's query implementation, SQL translation context, and loader APIs. The regression suite exercises real **6.6.13.Final, 7.0.10.Final, 7.1.4.Final, and 7.4.9.Final** runtimes. Other 6.6/7.x releases use the same adapter; unsupported SPI/mapping features fail or become explicitly unresolved rather than silently substituting another Hibernate version.

Extraction uses the PostgreSQL **16.8** dialect, disables JDBC metadata access and schema generation, and sets `jakarta.persistence.validation.mode=none`. It obtains physical mappings, static mutation SQL, single-ID loader SQL, and translated HQL through Hibernate APIs. Physical SQL aliases are resolved to their own tables, including JOINED inheritance and secondary-table dependencies. PostgreSQL identifier matching folds unquoted names and truncates at 63 UTF-8 bytes without splitting a code point. Basic collection persisters contribute real loader, insert/update/delete-row, and delete-all plans for join tables and `@ElementCollection`. One-to-many/custom collection plans and optional-secondary-table MERGE SQL remain unresolved where unsupported. Registered named HQL and native queries are included. Synthetic Hibernate discriminator placeholders do not become physical columns. Formula selectables are omitted from physical dependencies and recorded as unresolved where their SQL dependencies remain unproven.

## Entity overrides and partial extraction

`operations` accepts a nonempty subset of `SELECT`, `INSERT`, `UPDATE`, and `DELETE`. Immutable Hibernate entities default to `SELECT`; override this explicitly if the intended contract must include other operations. Excluding an operation narrows the declared scope and is recorded in coverage notes; it is not proof that the application never performs that operation.

`resultMappingOnly: true` retains a class in Hibernate bootstrap for query result mappings but omits entity CRUD and table-dependency claims. Named queries still carry their own evidence and limitations. `exclude: true` removes the class from bootstrap. Associations to an explicitly excluded entity remain errors or unresolved dependencies.

Without `--keep-going`, a failed bootstrap or missing compiled entity class is a runtime failure. With it, Epoch first attempts to narrow failure within a connected graph: it omits failed entities and uses extraction-only ORM XML overrides to omit associations to them, including inherited mapped-superclass properties and explicit `targetEntity` mappings. Every excluded entity and each retained entity's omitted property path becomes unresolved; healthy associations stay mapped, and application class files are unchanged. A shared generic superclass override is refused when it would also erase a healthy specialization. Inheritance dependents remain grouped. This phase has a 32-attempt/eight-excluded-entity bound, including inheritance dependents; a further 64-attempt component-isolation fallback handles unsupported recovery cases. Successful retained mappings still produce useful SQL, but partial extraction always has incomplete coverage. Incomplete or conflicting Hibernate runtimes still fail before bootstrap. Fatal VM failures are not treated as recoverable mapping errors.

Sparse overrides preserve the mapping's unambiguous field or property access, including Hibernate 7.4.9's XML model requirements. Ambiguous access or an override that would erase a healthy specialization is unsupported and falls back to component isolation. Recovery tries individual exclusions before combinations so a diagnostic naming a healthy caller does not unnecessarily discard it.

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
| `accessPaths` | Per-method evidence: `STATIC`, `DECLARED`, `OBSERVED`, or `UNRESOLVED`, with method identity, mechanism, query IDs, reason, and optional source line. |

Entity entries contain a unique `id`, a schema-qualified `table`, `columns`, and an operation set. A column records its physical `name`, optional `typeName` and `nullable`, and `insertable`, `updatable`, and `generated` flags. Columns also retain Hibernate's `length`, `precision`, `scale`, finite relational `enumValues`, `logicalJavaType`, and `inputDomainComplete` when available. DDL bounds alone are not proof of a logical input domain: a VARCHAR-backed UUID is not an arbitrary string. Custom converters/JDBC types or unknown logical domains set `inputDomainComplete=false`; rules requiring those domains remain `UNKNOWN`. This does not invalidate an otherwise complete, unchanged structural SQL contract. Primitive properties do not claim nullable application inputs. `generatedIdentifier` records the strategy; `identifierSequence` and `identifierAllocationSize` retain actual sequence dependencies. Identity/serial generation and physical sequences with allocationSize 1 are checked against PostgreSQL. Pooled and custom identifier allocation remain unresolved; a preassigned sequence value remains an explicit INSERT column.

Nullability has distinct sources:

| Column field | Evidence |
| --- | --- |
| `declaredNonNull` | `true` for an established non-null Java/Kotlin member, `false` for an explicitly nullable member, and `null` when unknown. Kotlin metadata is matched by JVM signature to Hibernate's actual field/getter; Java declaration annotations are read from bytecode, including CLASS retention. |
| `hibernateNullable` | Hibernate's raw physical selectable nullability, independently of the source language. |
| `nullable` | The retained legacy mapping flag, including its primitive-property adjustment. Existing contracts may omit the newer facts. |

For example, Kotlin `var x: String` can have `declaredNonNull=true` and `hibernateNullable=true`, while `var y: String?` can have `declaredNonNull=false` even if `@Column(nullable=false)` makes `hibernateNullable=false`. Unannotated Java references, ambiguous metadata, and unresolved generic types do not imply nullable inputs. Declared facts attach to supported basic attributes and basic identifiers; the nullability of an association, component, or collection object does not establish each underlying JDBC column's nullability. A declared member fact also does not prove an arbitrary converter's SQL output. Missing nullability evidence does not by itself invalidate complete SQL extraction.

Query entries record:

- `id`, `operation`, and `sql` with JDBC `?` placeholders rather than captured parameter values.
- `tables`, `columns`, and `writeColumns`, using physical names. Optional `targetTable` disambiguates writes such as `INSERT ... SELECT` that also read other tables.
- Ordered `parameterTypes` and optional `resultColumns` with labels/types. When supplied, the declared parameter count must match JDBC placeholders. Missing native/runtime parameter types can be inferred by PostgreSQL where unambiguous. `DOUBLE` maps to `double precision`; `TINYINT` maps to `smallint`. PostgreSQL resolves `OTHER` from query/column context when possible, including UUID, JSON, and custom types. Ambiguous types remain `UNKNOWN` and need an explicit declaration. Inferred baseline types are frozen when checking later schemas.
- `source`: `HIBERNATE_MAPPING`, `NAMED_HQL`, `NAMED_NATIVE`, `STATIC`, `RUNTIME_OBSERVED`, or `DYNAMIC_VARIANT`.
- `verificationStatus`: `EXTRACTED`, `OBSERVED`, or `UNSUPPORTED`.
- `dependenciesComplete`, `explicitColumnList`, `exactResultShape`, and optional `variant`.
- `accessPathIds` link exact inventoried methods to fixed templates. `observationFrames` preserve runtime stack frames; an observed frame does not establish a method overload or exhaustive method coverage.

Supported fixed native SELECT and explicit-column DML templates retain their SQL-derived physical dependencies. Explicit parameter declarations or unambiguous PostgreSQL inference establish parameter types independently. Wildcards, nested scopes, ambiguous aliases, positional inserts, conflict-update variants, and opaque function/sequence access remain unresolved. Complete structural dependencies do not prove arbitrary native hydration or behavioral equivalence. An old native statement that PostgreSQL accepts at baseline and rejects after migration supplies concrete breaking evidence even when another part of its contract is unresolved.

Entity/query IDs must be unique within a contract. Keep them stable across releases. Multiple input contracts are namespaced as `contract[1]/...`, `contract[2]/...`, and so on, in input order. Their application versions and scopes are retained in coverage notes.

## What completeness means

`coverage.complete` applies to the **declared** contract, not every database access in the application. `coverage.mechanisms` and `coverage.notes` record how evidence was obtained and its scope. A global flag cannot override an incomplete query, observed-only evidence, unresolved source, or unsupported scope. An empty contract cannot prove compatibility.

Do not change completeness flags or delete unresolved entries just to obtain `SAFE`. Entity package scanning and repository bytecode inventory are separate. Repository inventory recognizes constant JPA/Hibernate/JDBC calls, supported Spring query annotations/derived methods, and supported Panache query forms. Criteria builders, unenumerated concatenation, reflection, framework variants, and unsupported access APIs remain unresolved. Per-method `STATIC` means a fixed definition was found; it does not override native SQL's unverified behavior or a failed Hibernate translation. Finite variants must include every relevant fixed alternative; a nonempty list does not establish that application control flow has no other variants.

`dev.epoch.hibernate.SqlCollector` implements `StatementInspector`. Install it as a test dependency and configure:

```properties
hibernate.session_factory.statement_inspector=dev.epoch.hibernate.SqlCollector
```

Run application integration tests with `-Depoch.sql.capture=/tmp/epoch-observed.jsonl`, then pass that file through `runtimeSqlFiles` or `extract --runtime-sql /tmp/epoch-observed.jsonl`. Each schemaVersion 1 JSONL record contains `sql` and factual `callSites`; no bind values are collected. `statements()` returns sorted distinct SQL, `queries()` creates observed incomplete entries, and `clear()` resets in-memory capture. Runtime evidence remains non-exhaustive even if every observed statement prepares successfully. Reports mention the observation limitation only when observed SQL is present.

## Report and accepted-baseline schemas

A report also preserves `accessPaths` and provides per-method discovery counts. A report contains `schemaVersion`, `applicationVersion`, `scope`, `verdict`, `baseline`, ordered `migrations`, `ruleSummary`, and `limitations`. `baseline` contains health `verdict`, `findings`, a deterministic `fingerprint`, and optional `cacheStatus`/`cacheKey`. Baseline findings have `migration: null`; they appear once and are not attributed to pending migrations. With no pending migrations, `migrations` is empty and the overall verdict reflects baseline health.

Each migration contains its version/filename, delta verdict, and findings. Each finding has `ruleId`, `objectName`, `message`, `evidence`, `affectedContractIds`, optional migration attribution, and optional mitigation. `ruleSummary` groups counts by rule and verdict: `baselineCount`, `migrationCount`, and `acceptedBaselineCount`.

The overall `verdict` includes baseline health. Explicit baseline acceptance adds `gateVerdict` and `acceptedBaseline` without changing that compatibility verdict. For example, an accepted unresolved baseline may yield `verdict: UNKNOWN` and `gateVerdict: SAFE` when no new migration issue is found. This is a reviewed policy gate, not a new proof of compatibility.

An acceptance file has `schemaVersion: 1`, `baselineFingerprint`, and `findings`, each with a finding `fingerprint` and a required nonblank `justification`. The fingerprint binds acceptance to the contract, baseline files/settings, catalog, and baseline evidence. Stale, unknown, duplicated, or unjustified entries are rejected. Pending-migration findings cannot be accepted through this file. See [CI usage](ci.md) for the generation and review workflow.
