package dev.epoch.postgres

import dev.epoch.core.*
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import java.sql.Connection
import java.sql.ResultSet

/** Observes only the owned migration session; never changes its transaction or application data. */
internal class PostgresOperationalMonitor(baseline: SchemaSnapshot, sizes: TableSizes? = null) : Callback {
    private val baselineNames = baseline.tables.mapNotNull { (name, table) -> table.relationOid?.let { it to name } }.toMap()
    private val sizes = OperationalSizes.validateAndNormalize(sizes)
    private val completeBaselineIdentity = baseline.tables.values.all { it.relationOid != null }
    private var current: MigrationInfo? = null
    private var ordinal = 0
    private val statements = mutableListOf<OperationalStatementReport>()
    private var active: Active? = null
    private var knownTimeout: Pair<Long, OperationalTimeoutOrigin>? = null

    override fun getCallbackName() = "epoch-operational-measurement"
    override fun supports(event: Event, context: Context?) = event in setOf(Event.BEFORE_EACH_MIGRATE,
        Event.BEFORE_EACH_MIGRATE_STATEMENT, Event.AFTER_EACH_MIGRATE_STATEMENT, Event.AFTER_EACH_MIGRATE_STATEMENT_ERROR)
    override fun canHandleInTransaction(event: Event, context: Context?) = true
    override fun handle(event: Event, context: Context) {
        val migration = context.migrationInfo.let { MigrationInfo(it.version?.toString() ?: "repeatable", it.script) }
        if (event == Event.AFTER_EACH_MIGRATE_STATEMENT_ERROR) { active = null; return } // Failed TX cannot be inspected.
        try {
            when (event) {
                Event.BEFORE_EACH_MIGRATE -> {
                    check(active == null) { "Previous operational statement did not finish" }
                    check(!context.configuration.isBatch) { "Operational measurement requires Flyway batch=false" }
                    current = migration; ordinal = 0; statements.clear()
                    if (knownTimeout?.second == OperationalTimeoutOrigin.MIGRATION_LOCAL) knownTimeout = null
                }
                Event.BEFORE_EACH_MIGRATE_STATEMENT -> {
                    check(current == migration && active == null) { "Unexpected operational statement callback order" }
                    val sql = requireNotNull(context.statement.sql)
                    val intent = OperationalSql.inspect(sql)
                    val before = observe(context.connection, intent)
                    active = Active(++ordinal, sql, intent, context.connection.autoCommit, before, timeout(before.timeout))
                }
                Event.AFTER_EACH_MIGRATE_STATEMENT -> {
                    val step = checkNotNull(active) { "Operational statement has no matching before callback" }
                    check(current == migration && step.sql == context.statement.sql) { "Operational statement callback changed identity" }
                    val after = observe(context.connection, step.intent, step.before.resolved.values.toSet())
                    val rewritten = after.relations.values.any { value -> step.before.relations[value.oid]?.let { it.filenode != 0L && value.filenode != 0L && it.filenode != value.filenode } == true }
                    val opaque = (step.before.events + after.events).distinct().filter { it.applies(step.intent.commandTag) && (it.event != "table_rewrite" || rewritten) }
                        .map { "Enabled event trigger ${it.name} (${it.event}) can execute procedural work for ${step.intent.commandTag ?: "this statement"}." }.sorted()
                    if (step.intent.timeoutSetting != null) knownTimeout = after.timeout.first to
                        if (step.intent.timeoutSetting == "LOCAL") OperationalTimeoutOrigin.MIGRATION_LOCAL else OperationalTimeoutOrigin.MIGRATION_SESSION
                    else if (step.intent.intents.any { it.kind == OperationalActionKind.UNKNOWN } || opaque.isNotEmpty()) knownTimeout = null
                    val beforeLocks = locks(step.before)
                    val afterLocks = locks(after, step.before)
                    val beforeKeys = step.before.locks.map { it.oid to it.mode }.toSet()
                    val newLocks = after.locks.filter { (it.oid to it.mode) !in beforeKeys }.mapNotNull { lock(after, it, step.before) }
                    val rewrites = after.relations.values.mapNotNull { relation ->
                        val old = step.before.relations[relation.oid]
                        if (relation.kind !in setOf("table", "materialized_view") || old == null || old.filenode == 0L || relation.filenode == 0L || old.filenode == relation.filenode) null
                        else OperationalRewrite(relation(relation), old.name, relation.name)
                    }.sortedBy { it.afterName }
                    val actions = step.intent.intents.flatMap { actions(it, step.before, after) }
                    statements += OperationalAssessmentRules.assess(OperationalStatementFacts(migration, step.ordinal, step.sql,
                        step.autoCommit, step.timeout, timeout(after.timeout), beforeLocks, afterLocks,
                        newLocks.sortedWith(lockOrder), rewrites, actions, opaque), sizes)
                    active = null
                }
                else -> Unit
            }
        } catch (failure: Exception) {
            if (failure is VerificationException) throw failure
            throw VerificationException("OPERATIONAL_MEASUREMENT_FAILED", migration,
                "Cannot observe the migration's operational facts: ${failure.message}", failure)
        }
    }

