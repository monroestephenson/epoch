# Epoch

Epoch checks whether an old application's declared database contract remains structurally compatible with a sequence of PostgreSQL migrations. It extracts mappings and SQL through Hibernate, applies Flyway migrations to a disposable PostgreSQL database, and reports `SAFE`, `BREAKING`, or `UNKNOWN` for each intermediate schema.

`SAFE` applies only to the explicit structural contract. It does not certify every application query, data transformation, production data set, or deployment operation. Unsupported and unresolved behavior produces `UNKNOWN`; a proven incompatibility produces `BREAKING` even when other findings are unknown.

## Build and test

Required:

- JDK 21, with `JAVA_HOME` pointing to that JDK.
- A working Docker daemon accessible to the current user for verification and PostgreSQL integration tests.
- Network access for the initial Gradle/dependency downloads and container pulls.

The checked-in Gradle wrapper pins Gradle 8.14.3 and verifies its distribution checksum. There is no separate global Gradle installation step.

```sh
./gradlew test :cli:installDist
cli/build/install/db-compat/bin/db-compat --help
```

`test` includes the Docker-backed `integration-tests` project. Missing Docker is a test failure, not a skipped compatibility check. To run only the tests that do not require Docker:

```sh
./gradlew :core:test :hibernate:test :cli:test
```

The project targets Kotlin/JVM, Hibernate **6.6 and 7 (including 7.4.9)**, Flyway 11, PostgreSQL **16.8**, and Testcontainers **2.0.5**. Hibernate 7 extraction uses the application’s supplied runtime in an isolated classloader; see the tested versions and adapter boundaries in [the contract guide](docs/contracts.md). The required container images are `postgres:16.8` and `testcontainers/ryuk:0.14.0`.

## Check an old application release

Build the **old** application's entity classes first. Supply those compiled classes and their mapping dependencies to an extraction descriptor. Classpath entries are directories or JARs, resolved relative to the descriptor file. Use `scanPackages` to discover entities in compiled artifacts instead of listing every class. Repository and DAO bytecode discovery is enabled by default. Set `repositoryPackages` to your application’s repository/DAO packages. The descriptor also supports Hibernate properties, entity overrides, and native-query parameter declarations or PostgreSQL inference. [Repository coverage](docs/repository-coverage.md) explains inherited API owners, query origins and unresolved variants. The optional [API inventory](docs/repository-api-inventory.md) separates raw known-family calls from extracted query paths and makes missing-bytecode or ancestry gaps explicit.

```sh
cli/build/install/db-compat/bin/db-compat extract \
  --descriptor descriptor.json \
  --output contract.json

cli/build/install/db-compat/bin/db-compat verify \
  --contract contract.json \
  --baseline migrations/baseline \
  --migrations migrations/pending \
  --report compatibility-report.json

cli/build/install/db-compat/bin/db-compat report \
  --input compatibility-report.json
```

The baseline directory contains the Flyway SQL migrations that establish the schema used by the old application. The pending directory contains subsequent versioned migrations. Versions must be unique and later than the baseline. Verification checks the contract against every pending migration state, always relative to the original baseline. A later migration that restores a dropped column does not erase an earlier breaking result.

Verification starts its own PostgreSQL container. There is no target JDBC URL option and no production database credential is needed. Docker and the pinned container image must be available.

| Default command outcome | Exit code |
| --- | ---: |
| `SAFE`, successful extraction, or help | 0 |
| `BREAKING` | 1 |
| `UNKNOWN` | 2 |
| Invalid arguments, runtime failure, or incomplete report | 3 |
| Passing compatibility gate, but operational `RISKY`, `UNKNOWN`, or unavailable with `--fail-on-operational` | 4 |

