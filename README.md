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

The project targets Kotlin/JVM, Hibernate 6.6, Flyway 11, and PostgreSQL 16.8. See the checked-in Gradle files for exact dependency versions.

## Check an old application release

Build the **old** application's entity classes first. Supply those compiled classes and their mapping dependencies to an extraction descriptor. Classpath entries are directories or JARs, resolved relative to the descriptor file.

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

| Command outcome | Exit code |
| --- | ---: |
| `SAFE`, successful extraction, or help | 0 |
| `BREAKING` | 1 |
| `UNKNOWN` | 2 |
| Invalid arguments or runtime failure | 3 |

`report` returns the verdict's exit code too. A report with `UNKNOWN` is deliberately a failing CI gate. A pending migration execution or validation failure is recorded as `UNKNOWN` when a report can be produced; a failure to start PostgreSQL or establish the baseline is a runtime failure. Extraction success means a contract was written; inspect `coverage` and `unresolvedQueries` before treating it as complete.

Read [the descriptor and contract format](docs/contracts.md), [verification boundaries](docs/verification.md), and [CI usage](docs/ci.md) before integrating the result into a deployment workflow.

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
| `cli/` | `extract`, `verify`, and `report` commands and their exit codes. |
| `integration-tests/` | Compiled entity fixtures and real PostgreSQL migration scenarios. |

Extraction and verification are separate so an old release's JSON contract can be retained and checked against later migrations. Findings identify the migration, rule, affected contract entries, and evidence. Human-readable rendering uses the same saved JSON report as CI.

## License

A license has not yet been selected for this repository.