    fun snapshot(migration: MigrationInfo): OperationalAssessment {
        check(current == migration && active == null) { "Operational migration has unmatched statement observations" }
        return OperationalAssessmentRules.aggregate(statements.toList())
    }

    private fun timeout(value: Pair<Long, String>): OperationalTimeout {
        val origin = when {
            value.second == "client" -> OperationalTimeoutOrigin.VERIFIER
            value.second == "session" && knownTimeout?.first == value.first -> knownTimeout!!.second
            else -> OperationalTimeoutOrigin.UNKNOWN
        }
        return OperationalTimeout(value.first, value.second, origin)
    }
    private fun relation(value: Relation) = OperationalRelation(value.name, value.kind,
        if (completeBaselineIdentity) value.oid in baselineNames else null, baselineNames[value.oid])
    private fun locks(observation: Observation, before: Observation? = null) = observation.locks
        .mapNotNull { lock(observation, it, before) }.distinct().sortedWith(lockOrder)
    private fun lock(observation: Observation, held: HeldLock, before: Observation? = null): OperationalLock? {
        // DROP can remove pg_class rows while its transaction still holds the relation locks.
        // Use the exact same OID's observed prior identity, never a name guessed from SQL.
        val rel = observation.relations[held.oid] ?: before?.relations?.get(held.oid) ?: return null
        val table = if (rel.kind in setOf("index", "partitioned_index")) rel.owner?.let { owner ->
            observation.relations[owner] ?: before?.relations?.get(owner)
        } else rel
        return OperationalLock(rel.name, rel.kind, held.mode, table?.let(::relation))
    }

