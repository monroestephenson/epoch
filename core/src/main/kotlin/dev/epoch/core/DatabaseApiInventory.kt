package dev.epoch.core

/** Counts static calls within declared roots and known API families, never all possible application access. */
data class DatabaseApiInventory(
    val schemaVersion: Int = 1,
    val invocations: List<DatabaseApiInvocation> = emptyList(),
    val selectedPackages: List<String> = emptyList(),
    val scannedClasses: List<String> = emptyList(),
    val failedSources: List<String> = emptyList(),
    val ownerGaps: List<DatabaseApiOwnerGap> = emptyList(),
    val inspectedMethodInvocations: Int = 0,
    val declarationAccessPathIds: List<String> = emptyList(),
    val completeWithinDeclaredScope: Boolean = false,
    val limitations: List<String> = emptyList(),
    /** Direct database API method references are potential access, not ordinary bytecode invocations. */
    val methodReferenceAccessPathIds: List<String> = emptyList(),
) {
    fun prefixed(prefix: String) = copy(
        invocations = invocations.map { call -> call.copy(id = prefix + call.id, className = prefix + call.className,
            accessPathIds = call.accessPathIds.map { prefix + it }) },
        methodReferenceAccessPathIds = methodReferenceAccessPathIds.map { prefix + it },
        selectedPackages = selectedPackages.map { prefix + it }, scannedClasses = scannedClasses.map { prefix + it },
        failedSources = failedSources.map { prefix + it }, declarationAccessPathIds = declarationAccessPathIds.map { prefix + it },
        ownerGaps = ownerGaps.map { it.copy(owner = prefix + it.owner, callSiteIds = it.callSiteIds.map { id -> prefix + id }) },
    )
    companion object {
        fun merge(inventories: List<DatabaseApiInventory?>): DatabaseApiInventory? {
            if (inventories.all { it == null }) return null
            val known = inventories.filterNotNull()
            return DatabaseApiInventory(invocations = known.flatMap { it.invocations }.sortedBy { it.id },
                selectedPackages = known.flatMap { it.selectedPackages }.distinct().sorted(), scannedClasses = known.flatMap { it.scannedClasses }.sorted(),
                failedSources = known.flatMap { it.failedSources }.sorted(), ownerGaps = known.flatMap { it.ownerGaps }.sortedBy { it.owner },
                inspectedMethodInvocations = known.sumOf { it.inspectedMethodInvocations },
                declarationAccessPathIds = known.flatMap { it.declarationAccessPathIds }.sorted(),
                methodReferenceAccessPathIds = known.flatMap { it.methodReferenceAccessPathIds }.sorted(),
                completeWithinDeclaredScope = inventories.all { it?.completeWithinDeclaredScope == true },
                limitations = (known.flatMap { it.limitations } + if (inventories.any { it == null })
                    listOf("At least one merged contract lacks a static API inventory; its denominator is unknown.") else emptyList()).distinct().sorted())
        }
    }
}

/** Missing ancestry is not evidence that these invocations are non-database helpers. */
data class DatabaseApiOwnerGap(
    val owner: String,
    val callSiteIds: List<String>,
    val reason: String,
)

enum class DatabaseApiRole {
    FACTORY, EXECUTION, ORM_OPERATION, MODIFIER, BINDING, LIFECYCLE_OR_METADATA, UNSUPPORTED,
}

data class DatabaseApiInvocation(
    val id: String,
    val className: String,
    /** JVM name plus descriptor; overloaded methods remain distinct. */
    val method: String,
    /** Ordinal of all MethodInsnNode instructions, assigned before classification. */
    val invocationIndex: Int,
    val line: Int? = null,
    val owner: String,
    val name: String,
    val descriptor: String,
    val family: String,
    val role: DatabaseApiRole,
    val queryCapable: Boolean,
    val accessPathIds: List<String> = emptyList(),
    val unresolvedReason: String? = null,
)
