package dev.epoch.core

/** Portable catalog dependency facts. Missing inventory is not evidence of absent behavior. */
data class BehaviorGraph(
    val version: Int = 1,
    val attachmentInventoryComplete: Boolean,
    val nodes: Map<String, BehaviorNode>,
    val attachments: List<BehaviorAttachment>,
)

enum class BehaviorReferenceKind { RELATION, COLUMN, SEQUENCE, TYPE, EXTENSION }
enum class BehaviorAccess { METADATA, SELECT, INSERT, UPDATE, DELETE, UNKNOWN }
enum class BehaviorActivation { ALWAYS, DEFAULT, GENERATED, COLUMN_TYPE }

data class BehaviorReference(
    val kind: BehaviorReferenceKind,
    val name: String,
    val column: String? = null,
    val access: BehaviorAccess = BehaviorAccess.METADATA,
)

data class BehaviorNode(
    val kind: String,
    val definition: String,
    val childNodeIds: List<String> = emptyList(),
    val references: List<BehaviorReference> = emptyList(),
    val dependenciesComplete: Boolean = false,
    val semanticsSupported: Boolean = false,
    val planningMayEvaluate: Boolean = true,
    val opaqueReasons: List<String> = emptyList(),
)

data class BehaviorAttachment(
    val table: String,
    val rootNodeId: String,
    val operations: Set<Operation>,
    val activation: BehaviorActivation = BehaviorActivation.ALWAYS,
    val column: String? = null,
    val objectName: String? = null,
    val planningRelevant: Boolean = true,
    val updateColumns: Set<String> = emptySet(),
)

/** SQL parser facts, independent of catalog reachability and semantic support. */
data class QueryBehaviorFacts(
    val complete: Boolean = false,
    val explicitRootNodeIds: Set<String> = emptySet(),
    val references: Set<BehaviorReference> = emptySet(),
    val defaultColumns: Set<ColumnReference> = emptySet(),
    val defaultAssignmentsComplete: Boolean = false,
    val unresolvedReasons: List<String> = emptyList(),
    val builtinFunctionCalls: Set<String> = emptySet(),
    /** Independently proven absence of hidden relation access, even when semantic syntax
     * support is incomplete. Legacy/missing facts never establish this boundary. */
    val dependencyCoverageComplete: Boolean = false,
)

data class BehaviorUncertainty(val nodeId: String, val reason: String)

data class OperationBehaviorScope(
    val semanticNodeIds: Set<String> = emptySet(),
    /** Reached behavior dependencies; ordinary SQL dependencies remain in QueryContract. */
    val references: Set<BehaviorReference> = emptySet(),
    val uncertainties: List<BehaviorUncertainty> = emptyList(),
    val planningNodeIds: Set<String> = emptySet(),
    val planningReferences: Set<BehaviorReference> = emptySet(),
    val planningUncertainties: List<BehaviorUncertainty> = emptyList(),
    val planningMayEvaluate: Boolean = false,
    val writeTransformations: Set<String> = emptySet(),
    /** Only synthetic query-syntax causes may use this positively bounded dependency set. */
    val boundedQueryUncertaintyIds: Set<String> = emptySet(),
    val queryDependencyReferences: Set<BehaviorReference> = emptySet(),
)

/** Exact dependency closure for one cause; separate from all roots reached by its caller. */
data class BehaviorDependencyBoundary(
    val complete: Boolean,
    val nodeIds: Set<String>,
    val references: Set<BehaviorReference>,
)
