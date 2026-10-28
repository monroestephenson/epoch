package dev.epoch.core

import com.fasterxml.jackson.annotation.JsonIgnore

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
    val accessPaths: List<AccessPathCoverage> = emptyList(),
)

enum class VerificationScope { STRUCTURAL_CONTRACT, WHOLE_APPLICATION }
enum class Verdict { SAFE, BREAKING, UNKNOWN }
enum class Operation { SELECT, INSERT, UPDATE, DELETE }
enum class QuerySource { HIBERNATE_MAPPING, NAMED_HQL, NAMED_NATIVE, STATIC, RUNTIME_OBSERVED, DYNAMIC_VARIANT }
enum class VerificationStatus { EXTRACTED, OBSERVED, UNSUPPORTED }
enum class EnumMode { STRING, ORDINAL }
enum class EnumEncoding { NAME, ORDINAL, EXPLICIT }

data class Coverage(
    val complete: Boolean = false,
    val mechanisms: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

data class UnresolvedQuery(
    val id: String,
    val reason: String,
    /** Complete source inventory for one shared extraction failure; empty retains single-source semantics. */
    val affectedIds: List<String> = emptyList(),
)

/** table is schema-qualified; column names are the physical Hibernate column names. */
data class EntityContract(
    val id: String,
    val table: String,
    val columns: List<ColumnContract>,
    val operations: Set<Operation> = Operation.entries.toSet(),
    val generatedIdentifier: String? = null,
    val identifierSequence: String? = null,
    val identifierAllocationSize: Int? = null,
    val hierarchy: HierarchyContract? = null,
)

data class ColumnContract(
    val name: String,
    val typeName: String? = null,
    val nullable: Boolean? = null,
    val insertable: Boolean = true,
    val updatable: Boolean = true,
    val generated: Boolean = false,
    val length: Long? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    /** Database values produced by a supported enum mapping, not Java enum names guessed from reflection. */
    val enumValues: List<String> = emptyList(),
    val logicalJavaType: String? = null,
    val inputDomainComplete: Boolean = true,
    /** Logical mapped member's declared type; null means unavailable, ambiguous, or unsupported. */
    val declaredNonNull: Boolean? = null,
    /** Raw Hibernate selectable nullability, independent of Kotlin/Java declaration facts. */
    val hibernateNullable: Boolean? = null,
    /** Supported Hibernate enum mapping mode; null for unknown/custom conversion semantics. */
    val enumMode: EnumMode? = null,
    /** Java/Kotlin declaration order, not a sorted set. */
    val enumConstants: List<String> = emptyList(),
    /** Database values aligned with enumConstants; empty when conversion is unsupported. */
    val enumRelationalValues: List<String> = emptyList(),
    val enumEncoding: EnumEncoding? = null,
    val converterClass: String? = null,
    /** SHA-256 of the converter's defining class artifact; not a semantic equivalence proof. */
    val converterBytecodeSha256: String? = null,
    /** A supported explicit unbounded string declaration; missing length alone is not evidence. */
    val lengthUnbounded: Boolean = false,
    /** Positive direct decoder fact, independent of write transformations and generated values. */
    val readDomainComplete: Boolean = false,
    /** A known direct String binder has no Hibernate DDL-length cap; database admission still applies. */
    val stringInputLengthUnrestricted: Boolean = false,
)

data class ColumnReference(val table: String, val column: String)
data class ResultColumn(val name: String, val typeName: String? = null)
data class MappedColumnRead(
    val entityId: String,
    val column: ColumnReference,
    /** One-based actual selected JDBC slot; null means unavailable, never inferred from labels. */
    val resultPosition: Int? = null,
)
data class PolymorphicRead(val rootEntityId: String, val unrestricted: Boolean = false)
data class DiscriminatorWrite(val entityId: String, val column: ColumnReference, val value: String)
data class HierarchyContract(
    val rootEntityId: String?,
    val strategy: String,
    val concrete: Boolean,
    val discriminator: ColumnReference? = null,
    val discriminatorType: String? = null,
    val entityValue: String? = null,
    val acceptedValues: List<String> = emptyList(),
    val complete: Boolean = false,
)

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
    val accessPathIds: List<String> = emptyList(),
    val observationFrames: List<String> = emptyList(),
    /** Exact entity/table mapping entries from which this ORM statement was generated. */
    val mappedEntityIds: List<String> = emptyList(),
    /** Actual selected Hibernate decoder bindings, never predicate-only dependencies. */
    val mappedReads: List<MappedColumnRead> = emptyList(),
    val mappedReadCoverageComplete: Boolean = false,
    val polymorphicReads: List<PolymorphicRead> = emptyList(),
    /** Literal discriminator values supplied by actual supported generated INSERT statements. */
    val discriminatorWrites: List<DiscriminatorWrite> = emptyList(),
)

data class SchemaSnapshot(
    val tables: Map<String, TableSchema> = emptyMap(),
    /** Stable names -> PostgreSQL definitions, including views, functions, triggers and policies. */
    val behaviors: Map<String, String> = emptyMap(),
    val sequences: Map<String, String> = emptyMap(),
    /** Extension name -> installed version; member objects are excluded from behaviors. */
    val extensions: Map<String, String> = emptyMap(),
    val sequenceOwners: Map<String, ColumnReference> = emptyMap(),
    val enumTypes: Map<String, List<String>> = emptyMap(),
    /** Actual current_schemas(true), including implicit schemas, for interpreting deparsed expressions. */
    val expressionSearchPath: List<String> = emptyList(),
    val behaviorGraph: BehaviorGraph? = null,
)

