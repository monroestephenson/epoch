# CI and local execution

## Build and container prerequisites

Provide JDK 21 and an accessible Docker daemon:

```sh
docker pull postgres:16.8
docker pull testcontainers/ryuk:0.14.0
./gradlew --no-daemon test :cli:installDist
cli/build/install/db-compat/bin/db-compat --help
```

The wrapper pins Gradle 8.14.3; it does not install a JDK. `test` includes real PostgreSQL integration tests and fails if Docker is unavailable. Testcontainers is pinned to **2.0.5**, replacing the older client that failed with Docker Engine 29. No forced Docker API version is required by Epoch. The current development machine runs Docker 28.4.0; testing there does not establish that an Engine 29 run has completed.

Ryuk handles cleanup after interrupted clients. On a runner with its own reliable container cleanup, the standard Testcontainers option is available:

```sh
TESTCONTAINERS_RYUK_DISABLED=true cli/build/install/db-compat/bin/db-compat verify \
  --contract artifacts/old-contract.json \
  --baseline migrations/baseline \
  --migrations migrations/pending \
  --report artifacts/report.json
```

Normal verification still closes its own PostgreSQL container. With Ryuk disabled, the runner must clean up containers after abrupt process termination. The default remains enabled.

## Gate one or several persistence units

Keep reviewed old-release contracts for the versions and persistence units still running:

```sh
cli/build/install/db-compat/bin/db-compat verify \
  --contract artifacts/orders-contract.json \
  --contract artifacts/billing-contract.json \
  --baseline migrations/baseline \
  --migrations migrations/pending \
  --baseline-cache .cache/epoch-baseline \
  --report artifacts/compatibility-report.json
```

Repeated `--contract` inputs share one migrated baseline and pending migration sequence. They must refer to the same database baseline; use separate verification runs for different databases. Findings namespace contract IDs by input position.

Exit codes are `0` for `SAFE`, `1` for `BREAKING`, `2` for `UNKNOWN`, and `3` for invalid input or runtime failure, including unexpected linkage errors. Without acceptance, the exit code follows the overall compatibility verdict. With `--accepted-baseline`, it follows the distinct `gateVerdict`; the report still preserves the overall verdict and justified baseline debt.

Preserve the executable's exit status in CI. Upload the JSON report after verification, including nonzero compatibility results. Verification checkpoints are explicitly incomplete until the entire requested run succeeds; incomplete reports return exit 3 when rendered and cannot be used to accept baseline debt. Invalid arguments can still fail before a checkpoint is created, so never reuse an earlier run's report as the current result.

## Review existing baseline debt

First run verification without an acceptance file. Inspect baseline health separately from migration changes:

```sh
cli/build/install/db-compat/bin/db-compat report \
  --input artifacts/compatibility-report.json --summary

cli/build/install/db-compat/bin/db-compat report \
  --input artifacts/compatibility-report.json --details
```

After reviewing the actual baseline findings, create a candidate acceptance file with a specific justification:

```sh
cli/build/install/db-compat/bin/db-compat report \
  --input artifacts/compatibility-report.json \
  --write-accepted-baseline accepted-baseline.json \
  --justification "Reviewed baseline debt tracked in ENG-123; retain the existing application contract while addressing these gaps."
```

This operation writes every non-safe baseline finding with that justification and exits `0` on success. Review the file, remove entries that should remain blocking, and replace the shared justification with individual explanations where needed. Keep the reviewed file in version control, then add `--accepted-baseline accepted-baseline.json` to verification.

Acceptance never suppresses new migration findings. A stale contract, baseline input, catalog, or evidence fingerprint is rejected. Regenerate and review the file when those inputs deliberately change. An accepted-baseline gate can pass while the overall compatibility verdict remains `UNKNOWN`; display both in CI.

## Baseline cache

`--baseline-cache DIR` is optional. Epoch saves a custom-format `pg_dump` of the completed baseline, including Flyway history and baseline fixture data. It never caches the database after pending migrations. Each run starts a fresh container and restores the saved baseline into that owned database.

The cache key covers all baseline file names and bytes, PostgreSQL image identity, Flyway version, and relevant settings. File locks coordinate cache builders. Each published manifest names an immutable archive generation, so warm readers validate and restore outside the writer lock. Archive and catalog checksums detect corruption or an incompatible restore. Flyway validates restored history against current baseline files. Reports expose `DISABLED`, `MISS`, `HIT`, or `REBUILT` in `baseline.cacheStatus`. A valid hit avoids rerunning the historical migrations. Every `MISS` or `REBUILT` includes a `baseline.cacheReason`, also logged immediately after cache preparation under `--verbose`. Invalid entries are rebuilt from the supplied baseline; no stale pending-migration state is reused. The manifest-based cache uses a new format key, so older cache entries produce a cold miss after upgrading. Old archive generations are retained to protect readers already restoring them; remove the cache directory only when no verification uses it.

Preserve this directory between trusted CI runs if desired. The cache must be outside both migration directories; nested paths and symlink aliases are rejected. Baseline and pending directories must not overlap, since Flyway scans them recursively. Cache metadata can differ between otherwise equivalent reports; compare compatibility evidence and verdicts when assessing repeatability across cache states.

## Planning cost and interrupted runs

