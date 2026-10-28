package dev.epoch.core

/** Pure classification of adapter-observed facts; it neither parses migration text nor measures time. */
object OperationalAssessmentRules {
    internal fun assess(facts: OperationalStatementFacts, sizes: TableSizes? = null): OperationalStatementReport {
        require(facts.ordinal > 0) { "Operational statement ordinals must be positive" }
        require(facts.lockTimeoutBefore.milliseconds >= 0 && facts.lockTimeoutAfter.milliseconds >= 0) {
            "Observed lock_timeout cannot be negative"
        }
        val findings = mutableListOf<OperationalRiskFinding>()
        fun add(verdict: OperationalVerdict, rule: String, name: String, message: String, evidence: String,
                relation: OperationalRelation? = null, mitigation: String? = null) {
            val grade = if (verdict == OperationalVerdict.RISKY) OperationalSizes.grade(
                relation ?: OperationalRelation(name, "unknown", null), sizes) else null
            findings += OperationalRiskFinding(rule, verdict, name, message,
                evidence + (grade?.let { "; ${it.evidence}" } ?: ""), facts.migration, facts.ordinal, grade?.severity, mitigation)
        }
        fun unknown(name: String, message: String, evidence: String, rule: String = "OP_STATEMENT_UNSUPPORTED") =
            add(OperationalVerdict.UNKNOWN, rule, name, message, evidence,
                mitigation = "Review the unsupported operation or provide a deterministic proof within the documented operational model.")
        fun safe(action: OperationalAction, rule: String, message: String, evidence: String) {
            if (!action.effectsComplete) unknown(action.target?.name ?: "statement", "The operation has unmodeled effects beyond its recognized catalog change.",
                evidence + "; " + action.evidence)
            else if (facts.opaqueEffects.isEmpty()) add(OperationalVerdict.SAFE, rule, action.target?.name ?: "statement", message,
                evidence + "; " + action.evidence)
        }
        fun risk(relation: OperationalRelation, rule: String, message: String, evidence: String, mitigation: String) =
            add(OperationalVerdict.RISKY, rule, relation.name, message, evidence, relation, mitigation)

        // Measured table locks establish exposure independently of recognizing a scan/rewrite.
        // This only grades lock acquisition protection. A timeout never limits how long an
        // acquired lock is held, and cannot waive the scan/rewrite rules below.
        val authoredOrigins = setOf(OperationalTimeoutOrigin.MIGRATION_LOCAL, OperationalTimeoutOrigin.MIGRATION_SESSION)
        val protectedAcquisition = facts.lockTimeoutBefore.milliseconds > 0 &&
            facts.lockTimeoutBefore.origin in authoredOrigins &&
            facts.lockTimeoutAfter.milliseconds == facts.lockTimeoutBefore.milliseconds &&
            facts.lockTimeoutAfter.origin == facts.lockTimeoutBefore.origin &&
            facts.opaqueEffects.isEmpty() && facts.actions.isNotEmpty() && facts.actions.all { action ->
                action.kind in ordinaryCommandKinds && action.catalogConfirmed && action.effectsComplete &&
                    action.target?.kind in tableKinds
            }
        fun blockingLocks(locks: List<OperationalLock>) = locks
            .filter { it.relationKind in tableKinds && it.mode in blockingModes }.toSet()
        val observedBlocking = blockingLocks(facts.heldLocksBefore + facts.heldLocksAfter + facts.newlyObservedLocks)
        // Literal settings do not acquire relation locks. Unchanged exact lock tuples are
        // retained acquisitions already assessed on prior statements, not new exposure caused
        // by SET. This does not erase prior findings, excuse opaque settings/callbacks, or
        // waive new/upgraded modes and identities (including a renamed physical relation).
        val settingsOnlyWithoutAcquisition = facts.actions.isNotEmpty() && facts.actions.all { action ->
            action.kind == OperationalActionKind.SETTINGS_CHANGE && action.catalogConfirmed && action.effectsComplete
        } && facts.opaqueEffects.isEmpty() && facts.rewrittenTables.isEmpty() &&
            blockingLocks(facts.heldLocksBefore) == blockingLocks(facts.heldLocksAfter) &&
            blockingLocks(facts.newlyObservedLocks).isEmpty()
        // These are required PostgreSQL modes for this adapter's bounded, catalog-confirmed
        // commands, not invented pg_locks observations. In particular METADATA_CHANGE is
        // currently ALTER TABLE metadata only, never a generic arbitrary-DDL certification.
        data class RequiredLock(val table: OperationalRelation, val mode: String, val command: String)
        val requiredLocks = facts.actions.filter { it.catalogConfirmed }.flatMap { action ->
            fun on(relations: List<OperationalRelation>, mode: String) = relations.filter { it.kind in tableKinds }
                .map { RequiredLock(it, mode, action.kind.name) }
            when (action.kind) {
                OperationalActionKind.METADATA_CHANGE, OperationalActionKind.SET_NOT_NULL,
                OperationalActionKind.ADD_CHECK, OperationalActionKind.ALTER_COLUMN_TYPE ->
                    on(listOfNotNull(action.target), "AccessExclusiveLock")
                OperationalActionKind.ADD_FOREIGN_KEY ->
                    on(listOfNotNull(action.target) + action.relatedTables, "ShareRowExclusiveLock")
                OperationalActionKind.CREATE_INDEX -> if (!action.concurrent)
                    on(listOfNotNull(action.target), "ShareLock") else emptyList()
                OperationalActionKind.CREATE_TABLE -> if (!action.noOp)
                    on(action.relatedTables, "ShareRowExclusiveLock") else emptyList() // Actual inline-FK references.
                else -> emptyList()
            }
        }.distinct()
        val oldLocks = observedBlocking.filter { it.table?.preExisting == true }
            .groupBy { identity(requireNotNull(it.table)) }
        val oldRequired = requiredLocks.filter { it.table.preExisting == true }.groupBy { identity(it.table) }
        if (!protectedAcquisition && !settingsOnlyWithoutAcquisition) for (baselineIdentity in (oldLocks.keys + oldRequired.keys).sorted()) {
            val locks = oldLocks[baselineIdentity].orEmpty()
            val required = oldRequired[baselineIdentity].orEmpty()
            // Prefer the after-snapshot name when a table was renamed in this statement, while
            // grading supplied size metadata using its original baseline identity.
            val relation = (facts.heldLocksAfter + facts.newlyObservedLocks).mapNotNull { it.table }
                .filter { it.preExisting == true && identity(it) == baselineIdentity }.sortedBy { it.name }.firstOrNull()
                ?: required.map { it.table }.sortedBy { it.name }.firstOrNull()
                ?: requireNotNull(locks.sortedWith(lockOrder).first().table)
            val observed = locks.map { it.mode }.distinct().sorted().joinToString().ifEmpty { "none retained in the samples" }
            val needed = required.map { "${it.mode} for ${it.command}" }.distinct().sorted().joinToString().ifEmpty { "not established" }
            risk(relation, "OP_WRITE_BLOCKING_LOCK",
                "A write-blocking lock on a pre-existing relation has no established migration-authored acquisition bound.",
                "Observed modes=$observed; required modes from catalog-confirmed PostgreSQL commands=$needed; " +
                    "sampled locked relations=${locks.map { it.relation }.distinct().sorted().joinToString().ifEmpty { "none" }}; " +
                    "baseline=${relation.baselineName}; Migration-authored lock_timeout before statement: ${authoredTimeoutEvidence(facts.lockTimeoutBefore)}; " +
                    "lock_timeout before=${facts.lockTimeoutBefore.milliseconds}ms (${facts.lockTimeoutBefore.origin}), " +
                    "after=${facts.lockTimeoutAfter.milliseconds}ms (${facts.lockTimeoutAfter.origin}). Verifier timeouts are not deployment-authored protection. " +
                    "Only a supported ordinary command without opaque effects can use a positive authored before-timeout; neither sampled nor required locks establish scan timing or hold duration.",
                "Use an explicit migration-authored lock_timeout before the operation, keep lock-holding transactions short, and review unsupported procedural effects.")
        }
        val unknownObserved = observedBlocking.filter { it.table?.preExisting == null }.groupBy { it.relation }
        val unknownRequired = requiredLocks.filter { it.table.preExisting == null }.groupBy { it.table.name }
        for (name in (unknownObserved.keys + unknownRequired.keys).sorted()) unknown(name,
            "A write-blocking lock has no established old/new table identity.",
            "Observed modes=${unknownObserved[name].orEmpty().map { it.mode }.distinct().sorted().joinToString()}; " +
                "required modes=${unknownRequired[name].orEmpty().map { it.mode }.distinct().sorted().joinToString()}; table identity is incomplete.")

        for (rewrite in facts.rewrittenTables.distinct().sortedBy { it.afterName }) when (rewrite.table.preExisting) {
            true -> risk(rewrite.table, "OP_TABLE_REWRITE", "The statement rewrote pre-existing relation storage.",
                "Observed the same physical relation with changed nonzero storage identity; before=${rewrite.beforeName}; after=${rewrite.afterName}; baseline=${rewrite.table.baselineName}.",
                "Use an expand/backfill/contract plan or schedule the rewrite with an explicit production locking and capacity plan.")
            null -> unknown(rewrite.afterName, "A measured rewrite has no established pre-existing/new-window identity.",
                "before=${rewrite.beforeName}; after=${rewrite.afterName}; preExisting=null")
            false -> Unit // A new relation is exempt only when the whole recognized action supports that proof.
        }
        if (facts.opaqueEffects.isNotEmpty()) unknown("statement", "Applicable database callbacks or procedural effects are not exhaustively modeled.",
            facts.opaqueEffects.distinct().sorted().joinToString("; "), "OP_DATABASE_CALLBACK_UNPROVEN")

        // A lock first observed after a statement may have been acquired by an end-of-DDL
        // callback after its scan. Prior held locks are the positive retained-lock witness;
        // normal build/validation locks are also established by the action-specific PG rules.
        val held = facts.heldLocksBefore.distinct().sortedWith(lockOrder)
        val blocking = held.filter { lock -> lock.relationKind in tableKinds && lock.mode in blockingModes }
        fun retainedBlockingRisk(scan: String, scanningAction: OperationalAction): Boolean {
            var found = false
            val established = blocking.mapNotNull { it.table?.let(::identity) }.toMutableSet()
            for (lock in blocking) {
                val relation = lock.table
                when (relation?.preExisting) {
                    true -> {
                        risk(relation, "OP_VALIDATION_UNDER_RETAINED_LOCK", "A scan runs while this pre-existing table is held under a write-blocking lock.",
                            "Scan=$scan; observed held mode=${lock.mode}; relation=${lock.relation}; baseline=${relation.baselineName}. Held locks include earlier statements in the same transaction, not only newly acquired locks.",
                            "Release strong table locks before running validation/build scans, for example by separating the operations into transactions.")
                        found = true
                    }
                    null -> {
                        unknown(lock.relation, "A scan runs under a blocking lock whose table identity is incomplete.",
                            "Scan=$scan; mode=${lock.mode}; relationKind=${lock.relationKind}; table=$relation")
                        found = true
                    }
                    false -> Unit
                }
            }
            if (facts.actions.size > 1) {
                // PostgreSQL chooses the strongest ALTER TABLE subcommand lock before
                // processing any of its subcommands, including otherwise-online validation.
                val scanned = scanningAction.target
                val compound = facts.actions.filter { it.catalogConfirmed && it.kind in strongAlterKinds &&
                    it.target?.preExisting == true && scanned?.preExisting == true && identity(it.target) == identity(scanned) }
                for (strong in compound.distinctBy { identity(it.target!!) }) {
                    val relation = strong.target!!
                    risk(relation, "OP_VALIDATION_UNDER_COMPOUND_LOCK", "A scan shares an ALTER TABLE statement with a subcommand requiring a write-blocking lock.",
                        "Scan=$scan; strong subcommand=${strong.kind}; PostgreSQL acquires the maximum required ALTER TABLE lock for the whole statement before its subcommands.",
                        "Separate the strong metadata change from validation into different transactions so its lock is released before the scan.")
                    established += identity(relation)
                    found = true
                }
                for (lock in facts.heldLocksAfter.filter { it.relationKind in tableKinds && it.mode in blockingModes }) {
                    val relation = lock.table
                    if (relation?.preExisting == false || relation?.let(::identity) in established) continue
                    unknown(lock.relation, "A compound statement finishes with an old-table blocking lock whose timing relative to its scan is not established.",
                        "Scan=$scan; after-only mode=${lock.mode}; relation=$relation; subcommands=${facts.actions.map { it.kind }}")
                    found = true
                }
            }
            return found
        }
        fun relations(action: OperationalAction) = (listOfNotNull(action.target) + action.relatedTables).distinct()
        fun allNew(action: OperationalAction): Boolean = relations(action).isNotEmpty() && relations(action).all { it.preExisting == false }
        fun newOperation(action: OperationalAction) = safe(action, "OP_NEW_RELATION_OPERATION",
            "The supported operation affects only relations created within this pending window.",
            "All affected relation identities are absent from the baseline; names=${relations(action).map { it.name }.sorted()}; no retained blocking lock on an old table was observed during a recognized scan.")

        val actions = facts.actions.ifEmpty { listOf(OperationalAction(OperationalActionKind.UNKNOWN, evidence = "No recognized action was supplied.")) }
        for (action in actions) {
            val target = action.target
            if (action.kind in setOf(OperationalActionKind.UNKNOWN, OperationalActionKind.DATA_CHANGE)) {
                unknown(target?.name ?: "statement", "The statement is outside the supported operational proof model.", action.evidence)
                continue
            }
            if (!action.catalogConfirmed) {
                unknown(target?.name ?: "statement", "The recognized statement effect is not corroborated by before/after catalog or session facts.",
                    "kind=${action.kind}; ${action.evidence}")
                continue
            }
            if (action.noOp) {
                safe(action, "OP_METADATA_ONLY", "Catalog observations establish that the recognized operation did not perform a build or validation.",
                    "Known no-op for ${action.kind}.")
                continue
            }
            if (action.kind == OperationalActionKind.SETTINGS_CHANGE) {
                safe(action, "OP_METADATA_ONLY", "A recognized session setting changed without a relation scan or rewrite.",
                    "lock_timeout before=${facts.lockTimeoutBefore.milliseconds}ms (${facts.lockTimeoutBefore.origin}); after=${facts.lockTimeoutAfter.milliseconds}ms (${facts.lockTimeoutAfter.origin}). Verifier settings are not deployment-authored protection.")
                continue
            }
            if (target == null || target.preExisting == null) {
                unknown(target?.name ?: "statement", "The action's physical target or baseline identity is not established.",
                    "kind=${action.kind}; target=$target; ${action.evidence}")
                continue
            }
            when (action.kind) {
                OperationalActionKind.CREATE_TABLE -> {
                    if (target.preExisting == false) safe(action, "OP_NEW_RELATION_OPERATION",
                        "A supported ordinary CREATE TABLE does not validate pre-existing rows.",
                        "New physical relation=${target.name}; related relations=${action.relatedTables.map { it.name }.sorted()}.")
                    else unknown(target.name, "CREATE TABLE was not established as a new relation or a known no-op.", action.evidence)
                }
                OperationalActionKind.CREATE_INDEX -> {
                    if (action.concurrent && !facts.autoCommit) {
                        unknown(target.name, "Concurrent-index execution is inconsistent with the observed transactional connection.",
                            "concurrent=true; autoCommit=false; ${action.evidence}")
                        continue
                    }
                    val retained = retainedBlockingRisk("CREATE INDEX on ${target.name}", action)
                    if (!action.concurrent && target.preExisting == true) risk(target, "OP_INDEX_BUILD_BLOCKS_WRITES",
                        "An ordinary index build scans pre-existing data while blocking table writes.",
                        "A catalog-confirmed non-concurrent index was built; PostgreSQL acquires ShareLock for the build. Empty post-autocommit lock samples do not imply that no lock was held.",
                        "Use CREATE INDEX CONCURRENTLY in a nontransactional migration where supported, with explicit production timeout and capacity policy.")
                    else if (!retained) {
                        if (allNew(action)) newOperation(action)
                        else if (action.concurrent) safe(action, "OP_ONLINE_INDEX_BUILD",
                            "The supported successful concurrent index build permits ordinary table writes during its scans.",
                            "Catalog-confirmed valid/ready index; nontransactional execution; no retained old-table blocking lock observed. Long-transaction waits and I/O remain outside this SAFE proof.")
                        else unknown(target.name, "The index build involves related relations with unestablished old/new identity.", action.evidence)
                    }
                }
                OperationalActionKind.SET_NOT_NULL -> {
                    when {
                        action.priorNotNull == true || action.priorValidatedNonNullCheck == true -> safe(action, "OP_VALIDATED_NOT_NULL",
                            "A prior non-null invariant establishes that SET NOT NULL needs no row-validation scan.",
                            "priorNotNull=${action.priorNotNull}; priorValidatedNonNullCheck=${action.priorValidatedNonNullCheck}.")
                        action.priorNotNull == false && action.priorValidatedNonNullCheck == false -> {
                            val retained = retainedBlockingRisk("SET NOT NULL on ${target.name}", action)
                            if (target.preExisting == true) risk(target, "OP_NOT_NULL_SCAN",
                                "SET NOT NULL validates pre-existing rows while holding AccessExclusiveLock.",
                                "The column was nullable and no validated supported non-null CHECK preceded this statement.",
                                "Add a supported CHECK NOT VALID, validate it after releasing strong locks, then SET NOT NULL using the validated proof.")
                            else if (!retained && allNew(action)) newOperation(action)
                            else if (!retained) unknown(target.name, "The validation includes relations without a new-window proof.", action.evidence)
                        }
                        else -> unknown(target.name, "The prior column/CHECK facts do not establish whether SET NOT NULL scans.",
                            "priorNotNull=${action.priorNotNull}; priorValidatedNonNullCheck=${action.priorValidatedNonNullCheck}; ${action.evidence}")
                    }
                }
                OperationalActionKind.ADD_CHECK, OperationalActionKind.ADD_FOREIGN_KEY -> {
                    if (action.notValid) safe(action, "OP_DEFERRED_CONSTRAINT_VALIDATION",
                        "The catalog-confirmed NOT VALID constraint skips the initial existing-row validation scan.",
                        "kind=${action.kind}; notValid=true. Lock acquisition and later validation are assessed separately.")
                    else {
                        val retained = retainedBlockingRisk("immediate ${action.kind} validation on ${target.name}", action)
                        val old = relations(action).filter { it.preExisting == true }
                        for (relation in old) risk(relation, "OP_CONSTRAINT_VALIDATION_BLOCKS_WRITES",
                            "Immediate constraint validation can hold a write-blocking lock while checking pre-existing data.",
                            "Catalog-confirmed validated ${action.kind}; owning relation=${target.name}; affected relation=${relation.name}; referenced relations=${action.relatedTables.map { it.name }.sorted()}.",
                            "Separate constraint installation with NOT VALID from a later validation after releasing strong table locks.")
                        if (old.isEmpty() && !retained) {
                            if (allNew(action)) newOperation(action)
                            else unknown(target.name, "The constraint's related relation identities are incomplete.", action.evidence)
                        }
                    }
                }
                OperationalActionKind.VALIDATE_CONSTRAINT -> {
                    when (action.priorConstraintValidated) {
                        true -> safe(action, "OP_METADATA_ONLY", "The constraint was already validated before this statement.",
                            "priorConstraintValidated=true; no new row-validation scan is required.")
                        false -> {
                            val retained = retainedBlockingRisk("VALIDATE CONSTRAINT on ${target.name}", action)
                            if (!retained) {
                                if (relations(action).any { it.preExisting == null }) unknown(target.name,
                                    "Constraint validation includes relations with unknown baseline identity.", action.evidence)
                                else if (allNew(action)) newOperation(action)
                                else safe(action, "OP_NONBLOCKING_VALIDATION", "The supported validation scan does not hold an observed old-table write-blocking lock.",
                                    "A previously unvalidated constraint became validated; PostgreSQL's normal validation lock permits ordinary DML. All held old-table locks were checked, including those from previous statements.")
                            }
                        }
                        null -> unknown(target.name, "The constraint's prior validation state is unavailable.", action.evidence)
                    }
                }
                OperationalActionKind.ALTER_COLUMN_TYPE -> {
                    // Even without a heap rewrite, a type change can rebuild indexes or validate values.
                    val retained = facts.rewrittenTables.isNotEmpty() && retainedBlockingRisk("ALTER COLUMN TYPE rewrite on ${target.name}", action)
                    val unboundedHold = blocking.any { it.table?.preExisting != false }
                    if (allNew(action) && !retained && !unboundedHold) newOperation(action)
                    else if (facts.rewrittenTables.none { it.table.preExisting == true }) unknown(target.name,
                        "An existing-relation type change has no complete no-scan or rewrite proof.",
                        "Absence of an observed heap rewrite does not establish absence of validation or index work; ${action.evidence}")
                }
                OperationalActionKind.METADATA_CHANGE -> safe(action, "OP_METADATA_ONLY",
                    "The supported catalog change needs no existing-row scan or rewrite within this model.", action.evidence)
                else -> unknown(target.name, "The recognized action has no operational proof rule.", action.evidence)
            }
            if (!action.effectsComplete && findings.none { it.verdict == OperationalVerdict.UNKNOWN && it.objectName == target.name })
                unknown(target.name, "Additional effects of the recognized action are outside the operational proof model.", action.evidence)
        }
        if (findings.isEmpty()) unknown("statement", "No positive operational assessment was established.", "Recognized actions did not provide a complete proof.")
        val ordered = findings.distinct().sortedWith(findingOrder)
        return OperationalStatementReport(facts.migration, facts.ordinal, facts.sql, facts.autoCommit,
            facts.lockTimeoutBefore, facts.lockTimeoutAfter,
            facts.heldLocksBefore.distinct().sortedWith(lockOrder), facts.heldLocksAfter.distinct().sortedWith(lockOrder),
            facts.newlyObservedLocks.distinct().sortedWith(lockOrder), facts.rewrittenTables.distinct().sortedWith(
                compareBy<OperationalRewrite> { it.afterName }.thenBy { it.beforeName }), ordered,
            aggregateVerdicts(ordered.map { it.verdict }))
    }

