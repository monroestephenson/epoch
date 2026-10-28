# Descriptors and database contracts

`db-compat descriptors` can generate per-unit inputs from supported Quarkus properties or standard `persistence.xml`; see [persistence-unit descriptors](persistence-unit-descriptors.md). Configuration completeness is separate from query coverage, and unsupported settings remain explicit extraction debt.

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
| `nativeQueries` | Stable query ID to fixed native SQL. Supported explicit-column templates retain complete physical dependencies; unsupported forms remain incomplete. |
| `nativeQueryParameterTypes` | Query ID or registered named-native-query name to ordered PostgreSQL/JDBC parameter type names. |
| `finiteHqlVariants` | Source ID to a finite list of fixed HQL alternatives. Exhaustiveness is the caller's assertion. |
| `unresolvedDynamicQueries` | Source ID to the reason its query variants cannot be enumerated. |
| `keepGoing` | Isolate failed entities and recover supported associations within mapping graphs; record every omission as unresolved; defaults to `false`. The CLI's `--keep-going` enables it too. |

The bundled runtime is **Hibernate 6.6.13.Final**. When the classpath supplies Hibernate 6.6 or 7.x, Epoch loads the application's Hibernate, JPA API, mapping classes, and dependencies in an isolated classloader. No Hibernate objects cross into the bundled runtime, and missing Hibernate/JPA classes never fall back to bundled classes. The artifact version must agree with the runtime's own version; conflicting or incomplete runtimes fail clearly. Version-sensitive SPI accessors bridge changes in Hibernate 7's query implementation, SQL translation context, and loader APIs. The regression suite exercises real **6.6.13.Final, 7.0.10.Final, 7.1.4.Final, and 7.4.9.Final** runtimes. Other 6.6/7.x releases use the same adapter; unsupported SPI/mapping features fail or become explicitly unresolved rather than silently substituting another Hibernate version.

The application may supply separate entity and repository JARs, including its own ASM dependencies. Epoch keeps the entire adapter-owned ASM family in its own loader, preventing application ASM core from mixing with a different loader's ASM tree classes. This does not change which Hibernate runtime extracts the application. `Hibernate7JarDiscoveryTest` exercises that multi-module JAR layout with actual Hibernate 7.4.9 and verifies an inline-only native-column rename against PostgreSQL.

Query translation is isolated per template. A failed generated operation, HQL/native query, or declared variant is recorded by its source ID while healthy sibling queries remain available. Repository call-site links retain the class, method, and line when discovered. A query-local stack overflow is recoverable extraction debt; interruption and other fatal VM failures remain hard failures. Caught causes stay in portable unresolved evidence, and `--verbose` exposes original diagnostic traces. No half-translated query is published as complete evidence. `--keep-going` separately controls partial entity-bootstrap recovery.

Extraction uses the PostgreSQL **16.8** dialect, disables JDBC metadata access and schema generation, and sets `jakarta.persistence.validation.mode=none`. It obtains physical mappings, static mutation SQL, single-ID loader SQL, and translated HQL through Hibernate APIs. Physical SQL aliases are resolved to their own tables, including JOINED inheritance and secondary-table dependencies. PostgreSQL identifier matching folds unquoted names and truncates at 63 UTF-8 bytes without splitting a code point. Basic collection persisters contribute real loader, insert/update/delete-row, and delete-all plans for join tables and `@ElementCollection`. One-to-many/custom collection plans and optional-secondary-table MERGE SQL remain unresolved where unsupported. Registered named HQL and native queries are included. Synthetic Hibernate discriminator placeholders do not become physical columns. Formula selectables are omitted from physical dependencies and recorded as unresolved where their SQL dependencies remain unproven.

Dialect selection uses Hibernate's standard resolver with authoritative offline PostgreSQL product/version facts. A supplied standard PostgreSQL `hibernate.dialect` declaration is normalized away; deprecated metadata and legacy `javax.persistence.database-*` hints cannot override the pinned version. Custom `hibernate.dialect_resolvers` are rejected, and application resolver services are not used for dialect selection. This avoids warnings caused by Epoch's own explicit-dialect settings without opening a database connection or suppressing application diagnostics under `--verbose`.

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
| `unresolvedQueries` | Stable diagnostic IDs and reasons, with optional `affectedIds` retaining every source sharing a cause. |
| `accessPaths` | Per-method evidence: `STATIC`, `DECLARED`, `OBSERVED`, or `UNRESOLVED`, with method identity, mechanism, query IDs, reason, and optional source line. |

