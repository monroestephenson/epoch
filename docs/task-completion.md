# Ordered field-trial task completion

This log follows the requested 17-item order. Each completed item is checked with `./gradlew --no-daemon --offline test`, including the Docker-backed PostgreSQL suite. Gradle may reuse passing results for unchanged inputs. Proof-only additions do not replace an already working implementation.

| Item | Status | Evidence and remaining limits |
| --- | --- | --- |
| 1. Hibernate 7 | SKIPPED (verified done) | `Hibernate7CompiledFixtureTest.Kotlin List associations compiled against Hibernate 7 extract with the application runtime` compiles Kotlin generic `@ManyToMany` and join-table `@OneToMany` entities against Hibernate 7.1.4, then extracts with real 7.0.10 and 7.1.4 runtimes. The bundled 6.6 runtime stays isolated. Added the missing proof fixture; no adapter rework. Full test command passed. |
| 2. Repository and inline queries | Pending | |
| 3. Execution and failure semantics | Pending | |
| 4. Performance and partial results | Pending | |
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
