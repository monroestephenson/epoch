package dev.epoch.postgres

import dev.epoch.core.*
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.AllColumns
import net.sf.jsqlparser.statement.select.AllTableColumns
import net.sf.jsqlparser.statement.select.PlainSelect
import java.sql.Connection
import java.sql.ResultSet

/** Actual catalog dependencies, rooted by the operations that can invoke them. Addresses remain private. */
internal class PostgresBehaviorGraph {
    fun inspect(connection: Connection, snapshot: SchemaSnapshot): BehaviorGraph = Collector(connection, snapshot).collect()

    private data class Address(val catalog: String, val oid: Long, val sub: Int = 0)
    private data class Edge(val target: Address, val kind: String)
    private data class Relation(val name: String, val kind: String, val localName: String)
    private data class Type(val name: String, val kind: String, val definition: String, val builtin: Boolean)
    private data class Function(val id: String, val definition: String, val parsed: Boolean, val language: String)
    private data class Root(
        val id: String, val address: Address?, val kind: String, val definition: String,
        val complete: Boolean = false, val supported: Boolean = false, val planning: Boolean = true,
        val access: BehaviorAccess = BehaviorAccess.METADATA, val reason: String? = null,
        val children: List<String> = emptyList(), val references: List<BehaviorReference> = emptyList(),
    )

    private class Collector(private val connection: Connection, private val snapshot: SchemaSnapshot) {
        private val relations = mutableMapOf<Long, Relation>()
        private val columns = mutableMapOf<Pair<Long, Int>, String>()
        private val types = mutableMapOf<Long, Type>()
        private val functions = mutableMapOf<Long, Function>()
        private val edges = mutableMapOf<Address, MutableList<Edge>>()
        private val extensions = mutableMapOf<Long, Pair<String, String>>()
        private val roots = linkedMapOf<String, Root>()
        private val addresses = mutableMapOf<Address, String>()
        private val attachments = mutableListOf<BehaviorAttachment>()
        private val nodes = sortedMapOf<String, BehaviorNode>()
        private val unknownIdentities = mutableMapOf<Address, String>()
        private val allOperations = Operation.entries.toSet()
        private val writes = setOf(Operation.INSERT, Operation.UPDATE)
        private val builtinResolution = snapshot.expressionSearchPath.firstOrNull() == "pg_catalog"

        fun collect(): BehaviorGraph {
            inventory()
            defaultRoots()
            checkRoots()
            viewAndRuleRoots()
            indexRoots()
            triggerRoots()
            policyRoots()
            columnTypeRoots()
            collationRoots()
            otherOwnerRoots()
            foreignKeyRoots()
            // Inventory user overloads even when unused. SQL-call resolution may need to rule out a
            // shadowing overload; presence alone does not attach the function to any operation.
            functions.keys.sorted().forEach(::functionId)
            // Freeze the root list: recursively resolved functions/types are added during construction.
            roots.keys.toList().forEach(::build)
            return BehaviorGraph(attachmentInventoryComplete = true, nodes = nodes.toSortedMap(),
                attachments = attachments.distinct().sortedWith(compareBy({ it.table }, { it.rootNodeId },
                    { it.operations.joinToString(",") }, { it.column ?: "" }, { it.objectName ?: "" })))
        }

