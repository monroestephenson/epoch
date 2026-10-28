package dev.epoch.hibernate

import dev.epoch.core.Operation

/** Compiled-application inputs; paths are interpreted by the caller (the CLI resolves descriptor-relative paths). */
data class ExtractionRequest(
    val entityClasses: List<String> = emptyList(),
    val classpath: List<String> = emptyList(),
    val applicationVersion: String = "unspecified",
    val physicalNamingStrategy: String? = null,
    val implicitNamingStrategy: String? = null,
    val hqlQueries: Map<String, String> = emptyMap(),
    val nativeQueries: Map<String, String> = emptyMap(),
    val finiteHqlVariants: Map<String, List<String>> = emptyMap(),
    val unresolvedDynamicQueries: Map<String, String> = emptyMap(),
    val hibernateProperties: Map<String, String> = emptyMap(),
    val scanPackages: List<String> = emptyList(),
    val keepGoing: Boolean = false,
    val entityOverrides: Map<String, EntityOverride> = emptyMap(),
    /** Ordered JDBC placeholder types, keyed by named-query name or nativeQueries entry ID. */
    val nativeQueryParameterTypes: Map<String, List<String>> = emptyMap(),
    /** Optional scope for bytecode repository/query discovery; empty scans the supplied application classpath. */
    val repositoryPackages: List<String> = emptyList(),
    /** Disable only for an intentionally limited entity/descriptor contract. */
    val discoverRepositories: Boolean = true,
    /** JSONL files written by SqlCollector during application integration tests. */
    val runtimeSqlFiles: List<String> = emptyList(),
    /** Fixed local JPA/Hibernate pagination, rendered by the supplied Hibernate runtime. */
    val hqlQueryPagination: Map<String, QueryPagination> = emptyMap(),
)

data class EntityOverride(
    val operations: Set<Operation>? = null,
    /** Retain this class in Hibernate bootstrap for result mappings, without declaring entity CRUD/table dependencies. */
    val resultMappingOnly: Boolean = false,
    /** Remove this class from bootstrap; references from retained mappings remain errors/unresolved, never silently pruned. */
    val exclude: Boolean = false,
)

/** Values are compile-time constants at a supported local query execution; no SQL is appended by Epoch. */
data class QueryPagination(val firstResult: Int? = null, val maxResults: Int? = null) {
    init {
        require(firstResult == null || firstResult >= 0) { "firstResult must be nonnegative" }
        require(maxResults == null || maxResults >= 0) { "maxResults must be nonnegative" }
    }
    internal fun suffix(): String = ".pagination.offset${firstResult ?: "none"}.limit${maxResults ?: "none"}"
    internal fun isEmpty() = firstResult == null && maxResults == null
}
