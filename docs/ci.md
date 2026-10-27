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

Preserve the executable's exit status in CI. Upload the JSON report after verification, including nonzero compatibility results. Startup or baseline failure can occur before a report is written; never reuse an earlier run's report as the current result.

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

The cache key covers all baseline file names and bytes, PostgreSQL image identity, Flyway version, and relevant settings. File locks and atomic publication protect concurrent writers; archive and catalog checksums detect corruption or an incompatible restore. Flyway validates restored history against current baseline files. Reports expose `DISABLED`, `MISS`, `HIT`, or `REBUILT` in `baseline.cacheStatus`. A valid hit avoids rerunning the historical migrations. Invalid entries are rebuilt from the supplied baseline; no stale pending-migration state is reused.

Preserve this directory between trusted CI runs if desired. The cache must be outside both migration directories; nested paths and symlink aliases are rejected. Baseline and pending directories must not overlap, since Flyway scans them recursively. Cache metadata can differ between otherwise equivalent reports; compare compatibility evidence and verdicts when assessing repeatability across cache states.

## Logging and offline use

The CLI logs warnings/errors by default. Add `--verbose` (or `-v`) for library INFO logs and full exception traces. Failures always include their underlying cause chain. Default reports group findings by rule and print at most 20 details, prioritizing migrations. `report --summary` omits detail; `--details` prints everything retained in JSON.

Initial setup downloads Gradle, dependencies, and Docker images. Once all dependencies for the selected tasks and the required images are cached:

```sh
./gradlew --offline --no-daemon test :cli:installDist
```

`--offline` controls Gradle dependency resolution. Docker must still have the images locally. Installed CLI distributions contain their runtime JARs and do not need Gradle to extract or report. Baseline caching is separate from dependency/image caching and does not remove the Docker requirement.

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| Exit `3` with `NoClassDefFoundError` | Inspect the reported missing dependency and descriptor classpath. Bean Validation is disabled during extraction. |
| Hibernate version mismatch | Use this adapter with Hibernate 6.6.13.Final or use a compatible adapter for the application's version. Hibernate 7 extraction is not implemented. |
| Entity list too large | Use `scanPackages` over compiled class directories/JARs. |
| A mapping group fails bootstrap | Use `--keep-going` to preserve independent results; failed groups remain unresolved. |
| Container or Ryuk cannot start | Read the cause chain, check Docker access, both required images, and runner cleanup/permission requirements. |
| Baseline debt dominates output | Read `baseline` and rule summaries; use reviewed baseline acceptance if a migration-only gate is appropriate. |
| Cache repeatedly rebuilds | Check file permissions, archive integrity, and catalog differences after restoration. |
| Migration returns `UNKNOWN` | Inspect new findings and retained baseline debt separately; acceptance does not prove missing behavior. |

There is no production JDBC URL or production credential configuration step.