        private fun inventory() {
            connection.rows("""
                SELECT c.oid, n.nspname || '.' || c.relname AS name, c.relkind, c.relname
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            """) { relations[it.getLong("oid")] = Relation(it.getString("name"), it.getString("relkind"), it.getString("relname")) }
            connection.rows("""
                SELECT a.attrelid, a.attnum, a.attname FROM pg_catalog.pg_attribute a
                WHERE a.attnum > 0 AND NOT a.attisdropped
            """) { columns[it.getLong("attrelid") to it.getInt("attnum")] = it.getString("attname") }
            connection.rows("""
                SELECT t.oid, n.nspname || '.' || t.typname AS name, t.typtype,
                       t.oid < 16384 AND n.nspname='pg_catalog' AS builtin,
                       pg_catalog.format_type(t.typbasetype,t.typtypmod) AS base_type,
                       t.typnotnull, t.typdefault,
                       ARRAY(SELECT e.enumlabel FROM pg_catalog.pg_enum e WHERE e.enumtypid=t.oid ORDER BY e.enumsortorder) AS enum_labels
                FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
            """) { row ->
                val name = row.getString("name")
                types[row.getLong("oid")] = Type(name, row.getString("typtype"),
                    snapshot.behaviors["domain:$name"] ?: row.strings("enum_labels").takeIf { it.isNotEmpty() }?.joinToString("|") { "${it.length}:$it" } ?:
                    "${row.getString("typtype")}:${row.getString("base_type")}:${row.getBoolean("typnotnull")}:${row.getString("typdefault")}",
                    row.getBoolean("builtin"))
            }
            connection.rows("""
                SELECT p.oid, n.nspname || '.' || p.proname || '(' || pg_catalog.pg_get_function_identity_arguments(p.oid) || ')' AS name,
                       CASE WHEN p.prokind='a' THEN 'aggregate ' || n.nspname || '.' || p.proname
                            ELSE pg_catalog.pg_get_functiondef(p.oid) END AS definition,
                       p.prosqlbody IS NOT NULL AS parsed, l.lanname
                FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid >= 16384
            """) { row -> functions[row.getLong("oid")] = Function("function:${row.getString("name")}",
                row.getString("definition"), row.getBoolean("parsed"), row.getString("lanname")) }
            connection.rows("SELECT oid, extname, extversion FROM pg_catalog.pg_extension") {
                extensions[it.getLong("oid")] = it.getString("extname") to it.getString("extversion")
            }
            connection.rows("""
                SELECT sc.relname AS source_catalog, d.objid, d.objsubid,
                       tc.relname AS target_catalog, d.refobjid, d.refobjsubid, d.deptype
                FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_class sc ON sc.oid=d.classid
                JOIN pg_catalog.pg_class tc ON tc.oid=d.refclassid
                WHERE d.objid >= 16384 AND d.deptype IN ('n','e')
            """) { row ->
                val source = Address(row.getString("source_catalog"), row.getLong("objid"), row.getInt("objsubid"))
                edges.getOrPut(source) { mutableListOf() } += Edge(Address(row.getString("target_catalog"),
                    row.getLong("refobjid"), row.getInt("refobjsubid")), row.getString("deptype"))
            }
        }

        private fun add(root: Root, attachment: BehaviorAttachment? = null) {
            roots[root.id] = root
            root.address?.let { addresses[it] = root.id }
            attachment?.let(attachments::add)
        }

        private fun defaultRoots() {
            connection.rows("""
                SELECT d.oid, d.adrelid, d.adnum, a.attname, a.attgenerated,
                       pg_catalog.pg_get_expr(d.adbin,d.adrelid) AS expression
                FROM pg_catalog.pg_attrdef d JOIN pg_catalog.pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum
            """) { row ->
                val table = relations[row.getLong("adrelid")]?.name ?: return@rows
                val schema = snapshot.tables[table] ?: return@rows
                val column = row.getString("attname")
                val mapping = schema.columns[column] ?: return@rows
                val generated = row.getString("attgenerated").isNotEmpty()
                val expression = row.getString("expression")
                val safe = builtinResolution && builtinColumn(row.getLong("adrelid"), row.getInt("adnum")) &&
                    (CompatibilityViews.safeDefault(expression, mapping.type, snapshot) ||
                        CompatibilityViews.simpleExpression(expression, if (generated) schema else TableSchema()))
                val kind = if (generated) "generated" else "default"
                val id = "$kind:$table.$column"
                add(Root(id, Address("pg_attrdef", row.getLong("oid")), kind, expression, safe, safe, !safe,
                    reason = if (safe) null else "Expression may invoke unsupported code or observe relation names not recorded by pg_depend."),
                    BehaviorAttachment(table, id, writes, if (generated) BehaviorActivation.GENERATED else BehaviorActivation.DEFAULT,
                        column, "$table.$column"))
            }
        }

