package dev.epoch.core

/** Independent from SQL compatibility; scoped to measured blocking exposure, scans and rewrites. */
enum class OperationalVerdict { SAFE, RISKY, UNKNOWN }
enum class OperationalSeverity { LOW, MEDIUM, HIGH }
enum class OperationalTimeoutOrigin { VERIFIER, MIGRATION_SESSION, MIGRATION_LOCAL, UNKNOWN }

/** Optional production catalog estimates supplied by the caller; Epoch does not fetch production data. */
data class TableSizes(val schemaVersion: Int = 1, val tables: Map<String, TableSize> = emptyMap())
data class TableSize(val estimatedRows: Long? = null, val totalBytes: Long? = null)

/** Physical identity is resolved by the adapter; only stable names cross the report boundary. */
data class OperationalRelation(
    val name: String,
    val kind: String,
    val preExisting: Boolean?,
    val baselineName: String? = null,
)

data class OperationalLock(
    val relation: String,
    val relationKind: String,
    val mode: String,
    /** The locked table itself, or the owning table of a distinct index relation. */
    val table: OperationalRelation? = null,
)

data class OperationalRewrite(
    val table: OperationalRelation,
    val beforeName: String,
    val afterName: String,
)

data class OperationalTimeout(
    val milliseconds: Long,
    val postgresSource: String,
    val origin: OperationalTimeoutOrigin = OperationalTimeoutOrigin.UNKNOWN,
)

data class OperationalRiskFinding(
    val ruleId: String,
    val verdict: OperationalVerdict,
    val objectName: String,
    val message: String,
    val evidence: String,
    val migration: MigrationInfo,
    val statementOrdinal: Int,
    val severity: OperationalSeverity? = null,
    val mitigation: String? = null,
)

data class OperationalStatementReport(
    val migration: MigrationInfo,
    val ordinal: Int,
    val sql: String,
    val autoCommit: Boolean,
    val lockTimeoutBefore: OperationalTimeout,
    val lockTimeoutAfter: OperationalTimeout,
    val heldLocksBefore: List<OperationalLock>,
    val heldLocksAfter: List<OperationalLock>,
    val newlyObservedLocks: List<OperationalLock>,
    val rewrittenTables: List<OperationalRewrite>,
    val findings: List<OperationalRiskFinding>,
    val verdict: OperationalVerdict,
)

data class OperationalAssessment(
    val verdict: OperationalVerdict,
    val statements: List<OperationalStatementReport>,
    val findings: List<OperationalRiskFinding>,
    val limitations: List<String>,
)

/** Adapter-proven statement meaning. Missing proof fields must never imply a cheap operation. */
internal enum class OperationalActionKind {
    CREATE_TABLE, CREATE_INDEX, SET_NOT_NULL, ADD_CHECK, ADD_FOREIGN_KEY,
    VALIDATE_CONSTRAINT, ALTER_COLUMN_TYPE,
    /** Bounded ALTER TABLE metadata only; a confirmed action requires its normal table lock. */
    METADATA_CHANGE, SETTINGS_CHANGE,
    DATA_CHANGE, UNKNOWN,
}

internal data class OperationalAction(
    val kind: OperationalActionKind,
    val target: OperationalRelation? = null,
    val relatedTables: List<OperationalRelation> = emptyList(),
    val catalogConfirmed: Boolean = false,
    val effectsComplete: Boolean = false,
    val concurrent: Boolean = false,
    val notValid: Boolean = false,
    val noOp: Boolean = false,
    val priorNotNull: Boolean? = null,
    val priorValidatedNonNullCheck: Boolean? = null,
    val priorConstraintValidated: Boolean? = null,
    val evidence: String = "",
)

internal data class OperationalStatementFacts(
    val migration: MigrationInfo,
    val ordinal: Int,
    val sql: String,
    val autoCommit: Boolean,
    val lockTimeoutBefore: OperationalTimeout,
    val lockTimeoutAfter: OperationalTimeout,
    val heldLocksBefore: List<OperationalLock>,
    val heldLocksAfter: List<OperationalLock>,
    val newlyObservedLocks: List<OperationalLock>,
    val rewrittenTables: List<OperationalRewrite>,
    val actions: List<OperationalAction>,
    val opaqueEffects: List<String> = emptyList(),
)
