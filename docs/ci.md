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

## Compatibility CI policy

`verify`, `verify-release`, and saved `report` replay share `--fail-on unknown|breaking`. The default `unknown` blocks UNKNOWN and BREAKING; `breaking` permits completed UNKNOWN results while teams address that debt. This changes exit policy only: JSON, terminal verdicts, findings and coverage remain unchanged. Without baseline acceptance the policy uses the overall verdict; with reviewed `--accepted-baseline` it uses the distinct `gateVerdict`. All requested directions contribute, including new-writes-old-reads.

| Completed compatibility gate | `--fail-on unknown` (default) | `--fail-on breaking` |
| --- | ---: | ---: |
| SAFE | 0 | 0 |
| BREAKING | 1 | 1 |
| UNKNOWN | 2 | 0 |

For example, replay an existing report under the less restrictive policy without rerunning or changing its evidence:

```sh
db-compat report --input artifacts/compatibility-report.json --summary --fail-on breaking
```

Invalid policy values, other invalid input, runtime failures and incomplete reports always return `3`. The optional operational gate below adds exit `4` after compatibility passes the selected policy. See the [rule reference](compatibility-model.md) for each verdict's proof and limits; allowing UNKNOWN does not establish SAFE.

Preserve the executable's exit status in CI. Upload the JSON report after verification, including nonzero compatibility results. Verification checkpoints are explicitly incomplete until the entire requested run succeeds; incomplete reports return exit 3 when rendered and cannot be used to accept baseline debt. Invalid arguments can still fail before a checkpoint is created, so never reuse an earlier run's report as the current result.

## Operational CI gate

Every forward verification records measured operational findings for pending migrations. Default CI behavior remains compatibility-only. Add the boolean `--fail-on-operational` to require operational `SAFE` as well:

```sh
db-compat verify \
  --contract artifacts/old-contract.json \
  --baseline migrations/baseline --migrations migrations/pending \
  --table-sizes artifacts/table-sizes.json \
  --fail-on-operational --report artifacts/compatibility-report.json

db-compat report --input artifacts/compatibility-report.json \
  --summary --fail-on-operational
```

`verify-release` accepts the same `--table-sizes` and `--fail-on-operational` options. `report` replays saved grading and can apply the gate without rerunning migrations; it does not regrade with a new size file. The gate requires a measured operational SAFE result regardless of risk severity. A LOW RISKY finding still fails. Baseline acceptance affects compatibility debt only and does not waive operational findings.

| Precedence | Exit |
| --- | ---: |
| Invalid input, runtime failure, failed measurement, or incomplete run | 3 |
| Compatibility gate BREAKING | 1 |
| Compatibility gate UNKNOWN with the default `--fail-on unknown` | 2 |
| Compatibility policy passes (including UNKNOWN with `--fail-on breaking`); requested operational result is RISKY, UNKNOWN, or unavailable | 4 |
| Compatibility policy passes and the optional operational requirement is satisfied or disabled | 0 |

An older saved report without operational fields returns 4 under that flag only after its compatibility policy passes. With `--new-contract`, all requested compatibility directions contribute first, while operational assessment comes solely from the actual forward migration sequence. Store JSON checkpoints and reports even on exit 4. `report --summary` shows operational rule counts; `--details` adds every retained statement and finding, timeout changes, held-after locks, and rewrites. JSON also retains before-statement and newly observed lock sets. Current SARIF exports compatibility findings only, so keep JSON for operational review. `lint` remains a separate static advisory command and does not satisfy the measured gate.

`OP_WRITE_BLOCKING_LOCK` can make a structurally compatible metadata migration operationally RISKY. It uses actual old-table locks, including FK parents and retained dropped-table identities, or separately labeled required modes for catalog-confirmed supported commands when autocommit releases locks before sampling. Unsupported SQL can retain both measured RISKY evidence and UNKNOWN effects. Required modes are not fabricated lock samples.

For supported ordinary metadata changes, configure acquisition protection in the migration itself:

```sql
SET LOCAL lock_timeout = '750ms';
ALTER TABLE IF EXISTS accounts ADD COLUMN IF NOT EXISTS metadata text;
```

The positive authored timeout must precede the command and remain unchanged afterward; complete effects and absence of opaque callbacks are also required. `SET LOCAL` applies within its transaction; a nontransactional deployment needs deliberate session-scoped configuration. Literal lock-timeout settings do not themselves create structural uncertainty. Other settings, function calls and procedural effects retain their normal limits. Epoch's verifier-injected timeout does not count as migration-authored protection.

This policy bounds acquisition waiting only. It does not limit lock hold duration or waive blocking scans, validation under retained locks, or rewrites. An existing-column no-op still acquires ALTER's lock. See the [operational proof boundaries](verification.md#operational-migration-assessment) before interpreting a SAFE metadata finding as an overall operational result.

