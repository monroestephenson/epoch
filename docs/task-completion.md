# Ordered field-trial task completion

This log follows the requested 17-item order. Each completed item is checked with `./gradlew --no-daemon --offline test`, including the Docker-backed PostgreSQL suite. Gradle may reuse passing results for unchanged inputs. Proof-only additions do not replace an already working implementation.

| Item | Status | Evidence and remaining limits |
| --- | --- | --- |
| 1. Hibernate 7 | SKIPPED (verified done) | `Hibernate7CompiledFixtureTest.Kotlin List associations compiled against Hibernate 7 extract with the application runtime` compiles Kotlin generic `@ManyToMany` and join-table `@OneToMany` entities against Hibernate 7.1.4, then extracts with real 7.0.10 and 7.1.4 runtimes. The bundled 6.6 runtime stays isolated. Added the missing proof fixture; no adapter rework. Full test command passed. |
| 2. Repository and inline queries | IMPLEMENTED | `InlineQueryCoverageTest.compiled Kotlin query sources have complete dependencies and inline-only rename breaks real SQL` covers all requested fixed sources plus a located unresolved dynamic query. `LiteralDiscoveryTest` proves normalization and bounded variants; `NativeDependenciesTest` covers conservative dependency boundaries. The real PostgreSQL `ORDER BY and RETURNING dependencies invalidate only their affected native plans` regression proves selective re-planning. Runtime capture cross-checks static SQL without claiming exhaustive dynamic coverage. Full test command passed. |
| 3. Execution and failure semantics | SKIPPED (verified done) | `VerificationOperationsTest.CREATE INDEX CONCURRENTLY completes with Flyway nontransactional migration and lock` and its timeout test pass. Added `RuntimeCliPostgresTest.stopping the owned container during a pending migration exits 3 instead of UNKNOWN` and `malformed pending SQL exits 3 with PostgreSQL syntax evidence and migration stage`; both verify actual CLI exit 3 and `MIGRATION_EXECUTION_FAILED`. No production rework. Full test command passed. |
| 4. Performance and partial results | IMPLEMENTED | `VerificationScaleTest.ten thousand statements over one hundred migrations only replan changed table dependencies` checks 10,000 real PostgreSQL templates across 100 steps, with 100 re-plans and 9,900 reused plans per step. Its visibility test covers pooled committed and serial transactional checks. `BaselinePlanCacheTest` verifies cached contract checks and invalidation; `VerificationCheckpointIntegrationTest` verifies timeout and actual SIGTERM checkpoints. Incomplete reports exit 3. Full suite passed: 283 tests, no failures or skips. |
| 5. Baseline cache | Pending | |
| 6. SQL dependency attribution | Pending | |
| 7. Identifier truncation | Pending | |
| 8. Compatibility views | Pending | |
| 9. Keep-going granularity | Pending | |
| 10. Declared nullability | Pending | |
| 11. Enum and converter metadata | Pending | |
| 12. SAFE rule refinements | Pending | |
| 13. BREAKING rule refinements | Pending | |
| 14. Old/new contract diff | Pending | |
| 15. Operational verdict | Pending | |
| 16. Output and usability | Pending | |
| 17. Persistence-unit descriptors | Pending | |