    /** Call only with measured statements; null/missing legacy assessments are not an empty measured window. */
    fun aggregate(statements: List<OperationalStatementReport>, complete: Boolean = true): OperationalAssessment {
        val copied = statements.map { it.copy(heldLocksBefore = it.heldLocksBefore.toList(), heldLocksAfter = it.heldLocksAfter.toList(),
            newlyObservedLocks = it.newlyObservedLocks.toList(), rewrittenTables = it.rewrittenTables.toList(), findings = it.findings.toList()) }
        return OperationalAssessment(aggregateVerdicts(copied.map { it.verdict }, complete), copied,
            copied.flatMap { it.findings }.distinct().sortedWith(findingOrder), limitations + when {
                !complete -> listOf("The requested deployment window was not completely observed; completed evidence cannot certify the remaining statements.")
                copied.isEmpty() -> listOf("The completed measured pending window contained no SQL statements.")
                else -> emptyList()
            })
    }

    fun aggregateVerdicts(values: List<OperationalVerdict>, complete: Boolean = true): OperationalVerdict = when {
        OperationalVerdict.RISKY in values -> OperationalVerdict.RISKY
        !complete || OperationalVerdict.UNKNOWN in values -> OperationalVerdict.UNKNOWN
        else -> OperationalVerdict.SAFE
    }