## Table-size estimates

`--table-sizes FILE` is optional metadata input for severity, and never supplies a database connection. Export estimates for the original baseline schema using your normal authorized read-only database workflow, then pass only the JSON file to Epoch. Epoch neither queries production nor interprets disposable fixture sizes as production volume.

```json
{
  "schemaVersion": 1,
  "tables": {
    "public.accounts": { "estimatedRows": 1000000, "totalBytes": 268435456 }
  }
}
```

This PostgreSQL query returns the input JSON using system catalog estimates and relation-size metadata; it does not scan application rows. Save its single output value, without a client header/footer, as `table-sizes.json` (for example using your client's unaligned tuples-only output):

```sql
SELECT jsonb_pretty(jsonb_build_object(
  'schemaVersion', 1,
  'tables', COALESCE(jsonb_object_agg(
    n.nspname || '.' || c.relname,
    jsonb_build_object(
      'estimatedRows', CASE WHEN c.reltuples >= 0
        THEN ceil(c.reltuples::numeric)::bigint ELSE NULL END,
      'totalBytes', pg_total_relation_size(c.oid)
    )
  ), '{}'::jsonb)
))
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'm')
  AND n.nspname NOT LIKE 'pg_%'
  AND n.nspname <> 'information_schema'
  AND c.relname <> 'flyway_schema_history';
```

The export includes ordinary physical tables, partition leaves, and materialized views; partition parents have no summed estimate here. `reltuples` can be stale or unknown, so negative estimates become JSON null. `totalBytes` includes indexes and TOAST. Review the snapshot's age and chosen schema scope. Use original baseline names even when pending migrations rename tables; names are exact stored catalog identifiers without SQL quoting, and embedded dots are unsupported.

| Severity for an established risk | Required supplied estimates |
| --- | --- |
| LOW | Rows ≤ 10,000 **and** total bytes ≤ 16 MiB (16,777,216). |
| MEDIUM | Otherwise, rows ≤ 1,000,000 **and** total bytes ≤ 1 GiB (1,073,741,824). |
| HIGH | Either medium limit exceeded, either estimate missing, table missing, or baseline identity unavailable. |

Both estimates must be nonnegative integers to lower severity; explicit zero is valid, but missing is not zero. Duplicate keys, normalized-name collisions, invalid numbers, and unsupported schema versions fail with exit 3. Grading is deterministic and advisory: it changes severity, never RISKY to SAFE, and does not estimate migration duration. Findings retain the supplied values and baseline lookup name without embedding the local input path.

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

This operation writes every non-safe baseline finding with that justification and exits `0` on success under either `--fail-on` policy. If `--fail-on-operational` is also requested, its independent gate still applies after the file is written. Review the file, remove entries that should remain blocking, and replace the shared justification with individual explanations where needed. Keep the reviewed file in version control, then add `--accepted-baseline accepted-baseline.json` to verification.

Acceptance never suppresses new migration findings. A stale contract, baseline input, catalog, or evidence fingerprint is rejected. Regenerate and review the file when those inputs deliberately change. An accepted-baseline gate can pass while the overall compatibility verdict remains `UNKNOWN`; display both in CI.

## Baseline cache

`--baseline-cache DIR` is optional. Epoch saves a custom-format `pg_dump` of the completed baseline, including Flyway history and baseline fixture data. It never caches the database after pending migrations. Each run starts a fresh container and restores the saved baseline into that owned database.

The cache key covers all baseline file names and bytes, PostgreSQL image identity, Flyway version, and relevant settings. File locks coordinate cache builders. Each published manifest names an immutable archive generation, so warm readers validate and restore outside the writer lock. Archive and catalog checksums detect corruption or an incompatible restore. Flyway validates restored history against current baseline files. Reports expose `DISABLED`, `MISS`, `HIT`, or `REBUILT` in `baseline.cacheStatus`. A valid hit avoids rerunning the historical migrations. Every `MISS` or `REBUILT` includes a `baseline.cacheReason`, also logged immediately after cache preparation under `--verbose`. Invalid entries are rebuilt from the supplied baseline; no stale pending-migration state is reused. The manifest-based cache uses a new format key, so older cache entries produce a cold miss after upgrading. Old archive generations are retained to protect readers already restoring them; remove the cache directory only when no verification uses it.

Preserve this directory between trusted CI runs if desired. The cache must be outside both migration directories; nested paths and symlink aliases are rejected. Baseline and pending directories must not overlap, since Flyway scans them recursively. Cache metadata can differ between otherwise equivalent reports; compare compatibility evidence and verdicts when assessing repeatability across cache states.

## Planning cost and interrupted runs

Use `--planning-parallelism 4` to bound independent PostgreSQL planning workers (1–32; default 4). Selected queries with the known baseline verification-session settings and supported planning effects can run in parallel after each migration commits. They finish before the next migration script starts, so an incompatible intermediate schema cannot be hidden by a later repair. The final pending migration is checked after Flyway returns successfully.

The migration owner supplies operational observations, not application query evidence. Temporary shadows, SET LOCAL and migration-only roles are discarded before contract checking. Checks that are unsafe to parallelize run serially on a committed verification connection. Fresh sessions that already differ from the baseline policy make the selected query evidence UNKNOWN; they cannot reuse prior accepted plans. Session drift during a check, worker failure or unexpected commit-time catalog change fails with exit 3 and the original migration identity. No failed COMMIT is published as a completed step. See [planning boundaries](planning.md).

Each step computes its catalog changes once and retains the previous plans for unaffected complete dependencies. Incomplete SQL, hidden database behavior, and relevant constraint or type changes take the conservative re-check path. `replannedQueries` and `reusedQueries` show the work performed. A 10,000-statement contract with 100 statements affected per step needs 10,000 re-checks over 100 steps, rather than 1,000,000. Baseline contract-check caching is separate from the database dump cache and remains bound to the contract, baseline, catalog, and planner format. Graph/dependency-model changes invalidate older catalog and planning-cache formats instead of reusing obsolete proof inputs.

Size CI from measured planning cost on the actual runner. For example, at 4 ms per statement, 10,000 serial checks cost about 40 seconds before startup, catalog inspection, and report output. Re-checking 100 statements represents roughly 0.4 seconds of serial planning work at the same rate, before orchestration and catalog cost. Eligible worker checks can overlap, but connection setup, serial-only checks and two additional canonical catalog inspections per pending step remain overhead. These are arithmetic estimates, not latency or linear-speedup promises. Start with 2–4 planning workers and leave memory and CPU for PostgreSQL as well as the JVM; increase the global timeout for large contracts. Do not multiply every migration by the entire contract size when dependencies are complete.

The CLI writes an initial incomplete checkpoint and replaces it atomically after baseline verification and each completed step. On timeout, failure, SIGINT, or SIGTERM, completed results remain available. `report` identifies the checkpoint as incomplete and returns exit 3 even if its completed steps were safe. The in-flight step is not claimed complete, including while committed worker checks are pending, and SIGKILL cannot run a shutdown hook. Its per-migration deadline keeps the same budget through SQL, COMMIT and verification; waiting for workers does not start a new timeout. Generic signal checkpoints still use the existing interruption label rather than a separate pending-worker-stage field. Reports omit elapsed times, process IDs, and scheduling-dependent ordering; compare findings and verdicts separately from cache-hit metadata.

## Logging and offline use

Library output, including migration PostgreSQL NOTICEs, is quiet by default; the CLI still prints its own findings, classpath warnings and concise failure cause chains. Add `--verbose` (or `-v`) for library INFO logs and original exception traces. Extraction keeps shared diagnostic causes and their full source inventory even when normal output is compact.

Default reports print per-rule counts of raw baseline findings and migration observations, and spend at most 20 detail items. An established shared-source group under one of the three supported behavior/rename rules consumes one item; an ungrouped finding consumes one. Output prioritizes BREAKING migrations/findings, then newly reported episodes within a severity, and limits affected-ID samples to 20. `findingChanges` and terminal transition counts distinguish new, persisting and no-longer-reported caller identities without changing the gate.

Default extraction summarizes up to 20 classes without dumping individual access paths. `report --summary` omits individual finding evidence while retaining available transition counts; `extract --details`, `verify --details` and `report --details` expose the retained inventories. `report --details` expands every raw finding rather than collapsing shared-source groups. Full JSON and SARIF retain individual current evidence; large JSON files are not compacted by these display groups. See [report interpretation](reporting.md) for group scope, origins and count denominators.

Extraction resolves Hibernate's PostgreSQL 16.8 dialect from pinned offline product facts without a database connection. It does not inject the deprecated explicit-dialect or legacy JDBC-metadata settings that would cause its own verbose warnings. Custom application dialect resolvers are unsupported for this pinned model; see [bootstrap settings](contracts.md#extraction-descriptor).

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

`lint --migrations db/pending --report build/operations.json` remains optional static review. Its hints are separate from the measured operational assessment and do not affect the compatibility exit code. Configure `--migration-timeout-seconds` and `--global-timeout-seconds` for your runner; a failed migration or infrastructure failure exits 3. No PostgreSQL NOTICE or library stack trace is emitted in default CLI logging; `--verbose` enables library diagnostics.