Use `--planning-parallelism 4` to bound independent PostgreSQL planning workers. Baseline checks and already committed states can use separate connections. Checks within a transactional Flyway migration stay on its own connection so they see that migration's uncommitted DDL; opening worker connections there would check the wrong schema or block on migration locks.

Each step computes its catalog changes once and retains the previous plans for unaffected complete dependencies. Incomplete SQL, hidden database behavior, and relevant constraint or type changes take the conservative re-check path. `replannedQueries` and `reusedQueries` show the work performed. A 10,000-statement contract with 100 statements affected per step needs 10,000 re-checks over 100 steps, rather than 1,000,000. Baseline contract-check caching is separate from the database dump cache and remains bound to the contract, baseline, catalog, and planner format.

Size CI from measured planning cost on the actual runner. For example, at 4 ms per statement, 10,000 serial checks cost about 40 seconds before startup, catalog inspection, and report output. Re-checking 100 statements adds roughly 0.4 seconds per transactional step at the same rate. These are arithmetic estimates, not latency promises. Start with 2–4 planning workers and leave memory and CPU for PostgreSQL as well as the JVM; increase the global timeout for large contracts. Do not multiply every migration by the entire contract size when dependencies are complete.

The CLI writes an initial incomplete checkpoint and replaces it atomically after baseline verification and each completed step. On timeout, failure, SIGINT, or SIGTERM, completed results remain available. `report` identifies the checkpoint as incomplete and returns exit 3 even if its completed steps were safe. The in-flight step is not claimed complete, and SIGKILL cannot run a shutdown hook. Reports omit elapsed times, process IDs, and scheduling-dependent ordering; compare findings and verdicts separately from cache-hit metadata.

## Logging and offline use

The CLI logs concise CLI failures by default. Add `--verbose` (or `-v`) for library INFO logs and full exception traces. Failures always include their underlying cause chain. Default reports group findings by rule and print at most 20 details, prioritizing migrations. `report --summary` omits detail; `--details` prints everything retained in JSON.

Initial setup downloads Gradle, dependencies, and Docker images. Once all dependencies for the selected tasks and the required images are cached:

```sh
./gradlew --offline --no-daemon test :cli:installDist
```

`--offline` controls Gradle dependency resolution. Docker must still have the images locally. Installed CLI distributions contain their runtime JARs and do not need Gradle to extract or report. Baseline caching is separate from dependency/image caching and does not remove the Docker requirement.

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| Exit `3` with `NoClassDefFoundError` | Inspect the reported missing dependency and descriptor classpath. Bean Validation is disabled during extraction. |
| Hibernate runtime/classpath mismatch | Include the application's actual Hibernate 6.6/7.x runtime and all dependencies. Epoch isolates that runtime; tested versions are 6.6.13, 7.0.10, 7.1.4, and 7.4.9. Incomplete runtimes and unsupported SPI paths fail clearly instead of falling back to bundled Hibernate. |
| Entity list too large | Use `scanPackages` over compiled class directories/JARs. |
| A mapping fails bootstrap | Use `--keep-going` to retain healthy entities where recovery is supported; excluded entities and association paths remain unresolved. |
| Container or Ryuk cannot start | Read the cause chain, check Docker access, both required images, and runner cleanup/permission requirements. |
| Baseline debt dominates output | Read `baseline` and rule summaries; use reviewed baseline acceptance if a migration-only gate is appropriate. |
| Cache repeatedly rebuilds | Check file permissions, archive integrity, and catalog differences after restoration. |
| Migration returns `UNKNOWN` | Inspect new findings and retained baseline debt separately; acceptance does not prove missing behavior. |

There is no production JDBC URL or production credential configuration step.

## Release refs, reverse compatibility, and SARIF

Retain the N-1 and N contracts as build artifacts. With both Git refs fetched locally, `verify-release` reads immutable migration blobs without changing the checkout:

```bash
db-compat verify-release --repository . --base-ref v1.4.0 --target-ref HEAD \
  --migration-path src/main/resources/db/migration \
  --contract artifacts/old-contract.json --new-contract artifacts/new-contract.json \
  --report build/release-report.json --sarif build/epoch.sarif
```

Existing SQL and `.sql.conf` files must be unchanged between refs. Flyway determines pending migration order. The JSON records both commit IDs; `report --input build/release-report.json` renders all requested directions. `new-on-old` checks deploy-before-migrate or rollback schema compatibility, not the correctness of down migrations. `new-writes-old-reads` separately checks supported mapped data domains on the final schema; a data-only BREAKING result exits 1 even when both schema directions are SAFE. Incomplete assessment exits 3. See [data verification boundaries](verification.md#new-writes-and-old-readers).

`verify --sarif build/epoch.sarif --migration-source-prefix src/main/resources/db/migration` and `report --sarif ...` also export SARIF 2.1.0. Upload that file through your CI provider's normal code-scanning integration. Baseline findings have no pending-file location. Catalog findings use line 1 as a migration-wide anchor, not an invented exact source line.

`lint --migrations db/pending --report build/operations.json` is optional and separate. Its hints do not affect the compatibility exit code. Configure `--migration-timeout-seconds` and `--global-timeout-seconds` for your runner; a failed migration or infrastructure failure exits 3. No PostgreSQL NOTICE or library stack trace is emitted in default CLI logging; `--verbose` enables library diagnostics.
