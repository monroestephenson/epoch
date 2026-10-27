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
)

data class EntityOverride(
    val operations: Set<Operation>? = null,
    /** Retain this class in Hibernate bootstrap for result mappings, without declaring entity CRUD/table dependencies. */
    val resultMappingOnly: Boolean = false,
    /** Remove this class from bootstrap; references from retained mappings remain errors/unresolved, never silently pruned. */
    val exclude: Boolean = false,
)