        private val columnTypes = mutableMapOf<Pair<Long, Int>, Long>()
        private fun builtinColumn(relation: Long, column: Int): Boolean {
            loadColumnTypes()
            return types[columnTypes[relation to column]]?.builtin == true
        }
        private var columnTypesLoaded = false
        private fun loadColumnTypes() {
            if (columnTypesLoaded) return
            connection.rows("SELECT attrelid, attnum, atttypid FROM pg_catalog.pg_attribute WHERE attnum>0 AND NOT attisdropped") {
                columnTypes[it.getLong("attrelid") to it.getInt("attnum")] = it.getLong("atttypid")
            }
            columnTypesLoaded = true
        }

        private fun checkRoots() {
            connection.rows("""
                SELECT co.oid, co.conrelid, co.conname, pg_catalog.pg_get_expr(co.conbin,co.conrelid) AS expression,
                       co.conkey FROM pg_catalog.pg_constraint co WHERE co.contype='c' AND co.conrelid<>0
            """) { row ->
                val relation = row.getLong("conrelid")
                val table = relations[relation]?.name ?: return@rows
                val schema = snapshot.tables[table] ?: return@rows
                val name = row.getString("conname")
                val expression = row.getString("expression")
                val scalar = row.ints("conkey").all { builtinColumn(relation, it) }
                val enum = schema.constraints[name]?.let(RuleProofs::enumCheck)
                val safeEnum = enum != null && schema.columns[enum.first]?.type?.let { it == "text" || it.startsWith("character varying") } == true
                val safe = builtinResolution && scalar && (safeEnum || CompatibilityViews.simpleExpression(expression, schema))
                val id = "check:$table.$name"
                add(Root(id, Address("pg_constraint", row.getLong("oid")), "check", expression, safe, safe, !safe,
                    reason = if (safe) null else "CHECK expression dependency completeness is not established."),
                    BehaviorAttachment(table, id, writes, objectName = "$table.$name"))
            }
        }

        private fun viewAndRuleRoots() {
            connection.rows("""
                SELECT r.oid, r.ev_class, r.rulename, r.ev_type, r.ev_enabled,
                       CASE WHEN r.rulename='_RETURN' THEN pg_catalog.pg_get_viewdef(r.ev_class,true)
                            ELSE pg_catalog.pg_get_ruledef(r.oid,true) END AS definition
                FROM pg_catalog.pg_rewrite r
            """) { row ->
                val relation = relations[row.getLong("ev_class")] ?: return@rows
                val table = snapshot.tables[relation.name] ?: return@rows
                val name = row.getString("rulename")
                val definition = row.getString("definition")
                if (name == "_RETURN") {
                    if (relation.kind == "m") {
                        // A materialized read does not execute its defining SELECT.
                        add(Root("view:${relation.name}", null, "materialized_view", definition, true, true, false,
                            references = listOf(BehaviorReference(BehaviorReferenceKind.RELATION, relation.name))),
                            BehaviorAttachment(relation.name, "view:${relation.name}", setOf(Operation.SELECT)))
                    } else {
                        val pure = functionFreeView(definition) && builtinResolution
                        val supported = pure && table.view?.completeDirectProjection == true && table.view.options.isEmpty()
                        val id = "view:${relation.name}"
                        add(Root(id, Address("pg_rewrite", row.getLong("oid")), "view", definition, pure, supported, !pure,
                            BehaviorAccess.SELECT, if (pure) null else "View expressions may invoke unsupported functions or hidden relation lookups."),
                            BehaviorAttachment(relation.name, id, allOperations, objectName = relation.name))
                    }
                } else if (row.getString("ev_enabled") != "D") {
                    val id = "rule:${relation.name}.$name"
                    val operation = when (row.getString("ev_type")) { "1" -> Operation.SELECT; "2" -> Operation.UPDATE; "3" -> Operation.INSERT; "4" -> Operation.DELETE; else -> null }
                    add(Root(id, Address("pg_rewrite", row.getLong("oid")), "rule", "$definition; enabled=${row.getString("ev_enabled")}",
                        access = BehaviorAccess.UNKNOWN, reason = "Rule actions and their operation directions are unsupported."),
                        BehaviorAttachment(relation.name, id, operation?.let(::setOf) ?: allOperations, objectName = "${relation.name}.$name"))
                }
            }
        }