data class TableSchema(
    val columns: Map<String, ColumnSchema> = emptyMap(),
    val constraints: Map<String, ConstraintSchema> = emptyMap(),
    val indexes: Map<String, String> = emptyMap(),
    val kind: String = "table",
    val uniqueIndexes: Map<String, Boolean> = emptyMap(),
    val plainIndexes: Set<String> = emptySet(),
    val indexColumns: Map<String, List<String>> = emptyMap(),
    val indexMethods: Map<String, String> = emptyMap(),
    val indexValidity: Map<String, Boolean> = emptyMap(),
    val indexReadiness: Map<String, Boolean> = emptyMap(),
    val view: ViewSchema? = null,
    val relationSecurity: RelationSecurity? = null,
    /** Live-database identity for rename proofs; never a portable catalog or fingerprint fact. */
    @get:JsonIgnore val relationOid: Long? = null,
)

/** Conservative guards for proving a compatibility view without a full permission model. */
data class RelationSecurity(
    val owner: String,
    /** Includes explicit relation privileges and explicit privileges on any live column. */
    val explicitPrivileges: Boolean,
    val rowSecurity: Boolean,
    val userTriggers: Boolean,
    val userRules: Boolean,
    val inheritance: Boolean = false,
)

/** PostgreSQL capability bits are UPDATE=4, INSERT=8 and DELETE=16. */
data class ViewSchema(
    val definition: String,
    val updatableMask: Int,
    val updatableMaskWithTriggers: Int,
    val updatableColumns: Set<String>,
    val updatableColumnsWithTriggers: Set<String>,
    val backingTable: String? = null,
    /** Definition shape only; identity, permissions and behavior need separate proof. */
    val completeDirectProjection: Boolean = false,
    val options: List<String> = emptyList(),
)

data class ColumnSchema(
    val type: String,
    val nullable: Boolean,
    val defaultExpression: String? = null,
    val identity: String? = null,
    val generated: String? = null,
    val ordinal: Int = 0,
    /** Stable schema-qualified collation name; null for non-collatable types. */
    val collation: String? = null,
    /** Catalog-proven absence of domain constraints, type-level NOT NULL, and type defaults. */
    val typeAcceptsImplicitNull: Boolean? = null,
)

data class ConstraintSchema(
    val type: String,
    val definition: String,
    val columns: List<String> = emptyList(),
    val referencedTable: String? = null,
    val referencedColumns: List<String> = emptyList(),
    val onDelete: String? = null,
    val onUpdate: String? = null,
    val checkExpression: String? = null,
    val validated: Boolean = true,
)
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
    val replannedQueries: Int? = null,
    val reusedQueries: Int? = null,
    /** Effective number of planning workers; transactional DDL is checked on its owning connection. */
    val planningParallelism: Int? = null,
    /** Actual pending-statement observations; null means no measured assessment is available. */
    val operational: OperationalAssessment? = null,
    /** Immediate before/after catalog observation; null means no comparison was recorded. */
    val catalogChanged: Boolean? = null,
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
    val accessPaths: List<AccessPathCoverage> = emptyList(),
    /** A checkpoint cannot certify all requested migration states. */
    val complete: Boolean = true,
    val incompleteReason: String? = null,
    /** Independent operational assessment of the executed deployment window only. */
    val operationalVerdict: OperationalVerdict? = null,
    val operationalLimitations: List<String> = emptyList(),
)

/** A baseline fingerprint binds acceptance to the contract, catalog and baseline migration input. */
data class BaselineReport(
    val verdict: Verdict,
    val findings: List<Finding>,
    val fingerprint: String,
    val cacheStatus: String? = null,
    val cacheKey: String? = null,
    val cacheReason: String? = null,
    val planCacheStatus: String? = null,
    val planCacheKey: String? = null,
    val planCacheReason: String? = null,
    val plannedQueries: Int? = null,
    val reusedQueries: Int? = null,
    val planningParallelism: Int? = null,
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

/** Independently reported schema rollout directions and new-write/old-read data compatibility. */
data class BidirectionalReport(
    val schemaVersion: Int = 1,
    val oldOnNew: CompatibilityReport,
    val newOnOld: CompatibilityReport,
    val verdict: Verdict,
    val gateVerdict: Verdict? = null,
    /** New mapped writes assessed against old mapped readers on the final deployment schema. */
    val dataCompatibility: CompatibilityReport? = null,
    /** The old-on-new deployment assessment; reverse schema/data checks execute no migration. */
    val operationalVerdict: OperationalVerdict? = null,
)

enum class AccessPathStatus { STATIC, OBSERVED, DECLARED, UNRESOLVED }
data class AccessPathCoverage(
    val id: String,
    val className: String,
    val method: String,
    val status: AccessPathStatus,
    val queryIds: List<String> = emptyList(),
    val reason: String? = null,
    val mechanism: String? = null,
    val line: Int? = null,
)