Entity entries contain a unique `id`, a schema-qualified `table`, `columns`, and an operation set. A column records its physical `name`, optional `typeName` and `nullable`, and `insertable`, `updatable`, and `generated` flags. Columns also retain Hibernate's `length`, `precision`, `scale`, finite relational `enumValues`, `logicalJavaType`, and `inputDomainComplete` when available. DDL bounds alone are not proof of a logical input domain: a VARCHAR-backed UUID is not an arbitrary string. Custom converters/JDBC types or unknown logical domains set `inputDomainComplete=false`; rules requiring those domains remain `UNKNOWN`. This does not invalidate an otherwise complete, unchanged structural SQL contract. Primitive properties do not claim nullable application inputs. `generatedIdentifier` records the strategy; `identifierSequence` and `identifierAllocationSize` retain actual sequence dependencies. Identity/serial generation and physical sequences with allocationSize 1 are checked against PostgreSQL. Pooled and custom identifier allocation remain unresolved; a preassigned sequence value remains an explicit INSERT column.

`lengthUnbounded=true` is positive evidence of a supported unbounded string declaration, retained separately from Hibernate's raw `length`. Missing length alone does not mean unbounded. Extraction obtains this fact from the originating boot mapping's resolved SQL type; adapters supplied only an existing SessionFactory leave unavailable boot facts unknown. Custom write expressions, custom table mutation SQL, dynamic mutations, and optional secondary-table writes also prevent a complete direct input-domain claim for the affected operations. A nullable logical property transformed by `coalesce(?, '')`, for example, does not prove that SQL NULL reaches the column. An all-NULL optional secondary row can instead be omitted or deleted. Property generation is scoped to the generated property's physical columns, so a generated timestamp does not invalidate unrelated fields' input facts.

`stringInputLengthUnrestricted=true` instead describes a supported direct `java.lang.String` JDBC binder: Hibernate's DDL `length` hint does not cap its runtime input. A baseline TEXT column can therefore accept 501 characters even when Hibernate reports its default length of 255. The verifier still needs baseline admission and supported write effects before using that value as a breaking witness. This flag defaults to false and does not apply to enums, converters, custom binders or transformed writes. Raw `@Column(length)` on TEXT is not a universal bound for index-input proofs.

Nullability has distinct sources:

| Column field | Evidence |
| --- | --- |
| `declaredNonNull` | `true` for an established non-null Java/Kotlin member, `false` for an explicitly nullable member, and `null` when unknown. Kotlin metadata is matched by JVM signature to Hibernate's actual field/getter; Java declaration annotations are read from bytecode, including CLASS retention. |
| `hibernateNullable` | Hibernate's raw physical selectable nullability, independently of the source language. |
| `nullable` | The retained legacy mapping flag, including its primitive-property adjustment. Existing contracts may omit the newer facts. |

For example, Kotlin `var x: String` can have `declaredNonNull=true` and `hibernateNullable=true`, while `var y: String?` can have `declaredNonNull=false` even if `@Column(nullable=false)` makes `hibernateNullable=false`. Unannotated Java references, ambiguous metadata, and unresolved generic types do not imply nullable inputs. Declared facts attach to supported basic attributes and basic identifiers; the nullability of an association, component, or collection object does not establish each underlying JDBC column's nullability. A declared member fact also does not prove an arbitrary converter's SQL output. Missing nullability evidence does not by itself invalidate complete SQL extraction.

Enum and converter metadata retain the distinction between source declarations and database encodings:

| Column field | Evidence |
| --- | --- |
| `enumMode` | `STRING` or `ORDINAL` for the recognized effective Hibernate mapping. An explicit JDBC-type override can differ from the source `@Enumerated` annotation; unknown/custom semantics remain `null`. |
| `enumConstants` | The complete Java/Kotlin constant names in declaration order. |
| `enumRelationalValues` | Database values aligned with `enumConstants`, when the encoding is supported. The existing `enumValues` remains a sorted relational value set. |
| `enumEncoding` | `NAME`, `ORDINAL`, or `EXPLICIT`; explicit Hibernate 7 `@EnumeratedValue` codes are distinguished from ordinary names/ordinals. |
| `converterClass` | The actual JPA AttributeConverter class supplied by Hibernate, including auto-applied converters. |
| `converterBytecodeSha256` | Lowercase SHA-256 of the converter class bytes from its defining artifact, or `null` when unavailable. Multi-release JARs use the runtime-selected entry. |