        private fun indexRoots() {
            connection.rows("SELECT indexrelid, indrelid FROM pg_catalog.pg_index") { row ->
                val table = relations[row.getLong("indrelid")]?.name ?: return@rows
                val schema = snapshot.tables[table] ?: return@rows
                val name = relations[row.getLong("indexrelid")]?.localName ?: return@rows
                if (name in schema.plainIndexes) return@rows
                val id = "index:$table.$name"
                add(Root(id, Address("pg_class", row.getLong("indexrelid")), "index", schema.indexes[name] ?: return@rows,
                    reason = "Index expressions, predicates or custom operator classes require additional behavior proofs."),
                    BehaviorAttachment(table, id, writes, objectName = "$table.$name"))
            }
        }

        private fun triggerRoots() {
            connection.rows("""
                SELECT t.oid,t.tgrelid,t.tgname,t.tgfoid,t.tgtype,t.tgenabled,t.tgattr,
                       pg_catalog.pg_get_triggerdef(t.oid,true) AS definition
                FROM pg_catalog.pg_trigger t WHERE NOT t.tgisinternal AND t.tgenabled<>'D'
            """) { row ->
                val relation = row.getLong("tgrelid")
                val table = relations[relation]?.name?.takeIf { it in snapshot.tables } ?: return@rows
                val mask = row.getInt("tgtype")
                val operations = buildSet { if (mask and 4 != 0) add(Operation.INSERT); if (mask and 8 != 0) add(Operation.DELETE); if (mask and 16 != 0) add(Operation.UPDATE) }
                if (operations.isEmpty()) return@rows
                val id = "trigger:$table.${row.getString("tgname")}" 
                val function = functionId(row.getLong("tgfoid"))
                add(Root(id, Address("pg_trigger", row.getLong("oid")), "trigger", "${row.getString("definition")}; enabled=${row.getString("tgenabled")}",
                    planning = false, reason = "Trigger behavior is not proven equivalent.", children = listOf(function)),
                    BehaviorAttachment(table, id, operations, objectName = "$table.${row.getString("tgname")}", planningRelevant = false,
                        // Generated columns may activate UPDATE OF indirectly; scope retains those uncertain cases.
                        updateColumns = row.getString("tgattr").split(' ').mapNotNull { it.toIntOrNull()?.let { number -> columns[relation to number] } }.toSortedSet()))
            }
        }

        private fun policyRoots() {
            connection.rows("""
                SELECT p.oid,p.polrelid,p.polname,p.polcmd,p.polpermissive,
                       coalesce(pg_catalog.pg_get_expr(p.polqual,p.polrelid),'') AS using_expr,
                       coalesce(pg_catalog.pg_get_expr(p.polwithcheck,p.polrelid),'') AS check_expr,
                       ARRAY(SELECT CASE WHEN role_oid=0 THEN 'PUBLIC' ELSE r.rolname END
                             FROM unnest(p.polroles) role_oid LEFT JOIN pg_catalog.pg_roles r ON r.oid=role_oid ORDER BY 1) AS roles
                FROM pg_catalog.pg_policy p JOIN pg_catalog.pg_class c ON c.oid=p.polrelid
                WHERE c.relrowsecurity OR c.relforcerowsecurity
            """) { row ->
                val table = relations[row.getLong("polrelid")]?.name?.takeIf { it in snapshot.tables } ?: return@rows
                val command = row.getString("polcmd")
                val operations = when (command) { "r" -> setOf(Operation.SELECT, Operation.UPDATE, Operation.DELETE); "a" -> setOf(Operation.INSERT); "w" -> setOf(Operation.UPDATE); "d" -> setOf(Operation.DELETE); else -> allOperations }
                val id = "policy:$table.${row.getString("polname")}" 
                val definition = "$command:${row.getBoolean("polpermissive")}:${row.strings("roles").joinToString(",")}:${row.getString("using_expr")}:${row.getString("check_expr")}" 
                add(Root(id, Address("pg_policy", row.getLong("oid")), "policy", definition,
                    reason = "Policy role, visibility and expression semantics are unsupported."),
                    BehaviorAttachment(table, id, operations, objectName = "$table.${row.getString("polname")}"))
            }
        }