    private fun actions(intent: OperationalIntent, before: Observation, after: Observation): List<OperationalAction> {
        val oid = intent.table?.let { before.resolved[it] ?: after.resolved[it] }
        val old = oid?.let(before.tables::get)
        val next = oid?.let(after.tables::get)
        val physical = oid?.let { after.relations[it] ?: before.relations[it] }
        val target = physical?.let(::relation)
        val ordinary = physical?.kind == "table" && physical.inheritance == false
        fun action(kind: OperationalActionKind = intent.kind, confirmed: Boolean = false, complete: Boolean = false,
                   related: List<OperationalRelation> = emptyList(), noOp: Boolean = false,
                   priorNotNull: Boolean? = null, priorCheck: Boolean? = null, priorValidated: Boolean? = null,
                   evidence: String = "") = OperationalAction(kind, target, related, confirmed, complete && (target == null || ordinary && before.catalogFirst && after.catalogFirst),
            intent.concurrent, intent.notValid, noOp, priorNotNull, priorCheck, priorValidated, evidence)
        fun references(constraint: Constraint): List<OperationalRelation> = listOfNotNull(constraint.referencedOid?.let { after.relations[it] ?: before.relations[it] }?.let(::relation))
        fun supported(constraint: Constraint, state: Table): Boolean = when (constraint.schema.type) {
            "c" -> simpleCheck(constraint.schema, state)
            "f" -> constraint.schema.columns.all { builtinColumn(state, it) } &&
                constraint.referencedOid?.let { after.tables[it] ?: before.tables[it] }?.let { parent ->
                    constraint.schema.referencedColumns.all { builtinColumn(parent, it) }
                } == true
            else -> false
        }
        if (intent.kind == OperationalActionKind.SETTINGS_CHANGE)
            return listOf(action(confirmed = intent.complete, complete = intent.complete, evidence = "A literal lock_timeout setting executed; sampled settings retain their origin."))
        if (intent.kind == OperationalActionKind.CREATE_TABLE) {
            val created = oid != null && oid !in before.relations && next != null
            val noOp = intent.ifNotExists && old != null && next != null
            val related = next?.constraints?.values.orEmpty().flatMap(::references).distinct()
            return listOf(action(confirmed = created || noOp, complete = intent.complete && physical?.kind == "table" && next?.let { state ->
                    state.columns.all { (name, column) -> builtinColumn(state, name) && column.generated == null &&
                        (column.identity == null || column.identity in setOf("a", "d") && column.type in setOf("smallint", "integer", "bigint")) &&
                        (column.defaultExpression == null || RuleProofs.constantDefault(column.defaultExpression, column.type)) } &&
                    state.indexes.values.all { it.plain } && state.constraints.values.all { it.schema.type in setOf("p", "u") || supported(it, state) }
                } == true,
                related = related, noOp = noOp, evidence = "Table ${target?.name}; created=$created; existing IF NOT EXISTS no-op=$noOp."))
        }
        if (intent.kind == OperationalActionKind.CREATE_INDEX) {
            val name = intent.detail?.let(::identifier)
            val index = next?.indexes?.values?.singleOrNull { it.name == name }
            val existed = old?.indexes?.values?.any { it.name == name } == true
            val noOp = intent.ifNotExists && existed
            return listOf(action(confirmed = index != null && index.valid && index.ready,
                complete = intent.complete && next != null && index?.plain == true && index.columns.all { builtinColumn(next, it) }, noOp = noOp,
                evidence = "Index ${intent.detail}; valid=${index?.valid}; ready=${index?.ready}; concurrent=${intent.concurrent}; prior index present=$existed."))
        }
        if (intent.ifExists && oid == null && intent.complete)
            return listOf(action(OperationalActionKind.METADATA_CHANGE, confirmed = true, complete = true, noOp = true,
                evidence = "ALTER TABLE IF EXISTS skipped the catalog-absent relation ${intent.table}."))
        if (old == null || next == null || target == null) return listOf(action(evidence = "Target relation/column observations are incomplete."))
        val oldColumn = intent.detail?.let(old.columns::get)
        val newColumn = intent.detail?.let(next.columns::get)
        when (intent.kind) {
            OperationalActionKind.SET_NOT_NULL -> {
                val proved = oldColumn != null && RuleProofs.scalarNullTest(oldColumn.type, SchemaSnapshot()) &&
                    old.constraints.values.any { it.schema.validated && RuleProofs.notNullCheck(it.schema) == intent.detail }
                return listOf(action(confirmed = oldColumn != null && newColumn?.nullable == false,
                    complete = intent.complete && intent.detail?.let { builtinColumn(old, it) } == true, noOp = oldColumn?.nullable == false,
                    priorNotNull = oldColumn?.let { !it.nullable }, priorCheck = proved,
                    evidence = "Column ${intent.detail}; before NOT NULL=${oldColumn?.let { !it.nullable }}; prior validated scalar non-null CHECK=$proved."))
            }
            OperationalActionKind.ADD_CHECK, OperationalActionKind.ADD_FOREIGN_KEY, OperationalActionKind.VALIDATE_CONSTRAINT -> {
                val constraint = next.constraints[intent.detail]
                val previous = old.constraints[intent.detail]
                val expectedType = if (intent.kind == OperationalActionKind.ADD_CHECK) "c" else if (intent.kind == OperationalActionKind.ADD_FOREIGN_KEY) "f" else null
                val confirmed = constraint != null && (expectedType == null || constraint.schema.type == expectedType) &&
                    if (intent.kind == OperationalActionKind.VALIDATE_CONSTRAINT) previous != null && constraint!!.schema.validated
                    else previous == null && constraint!!.schema.validated != intent.notValid
                return listOf(action(confirmed = confirmed, complete = intent.complete && constraint?.let { supported(it, next) } == true,
                    related = constraint?.let(::references).orEmpty(), noOp = intent.kind == OperationalActionKind.VALIDATE_CONSTRAINT && previous?.schema?.validated == true,
                    priorValidated = previous?.schema?.validated,
                    evidence = "Constraint ${intent.detail}; before validated=${previous?.schema?.validated}; after validated=${constraint?.schema?.validated}; type=${constraint?.schema?.type}."))
            }
            OperationalActionKind.ALTER_COLUMN_TYPE -> return listOf(action(confirmed = oldColumn != null && newColumn != null,
                complete = intent.complete && intent.detail?.let { builtinColumn(old, it) && builtinColumn(next, it) } == true &&
                    (oldColumn?.type == newColumn?.type || (oldColumn?.type to newColumn?.type) in before.intrinsicCasts),
                noOp = oldColumn?.type == newColumn?.type,
                evidence = "Column ${intent.detail}; type ${oldColumn?.type} -> ${newColumn?.type}; heap rewrite is measured separately."))
            OperationalActionKind.METADATA_CHANGE -> {
                var confirmed = false
                var complete = intent.complete
                var noOp = false
                when (intent.modifier) {
                    "RENAME_TABLE" -> confirmed = physical?.name?.substringAfterLast('.') == intent.replacement && old.relation.oid == next.relation.oid
                    "RENAME_COLUMN" -> confirmed = oldColumn != null && intent.detail !in next.columns && intent.replacement in next.columns
                    "RENAME_CONSTRAINT" -> confirmed = intent.detail in old.constraints && intent.detail !in next.constraints && intent.replacement in next.constraints
                    "DROP_NOT_NULL" -> confirmed = oldColumn != null && newColumn?.nullable == true
                    "DROP_DEFAULT" -> confirmed = oldColumn != null && newColumn != null && newColumn.defaultExpression == null
                    "SET_DEFAULT" -> { confirmed = oldColumn != null && newColumn?.defaultExpression != null; complete = complete && newColumn?.let { RuleProofs.constantDefault(it.defaultExpression, it.type) } == true }
                    "ADD_COLUMN" -> {
                        noOp = intent.ifNotExists && oldColumn != null && oldColumn == newColumn
                        confirmed = oldColumn == null && newColumn != null || noOp
                        complete = complete && (noOp || newColumn?.let { it.nullable && intent.detail in next.builtinColumns && builtin(it.type) && it.generated == null && it.identity == null &&
                            (it.defaultExpression == null || RuleProofs.constantDefault(it.defaultExpression, it.type)) } == true)
                    }
                    else -> complete = false
                }
                val result = mutableListOf(action(confirmed = confirmed, complete = complete, noOp = noOp,
                    evidence = "Catalog corroborates ${intent.modifier} on ${target.name}${intent.detail?.let { ".$it" }.orEmpty()}=$confirmed; existing IF NOT EXISTS no-op=$noOp."))
                if (intent.modifier == "ADD_COLUMN") for ((name, constraint) in next.constraints) {
                    if (name in old.constraints) continue
                    val kind = when (constraint.schema.type) { "c" -> OperationalActionKind.ADD_CHECK; "f" -> OperationalActionKind.ADD_FOREIGN_KEY; else -> OperationalActionKind.UNKNOWN }
                    result += action(kind, confirmed = true, complete = supported(constraint, next), related = references(constraint),
                        evidence = "The added column also introduces ${constraint.schema.type} constraint $name; validated=${constraint.schema.validated}.")
                }
                return result
            }
            else -> return listOf(action(evidence = "Statement is outside the supported operational grammar."))
        }
    }

