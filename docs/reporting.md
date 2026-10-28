# Reading compatibility reports

Every pending schema state is compared with the old application's baseline. A broken V2 remains BREAKING at V3 if V3 leaves that problem in place; a later restoration does not erase the failed intermediate state. Baseline health is separate and has no pending-migration attribution. Optional finding transitions identify when each continuing failure was first reported, while shared-source groups reduce repeated terminal detail without removing evidence.

## Inspect the saved result

These commands read the same saved JSON without running PostgreSQL or migrations again:

```sh
db-compat report --input artifacts/compatibility-report.json
db-compat report --input artifacts/compatibility-report.json --summary
db-compat report --input artifacts/compatibility-report.json --details
```

Default output puts BREAKING findings first, prioritizes new findings within a severity, and bounds detail. `--summary` retains verdicts, rule counts and available transition counts without individual evidence. `--details` expands every raw finding, affected ID and origin instead of collapsing shared-source groups. `--summary` and `--details` are mutually exclusive. Replay uses the selected [CI policy](ci.md#compatibility-ci-policy) and any already recorded accepted-baseline gate. A BREAKING gate exits 1; an incomplete checkpoint exits 3 under either compatibility policy.

## New and continuing findings

Each migration may contain `findingChanges`. A finding episode follows the same `(verdict, ruleId, objectName, affectedContractId)` through successive checked states. A finding without affected IDs contributes one unscoped identity. SAFE proof explanations do not create episodes.

| Kind | Meaning |
| --- | --- |
| `NEW` | First reported at this checked state, including recurrence after an absent state or a newly affected caller. |
| `PERSISTING` | The same identity was reported at the previous checked state; `firstObserved` retains its earlier start. |
| `RESOLVED` | No longer reported at this checked state. Human output says **no longer reported**; this is not an independent safety proof. |

For example, dropping `accounts.email` at V2, changing an unrelated table at V3, restoring the column at V4, and dropping it again at V5 yields NEW at V2, PERSISTING from V2 at V3, no longer reported at V4, and NEW from V5 at V5. The deployment window remains BREAKING. An UNKNOWN finding replacing a BREAKING finding also ends one identity and starts another; it does not prove the operation safe.

`firstObserved` identifies the first **checked state** of that continuous episode, not an inferred causal SQL line. Each original finding keeps its current migration and evidence. SARIF likewise keeps the file where that current evidence was observed. A grouped cause can contain callers with different origins, and text retains those distinctions.

Before annotation, known baseline identities are removed per affected caller. Baseline callers A/B followed by the same pending cause affecting A/B/C leave C in the pending findings. Existing invalid-baseline object exclusions remain in force. These reporting changes do not change accepted-baseline fingerprints, justify new debt, or turn baseline uncertainty into proof. The [accepted-baseline workflow](ci.md#review-existing-baseline-debt) still requires exact fingerprints and explicit justifications.

## Shared-source display groups

Groups are produced only for repeated non-SAFE findings under these three rule IDs:

- `DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN`
- `EXISTING_DATABASE_BEHAVIOR`
- `RELATION_RENAME_EXPRESSION_UNPROVEN`

They are contained within one baseline or migration section and one rule/verdict. Exact reached catalog sources can group multiple CHECK/default/index limitations that share the same complete set of named opaque dependencies. Source definitions from both snapshots are retained in the group's identity. Identical typed SQL templates can also share a group; differences in SQL, parameters, result mappings, variants or dependency facts keep them separate. An exact shared inventory failure has its own group kind.

A matching generic reason, exception class, or function basename is insufficient. Missing graph objects, incomplete attachment inventories and unsupported local expressions without an established shared source stay separate. Overloaded functions and distinct full source sets are not conflated. Sharing a function does not establish that proving the function would discharge every attached expression's local limitation.

The default display spends one detail-budget item on a group and shows its source IDs, member/object/caller counts, bounded object names and example evidence. Its remaining members stay in JSON and `--details`. Optional index metadata is validated before display; malformed or conflicting indexes fall back to raw findings. Groups do not change rule outcomes, compatibility gates or the evidence behind them.

**General JSON compaction remains partial:** extraction already groups repeated diagnostics, and these three compatibility rules now have shared-source display groups. The full JSON still contains every raw finding plus small index records. It can remain large. There is no compact-JSON output mode, arbitrary cross-rule root-cause deduplication, or removal of local evidence.

## Count denominators

| Output | What is counted |
| --- | --- |
| Rule summary | Raw baseline findings, raw migration **observations** across all checked steps, and accepted baseline findings. A continuing failure contributes an observation at each step where it is reported. |
| Finding transitions | Distinct identities per affected contract ID, or once for an unscoped finding. Separate NEW, PERSISTING and no-longer-reported counts do not count SQL executions or display groups. |
| Group header | Raw member findings, distinct affected object names and distinct affected contract IDs within that group. These counts can differ from transition counts. |
| Default detail budget | One item for a shared-source group or one ungrouped finding. This only limits terminal detail. |

## JSON, SARIF and checkpoints

`findingChanges` and `findingGroups` are optional additions to report schema version 1; their [fields are documented here](contracts.md#report-and-accepted-baseline-schemas). Absent/null metadata in an older report does not establish origins or groups. Replay preserves that absence instead of inventing history.

Export compatibility findings with their current migration locations:

```sh
db-compat report --input artifacts/compatibility-report.json \
  --sarif artifacts/compatibility-report.sarif \
  --migration-source-prefix db/migration --summary
```

SARIF keeps one result per raw non-SAFE finding. Optional `properties.findingChanges` carries only that result's affected identities; `properties.findingGroupId` links members without replacing their locations or evidence. No extra SARIF failure result is created for a no-longer-reported episode. These deployment-state transitions do not use SARIF's `baselineState` field. Operational findings remain in JSON and text, outside the current SARIF export.

An incomplete checkpoint retains completed findings and their episode/group metadata. It does not append an unverified empty state to resolve active episodes, certify an interrupted migration, or bypass exit 3. Ending an episode or collapsing its display never erases an earlier intermediate failure. Accepted-baseline and `--fail-on` policies continue using the underlying verdicts and findings.