Epoch does not invoke arbitrary application converters to manufacture a finite input proof. Unsupported conversion retains known declaration/artifact facts without guessing an enum encoding. A converter fingerprint describes one class artifact, not its inherited/helper code, configuration, or semantic behavior. Matching hashes do not prove behavioral equivalence; a changed hash alone does not prove incompatibility. Explicit enum codes can also remain stable when declaration order changes, so ordinal mode alone is insufficient to infer a data-format change.

`readDomainComplete` separately records support for the actual mapped decoder. It defaults to false for older contracts and is independent of write-domain completeness. Entity `hierarchy` facts describe the actual strategy, concrete/abstract state, physical discriminator, finite values, and exact root mapping ID. Unsupported formulas, synthetic discriminators, and wildcard fallback mappings do not become finite physical proofs.

`mappedReads[].resultPosition` records the one-based JDBC slot from Hibernate's actual SQL selection metadata. It defaults to null when unavailable and is never guessed from labels or column order. Together with supported decoder facts, exact mapping links and baseline-valid SQL, it can establish compatible reads after a type widening. Explicit result signatures remain exact.

An unresolved diagnostic's `affectedIds` contains the complete source inventory sharing its cause. An absent or empty list retains the older single-source meaning of `id`. Grouping does not resolve any source or improve coverage. Extraction reports the distinct union of affected sources, falling back to `id` for individual entries, separately from the diagnostic-group count. Multiple persistence units retain their unit namespaces on both diagnostic IDs and affected sources.

Query entries record:

- `id`, `operation`, and `sql` with JDBC `?` placeholders rather than captured parameter values.
- `tables`, `columns`, and `writeColumns`, using physical names. Optional `targetTable` disambiguates writes such as `INSERT ... SELECT` that also read other tables.
- Ordered `parameterTypes` and optional `resultColumns` with labels/types. When supplied, the declared parameter count must match JDBC placeholders. Missing native/runtime parameter types can be inferred by PostgreSQL where unambiguous. `DOUBLE` maps to `double precision`; `TINYINT` maps to `smallint`. PostgreSQL resolves `OTHER` from query/column context when possible, including UUID, JSON, and custom types. Ambiguous types remain `UNKNOWN` and need an explicit declaration. Inferred baseline types are frozen when checking later schemas.
- `source`: `HIBERNATE_MAPPING`, `NAMED_HQL`, `NAMED_NATIVE`, `STATIC`, `RUNTIME_OBSERVED`, or `DYNAMIC_VARIANT`.
- `mappedEntityIds` links generated ORM SQL to its actual originating mapping entries, including the physical target slice for multi-table mutations. A source label or matching table name alone does not establish this relationship. Named, native, static, and observed queries do not inherit another mapping's accepted input domain.
- `mappedReads` links actually decoded physical columns to their mapping IDs; `mappedReadCoverageComplete` distinguishes a supported read graph from missing metadata. Initial positive extraction is limited to ordinary by-ID loaders with supported basic selections and identifier-only predicates. Predicate dependencies, custom/conditional loaders, and unknown graph branches cannot manufacture decoded-column evidence.
- `polymorphicReads` records root mapping IDs and whether a supported physical discriminator load is unrestricted. `discriminatorWrites` records actual standard INSERT discriminator literals for concrete mappings. A subtype declaration alone is not write evidence. All nested mapping IDs receive the same multi-contract prefix as entity IDs.
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

Optional `migrations[].catalogChanged` records the immediately preceding and resulting inspected catalogs: true or false when observed, null when unavailable. False prints an explicit no-catalog-change message. It does not prove that no data changed, waive procedural uncertainty, or change baseline-relative finding attribution. Contract and report `schemaVersion` remains 1; the new fields have conservative defaults.

For catalog API users, `SchemaSnapshot.behaviorGraph` records versioned, stable object dependencies and relation/operation attachments, with separate dependency, semantic and planning facts. Missing or incomplete graphs cannot establish the new scoped behavior proofs; legacy nonempty behavior inventories retain conservative handling. `TableSchema.indexValidity` and `indexReadiness` retain PostgreSQL's actual index flags. These are inspected snapshot/cache facts, not descriptor switches for overriding coverage; see [behavior scopes](verification.md#operation-specific-database-behavior).