    private fun simpleCheck(constraint: ConstraintSchema, table: Table): Boolean {
        RuleProofs.notNullCheck(constraint)?.let { return builtinColumn(table, it) }
        val expression = constraint.checkExpression ?: return false
        val name = "(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z_0-9$]*)"
        val match = Regex("^\\(*\\s*($name)\\s*(?:>=|<=|<>|=|>|<)\\s*-?[0-9]+\\s*\\)*$").matchEntire(expression.trim()) ?: return false
        return identifier(match.groupValues[1]) in table.builtinColumns && table.columns[identifier(match.groupValues[1])]?.type in setOf("smallint", "integer", "bigint", "numeric")
    }
    private fun builtinColumn(table: Table, name: String) = name in table.builtinColumns && builtin(table.columns[name]?.type)
    private fun builtin(type: String?) = type != null && Regex("(?:smallint|integer|bigint|boolean|real|double precision|numeric(?:\\(\\d+(?:,\\s*-?\\d+)?\\))?|text|character varying(?:\\(\\d+\\))?|character(?:\\(\\d+\\))?|bytea|uuid|date|timestamp(?:\\(\\d+\\))? (?:with|without) time zone|time(?:\\(\\d+\\))? (?:with|without) time zone|interval|json|jsonb)").matches(type)
    private fun identifier(value: String) = postgresIdentifier(if (value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1).replace("\"\"", "\"") else value.lowercase())

