package dev.epoch.hibernate

import dev.epoch.core.DatabaseApiRole
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodInsnNode

internal data class DatabaseCall(
    val family: String,
    val role: DatabaseApiRole,
    val queryCapable: Boolean,
    val translation: String? = null,
    val reason: String? = null,
)
internal data class ApiResolution(val call: DatabaseCall?, val ancestryGaps: List<String> = emptyList())

internal class DatabaseApiClassifier(private val headers: RepositoryHeaders) {
    /** Do not discard a known override when control flow merges several receiver types. */
    fun classifyReceivers(call: MethodInsnNode, receivers: Set<RepositoryType.ClassType>?): ApiResolution {
        if (call.opcode == org.objectweb.asm.Opcodes.INVOKESTATIC || receivers.isNullOrEmpty()) return classify(call)
        val alternatives = receivers.sortedBy { it.name }.map { it.name to classify(call, it.name) }
        val gaps = alternatives.flatMap { it.second.ancestryGaps }.distinct().sorted()
        val resolutions = alternatives.map { it.second.call }.distinct()
        if (resolutions.size == 1) return ApiResolution(resolutions.single(), gaps)
        val known = resolutions.filterNotNull()
        if (known.isEmpty()) return ApiResolution(null, gaps)
        val families = known.map { it.family }.distinct()
        return ApiResolution(DatabaseCall(families.singleOrNull() ?: "AMBIGUOUS_DATABASE_API",
            DatabaseApiRole.UNSUPPORTED, true, reason =
                "Receiver alternatives have conflicting database API dispatch; framework semantics are not assumed: " +
                    alternatives.joinToString("; ") { (type, result) ->
                        "${type.replace('/', '.')}: ${result.call?.reason ?: result.call?.translation ?: result.call?.family ?: "unclassified"}"
                    }), gaps)
    }

