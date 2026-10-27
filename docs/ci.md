# CI and local execution

## Build from a clean checkout

Provide JDK 21 and a working Docker daemon, then run:

```sh
./gradlew --no-daemon test :cli:installDist
cli/build/install/db-compat/bin/db-compat --help
```

Do not interpret a successful compilation or a Docker-free unit-test run as a PostgreSQL integration-test result. The full `test` task includes integration tests that require Docker and fail when the database cannot start.

Gradle uses the repository's worker and memory limits in `gradle.properties`. Increase them only when the runner has sufficient resources. Set `JAVA_HOME` to a JDK 21 installation; the wrapper is not a JDK installer.

## Gate migrations

Keep a reviewed contract for each old application version still running during the migration window. Run verification against the correct baseline and candidate migration directory:

```sh
cli/build/install/db-compat/bin/db-compat verify \
  --contract artifacts/old-release-contract.json \
  --baseline migrations/baseline \
  --migrations migrations/pending \
  --report artifacts/compatibility-report.json
```

The executable returns `0` for `SAFE`, `1` for `BREAKING`, `2` for `UNKNOWN`, and `3` for invalid input or runtime failure. Preserve this exit status in CI. Upload the JSON report as an artifact after verification completes, including when the compatibility verdict is nonzero. Runtime failure can occur before a report is written; do not reuse a report from an earlier run.

Render a saved report with:

```sh
cli/build/install/db-compat/bin/db-compat report \
  --input artifacts/compatibility-report.json
```

Rendering also returns the verdict's exit code. It does not rerun verification.

## Offline after caching

Initial setup downloads the Gradle distribution, Maven dependencies, and required Docker images. Preserve the Gradle user-home cache and Docker image cache between CI runs when appropriate. Once **all dependencies for the requested tasks and all required images** are cached, Gradle can run without dependency-network access:

```sh
./gradlew --offline --no-daemon test :cli:installDist
```

`--offline` affects Gradle dependency resolution. Docker still needs its required images locally; Testcontainers may use helper images as well as PostgreSQL. A cache miss is a failure to fix by populating the cache from the configured registries. Do not disable signature, TLS, or checksum verification to populate it.

Installed CLI distributions contain their runtime JARs and can extract or render reports without Gradle. Verification still needs a working Docker daemon and locally available images when offline.

## Troubleshooting

| Symptom | Next check |
| --- | --- |
| JDK toolchain unavailable | Confirm `java -version`, JDK 21 installation, and `JAVA_HOME`. |
| Entity class cannot load | Build the old application and check descriptor-relative classpath entries, dependencies, and fully qualified class names. |
| Hibernate bootstrap differs from the application | Supply the correct naming strategies and dependencies; assess settings not represented by the descriptor. |
| Container cannot start | Confirm Docker access, image availability, and runner container permissions. |
| Migration fails | Read the Flyway/PostgreSQL error and reproduce against the disposable baseline; distinguish invalid SQL/schema assumptions from a tool startup problem. |
| Verification returns `UNKNOWN` | Read every unknown finding and the contract's coverage/unresolved sources; successful startup does not resolve evidence gaps. |

There is no production database URL or production credential configuration step.
