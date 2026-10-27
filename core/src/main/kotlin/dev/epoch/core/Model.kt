package dev.epoch.core

/** Versioned, factual coverage of a declared SQL contract, never a whole application proof. */
data class DatabaseContract(
    val schemaVersion: Int = 1,
    val applicationVersion: String = "unspecified",
    val dialect: String = "PostgreSQL",
    val scope: VerificationScope = VerificationScope.STRUCTURAL_CONTRACT,
    val entities: List<EntityContract> = emptyList(),
    val queries: List<QueryContract> = emptyList(),
    val coverage: Coverage = Coverage(),
    val unresolvedQueries: List<UnresolvedQuery> = emptyList(),
)

enum class VerificationScope { STRUCTURAL_CONTRACT, WHOLE_APPLICATION }
enum class Verdict { SAFE, BREAKING, UNKNOWN }
enum class Operation { SELECT, INSERT, UPDATE, DELETE }
enum class QuerySource { HIBERNATE_MAPPING, NAMED_HQL, NAMED_NATIVE, STATIC, RUNTIME_OBSERVED, DYNAMIC_VARIANT }
enum class VerificationStatus { EXTRACTED, OBSERVED, UNSUPPORTED }

data class Coverage(
    val complete: Boolean = false,
    val mechanisms: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class UnresolvedQuery(val id: String, val reason: String)

/** table is schema-qualified; column names are the physical Hibernate column names. */
data class EntityContract(
    val id: String,
    val table: String,
    val columns: List<ColumnContract>,
    val operations: Set<Operation> = Operation.entries.toSet(),
    val generatedIdentifier: String? = null,
)

data class ColumnContract(
    val name: String,
    val typeName: String? = null,
    val nullable: Boolean? = null,
    val insertable: Boolean = true,
    val updatable: Boolean = true,
    val generated: Boolean = false,
)

data class ColumnReference(val table: String, val column: String)
data class ResultColumn(val name: String, val typeName: String? = null)

data class QueryContract(
    val id: String,
    val operation: Operation,
    val sql: String,
    val tables: List<String> = emptyList(),
    val columns: List<ColumnReference> = emptyList(),
    val writeColumns: List<ColumnReference> = emptyList(),
    val parameterTypes: List<String> = emptyList(),
    val resultColumns: List<ResultColumn> = emptyList(),
    val source: QuerySource = QuerySource.STATIC,
    val verificationStatus: VerificationStatus = VerificationStatus.EXTRACTED,
    val dependenciesComplete: Boolean = false,
    val explicitColumnList: Boolean = true,
    val exactResultShape: Boolean = false,
    val variant: String? = null,
    /** Explicit DML target; table dependencies may also include SELECT sources or joins. */
    val targetTable: String? = null,
)

data class SchemaSnapshot(
    val tables: Map<String, TableSchema> = emptyMap(),
    /** Stable names -> PostgreSQL definitions, including views, functions, triggers and policies. */
    val behaviors: Map<String, String> = emptyMap(),
    val sequences: Map<String, String> = emptyMap(),
    /** Extension name -> installed version; member objects are excluded from behaviors. */
    val extensions: Map<String, String> = emptyMap(),
)

data class TableSchema(
    val columns: Map<String, ColumnSchema> = emptyMap(),
    val constraints: Map<String, ConstraintSchema> = emptyMap(),
    val indexes: Map<String, String> = emptyMap(),
)

data class ColumnSchema(
    val type: String,
    val nullable: Boolean,
    val defaultExpression: String? = null,
    val identity: String? = null,
    val generated: String? = null,
    val ordinal: Int = 0,
)

data class ConstraintSchema(val type: String, val definition: String)
data class MigrationInfo(val version: String, val filename: String)

data class Finding(
    val verdict: Verdict,
    val ruleId: String,
    val objectName: String,
    val message: String,
    val evidence: String,
    val affectedContractIds: List<String> = emptyList(),
    val migration: MigrationInfo? = null,
    val mitigation: String? = null,
)

data class MigrationReport(
    val migration: MigrationInfo,
    val verdict: Verdict,
    val findings: List<Finding>,
)

data class CompatibilityReport(
    val schemaVersion: Int = 1,
    val applicationVersion: String,
    val scope: VerificationScope,
    val verdict: Verdict,
    val migrations: List<MigrationReport>,
    val limitations: List<String> = listOf(
        "SAFE is limited to the declared structural contract; it does not prove application behavior or operational migration safety.",
    ),
    /** Existing problems are recorded once, without pending-migration attribution. */
    val baseline: BaselineReport? = null,
    /** Optional accepted-baseline gate; never substitutes for the scientific verdict. */
    val gateVerdict: Verdict? = null,
    val acceptedBaseline: List<AcceptedBaselineFinding> = emptyList(),
    val ruleSummary: List<RuleSummary> = emptyList(),
)

/** A baseline fingerprint binds acceptance to the contract, catalog and baseline migration input. */
data class BaselineReport(
    val verdict: Verdict,
    val findings: List<Finding>,
    val fingerprint: String,
    val cacheStatus: String? = null,
    val cacheKey: String? = null,
)

data class AcceptedBaseline(
    val schemaVersion: Int = 1,
    val baselineFingerprint: String,
    val findings: List<AcceptedBaselineFinding>,
)

data class AcceptedBaselineFinding(val fingerprint: String, val justification: String)

data class RuleSummary(
    val ruleId: String,
    val verdict: Verdict,
    val baselineCount: Int = 0,
    val migrationCount: Int = 0,
    val acceptedBaselineCount: Int = 0,
)