    fun classify(call: MethodInsnNode, concreteReceiver: String? = null): ApiResolution {
        if (call.owner.startsWith("[")) return ApiResolution(null)
        val owner = concreteReceiver ?: call.owner
        val hierarchy = headers.hierarchy(owner)
        val names = (hierarchy.nodes.keys + call.owner + hierarchy.nodes.values.flatMap { it.interfaces.orEmpty() + listOfNotNull(it.superName) }).toSet()
        val family = family(names) ?: return ApiResolution(null, hierarchy.gaps.sorted())
        val gaps = hierarchy.gaps.sorted()
        if (gaps.isNotEmpty()) return ApiResolution(DatabaseCall(family, DatabaseApiRole.UNSUPPORTED, true,
            reason = "Database API ancestry is incomplete"), gaps)
        val args = Type.getArgumentTypes(call.desc)
        val firstString = args.firstOrNull()?.descriptor == "Ljava/lang/String;"
        if (call.name in setOf("toString", "hashCode", "equals", "getClass"))
            return result(family, DatabaseApiRole.LIFECYCLE_OR_METADATA, false)
        // A known API signature does not establish the behavior of an application override.
        // Preserve ordinary framework interfaces while refusing visible custom rewrites.
        if (family in setOf("JPA", "HIBERNATE", "PANACHE_QUERY")) {
            frameworkDispatch(call, owner, family)?.let { return it }
        }
        if (family == "PANACHE_QUERY") {
            return when {
                call.name in setOf("list", "stream") && args.isEmpty() -> result(family, DatabaseApiRole.EXECUTION, true, "PANACHE_TERMINAL")
                call.name in setOf("count", "pageCount", "firstResult", "singleResult", "firstResultOptional", "singleResultOptional") ->
                    result(family, DatabaseApiRole.EXECUTION, true, "PANACHE_UNSUPPORTED_TERMINAL", "This terminal generates a distinct result/SQL variant")
                call.name in setOf("page", "range", "project", "filter", "withLock", "withHint", "withCountQuery", "enableFilter", "disableFilter") ->
                    result(family, DatabaseApiRole.MODIFIER, true, "PANACHE_VARIANTS")
                else -> result(family, DatabaseApiRole.UNSUPPORTED, true, reason = "Panache terminal/modifier variant has no proven translation")
            }
        }
        if (family == "PANACHE_REPOSITORY" || family == "PANACHE_ENTITY") {
            val declaration = headers.declaration(owner, call.name, call.desc)
            val origin = declaration.declarations.singleOrNull()?.owner
            if (declaration.gaps.isNotEmpty() || origin == null || !origin.startsWith("io/quarkus/hibernate/orm/panache/"))
                return result(family, DatabaseApiRole.UNSUPPORTED, true, reason =
                    "Application override or unresolved dispatch must be linked to its method body; framework shorthand is not assumed")
            val factory = call.name in setOf("find", "findAll")
            val immediate = call.name in setOf("list", "listAll", "stream", "streamAll", "count", "delete", "deleteAll", "update")
            if (args.any { it.className == "io.quarkus.panache.common.Sort" }) return result(family,
                if (factory) DatabaseApiRole.FACTORY else DatabaseApiRole.EXECUTION, true, "PANACHE",
                "Panache Sort arguments require enumerated ordering dependencies")
            if ((factory || immediate) && (firstString || args.isEmpty())) return result(family,
                if (factory) DatabaseApiRole.FACTORY else DatabaseApiRole.EXECUTION, true, "PANACHE")
            if (call.name in setOf("persist", "persistAndFlush", "flush", "deleteById", "isPersistent"))
                return result(family, DatabaseApiRole.ORM_OPERATION, true, "ORM_OPERATION")
            return result(family, DatabaseApiRole.UNSUPPORTED, true, reason = "Unrecognized Panache database API overload")
        }
        if (family == "JPA" || family == "HIBERNATE") return when {
            call.name == "createNamedQuery" && firstString -> result(family, DatabaseApiRole.FACTORY, true, "JPA_NAMED")
            call.name in setOf("createQuery", "createSelectionQuery", "createMutationQuery") ->
                result(family, DatabaseApiRole.FACTORY, true, if (firstString) "JPA_HQL" else "CRITERIA")
            call.name in setOf("createNativeQuery", "createSQLQuery") && firstString -> result(family, DatabaseApiRole.FACTORY, true, "JPA_NATIVE")
            call.name in setOf("getResultList", "getResultStream", "getSingleResult", "getSingleResultOrNull", "list", "stream", "executeUpdate") ->
                result(family, DatabaseApiRole.EXECUTION, true, "QUERY_TERMINAL")
            call.name in setOf("setParameter", "setProperties") -> result(family, DatabaseApiRole.BINDING, true, "QUERY_BINDING")
            call.name in setOf("setFirstResult", "setMaxResults") && args.map { it.sort } == listOf(Type.INT) ->
                result(family, DatabaseApiRole.MODIFIER, true, "QUERY_PAGINATION")
            call.name in setOf("setLockMode", "setHint", "setEntityGraph", "applyGraph", "setParameterList") ->
                result(family, DatabaseApiRole.MODIFIER, true, "QUERY_VARIANTS")
            call.name in setOf("persist", "merge", "remove", "find", "getReference", "refresh") -> result(family, DatabaseApiRole.ORM_OPERATION, true, "ORM_OPERATION")
            call.name in setOf("close", "clear", "isOpen", "contains", "getTransaction", "getMetamodel", "getCriteriaBuilder") ->
                result(family, DatabaseApiRole.LIFECYCLE_OR_METADATA, false)
            else -> result(family, DatabaseApiRole.UNSUPPORTED, true, reason = "Known persistence API call has no supported translation")
        }
        if (family == "SPRING_JDBC" && firstString && call.name in setOf("query", "queryForList", "queryForObject", "queryForMap", "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute"))
            return result(family, DatabaseApiRole.EXECUTION, true, "JDBC_SQL")
        if (family == "JDBC") return when {
            call.name == "prepareStatement" && firstString -> result(family, DatabaseApiRole.FACTORY, true, "JDBC_SQL")
            call.name == "prepareCall" -> result(family, DatabaseApiRole.FACTORY, true, "JDBC_PROCEDURE")
            call.name.startsWith("execute") -> result(family, DatabaseApiRole.EXECUTION, true, if (firstString) "JDBC_SQL" else "JDBC_PREPARED_EXECUTION")
            call.name.startsWith("set") && "java/sql/PreparedStatement" in names -> result(family, DatabaseApiRole.BINDING, true, "JDBC_BINDING")
            call.name in setOf("close", "getMetaData", "isClosed", "getConnection", "getWarnings", "clearWarnings", "createStatement", "commit", "rollback", "getAutoCommit", "setAutoCommit", "getCatalog", "getSchema", "setReadOnly", "isReadOnly") -> result(family, DatabaseApiRole.LIFECYCLE_OR_METADATA, false)
            else -> result(family, DatabaseApiRole.UNSUPPORTED, true, reason = "Known JDBC API invocation is not translated")
        }
        return result(family, DatabaseApiRole.UNSUPPORTED, true, reason = "Known database builder/repository family is not exhaustively translated")
    }