    private fun observe(connection: Connection, intent: OperationalSql, retainedIds: Set<Long> = emptySet()): Observation {
        val relations = linkedMapOf<Long, Relation>()
        connection.rows("""
            SELECT c.oid::bigint, n.nspname || '.' || c.relname AS name, c.relkind,
                   c.relfilenode::bigint AS filenode, i.indrelid::bigint AS owner,
                   c.relispartition OR c.relkind='p' OR EXISTS (SELECT 1 FROM pg_catalog.pg_inherits h WHERE h.inhrelid=c.oid OR h.inhparent=c.oid) AS inheritance
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            LEFT JOIN pg_catalog.pg_index i ON i.indexrelid=c.oid
            WHERE c.relkind IN ('r','p','m','v','i','I','S') AND n.nspname NOT LIKE 'pg_%'
              AND n.nspname <> 'information_schema' AND c.relname <> 'flyway_schema_history'
        """) { row ->
            val kind = when (row.getString("relkind")) { "r" -> "table"; "p" -> "partitioned_table"; "m" -> "materialized_view"; "v" -> "view"; "i" -> "index"; "I" -> "partitioned_index"; else -> "sequence" }
            val owner = row.getLong("owner").takeUnless { row.wasNull() }
            relations[row.getLong("oid")] = Relation(row.getLong("oid"), row.getString("name"), kind, row.getLong("filenode"), owner, row.getBoolean("inheritance"))
        }
        val locks = mutableListOf<HeldLock>()
        connection.rows("SELECT relation::bigint, mode FROM pg_catalog.pg_locks WHERE pid=pg_catalog.pg_backend_pid() AND locktype='relation' AND granted") {
            locks += HeldLock(it.getLong(1), it.getString(2))
        }
        var catalogFirst = false
        connection.rows("SELECT (pg_catalog.current_schemas(true))[1]='pg_catalog'") { catalogFirst = it.getBoolean(1) }
        var timeout: Pair<Long, String>? = null
        connection.rows("SELECT setting::bigint, source FROM pg_catalog.pg_settings WHERE name='lock_timeout'") { timeout = it.getLong(1) to it.getString(2) }
        val events = mutableListOf<EventTrigger>()
        connection.rows("SELECT evtname, evtevent, evttags FROM pg_catalog.pg_event_trigger WHERE evtenabled <> 'D'") {
            events += EventTrigger(it.getString(1), it.getString(2), it.strings("evttags").toSet())
        }
        val resolved = mutableMapOf<String, Long>()
        for (name in intent.tables) connection.prepareStatement("SELECT pg_catalog.to_regclass(?)::oid::bigint").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1).takeUnless { rows.wasNull() }?.let { resolved[name] = it } }
        }
        val tables = linkedMapOf<Long, Table>()
        for (oid in (resolved.values + retainedIds).distinct()) relations[oid]?.let { tables[oid] = table(connection, it) }
        // FK parent types are needed to establish known intrinsic validation rather than custom codecs.
        val referenced = tables.values.flatMap { it.constraints.values }.mapNotNull { it.referencedOid }.distinct()
        for (oid in referenced) if (oid !in tables) relations[oid]?.let { tables[oid] = table(connection, it) }
        val intrinsicCasts = mutableSetOf<Pair<String, String>>()
        // PostgreSQL reserves OIDs below FirstNormalObjectId (16384) for system objects.
        // A user-created function/opclass can live in pg_catalog; namespace alone is no proof.
        if (intent.intents.any { it.kind == OperationalActionKind.ALTER_COLUMN_TYPE }) connection.rows("""
            SELECT pg_catalog.format_type(c.castsource,NULL) AS source_type,
                   pg_catalog.format_type(c.casttarget,NULL) AS target_type
            FROM pg_catalog.pg_cast c
            JOIN pg_catalog.pg_type s ON s.oid=c.castsource
            JOIN pg_catalog.pg_type t ON t.oid=c.casttarget
            JOIN pg_catalog.pg_namespace sn ON sn.oid=s.typnamespace
            JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace
            LEFT JOIN pg_catalog.pg_proc p ON p.oid=c.castfunc
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.oid=p.pronamespace
            WHERE sn.nspname='pg_catalog' AND tn.nspname='pg_catalog'
              AND s.typtype='b' AND t.typtype='b' AND c.castcontext IN ('i','a')
              AND c.oid < 16384
              AND (c.castmethod='b' OR (c.castmethod='f' AND pn.nspname='pg_catalog' AND p.oid < 16384
                   AND p.prolang=(SELECT oid FROM pg_catalog.pg_language WHERE lanname='internal')))
        """) { intrinsicCasts += it.getString("source_type") to it.getString("target_type") }
        return Observation(relations, locks, requireNotNull(timeout), events, resolved, tables, catalogFirst, intrinsicCasts)
    }

    private fun table(connection: Connection, relation: Relation): Table {
        val columns = linkedMapOf<String, ColumnSchema>()
        val builtinColumns = mutableSetOf<String>()
        connection.rows("""
            SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod) AS type, a.attnotnull,
                   pg_catalog.pg_get_expr(d.adbin,d.adrelid) AS default_expr, a.attidentity, a.attgenerated,
                   tn.nspname='pg_catalog' AND t.typtype='b' AS builtin_type
            FROM pg_catalog.pg_attribute a
            JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace tn ON tn.oid=t.typnamespace
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
            WHERE a.attrelid=${relation.oid} AND a.attnum>0 AND NOT a.attisdropped
        """) { row -> columns[row.getString("attname")] = ColumnSchema(row.getString("type"), !row.getBoolean("attnotnull"),
            row.getString("default_expr"), row.getString("attidentity").takeIf(String::isNotEmpty), row.getString("attgenerated").takeIf(String::isNotEmpty))
            if (row.getBoolean("builtin_type")) builtinColumns += row.getString("attname")
        }
        val constraints = linkedMapOf<String, Constraint>()
        connection.rows("""
            SELECT co.conname, co.contype, co.convalidated, co.confrelid::bigint,
                   pg_catalog.pg_get_expr(co.conbin,co.conrelid) AS expression,
                   ARRAY(SELECT a.attname FROM unnest(co.conkey) WITH ORDINALITY k(num,pos)
                         JOIN pg_catalog.pg_attribute a ON a.attrelid=co.conrelid AND a.attnum=k.num ORDER BY k.pos) AS columns,
                   ARRAY(SELECT a.attname FROM unnest(co.confkey) WITH ORDINALITY k(num,pos)
                         JOIN pg_catalog.pg_attribute a ON a.attrelid=co.confrelid AND a.attnum=k.num ORDER BY k.pos) AS referenced_columns
            FROM pg_catalog.pg_constraint co WHERE co.conrelid=${relation.oid}
        """) { row -> constraints[row.getString("conname")] = Constraint(ConstraintSchema(row.getString("contype"), "",
            columns = row.strings("columns"), referencedColumns = row.strings("referenced_columns"), checkExpression = row.getString("expression"),
            validated = row.getBoolean("convalidated")), row.getLong("confrelid").takeIf { it != 0L }) }
        val indexes = linkedMapOf<Long, Index>()
        connection.rows("""
            SELECT i.indexrelid::bigint, c.relname, i.indisvalid, i.indisready,
                   i.indexprs IS NULL AND i.indpred IS NULL AND am.oid < 16384 AND am.amname IN ('btree','hash','gin','gist','spgist','brin')
                       AND NOT EXISTS (SELECT 1 FROM unnest(i.indclass) k(oid) JOIN pg_catalog.pg_opclass o ON o.oid=k.oid
                           JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace
                           WHERE n.nspname<>'pg_catalog' OR o.oid >= 16384 OR o.opcfamily >= 16384) AS plain,
                   ARRAY(SELECT a.attname FROM unnest(i.indkey) WITH ORDINALITY k(num,pos)
                         JOIN pg_catalog.pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num ORDER BY k.pos) AS columns
            FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid
            JOIN pg_catalog.pg_am am ON am.oid=c.relam WHERE i.indrelid=${relation.oid}
        """) { row -> indexes[row.getLong("indexrelid")] = Index(row.getString("relname"), row.getBoolean("indisvalid"), row.getBoolean("indisready"), row.getBoolean("plain"), row.strings("columns")) }
        return Table(relation, columns, constraints, indexes, builtinColumns)
    }

    private data class Active(val ordinal: Int, val sql: String, val intent: OperationalSql, val autoCommit: Boolean,
                              val before: Observation, val timeout: OperationalTimeout)
    private data class Relation(val oid: Long, val name: String, val kind: String, val filenode: Long, val owner: Long?, val inheritance: Boolean)
    private data class HeldLock(val oid: Long, val mode: String)
    private data class EventTrigger(val name: String, val event: String, val tags: Set<String>) {
        fun applies(tag: String?): Boolean {
            if (tag in setOf("SET", "RESET", "SELECT", "INSERT", "UPDATE", "DELETE")) return false
            if (event == "sql_drop" && tag != null && !tag.startsWith("DROP") && tag != "ALTER TABLE") return false
            return tag == null || tags.isEmpty() || tag in tags
        }
    }
    private data class Constraint(val schema: ConstraintSchema, val referencedOid: Long?)
    private data class Index(val name: String, val valid: Boolean, val ready: Boolean, val plain: Boolean, val columns: List<String>)
    private data class Table(val relation: Relation, val columns: Map<String, ColumnSchema>, val constraints: Map<String, Constraint>, val indexes: Map<Long, Index>, val builtinColumns: Set<String>)
    private data class Observation(val relations: Map<Long, Relation>, val locks: List<HeldLock>, val timeout: Pair<Long, String>,
                                   val events: List<EventTrigger>, val resolved: Map<String, Long>, val tables: Map<Long, Table>, val catalogFirst: Boolean,
                                   val intrinsicCasts: Set<Pair<String, String>>)
    companion object { private val lockOrder = compareBy<OperationalLock>({ it.relation }, { it.relationKind }, { it.mode }) }
}

private fun Connection.rows(sql: String, consume: (ResultSet) -> Unit) = createStatement().use { statement ->
    statement.executeQuery(sql).use { rows -> while (rows.next()) consume(rows) }
}
private fun ResultSet.strings(column: String): List<String> {
    val value = getArray(column) ?: return emptyList()
    return try { (value.array as Array<*>).map { it.toString() } } finally { value.free() }
}
