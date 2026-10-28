# Planning and commit boundaries

`--planning-parallelism` limits independent PostgreSQL planning workers to 1–32, with a default of 4. It changes scheduling, not the compatibility model. The report retains every intermediate schema and compares it with the original old contract.

For a pending migration, Epoch uses this order:

1. Execute its SQL through Flyway and capture statement locks, rewrites and timeout provenance on the migration connection.
2. Capture a canonical precommit catalog for comparison. Migration-local roles, settings and temporary objects are operational/commit evidence; they are not application query evidence.
3. Let Flyway write its history and commit. A successful after-migration callback is not treated as proof that COMMIT succeeded.
4. Open a fresh committed verification connection, compare the canonical catalog, and capture query/rule dependencies in that connection's actual context. Check its session policy against the baseline policy before reusing or planning queries.
5. Check the selected queries and optional execution witnesses. Eligible independent query groups can use workers; other selected checks run serially on the committed verification connection. Publish the completed step only after all required work succeeds.
6. Start the following migration script. The final step uses the successful return from Flyway as its commit boundary.

This ordering applies to versioned and repeatable pending migrations. Nontransactional migrations, including supported concurrent-index scripts, still need their normal Flyway sidecar configuration. Epoch does not replay migrations, manually commit Flyway transactions, or clone a database per step.

## Session context and reuse

The reference is the **baseline verification session used for contract checks**: the owned database's configured credentials/settings, effective search path and current/session users. This is not a reconstruction of arbitrary production roles, application interceptors or connection initialization. Fresh committed connections and workers must preserve the known session policy before their results can support compatibility evidence.

PostgreSQL loads procedural-language and extension settings lazily. Registering default `plpgsql.*` or `pg_trgm.*` settings during planning does not change policy: dotted setting names are omitted only when PostgreSQL reports the `default` source and the value equals its boot default. Non-default and session-authored values remain tracked. Applicable database and login-role custom overrides are recorded before their extension loads, using PostgreSQL's precedence (database-and-role, role, then database). Their declared source/literal remains the policy after PostgreSQL canonicalizes it, such as `.9` to `0.9`; a session override is still distinct. `SET ROLE` does not apply the target role's login settings. Capture reads catalog/settings metadata without executing an initialization block or preload, and reads no unrelated role configuration or configuration files.

Unregistered custom values set through unusual server-level configuration or unmodeled session initialization are outside this bounded inventory. If loading a module reveals such a non-default policy unexpectedly during checks, the run fails with `PENDING_PLANNING_SESSION_CHANGED`, retaining setting names without values; it does not produce a false BREAKING verdict. A fresh connection whose known policy already differs still produces `QUERY_SESSION_UNPROVEN` as described below.

A temporary table or SET LOCAL setting visible only on the migration connection cannot make the old application BREAKING after commit. The same boundary applies to PostgreSQL plan failures, optional boundary-execution counterexamples, and the catalog/name bindings supplied to compatibility rules. Only operational facts retain the migration owner's transient context.

If a fresh committed connection already differs from the baseline policy, one `QUERY_SESSION_UNPROVEN` UNKNOWN finding lists all affected baseline-accepted templates. Its evidence lists differing setting names; setting values are not included. No SQL, boundary execution or plan reuse is credited under that policy, and planning/reuse/worker counts are zero. This is an unestablished verification model, not a PostgreSQL rejection of an old query. Session policy is checked before reuse too: a continuing mismatch retains unverified checks, and restoration of the baseline policy forces every baseline-accepted template to be checked again independently of the catalog diff.

Within a matching policy, the dependency diff selects templates that need re-checking. Reports expose `replannedQueries`, `reusedQueries` and actual `planningParallelism`. The first count describes selected template checks under an established session policy; it is not a count of SQL executions. Checks withheld because the session policy is unestablished are excluded from both planning and reuse counts. A step with no selected checks may report zero workers. Serial-only batches may report one even when the configured upper bound is four. Parallel batch results are merged in contract order, while infrastructure failures can cancel unfinished work promptly.

The canonical catalog comparison temporarily uses an explicit `pg_catalog, pg_temp` path so ordinary deparser/path differences do not imitate commit-time DDL. The exact original setting is restored afterward, using transaction-local changes where appropriate. The committed connection is also inspected under its actual verification context for query/rule evidence; canonical inspection does not replace that evidence. Only the canonical snapshot's path field is excluded from equality. Changed catalog objects or behavior facts still stop verification.

## Failures and checkpoints

`COMMITTED_SCHEMA_CHANGED` means the captured structural catalog did not survive commit unchanged. `PENDING_PLANNING_SESSION_CHANGED` means a connection or worker changed its expected policy while checks were running. Both are execution failures (exit 3), not compatibility UNKNOWN or BREAKING. The latter differs from an already mismatched fresh connection, which produces the unverified query evidence described above. Earlier completed steps remain in the incomplete checkpoint. A deferred failure retains the preceding migration's identity even if it occurs at the next migration's before-callback.

One pending-migration deadline covers its script, history insertion/COMMIT, catalog checks, query workers and publication. Waiting for a future does not grant another complete timeout. Expiry cancels work and aborts registered connections. Global verification cancellation remains independent. A connection failure or failed COMMIT cannot become a completed compatibility finding.

The last report remains incomplete while any required work is pending. Generic SIGINT/SIGTERM checkpoint labeling has no separate pending-worker-stage field; preserve the report and diagnostic cause together. SIGKILL cannot run a shutdown hook. Report replay stays exit 3 for incomplete evidence.

## Cost and validation scope

Pooling can reduce selected planning time, while serial-only checks remain ordered. This implementation adds two canonical catalog inspections per pending step to establish the commit boundary; it does not promise a speedup for every schema or contract. Measure costs on the intended runner and size deadlines from observed workload. Incremental dependency counts are a more reproducible regression target than wall-clock assertions.

The prepared regressions cover committed workers, incompatible intermediate states, failed and effectful commits, mixed versioned/repeatable/concurrent-index migrations, transient migration sessions, policy-dependent reuse, original deadline consumption and prior-step attribution. The internal worker-failure test uses a JDBC connection-factory failure; it is not a deliberate live PostgreSQL worker-kill test. Existing container-loss and signal tests cover their separate infrastructure paths. See the implementation ledger for actual validation status.