The overall `verdict` includes baseline health. Explicit baseline acceptance adds `gateVerdict` and `acceptedBaseline` without changing that compatibility verdict. For example, an accepted unresolved baseline may yield `verdict: UNKNOWN` and `gateVerdict: SAFE` when no new migration issue is found. This is a reviewed policy gate, not a new proof of compatibility.

An acceptance file has `schemaVersion: 1`, `baselineFingerprint`, and `findings`, each with a finding `fingerprint` and a required nonblank `justification`. The fingerprint binds acceptance to the contract, baseline files/settings, catalog, and baseline evidence. Stale, unknown, duplicated, or unjustified entries are rejected. Pending-migration findings cannot be accepted through this file. See [CI usage](ci.md) for the generation and review workflow.

With `--new-contract`, the envelope contains `oldOnNew`, `newOnOld`, and `dataCompatibility`, plus their aggregate `verdict` and optional `gateVerdict`. The data assessment uses `<old-vs-new-contract>` attribution and no baseline waiver. JSON replay, terminal output, SARIF, and incomplete checkpoints retain the `new-writes-old-reads` direction. SARIF marks contract evidence without inventing a migration file or line. See [data verification boundaries](verification.md#new-writes-and-old-readers).

## Measured operational report fields

Operational evidence is additive to report schema version 1 and does not change the SQL contract or compatibility verdict. `MigrationReport.operational` is nullable for older/unmeasured reports. A forward `CompatibilityReport` adds `operationalVerdict` (`SAFE`, `RISKY`, or `UNKNOWN`) and `operationalLimitations`. The bidirectional envelope's operational verdict comes only from `oldOnNew`; reverse schema and data directions supply no deployment execution evidence.

| Field | Meaning |
| --- | --- |
| `operational.verdict` | Aggregate within the supported blocking-scan/storage-rewrite model; RISKY takes precedence over UNKNOWN, then SAFE. |
| `operational.statements` | Actual Flyway execution order, with `migration`, one-based `ordinal`, exact `sql`, `autoCommit`, and statement `verdict`. Ordinals reset for each migration and are not source line numbers. |
| `lockTimeoutBefore`, `lockTimeoutAfter` | Observed `milliseconds`, `postgresSource`, and `origin`: `VERIFIER`, `MIGRATION_SESSION`, `MIGRATION_LOCAL`, or `UNKNOWN`. Zero means disabled; verifier settings do not establish deployment protection. |
| `heldLocksBefore`, `heldLocksAfter`, `newlyObservedLocks` | Relation name, `relationKind`, lock `mode`, and optional owning `table` facts. Index locks remain identified as index locks. |
| `rewrittenTables` | Stable `beforeName`/`afterName` and `table` facts for observed storage rewrites; internal OIDs/filenodes are omitted. |
| Relation facts | `name`, `kind`, `preExisting` (`true`, `false`, or unknown `null`), and original `baselineName`. Baseline names survive renames and key supplied size estimates. |
| Statement `findings` and assessment `findings` | Findings at the statement level and their flat assessment view. Each includes `ruleId`, `verdict`, `objectName`, `message`, `evidence`, `migration`, `statementOrdinal`, optional `severity`, and optional `mitigation`. |
| `operational.limitations` | Explicit scope limits; missing estimates, absent measurements, and unsupported behavior are not implied safety. |

Risk severity is `LOW`, `MEDIUM`, or `HIGH` and is distinct from verdict. Caller-supplied size metadata is a separate versioned input, not production data embedded in an extraction descriptor:

```json
{
  "schemaVersion": 1,
  "tables": {
    "public.accounts": { "estimatedRows": 1000000, "totalBytes": 268435456 },
    "public.events": { "estimatedRows": null, "totalBytes": 536870912 }
  }
}
```

Keys use exact stored schema/table names as `schema.table`, without SQL identifier quotes; embedded dots are unsupported. Names follow PostgreSQL's 63-byte identifier boundary. Estimates are nonnegative 64-bit integers or null/missing. Duplicate JSON keys, normalization collisions, negative/fractional values, and unsupported versions are rejected. Missing estimates remain unknown; they are not zero. See [export SQL and grading thresholds](ci.md#table-size-estimates).

A completed known-empty pending window may have operational SAFE. Absent/null fields in a legacy report mean unavailable and cannot satisfy `--fail-on-operational`. Incomplete checkpoints keep completed statement facts but cannot certify the remaining deployment and replay with exit 3. JSON and terminal reports retain operational details; current SARIF output contains compatibility findings only. The separate static-lint report format is unchanged.
