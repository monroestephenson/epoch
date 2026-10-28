# Additional field-trial feedback

This tracks the feedback received during item 13 of the [ordered implementation](task-completion.md). Validation uses Epoch-owned application fixtures and disposable PostgreSQL databases. The field-trial application repository is outside the authorized test scope.

| Feedback | Status | Evidence or remaining work |
| --- | --- | --- |
| 1. Hibernate 7 discovery from application JARs | IMPLEMENTED | `Hibernate7JarDiscoveryTest` uses separate Kotlin entity/repository JARs compiled against actual 7.4.9, both with and without application ASM core. Keeping Epoch's ASM family together fixes the reproduced class cast. A native-only column rename still produces PostgreSQL SQLSTATE 42703 and the affected repository query ID. |
| 2. Selection-expression recursion and query isolation | IMPLEMENTED | `QueryTranslationIsolationTest` proves ordinary selection references under 6.6 and 7.4.9. `QueryExtractionBoundaryTest` covers cycles and atomic publication; `FailingQueryRendererTest` proves a real Hibernate renderer StackOverflow leaves healthy HQL/native/finite/ORM siblings available. `BootstrapCancellationTest` keeps cancellation and fatal failures out of keep-going recovery, including wrapped/suppressed failures. Full suite passed: 443 tests, no failures or skips. |
| 3. Missing baseline column crashes | IMPLEMENTED | `DeclaredWriteDomainTest` covers the null-key lookup in the catalog's sorted enum map. `WriteDomainCompatibilityTest.a dormant mapped column absent from baseline is reported without a crash or pending attribution` verifies actual PostgreSQL baseline health and continued verification. |
| 4. Global database-behavior dependency rule | Pending | Scope behavior uncertainty, query invalidation, and planning-cache eligibility together using catalog dependencies and contract reachability. Opaque reachable function bodies require explicit limits. |
| 5. CTE names as physical relations | Pending | Resolve lexical SQL scopes or retain incomplete dependencies without inventing physical CTE objects. |
| 6. Association join-column declarations | Pending | Establish supported identifier-reference facts without copying a non-null object declaration onto nullable natural keys or unknown composite leaves. |
| 7. Inherited Panache API owners | Pending | Resolve application base interfaces outside the selected scan packages and preserve generic entity context. |
| 8. Constant Panache query chains | Pending | Link terminal operations to their originating query; count operations need their own generated variant. |
| 9. Pagination variants | Pending | Apply known options through Hibernate and retain the generated SQL/parameter variants; unsupported aliasing or expressions stay unresolved. |
| 10. Index bounds from the contract | Pending | Use established finite write domains without treating raw DDL length hints as universal runtime input limits. Preserve the PostgreSQL oversized-key counterexample. |
| 11. Inconsistent nullability verdicts | IMPLEMENTED | SET NOT NULL and supported non-null CHECKs share one proof path. Core and PostgreSQL tests distinguish declarations, omitted INSERTs, framework restrictions, transformations, optional rows, and unvalidated existing rows. |
| 12. Full contract passes for a small change | Pending | Remove unrelated behavior from invalidation and preserve selective planning metrics. |
| 13. Parallel checks for transactional pending steps | Pending | Check eligible committed states before the next migration executes, retaining every intermediate state and session/deadline boundaries. |
| 14. Baseline query-plan caching | Existing implementation verified | `BaselinePlanCacheTest` proves MISS→HIT with zero warm planning calls. Feedback 4 must also remove unrelated behavior from cache exclusions. |
| 15. New findings versus continuing failures | Pending | Preserve baseline-relative compatibility while marking the step that introduced each failure episode. |
| 16. Repeated-root-cause aggregation | Pending | Group repeated diagnostics without dropping the affected source inventory. |
| 17. Causes and verbose caught traces | Implemented for query extraction; discovery follow-up pending | `QueryFailureDiagnosticsTest` runs fresh quiet/verbose CLI processes, proves identical portable contracts and retained healthy SQL, and verifies original caught traces only under verbose logging. Generated/HQL/native/runtime parser failures retain bounded cause summaries and source IDs. Discovery diagnostics and grouped causes are tracked with feedback 16/18. |
| 18. Coverage denominators | Pending | Report the detected database API invocation inventory, including inherited and unresolved sites, with explicit scan-scope limits. |