`verify`, `verify-release`, and saved `report` replay accept `--fail-on unknown|breaking`. The default `unknown` blocks both UNKNOWN and BREAKING. `--fail-on breaking` permits a completed UNKNOWN result with exit `0`; its JSON and terminal verdict remain UNKNOWN. With an explicitly reviewed accepted-baseline file, `verify` and `report` apply this policy to the separate `gateVerdict`; the overall compatibility verdict remains unchanged. All requested directions, including new-writes-old-reads, contribute to the gate. See [CI policy and precedence](docs/ci.md#compatibility-ci-policy).

Migration execution/validation failures, lost database connections, and verification timeouts return runtime exit `3` with their stage and migration; they are not completed compatibility verdicts. Incomplete reports remain exit `3` under either policy. Extraction success means a contract was written; inspect `coverage` and `unresolvedQueries` before treating it as complete.

Unexpected errors, including `NoClassDefFoundError`, return `3` and print the underlying cause. Library logging is quiet by default; the CLI prints concise failure causes. Use `--verbose` for INFO logs and full traces.

Read [the descriptor and contract format](docs/contracts.md), [verification boundaries](docs/verification.md), the [complete rule reference](docs/compatibility-model.md), and [CI usage](docs/ci.md) before integrating the result into a deployment workflow. [Current verification refinements](docs/feedback-round2.md) and [repository coverage](docs/repository-coverage.md) document the latest changes; [the earlier feedback record](docs/feedback.md) describes the previous revision.

## Baseline health, scale, and multiple persistence units

Reports separate preexisting baseline health from findings introduced by migrations. Each checked step can identify NEW, PERSISTING and no-longer-reported finding episodes; first-observed state is separate from current migration evidence. Default terminal output collapses established shared sources for three behavior/rename rules, while per-rule counts and full JSON retain raw observations. Use `report --summary` or `report --details` to choose the level of detail. Existing baseline findings have no pending-migration attribution. See [reading reports](docs/reporting.md) for origins, grouping limits and count denominators.

Generate per-unit extraction inputs with `db-compat descriptors --quarkus-properties application.properties --classpath build/classes/kotlin/main --output-directory generated-descriptors`, or use `--persistence-xml`. Supply the complete runtime classpath using repeated `--classpath`; [the configuration guide](docs/persistence-unit-descriptors.md) documents profiles, supported settings and unresolved configuration.

For several persistence units sharing one database, repeat `--contract`. The baseline is constructed once. Add `--baseline-cache DIR` to reuse a checksummed baseline dump across runs; every run still uses a fresh disposable PostgreSQL container.

```sh
cli/build/install/db-compat/bin/db-compat verify \
  --contract orders-contract.json --contract billing-contract.json \
  --baseline migrations/baseline --migrations migrations/pending \
  --baseline-cache .cache/epoch-baseline \
  --report compatibility-report.json
```

A justified accepted-baseline file can make the CI gate measure new migration findings while retaining baseline debt and the original compatibility verdict. It cannot suppress new migration findings, and stale input fingerprints are rejected. See [the review workflow](docs/ci.md#review-existing-baseline-debt) before enabling it.

Use `extract --keep-going` to recover usable mappings after bootstrap failures, with excluded entities and association paths recorded as unresolved. Failures become unresolved evidence; partial results never imply complete coverage.

## Rollout checks, release history, and CI

Add `--new-contract release-n.json` to `verify` to check old code on migrated schemas, new code on the retained old schema, and supported new writes against old readers on the final schema. The [data comparison](docs/verification.md#new-writes-and-old-readers) detects finite enum/discriminator and declared-nullability regressions; unknown codecs and missing read/write provenance remain UNKNOWN. `verify-release` can assemble baseline and pending migrations directly from immutable Git refs:

```sh
db-compat verify-release --repository . --base-ref v1.4.0 --target-ref v1.5.0 \
  --migration-path src/main/resources/db/migration \
  --contract release-1.4.0.json --new-contract release-1.5.0.json \
  --report release-report.json --sarif release-report.sarif
```

Existing migration files and their Flyway sidecars must remain unchanged. Contracts are retained release artifacts; Epoch does not rebuild applications from Git refs automatically. See [the release workflow](docs/feedback-round2.md#rollout-and-rollback-workflows).

Verification has per-migration and global deadlines (`--migration-timeout-seconds`, default 120; `--global-timeout-seconds`, default 1800). `CREATE INDEX CONCURRENTLY` uses Flyway’s session-level advisory lock and requires `executeInTransaction=false` in the migration’s `.sql.conf` sidecar. Reports expose reused/replanned query counts and baseline cache rebuild reasons. Eligible pending-step checks can use `--planning-parallelism` after their migration commits and before the next script runs. Checks that cannot be parallelized run serially against the committed state; migration-local settings never become application failure evidence. See [planning and commit boundaries](docs/planning.md).

`--execute-boundaries` optionally tries supported rolled-back INSERT counterexamples. A value accepted before a migration and rejected afterward proves a break; successful samples never establish safety.

## Operational migration report

Verification also reports a separate operational `SAFE`, `RISKY`, or `UNKNOWN` result for pending migrations. It observes Flyway's actual statements, held relation locks, storage rewrites, and lock-timeout settings in the disposable database. The supported model identifies old-table write-blocking locks, blocking scans and rewrites; catalog-confirmed commands supply separately labeled required-lock evidence when autocommit releases locks before sampling. Operational `SAFE` does not promise production latency, no locks, or sufficient I/O capacity; compatibility verdicts remain independent.

```sh
db-compat verify --contract contract.json \
  --baseline migrations/baseline --migrations migrations/pending \
  --table-sizes table-sizes.json --fail-on-operational \
  --report compatibility-report.json
```

Both flags are optional. Caller-supplied [table-size estimates](docs/ci.md#table-size-estimates) grade known risks; small estimates never turn `RISKY` into `SAFE`. Without `--fail-on-operational`, exit codes follow the selected compatibility policy. With it, a passing compatibility gate additionally requires measured operational `SAFE`; other operational outcomes return `4`. This also applies when `--fail-on breaking` permits UNKNOWN compatibility. Runtime/incomplete `3` and compatibility failures under the selected policy take precedence. `verify-release` and saved `report` replay support the same gate; legacy reports without operational evidence fail that optional gate.

A nullable-column addition can be structurally SAFE while its unprotected old-table lock is operationally RISKY. An explicit migration-authored `lock_timeout` can bound acquisition waiting for supported ordinary commands; the verifier's injected timeout does not count. Acquisition protection never proves short lock holding or waives scan/rewrite risks. See [timeout policy and an example](docs/ci.md#operational-ci-gate).

See [operational proof boundaries and rules](docs/verification.md#operational-migration-assessment) for supported concurrent-index and split-transaction validation patterns. Only forward deployment migrations contribute to this result. Operational details are in JSON and terminal reports; SARIF currently exports compatibility findings only. The separate `lint` command remains unchanged and provides static review hints.

## Run the examples

The repository includes a finite four-operation contract and three migration scenarios. After installing the CLI, run the compatible example from the repository root:

```sh
cli/build/install/db-compat/bin/db-compat verify \
  --contract examples/customer-contract.json \
  --baseline examples/baseline \
  --migrations examples/compatible \
  --report build/example-report.json
```

`examples/compatible` adds a nullable column; `examples/breaking` renames the old `email` column; `examples/unknown` adds a check constraint whose accepted-input compatibility is unproven. Substitute the migration directory to explore the three verdicts.

To extract a contract from the included compiled Hibernate entity fixture:

```sh
./gradlew :integration-tests:testClasses :cli:installDist
cli/build/install/db-compat/bin/db-compat extract \
  --descriptor examples/fixture-extraction.json \
  --output build/fixture-contract.json
```

For your own application, copy and adapt [the application descriptor template](examples/hibernate-extraction.json). Keep its unresolved-source reminder until you have inventoried the intended contract's database-access paths.

## Repository layout

| Directory | Responsibility |
| --- | --- |
| `core/` | Versioned contract/report model, deterministic compatibility rules, PostgreSQL catalog inspection, and Flyway verification. |
| `hibernate/` | Hibernate metadata and SQL extraction; optional SQL observation through `StatementInspector`. |
| `cli/` | Extraction, verification, Git release workflows, reporting/SARIF, and separate operational lint. |
| `integration-tests/` | Compiled entity fixtures and real PostgreSQL migration scenarios. |

Extraction and verification are separate so an old release's JSON contract can be retained and checked against later migrations. Findings identify the migration, rule, affected contract entries, and evidence. Human-readable rendering uses the same saved JSON report as CI.

## License

A license has not yet been selected for this repository.