    private fun result(family: String, role: DatabaseApiRole, query: Boolean, translation: String? = null, reason: String? = null) =
        ApiResolution(DatabaseCall(family, role, query, translation, reason))

    private fun frameworkDispatch(call: MethodInsnNode, owner: String, family: String): ApiResolution? {
        val declaration = headers.declaration(owner, call.name, call.desc)
        val declared = declaration.declarations.singleOrNull()?.owner
        if (declaration.gaps.isNotEmpty() || declared == null) return ApiResolution(
            DatabaseCall(family, DatabaseApiRole.UNSUPPORTED, true,
                reason = "Persistence API method dispatch is unresolved; its query behavior is not assumed"),
            declaration.gaps.ifEmpty { setOf("No unique declaration for $owner.${call.name}${call.desc}") }.sorted())
        val framework = if (family == "PANACHE_QUERY") declared.startsWith("io/quarkus/hibernate/orm/panache/")
            else declared.startsWith("jakarta/persistence/") || declared.startsWith("javax/persistence/") || declared.startsWith("org/hibernate/")
        return if (framework) null else result(family, DatabaseApiRole.UNSUPPORTED, true,
            reason = "Application override $declared.${call.name}${call.desc} can rewrite query behavior; framework semantics are not assumed")
    }

    private fun family(names: Set<String>): String? = when {
        "io/quarkus/hibernate/orm/panache/PanacheQuery" in names -> "PANACHE_QUERY"
        names.any { it in setOf("io/quarkus/hibernate/orm/panache/PanacheRepository", "io/quarkus/hibernate/orm/panache/PanacheRepositoryBase") } -> "PANACHE_REPOSITORY"
        names.any { it in setOf("io/quarkus/hibernate/orm/panache/PanacheEntity", "io/quarkus/hibernate/orm/panache/PanacheEntityBase") } -> "PANACHE_ENTITY"
        names.any { it.startsWith("jakarta/persistence/criteria/") || it.startsWith("javax/persistence/criteria/") } -> "CRITERIA"
        names.any { it in setOf("jakarta/persistence/EntityManager", "javax/persistence/EntityManager", "jakarta/persistence/Query", "javax/persistence/Query", "jakarta/persistence/TypedQuery", "javax/persistence/TypedQuery") } -> "JPA"
        names.any { it in setOf("org/hibernate/Session", "org/hibernate/StatelessSession", "org/hibernate/query/Query", "org/hibernate/query/SelectionQuery", "org/hibernate/query/MutationQuery") } -> "HIBERNATE"
        names.any { it in setOf("java/sql/Connection", "java/sql/Statement", "java/sql/PreparedStatement", "java/sql/CallableStatement", "javax/sql/DataSource", "java/sql/DriverManager") } -> "JDBC"
        names.any { it.startsWith("org/springframework/jdbc/core/") } -> "SPRING_JDBC"
        "org/springframework/data/repository/Repository" in names -> "SPRING_DATA"
        names.any { it.startsWith("com/querydsl/") } -> "QUERYDSL"
        names.any { it.startsWith("org/jooq/") } -> "JOOQ"
        else -> null
    }
}