    private val tableKinds = setOf("table", "partitioned_table", "materialized_view")
    private val blockingModes = setOf("ShareLock", "ShareRowExclusiveLock", "ExclusiveLock", "AccessExclusiveLock")
    private val strongAlterKinds = setOf(OperationalActionKind.METADATA_CHANGE, OperationalActionKind.SET_NOT_NULL,
        OperationalActionKind.ADD_CHECK, OperationalActionKind.ADD_FOREIGN_KEY, OperationalActionKind.ALTER_COLUMN_TYPE)
    private val ordinaryCommandKinds = setOf(OperationalActionKind.CREATE_TABLE, OperationalActionKind.CREATE_INDEX,
        OperationalActionKind.SET_NOT_NULL, OperationalActionKind.ADD_CHECK, OperationalActionKind.ADD_FOREIGN_KEY,
        OperationalActionKind.VALIDATE_CONSTRAINT, OperationalActionKind.ALTER_COLUMN_TYPE, OperationalActionKind.METADATA_CHANGE)
    private fun identity(relation: OperationalRelation) = relation.baselineName ?: relation.name
    private fun authoredTimeoutEvidence(timeout: OperationalTimeout): String = when (timeout.origin) {
        OperationalTimeoutOrigin.VERIFIER -> "none (VERIFIER)"
        OperationalTimeoutOrigin.UNKNOWN -> "unknown (UNKNOWN)"
        OperationalTimeoutOrigin.MIGRATION_LOCAL, OperationalTimeoutOrigin.MIGRATION_SESSION ->
            if (timeout.milliseconds == 0L) "disabled (0ms; ${timeout.origin})"
            else "${timeout.milliseconds}ms (${timeout.origin})"
    }
    private val lockOrder = compareBy<OperationalLock> { it.relation }.thenBy { it.relationKind }.thenBy { it.mode }
        .thenBy { it.table?.baselineName ?: "" }.thenBy { it.table?.name ?: "" }
    private fun rank(verdict: OperationalVerdict) = when (verdict) { OperationalVerdict.RISKY -> 0; OperationalVerdict.UNKNOWN -> 1; OperationalVerdict.SAFE -> 2 }
    private val findingOrder = compareBy<OperationalRiskFinding> { rank(it.verdict) }.thenBy { it.migration.version }
        .thenBy { it.migration.filename }.thenBy { it.statementOrdinal }.thenBy { it.ruleId }.thenBy { it.objectName }.thenBy { it.evidence }
    val limitations: List<String> = listOf(
        "Operational SAFE is limited to supported PostgreSQL lock-acquisition, write-blocking scan and storage-rewrite proofs; it does not guarantee production latency, lock hold duration, I/O capacity, or general deployment safety.",
        "Locks and lock_timeout are observed on the disposable migration connection. Verifier-injected timeouts do not establish deployment-authored protection; after-autocommit lock samples may be empty after locks were released.",
        "Observed old-table write-blocking locks remain RISKY independently of SQL recognition unless a supported ordinary command has a positive migration-authored acquisition timeout. Catalog-confirmed bounded commands also establish required locks when autocommit releases them before sampling; required modes are labeled separately. A supported literal setting with unchanged complete blocking-lock tuples does not create another acquisition finding; earlier statement risks remain in the migration result. Unsupported effects remain UNKNOWN alongside risk; timeouts never waive blocking scan or rewrite risks.",
        "Optional table sizes grade risk severity only. Missing estimates stay unknown, fixture sizes are not production evidence, and smaller estimates never turn RISKY into SAFE.",
    )
}