        private fun columnTypeRoots() {
            loadColumnTypes()
            columnTypes.entries.sortedWith(compareBy({ relations[it.key.first]?.name }, { it.key.second })).forEach { (address, oid) ->
                val table = relations[address.first]?.name?.takeIf { it in snapshot.tables } ?: return@forEach
                val column = columns[address] ?: return@forEach
                val type = types[oid] ?: return@forEach
                if (type.builtin) return@forEach
                val id = typeId(oid)
                attachments += BehaviorAttachment(table, id, allOperations, BehaviorActivation.COLUMN_TYPE, column, "$table.$column")
            }
        }

        private fun collationRoots() {
            connection.rows("""
                SELECT a.attrelid,a.attname,co.oid,n.nspname || '.' || co.collname AS name,co.collversion
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation
                JOIN pg_catalog.pg_namespace n ON n.oid=co.collnamespace
                WHERE a.attnum>0 AND NOT a.attisdropped AND co.collname<>'default'
            """) { row ->
                val table = relations[row.getLong("attrelid")]?.name?.takeIf { it in snapshot.tables } ?: return@rows
                val column = row.getString("attname")
                val id = "collation:$table.$column"
                val children = if (row.getLong("oid") >= 16384) listOf(unknown(Address("pg_collation", row.getLong("oid")),
                    "User-defined collation provider and version semantics are unsupported.")) else emptyList()
                add(Root(id, null, "collation", "${row.getString("name")}:${row.getString("collversion") ?: ""}",
                    reason = "Nondefault collation behavior is unsupported.", children = children),
                    BehaviorAttachment(table, id, allOperations, BehaviorActivation.COLUMN_TYPE, column, "$table.$column"))
            }
        }

        private fun otherOwnerRoots() {
            snapshot.behaviors.forEach { (id, definition) ->
                when {
                    id.startsWith("rls:") || id.startsWith("partition:") || id.startsWith("inheritance:") -> {
                        val table = id.substringAfter(':').let { if (it in snapshot.tables) it else "public.$it" }
                        if (table in snapshot.tables) add(Root(id, null, id.substringBefore(':'), definition,
                            reason = "Relation security, partitioning or inheritance behavior is not modeled."),
                            BehaviorAttachment(table, id, allOperations, objectName = table))
                    }
                }
            }
            // Extension-owned/security/inheritance objects were omitted from the public noise inventory.
            snapshot.tables.forEach { (table, schema) ->
                if (schema.relationSecurity?.inheritance == true && attachments.none { it.table == table && (it.rootNodeId.startsWith("partition:") || it.rootNodeId.startsWith("inheritance:")) }) {
                    val id = "inheritance:$table"
                    add(Root(id, null, "inheritance", "Inherited or partitioned relation", reason = "Relation inheritance behavior is unsupported."), BehaviorAttachment(table, id, allOperations))
                }
                if (schema.relationSecurity?.rowSecurity == true && roots["rls:$table"] == null) {
                    val id = "rls:$table"
                    add(Root(id, null, "rls", "Row-level security enabled", reason = "Application role and row security are unsupported."), BehaviorAttachment(table, id, allOperations))
                }
            }
        }

