# Descriptors and database contracts

## Extraction descriptor

The `extract` command consumes an `ExtractionRequest` JSON document. It loads already compiled entity classes; it does not compile or scan an application repository.

```json
{
  "applicationVersion": "orders-1.0.0",
  "entityClasses": ["com.example.orders.Customer"],
  "classpath": ["../build/classes/java/main", "../build/resources/main"],
  "hqlQueries": {
    "Customer.byEmail": "select c from Customer c where c.email = :email"
  },
  "finiteHqlVariants": {},
  "nativeQueries": {},
  "unresolvedDynamicQueries": {}
}
```

This descriptor is illustrative: replace the class and paths with your compiled application artifacts. Include JARs or class directories needed by the entities, embeddables, converters, and naming strategies. The CLI distribution supplies its own Hibernate version; it does not automatically reproduce all settings from a Spring or application bootstrap.

| Field | Meaning |
| --- | --- |
| `entityClasses` | Required, nonempty list of fully qualified annotated entity class names. |
| `classpath` | Additional JARs/class directories, relative to the descriptor's directory; defaults to `[]`. |
| `applicationVersion` | Release identifier retained in the contract and report; defaults to `unspecified`. |
| `physicalNamingStrategy` | Optional fully qualified Hibernate physical naming strategy class. |
| `implicitNamingStrategy` | Optional fully qualified Hibernate implicit naming strategy class. |
| `hqlQueries` | Stable query ID to a fixed HQL query. The current adapter translates SELECT HQL. |
| `nativeQueries` | Stable query ID to native SQL retained as incomplete evidence. |
| `finiteHqlVariants` | Source ID to a finite list of fixed SELECT HQL alternatives. Exhaustiveness is the caller's assertion. |
| `unresolvedDynamicQueries` | Source ID to the reason its query variants cannot be enumerated. |

The adapter also reads registered named HQL and native queries from Hibernate metadata. It uses PostgreSQL dialect settings and defaults the schema to `public`. It disables JDBC metadata access and schema generation during extraction. It obtains physical mappings, static mutation SQL, single-ID loader SQL, and translated HQL through Hibernate APIs.

Extraction writes a contract even when individual query sources remain unresolved. A failed application bootstrap, missing compiled class, or invalid descriptor is a runtime error instead.

## Contract schema version 1

The JSON model is defined by [`Model.kt`](../core/src/main/kotlin/dev/epoch/core/Model.kt). Enum values are case-sensitive.

| Top-level field | Meaning |
| --- | --- |
| `schemaVersion` | Integer format version; the CLI currently accepts `1`. |
| `applicationVersion` | Identity of the old application release being checked. |
| `dialect` | `PostgreSQL`; other dialects are unsupported. |
| `scope` | `STRUCTURAL_CONTRACT`. Requesting `WHOLE_APPLICATION` yields `UNKNOWN`. |
| `entities` | Mapped table/column dependencies and declared operations. |
| `queries` | SQL templates with explicit dependency and type information. |
| `coverage` | Completeness within the declared scope, extraction mechanisms, and qualifications. |
| `unresolvedQueries` | Stable source IDs and reasons that coverage remains unresolved. |

Entity entries contain a unique `id`, a schema-qualified `table`, `columns`, and an operation set drawn from `SELECT`, `INSERT`, `UPDATE`, and `DELETE`. A column records its physical `name`, optional `typeName` and `nullable` value, and `insertable`, `updatable`, and `generated` flags. `generatedIdentifier` records a generator description when present; it does not prove allocation semantics.

Query entries record:

- `id`, `operation`, and a `sql` template. SQL contains JDBC `?` placeholders, not captured parameter values.
- `tables`, `columns`, and `writeColumns`, using schema-qualified table names and physical column names.
- Optional `targetTable` identifies the write target for queries such as `INSERT ... SELECT` that also read other tables. Ambiguous write targets remain `UNKNOWN`.
- Ordered `parameterTypes` using PostgreSQL-resolvable type names such as `bigint`, `varchar`, and `text`, plus optional `resultColumns` with names and types. The number of declared parameter types must match the number of JDBC placeholders.
- `source`: `HIBERNATE_MAPPING`, `NAMED_HQL`, `NAMED_NATIVE`, `STATIC`, `RUNTIME_OBSERVED`, or `DYNAMIC_VARIANT`.
- `verificationStatus`: `EXTRACTED`, `OBSERVED`, or `UNSUPPORTED`.
- `dependenciesComplete`, `explicitColumnList`, and `exactResultShape`; these describe evidence and positional dependencies, not preferences for relaxing checks.
- Optional `variant`, identifying a member of a declared finite query family.

Entity and query IDs must be unique across the contract. Keep IDs stable across releases to make findings traceable. Hand-authored contracts are supported, but their dependency lists and completeness assertions must accurately represent the declared operations.

## What completeness means

`coverage.complete` describes extraction of the **declared** contract. It does not mean every database access in the application has been found. The extractor sets it only when its declared sources have no unresolved entries. `coverage.mechanisms` records how evidence was obtained; `coverage.notes` records scope restrictions.

Each query's `dependenciesComplete` concerns that query's enumerated table/column dependencies. A global completeness flag cannot override an incomplete query, an observed-only query, an unresolved source, or an unsupported verification scope. An empty contract cannot produce a compatibility proof.

Do not change completeness flags or remove unresolved entries just to obtain `SAFE`. Add missing, justified evidence or accept that the answer remains `UNKNOWN`. Repository scanning, Criteria API builders, arbitrary JDBC, custom query builders, and unlisted runtime query sources are not automatically discovered.

Finite HQL variants must include every relevant fixed alternative. A nonempty list alone does not prove that application control flow cannot produce other variants. Collection-valued HQL parameters, dynamic insert/update, and other variable SQL shapes can still leave the contract unresolved.

## Optional SQL observations

`dev.epoch.hibernate.SqlCollector` implements Hibernate's `StatementInspector`. Install it through Hibernate's `hibernate.session_factory.statement_inspector` setting in application integration tests to capture SQL templates. `statements()` returns distinct, sorted statements; `queries()` represents them as observed, incomplete contract entries. `clear()` resets the collector.

The collector does not capture bind values or establish exhaustive coverage. It does not infer a complete read/write dependency contract from the observed text. Combining observations with extracted evidence cannot by itself prove that unexecuted application paths are safe.

## Report schema

The report records `schemaVersion`, `applicationVersion`, `scope`, aggregate `verdict`, ordered `migrations`, and `limitations`. Each migration contains its version/filename, verdict, and findings. A finding contains `ruleId`, `objectName`, `message`, `evidence`, `affectedContractIds`, migration attribution, and an optional mitigation.

Aggregation is conservative: any `BREAKING` finding wins; otherwise any `UNKNOWN` finding wins; `SAFE` requires all evaluated findings to be safe. When no migration is pending, the database verifier emits a synthetic `baseline` step to check the contract against the unchanged schema. At the report-model level, an empty list of evaluated steps aggregates to `UNKNOWN`.