        private fun foreignKeyRoots() {
            snapshot.tables.toSortedMap().forEach { (child, table) -> table.constraints.toSortedMap().forEach constraints@ { (name, constraint) ->
                if (constraint.type != "f") return@constraints
                val parent = constraint.referencedTable ?: return@constraints
                for ((operation, action) in listOf(Operation.DELETE to constraint.onDelete, Operation.UPDATE to constraint.onUpdate)) {
                    val access = when (action) { "c" -> if (operation == Operation.DELETE) BehaviorAccess.DELETE else BehaviorAccess.UPDATE; "n", "d" -> BehaviorAccess.UPDATE; "a", "r" -> BehaviorAccess.SELECT; else -> BehaviorAccess.UNKNOWN }
                    val known = access != BehaviorAccess.UNKNOWN
                    val id = "foreign-key:$child.$name:${operation.name}"
                    val affectedChild = if (access == BehaviorAccess.UPDATE && constraint.columns.isNotEmpty())
                        constraint.columns.map { BehaviorReference(BehaviorReferenceKind.COLUMN, child, it, access) }
                    else listOf(BehaviorReference(BehaviorReferenceKind.RELATION, child, access = access))
                    add(Root(id, null, "foreign_key_action", constraint.definition, known, known, false,
                        references = affectedChild +
                            constraint.referencedColumns.map { BehaviorReference(BehaviorReferenceKind.COLUMN, parent, it) },
                        reason = if (known) null else "Foreign-key action is unrecognized."),
                        BehaviorAttachment(parent, id, setOf(operation), objectName = "$child.$name", planningRelevant = false,
                            updateColumns = if (operation == Operation.UPDATE) constraint.referencedColumns.toSortedSet() else emptySet()))
                }
            } }
        }

        private fun functionId(oid: Long): String {
            val address = Address("pg_proc", oid)
            addresses[address]?.let { return it }
            val function = functions[oid]
            if (function == null) return unknown(address, "Referenced function is outside the supported function inventory.")
            add(Root(function.id, address, "function", function.definition, access = BehaviorAccess.UNKNOWN,
                reason = if (function.parsed && function.language == "sql") "Parsed SQL dependencies are recorded, but hidden relation-name lookup and operation direction are not proven absent."
                         else "${function.language} function body does not expose exhaustive dependencies through pg_depend."))
            return function.id
        }

        private fun typeId(oid: Long): String {
            val address = Address("pg_type", oid)
            addresses[address]?.let { return it }
            val type = types[oid] ?: return unknown(address, "Referenced type is absent from the supported catalog inventory.")
            val enum = type.kind == "e"
            val id = "type:${type.name}"
            add(Root(id, address, "type", type.definition, enum, enum, !enum,
                reason = if (enum) null else "User-defined type/domain codecs and constraints are unsupported.",
                references = listOf(BehaviorReference(BehaviorReferenceKind.TYPE, type.name))))
            return id
        }

        private fun unknown(address: Address, reason: String): String {
            addresses[address]?.let { return it }
            val identity = unknownIdentities.getOrPut(address) {
                connection.prepareStatement("SELECT type, identity FROM pg_catalog.pg_identify_object(?::regclass,?::oid,?)").use { statement ->
                    statement.setString(1, "pg_catalog.${address.catalog}"); statement.setLong(2, address.oid); statement.setInt(3, address.sub)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "Catalog object identity is unavailable." }
                        "${result.getString("type")}:${result.getString("identity")}" 
                    }
                }
            }
            val id = "catalog:$identity"
            add(Root(id, address, address.catalog, identity, reason = reason))
            return id
        }

        private fun build(id: String) {
            val pending = ArrayDeque<String>()
            pending.add(id)
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (current !in nodes) pending.addAll(buildNode(current))
            }
        }

        private fun buildNode(id: String): List<String> {
            val root = roots.getValue(id)
            val children = root.children.toMutableSet()
            val references = root.references.toMutableSet()
            val reasons = mutableSetOf<String>().apply { root.reason?.let(::add) }
            var complete = root.complete
            root.address?.let { address ->
                edges[address].orEmpty().forEach { edge ->
                    val target = edge.target
                    when {
                        edge.kind == "e" -> {
                            val extension = extensions[target.oid]
                            if (extension == null) { complete = false; reasons += "Extension membership could not be resolved." }
                            else references += BehaviorReference(BehaviorReferenceKind.EXTENSION, extension.first)
                        }
                        target.catalog == "pg_class" -> {
                            val relation = relations[target.oid]
                            val column = if (target.sub > 0) columns[target.oid to target.sub] else null
                            if (relation == null || target.sub > 0 && column == null) { complete = false; reasons += "A referenced relation or physical column is absent from the catalog inventory." }
                            else if (relation.kind == "S") references += BehaviorReference(BehaviorReferenceKind.SEQUENCE, relation.name)
                            else if (relation.kind in setOf("r", "p", "v", "m", "f")) references += BehaviorReference(
                                if (column == null) BehaviorReferenceKind.RELATION else BehaviorReferenceKind.COLUMN,
                                relation.name, column, root.access)
                            else { children += addresses[target] ?: unknown(target, "Referenced relation kind ${relation.kind} is unsupported."); complete = false }
                        }
                        target.catalog == "pg_proc" -> {
                            // Original pinned builtin functions are generally absent from pg_depend. If present,
                            // the positive expression fragment remains the guard; new pg_catalog objects are not builtin.
                            if (target.oid >= 16384) { children += functionId(target.oid); complete = false }
                        }
                        target.catalog == "pg_type" -> {
                            if (types[target.oid]?.builtin != true) children += typeId(target.oid)
                        }
                        target.catalog == "pg_namespace" -> Unit // Ownership namespace does not execute behavior.
                        target.catalog in setOf("pg_operator", "pg_opclass", "pg_opfamily", "pg_collation", "pg_am") && target.oid < 16384 -> Unit
                        else -> { children += addresses[target] ?: unknown(target, "Catalog dependency ${target.catalog} is not fully modeled."); complete = false }
                    }
                }
            }
            nodes[id] = BehaviorNode(root.kind, root.definition, children.sorted(), references.sortedWith(compareBy(
                { it.kind.name }, { it.name }, { it.column ?: "" }, { it.access.name })), complete,
                root.supported && complete, root.planning, reasons.sorted())
            return children.sorted()
        }
    }

    companion object {
        /** Positive direct-projection syntax. Catalog edges, rather than this parser, provide physical references. */
        internal fun functionFreeView(definition: String): Boolean = try {
            val select = CCJSqlParserUtil.parse(definition) as? PlainSelect
            select != null && select.fromItem is Table && select.joins.isNullOrEmpty() && select.where == null &&
                select.groupBy == null && select.having == null && select.qualify == null && select.distinct == null &&
                select.orderByElements.isNullOrEmpty() && select.limit == null && select.offset == null && select.fetch == null &&
                select.withItemsList.isNullOrEmpty() && select.windowDefinitions.isNullOrEmpty() &&
                select.selectItems.isNotEmpty() && select.selectItems.all {
                    val expression = it.expression
                    expression is Column && (expression.table?.name?.isNotBlank() == true ||
                        expression.columnName.startsWith('"') || expression.columnName.uppercase() !in sessionValues) ||
                        expression is AllColumns || expression is AllTableColumns
                }
        } catch (_: Exception) { false }

        // JSQLParser represents some PostgreSQL SQL-value functions as unqualified Column nodes.
        private val sessionValues = setOf("CURRENT_USER", "SESSION_USER", "CURRENT_ROLE", "USER", "SYSTEM_USER",
            "CURRENT_SCHEMA", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP")
    }
}

private fun Connection.rows(sql: String, consume: (ResultSet) -> Unit) = createStatement().use { statement ->
    statement.executeQuery(sql).use { result -> while (result.next()) consume(result) }
}
private fun ResultSet.strings(column: String): List<String> {
    val values = getArray(column) ?: return emptyList()
    return try { (values.array as Array<*>).map { it?.toString() ?: "<unresolved role>" } } finally { values.free() }
}
private fun ResultSet.ints(column: String): List<Int> = strings(column).mapNotNull(String::toIntOrNull)
