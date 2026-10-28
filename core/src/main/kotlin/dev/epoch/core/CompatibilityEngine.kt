package dev.epoch.core

/**
 * Compares every target with the original application baseline. Rules establish only the
 * declared structural contract. The database adapter adds authoritative planning failures and
 * UNKNOWN findings for migration effects that catalog snapshots cannot establish.
 */
class CompatibilityEngine {
    fun verifyStep(
        contract: DatabaseContract,
        baseline: SchemaSnapshot,
        after: SchemaSnapshot,
        migration: MigrationInfo,
        additionalFindings: List<Finding> = emptyList(),
    ): MigrationReport {
        val normalized = CompatibilityViews.normalize(baseline, after)
        return verifyNormalized(normalizeIdentifiers(contract), baseline, normalized.after, migration,
            additionalFindings, normalized.provenViews, after)
    }

    private fun verifyNormalized(
        contract: DatabaseContract,
        baseline: SchemaSnapshot,
        after: SchemaSnapshot,
        migration: MigrationInfo,
        additionalFindings: List<Finding> = emptyList(),
        provenViews: Map<String, String> = emptyMap(),
        rawAfter: SchemaSnapshot = after,
    ): MigrationReport {
        val findings = additionalFindings.map { it.copy(migration = migration) }.toMutableList()
        fun finding(verdict: Verdict, rule: String, objectName: String, message: String,
                    evidence: String, ids: List<String> = emptyList(), mitigation: String? = null) {
            findings += Finding(verdict, rule, objectName, message, evidence, ids.distinct().sorted(), migration, mitigation)
        }
        fun unknown(rule: String, name: String, message: String, evidence: String, ids: List<String> = emptyList()) =
            finding(Verdict.UNKNOWN, rule, name, message, evidence, ids, unknownMitigation(rule))

        if (contract.schemaVersion != 1) unknown("UNSUPPORTED_CONTRACT_VERSION", "contract", "Unsupported contract schema version.", "schemaVersion=${contract.schemaVersion}; supported=1")
        if (contract.dialect != "PostgreSQL") unknown("UNSUPPORTED_DIALECT", "contract", "Unsupported database dialect.", "dialect=${contract.dialect}; supported=PostgreSQL")
        if (contract.scope != VerificationScope.STRUCTURAL_CONTRACT) unknown("WHOLE_APPLICATION_UNPROVEN", "contract", "The MVP cannot prove whole-application compatibility.", "Only STRUCTURAL_CONTRACT is supported.")
        if (!contract.coverage.complete) unknown("INCOMPLETE_COVERAGE", "contract", "Extraction is incomplete for the declared contract.", contract.coverage.notes.joinToString("; ").ifBlank { "coverage.complete=false" })
        if (contract.entities.isEmpty() && contract.queries.isEmpty()) unknown("EMPTY_CONTRACT", "contract", "No database operations were supplied.", "An empty contract cannot establish compatibility.")
        val opaqueWriteBehavior = (baseline.behaviors.keys + after.behaviors.keys).any {
            it.startsWith("trigger:") || it.startsWith("rule:") || it.startsWith("rls:") || it.startsWith("policy:") || it.startsWith("inheritance:")
        }
        val allIds = contract.entities.map { it.id } + contract.queries.map { it.id }
        if (allIds.distinct().size != allIds.size) unknown("DUPLICATE_CONTRACT_ID", "contract", "Contract identifiers are ambiguous.", "Entity and query IDs must be unique.")
        contract.unresolvedQueries.forEach { unknown("UNRESOLVED_QUERY", it.id, "A database-access path remains unresolved.", it.reason, listOf(it.id)) }
        contract.accessPaths.filter { it.status == AccessPathStatus.UNRESOLVED && contract.unresolvedQueries.none { q -> q.id == it.id } }.forEach {
            unknown("UNRESOLVED_QUERY", it.id, "A discovered database-access path remains unresolved.",
                it.reason ?: "Unresolved ${it.className}#${it.method} (${it.mechanism ?: "unspecified mechanism"}).", listOf(it.id))
        }
        val tableIdentifiers = baseline.tables.keys + after.tables.keys + contract.entities.map { it.table } +
            contract.queries.flatMap { query ->
                query.tables + listOfNotNull(query.targetTable) + (query.columns + query.writeColumns).map { it.table }
            }
        tableIdentifiers.distinct().sorted().filter { name ->
            val parts = name.split('.')
            parts.size != 2 || parts.any { it.isEmpty() }
        }.forEach { name ->
            unknown("UNSUPPORTED_IDENTIFIER", name,
                "Qualified table identity cannot be represented unambiguously by the supported contract format.",
                "Expected exactly schema.table; dotted quoted schema/table identifiers can collide when flattened and are unsupported.")
        }

        contract.entities.forEach { entity ->
            if (entity.identifierSequence != null && Operation.INSERT in entity.operations && entity.identifierSequence !in baseline.sequences) {
                unknown("INVALID_BASELINE_SEQUENCE", entity.identifierSequence, "The mapped identifier sequence is absent from the baseline.",
                    "The old generator ${entity.generatedIdentifier} requires ${entity.identifierSequence}.", listOf(entity.id))
            }
            if (entity.generatedIdentifier?.endsWith(".IdentityGenerator") == true && Operation.INSERT in entity.operations) {
                entity.columns.filter { it.generated }.forEach { column ->
                    val schema = baseline.tables[entity.table]?.columns?.get(column.name)
                    if (schema != null && schema.defaultExpression == null && schema.identity.isNullOrEmpty()) unknown(
                        "INVALID_BASELINE_GENERATION", "${entity.table}.${column.name}",
                        "The baseline does not provide the mapped identity value.", "Mapping uses ${entity.generatedIdentifier}; baseline has neither identity nor default.", listOf(entity.id))
                }
            }
            if (entity.columns.isEmpty() || entity.operations.isEmpty()) unknown("INCOMPLETE_MAPPING", entity.table, "Mapping has no complete operation dependency set.", "Mapping ${entity.id} has ${entity.columns.size} columns and ${entity.operations.size} operations.", listOf(entity.id))
            val oldTable = baseline.tables[entity.table]
            if (oldTable == null) {
                unknown("INVALID_BASELINE_TABLE", entity.table, "A mapped table is absent from the baseline.", "The old operation is not established as valid against the baseline.", listOf(entity.id))
            } else {
                entity.columns.forEach { column ->
                    if (column.name !in oldTable.columns) unknown("INVALID_BASELINE_COLUMN", "${entity.table}.${column.name}", "A mapped column is absent from the baseline.", "The old operation is not established as valid against the baseline.", listOf(entity.id))
                    val physicalType = oldTable.columns[column.name]?.type
                    val enumLabels = baseline.enumTypes[physicalType] ?: baseline.enumTypes["public.$physicalType"]
                    if (enumLabels != null && column.enumValues.any { it !in enumLabels }) unknown(
                        "INVALID_BASELINE_ENUM", "${entity.table}.${column.name}", "The old mapped enum domain includes values absent from the baseline PostgreSQL enum.",
                        "Mapped=${column.enumValues}; baseline=$enumLabels.", listOf(entity.id))
                }
            }
        }
        contract.queries.forEach { query ->
            if (!query.dependenciesComplete || query.verificationStatus != VerificationStatus.EXTRACTED || query.sql.isBlank()) {
                unknown("INCOMPLETE_QUERY", query.id, "Query dependencies or exhaustive variants are not established.", "source=${query.source}; status=${query.verificationStatus}; dependenciesComplete=${query.dependenciesComplete}", listOf(query.id))
            }
            if (query.operation != Operation.SELECT && queryTarget(query) == null) {
                unknown("MISSING_WRITE_TARGET", query.id, "Write query has no established target table.", query.sql, listOf(query.id))
            }
            (query.tables + listOfNotNull(query.targetTable)).distinct().forEach { table ->
                if (table !in baseline.tables) unknown("INVALID_BASELINE_TABLE", table, "A query table is absent from the baseline.", query.sql, listOf(query.id))
            }
            (query.columns + query.writeColumns).distinct().forEach { column ->
                if (baseline.tables[column.table]?.columns?.containsKey(column.column) != true) unknown("INVALID_BASELINE_COLUMN", "${column.table}.${column.column}", "A query column is absent from the baseline.", query.sql, listOf(query.id))
            }
        }

        // A catalog mismatch is not a demonstrated regression if the old operation was
        // already invalid against the supplied baseline.
        val rejectedBaselineIds = (additionalFindings + findings).filter {
            it.ruleId == "BASELINE_SQL_UNVERIFIED" || it.ruleId.startsWith("INVALID_BASELINE_")
        }.flatMap { it.affectedContractIds }.toSet() +
            allIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val validBaselineIds = (contract.entities.filter { entity ->
            entity.columns.isNotEmpty() && entity.operations.isNotEmpty() &&
                entity.columns.all { it.name in (baseline.tables[entity.table]?.columns ?: emptyMap()) }
        }.map { it.id }.toSet() + contract.queries.filter { query ->
            query.sql.isNotBlank() && query.dependenciesComplete && query.verificationStatus == VerificationStatus.EXTRACTED &&
                (query.tables + listOfNotNull(query.targetTable)).all { it in baseline.tables } &&
                (query.columns + query.writeColumns).all { it.column in (baseline.tables[it.table]?.columns ?: emptyMap()) }
        }.map { it.id }.toSet()) - rejectedBaselineIds

        fun tableIds(table: String): List<String> =
            contract.entities.filter { it.table == table }.map { it.id } +
                contract.queries.filter { table in it.tables || it.targetTable == table || (it.columns + it.writeColumns).any { c -> c.table == table } }.map { it.id }
        fun columnIds(table: String, column: String): List<String> =
            contract.entities.filter { it.table == table && it.columns.any { c -> c.name == column } }.map { it.id } +
                contract.queries.filter { (it.columns + it.writeColumns).any { c -> c.table == table && c.column == column } ||
                    (table in it.tables && !it.explicitColumnList && it.exactResultShape) }.map { it.id }
        fun insertIds(table: String): List<String> =
            contract.entities.filter { it.table == table && Operation.INSERT in it.operations }.map { it.id } +
                contract.queries.filter { queryTarget(it) == table && it.operation == Operation.INSERT }.map { it.id }
        fun writeIds(table: String): List<String> =
            contract.entities.filter { it.table == table && it.operations.any { op -> op in setOf(Operation.INSERT, Operation.UPDATE, Operation.DELETE) } }.map { it.id } +
                contract.queries.filter { queryTarget(it) == table && it.operation != Operation.SELECT }.map { it.id }
        val allWriteIds = contract.entities.filter { it.operations.any { op -> op != Operation.SELECT } }.map { it.id } +
            contract.queries.filter { it.operation != Operation.SELECT }.map { it.id }

        provenViews.forEach { (name, backing) ->
            finding(Verdict.SAFE, "TRANSPARENT_COMPATIBILITY_VIEW", name,
                "The old relation name exposes the same backing table through a transparent, automatically writable view.",
                "Complete ordered direct projection from $backing; original table identity retained; owners agree; no explicit privileges, row security, user triggers/rules, inheritance, view options, or view default overrides. Backing columns, defaults, generators, constraints and indexes remain subject to the ordinary delta rules.",
                tableIds(name))
        }
        if (provenViews.isNotEmpty()) {
            val relationNames = provenViews.entries.associate { (old, backing) -> backing to old }
            (CompatibilityViews.expressionRisks(baseline) + CompatibilityViews.expressionRisks(rawAfter, relationNames))
                .distinct().sortedWith(compareBy({ it.objectName }, { it.kind }, { it.expression })).forEach { risk ->
                    unknown("RELATION_RENAME_EXPRESSION_UNPROVEN", risk.objectName,
                        "A stored expression may observe the table rename and change old reads or writes despite successful planning.",
                        "Unmodeled ${risk.kind} expression: ${risk.expression}; renamed relations: ${provenViews.toSortedMap()}. PREPARE does not evaluate row expressions, including expressions stored on other tables.",
                        tableIds(risk.table))
                }
        }
        rawAfter.tables.toSortedMap().forEach { (name, table) ->
            val view = table.view ?: return@forEach
            if (table.kind != "view" || baseline.tables[name]?.kind != "table") return@forEach
            for ((operation, bit) in listOf(Operation.INSERT to 8, Operation.UPDATE to 4, Operation.DELETE to 16)) {
                val entities = contract.entities.filter { it.table == name && operation in it.operations && it.id in validBaselineIds }
                val queries = contract.queries.filter { queryTarget(it) == name && it.operation == operation && it.id in validBaselineIds }
                val ids = entities.map { it.id } + queries.map { it.id }
                if (ids.isEmpty()) continue
                if (view.updatableMaskWithTriggers and bit == 0) {
                    finding(Verdict.BREAKING, "VIEW_WRITE_UNSUPPORTED", name,
                        "The replacement view rejects a previously valid $operation operation.",
                        "PostgreSQL pg_relation_is_updatable reports $operation unsupported even with trigger support; automatic mask=${view.updatableMask}; mask with triggers=${view.updatableMaskWithTriggers}; required bit=$bit.",
                        ids, "Keep an automatically writable compatibility view for the old write operation until old application versions are retired.")
                    continue
                }
                if (operation == Operation.DELETE) continue
                val columns = (entities.flatMap { entity -> entity.columns.filter { column ->
                    if (operation == Operation.INSERT) column.insertable && (!column.generated || entity.identifierSequence != null)
                    else column.updatable
                }.map { it.name } } + queries.flatMap { it.writeColumns.map { column -> column.column } }).distinct().sorted()
                columns.filter { it in table.columns && it !in view.updatableColumnsWithTriggers }.forEach { column ->
                    val writers = entities.filter { entity -> entity.columns.any {
                        it.name == column && if (operation == Operation.INSERT) it.insertable && (!it.generated || entity.identifierSequence != null) else it.updatable
                    } }.map { it.id } + queries.filter { query -> query.writeColumns.any { it.column == column } }.map { it.id }
                    unknown("VIEW_COLUMN_WRITE_UNPROVEN", "$name.$column",
                        "PostgreSQL does not establish this view column as writable for the old $operation.",
                        "pg_column_is_updatable is false even with trigger support. DEFAULT assignments can still be accepted; a known non-DEFAULT assignment or PostgreSQL planning failure is required to prove breakage.", writers)
                }
            }
        }

        fun writesColumn(entity: EntityContract, column: ColumnContract) =
            (Operation.INSERT in entity.operations && column.insertable && !column.generated) ||
                (Operation.UPDATE in entity.operations && column.updatable)
        fun columnWriters(table: String, column: String): List<String> =
            contract.entities.filter { it.table == table && it.columns.any { c -> c.name == column && writesColumn(it, c) } }.map { it.id } +
                contract.queries.filter { queryTarget(it) == table && it.operation in setOf(Operation.INSERT, Operation.UPDATE) &&
                    it.writeColumns.any { c -> c.column == column } }.map { it.id }
        fun omittingInserts(table: String, column: String): List<String> =
            contract.entities.filter { it.table == table && Operation.INSERT in it.operations &&
                it.columns.none { c -> c.name == column && c.insertable && (!c.generated || it.identifierSequence != null) } }.map { it.id } +
                contract.queries.filter { queryTarget(it) == table && it.operation == Operation.INSERT && it.explicitColumnList &&
                    it.writeColumns.none { c -> c.column == column } }.map { it.id }
        fun ordinaryWriteSemantics(table: TableSchema) = table.kind == "table" &&
            table.relationSecurity?.let { security ->
                !security.userTriggers && !security.userRules && !security.rowSecurity && !security.inheritance
            } == true
        fun omittedValueIsNull(table: TableSchema, columnName: String): Boolean {
            val column = table.columns[columnName] ?: return false
            return !opaqueWriteBehavior && ordinaryWriteSemantics(table) && column.nullable &&
                column.typeAcceptsImplicitNull == true && column.defaultExpression == null &&
                column.identity.isNullOrEmpty() && column.generated.isNullOrEmpty()
        }
        fun referencesConflict(table: String) = contract.queries.filter {
            queryTarget(it) == table && Regex("\\bON\\s+CONFLICT\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.sql)
        }.map { it.id }
        fun affectedReferencedWrites(constraint: ConstraintSchema): List<String> {
            val target = constraint.referencedTable ?: return allWriteIds
            return contract.entities.filter { entity ->
                entity.table == target && (Operation.DELETE in entity.operations ||
                    Operation.UPDATE in entity.operations && entity.columns.any { it.name in constraint.referencedColumns && it.updatable })
            }.map { it.id } + contract.queries.filter { query ->
                queryTarget(query) == target && (query.operation == Operation.DELETE ||
                    query.operation == Operation.UPDATE && (query.writeColumns.isEmpty() || query.writeColumns.any { it.column in constraint.referencedColumns }))
            }.map { it.id }
        }
        fun newForeignKey(table: String, name: String, constraint: ConstraintSchema) {
            val writers = affectedReferencedWrites(constraint)
            if (writers.isEmpty()) finding(Verdict.SAFE, "NEW_REFERENTIAL_CONSTRAINT_UNREFERENCED", "$table.$name",
                "The new foreign key does not affect a declared DELETE or referenced-key UPDATE.", constraint.definition)
            else unknown("NEW_REFERENTIAL_CONSTRAINT", "$table.$name",
                "Old DELETE or referenced-key UPDATE on ${constraint.referencedTable ?: "an unresolved target"} can now fail or cascade.",
                constraint.definition, writers)
        }
        fun hasInputForeignKey(table: TableSchema, column: String) = table.constraints.values.any {
            it.type == "f" && (it.columns.isEmpty() || column in it.columns)
        }
        fun unconstrainedInput(table: TableSchema, column: String) = !opaqueWriteBehavior && table.kind == "table" &&
            table.constraints.values.none { it.type == "c" } && !hasInputForeignKey(table, column)
        fun permitsEnumValue(table: TableSchema, column: String, value: String) = !opaqueWriteBehavior &&
            table.kind == "table" && !hasInputForeignKey(table, column) &&
            table.constraints.values.filter { it.type == "c" }.all { previous ->
                RuleProofs.enumCheck(previous)?.let { it.first == column && value in it.second } == true
            }

        for (tableName in (baseline.tables.keys + after.tables.keys).sorted()) {
            val old = baseline.tables[tableName]
            val new = after.tables[tableName]
            if (old == new) continue // baseline validity is checked above; unchanged relations need no delta rules
            val tableReferences = tableIds(tableName)
            if (old == null) {
                if (tableReferences.isEmpty()) finding(Verdict.SAFE, "UNREFERENCED_TABLE_ADDED", tableName, "Added table is outside the declared structural dependencies.", "Table absent at baseline and present after migration.")
                if (new != null && tableReferences.isNotEmpty()) unknown("INVALID_BASELINE_TABLE", tableName, "Referenced table did not exist in the baseline.", "No valid baseline operation has been established.", tableReferences)
                new?.constraints?.filterValues { it.type == "f" }?.forEach { (name, constraint) ->
                    newForeignKey(tableName, name, constraint)
                }
                continue
            }
            if (new == null) {
                if (tableReferences.any { it in validBaselineIds }) finding(Verdict.BREAKING, "REFERENCED_TABLE_DROPPED", tableName, "A table required by the old application no longer exists.", "Baseline table existed; target table is absent. A rename is a drop of the old name for old SQL.", tableReferences.filter { it in validBaselineIds }, "Keep the old table name available until all old application versions are retired.")
                else if (tableReferences.isEmpty()) finding(Verdict.SAFE, "UNREFERENCED_TABLE_DROPPED", tableName, "Dropped table is outside the declared structural dependencies.", "No declared entity or complete query references this table.")
                continue
            }
            if (old.kind != new.kind) unknown("RELATION_KIND_CHANGED", tableName,
                "The old SQL name remains available through a different relation kind; database behavior still requires verification.",
                "Baseline kind=${old.kind}; target kind=${new.kind}.", tableReferences)
            for (columnName in (old.columns.keys + new.columns.keys).sorted()) {
                val a = old.columns[columnName]
                val b = new.columns[columnName]
                if (a == b) continue
                val objectName = "$tableName.$columnName"
                val columnReferences = columnIds(tableName, columnName)
                if (a == null && b != null) {
                    val inserts = insertIds(tableName).filter { it in validBaselineIds }
                    if (!b.nullable && b.defaultExpression == null && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && inserts.isNotEmpty()) {
                        if (!opaqueWriteBehavior && ordinaryWriteSemantics(old) && ordinaryWriteSemantics(new) && b.typeAcceptsImplicitNull == true) {
                            finding(Verdict.BREAKING, "REQUIRED_COLUMN_OMITTED", objectName, "Old INSERT operations omit a new required column.",
                                "New column is NOT NULL without a column or type default, identity, generated expression, or opaque write behavior; the old INSERT omits it.",
                                inserts, "Introduce the column as nullable or provide a compatible default while old applications run.")
                        } else {
                            unknown("NEW_REQUIRED_COLUMN_BEHAVIOR", objectName,
                                "Old INSERT operations omit a new required column; type-level defaults or write behavior may supply its value.",
                                "type=${b.type}; typeAcceptsImplicitNull=${b.typeAcceptsImplicitNull}; baseline relation=${old.relationSecurity}; target relation=${new.relationSecurity}. No column default, identity, or generated expression is present.", inserts)
                        }
                    }
                    val shapeQueries = contract.queries.filter { tableName in it.tables && !it.explicitColumnList }
                    shapeQueries.forEach { query ->
                        unknown("POSITIONAL_SHAPE_CHANGED", objectName, "A positional operation or result shape may change when a column is added.", query.sql, listOf(query.id))
                    }
                    val constantDefault = RuleProofs.constantDefault(b.defaultExpression, b.type)
                    if (constantDefault && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && shapeQueries.isEmpty() && columnReferences.isEmpty()) {
                        finding(Verdict.SAFE, "CONSTANT_DEFAULT_COLUMN_ADDED", objectName,
                            "A constant default supplies the new column omitted by old explicit INSERT operations.",
                            "default=${b.defaultExpression}; type=${b.type}; nullable=${b.nullable}.", inserts)
                    }
                    if (((b.defaultExpression != null && !constantDefault) || !b.identity.isNullOrEmpty() || !b.generated.isNullOrEmpty()) && writeIds(tableName).isNotEmpty()) {
                        unknown("NEW_COLUMN_EXPRESSION", objectName, "A new default or generated expression can change write behavior.", "default=${b.defaultExpression}; identity=${b.identity}; generated=${b.generated}", writeIds(tableName))
                    }
                    if (b.nullable && b.defaultExpression == null && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && shapeQueries.isEmpty() && columnReferences.isEmpty()) {
                        val omissions = omittingInserts(tableName, columnName).filter { it in validBaselineIds }
                        if (omissions.isNotEmpty() && (!ordinaryWriteSemantics(old) || !omittedValueIsNull(new, columnName))) {
                            unknown("OMITTED_COLUMN_VALUE_UNPROVEN", objectName,
                                "The value of a new column omitted by old INSERTs is not established as an accepted NULL.",
                                "type=${b.type}; typeAcceptsImplicitNull=${b.typeAcceptsImplicitNull}; baseline relation=${old.relationSecurity}; target relation=${new.relationSecurity}. Type-level defaults, domain constraints, or opaque writes require separate proof.", omissions)
                        } else {
                            finding(Verdict.SAFE, "NULLABLE_COLUMN_ADDED", objectName, "Nullable column addition preserves explicit-column operations.",
                                "New column is outside old dependencies; any omitted INSERT value is a catalog-proven NULL with no default, generation, or opaque write behavior.", tableReferences)
                        }
                    }
                    continue
                }
                if (a != null && b == null) {
                    if (columnReferences.any { it in validBaselineIds }) finding(Verdict.BREAKING, "REFERENCED_COLUMN_DROPPED", objectName, "A column required by the old application no longer exists.", "Baseline column type=${a.type}; target column absent. A rename does not preserve the old SQL name.", columnReferences.filter { it in validBaselineIds }, "Retain the old column during the compatibility window, using an expand/contract migration.")
                    else if (columnReferences.isEmpty()) finding(Verdict.SAFE, "UNREFERENCED_COLUMN_DROPPED", objectName, "Dropped column is outside the declared structural dependencies.", "No declared operation depends on this column.")
                    contract.queries.filter { tableName in it.tables && !it.explicitColumnList && !it.exactResultShape }.forEach { query ->
                        unknown("POSITIONAL_SHAPE_CHANGED", objectName, "A positional operation's shape may change when a column is dropped.", query.sql, listOf(query.id))
                    }
                    continue
                }
                if (a != null && b != null) {
                    val writers = columnWriters(tableName, columnName).filter { it in validBaselineIds }
                    val mapped = contract.entities.filter { it.table == tableName && it.id in validBaselineIds }
                        .flatMap { entity -> entity.columns.filter { it.name == columnName && writesColumn(entity, it) }.map { entity.id to it } }
                    if (a.collation != b.collation && (columnReferences.isNotEmpty() || writeIds(tableName).isNotEmpty())) unknown(
                        "COLUMN_COLLATION_CHANGED", objectName,
                        "Changed collation may alter comparisons, ordering, and uniqueness for old operations.",
                        "Baseline collation=${a.collation}; target collation=${b.collation}; unchanged SQL types do not establish equivalent collation semantics.",
                        columnReferences + writers)
                    if (a.type != b.type && (columnReferences.isNotEmpty() || writeIds(tableName).isNotEmpty())) {
                        val narrowed = if (new.kind == "table" && unconstrainedInput(old, columnName)) mapped.mapNotNull { (id, column) ->
                            RuleProofs.mappedNarrowing(column, a.type, b.type)?.let { id to it }
                        } else emptyList()
                        val readers = contract.entities.filter { it.table == tableName && Operation.SELECT in it.operations && it.columns.any { c -> c.name == columnName } }.map { it.id } +
                            contract.queries.filter { tableName in it.tables && (it.operation == Operation.SELECT || it.resultColumns.isNotEmpty()) &&
                                (it.columns.any { c -> c.column == columnName } || !it.explicitColumnList) }.map { it.id }
                        when {
                            narrowed.isNotEmpty() -> finding(Verdict.BREAKING, "MAPPED_TYPE_NARROWED", objectName,
                                "The new type rejects or changes the representation of values within the declared mapped input bounds.",
                                narrowed.joinToString("; ") { it.second }, narrowed.map { it.first }, "Retain the mapped length/precision until old writers are retired.")
                            RuleProofs.widening(a.type, b.type) && (readers.isEmpty() || RuleProofs.stableReadRepresentation(a.type, b.type)) ->
                                finding(Verdict.SAFE, "TYPE_WIDENED", objectName, "Type widening preserves the declared input range and supported read representation.",
                                    "Baseline type=${a.type}; target type=${b.type}; affected readers=$readers.", columnReferences + writers)
                            else -> unknown("COLUMN_TYPE_CHANGED", objectName, "Parameter binding and result hydration compatibility are not established for a changed type.", "Baseline type=${a.type}; target type=${b.type}.", columnReferences + writers)
                        }
                    }
                    if (a.nullable && !b.nullable && writers.isNotEmpty()) {
                        val nullWriters = mapped.filter { it.second.nullable == true && it.second.inputDomainComplete &&
                            it.second.logicalJavaType !in setOf("byte", "short", "int", "long", "float", "double", "boolean", "char") }.map { it.first }
                        if (nullWriters.isNotEmpty() && unconstrainedInput(old, columnName) && new.kind == "table") finding(Verdict.BREAKING, "MAPPED_NULL_REJECTED", objectName,
                            "The new NOT NULL constraint rejects NULL in a declared nullable writable mapping.",
                            "Baseline accepts NULL; mapped nullable=true and writable=true; target nullable=false. No baseline CHECK, relevant foreign key, or opaque write behavior restricts the declared input domain.",
                            nullWriters, "Keep the column nullable until old writers no longer supply NULL.")
                        else unknown("NULLABILITY_CHANGED", objectName, "The new NOT NULL constraint may reject old inputs; their accepted domain is not established.",
                            "Baseline nullable=${a.nullable}; target nullable=${b.nullable}.", columnReferences + writers)
                    }
                    if (!a.nullable && b.nullable && columnReferences.isNotEmpty()) unknown("NULLABILITY_CHANGED", objectName,
                        "New NULL results may violate old hydration assumptions.", "Baseline nullable=false; target nullable=true.", columnReferences)
                    val omitted = omittingInserts(tableName, columnName).filter { it in validBaselineIds }
                    val generationRemoved = !opaqueWriteBehavior && old.kind == "table" && new.kind == "table" && (a.defaultExpression != null || !a.identity.isNullOrEmpty()) &&
                        b.defaultExpression == null && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && !b.nullable && omitted.isNotEmpty()
                    if (generationRemoved) finding(Verdict.BREAKING, "REQUIRED_GENERATION_REMOVED", objectName,
                        "Old INSERT operations omit a required column whose default or identity was removed.",
                        "Baseline default=${a.defaultExpression}, identity=${a.identity}; target has no default/identity and is NOT NULL.", omitted,
                        "Preserve the default or identity while old INSERT operations omit this column.")
                    else {
                        if (a.defaultExpression != b.defaultExpression && tableReferences.isNotEmpty()) unknown("DEFAULT_CHANGED", objectName, "Default changes may alter accepted writes or generated values.", "Baseline default=${a.defaultExpression}; target default=${b.defaultExpression}.", tableReferences)
                        if ((a.identity != b.identity || a.generated != b.generated) && tableReferences.isNotEmpty()) unknown("GENERATION_CHANGED", objectName, "Generated values or identifier behavior changed.", "Baseline identity=${a.identity}, generated=${a.generated}; target identity=${b.identity}, generated=${b.generated}.", tableReferences)
                    }
                    if (a.ordinal != b.ordinal && contract.queries.any { tableName in it.tables && !it.explicitColumnList }) unknown("COLUMN_ORDER_CHANGED", objectName, "Positional column order changed.", "Baseline ordinal=${a.ordinal}; target ordinal=${b.ordinal}.", tableReferences)
                }
            }
            for (name in (old.constraints.keys + new.constraints.keys).sorted()) {
                val a = old.constraints[name]
                val b = new.constraints[name]
                if (a == b) continue
                val involvedColumns = (a?.columns.orEmpty() + b?.columns.orEmpty()).distinct()
                val columnRelevant = involvedColumns.isEmpty() || involvedColumns.any { columnIds(tableName, it).isNotEmpty() }
                if (tableReferences.isEmpty() && b?.type != "f" && a?.type != "f") continue
                if (a == null && b?.type == "f") {
                    val inserts = insertIds(tableName).toSet()
                    val omissions = b.columns.associateWith { omittingInserts(tableName, it).toSet() }
                    // All-NULL tuples satisfy both MATCH SIMPLE and MATCH FULL. Any other implicit
                    // value, including a domain default or a generated identifier, needs proof.
                    val implicitNulls = b.columns.isNotEmpty() && ordinaryWriteSemantics(old) &&
                        b.columns.all { omittedValueIsNull(new, it) }
                    val localWriters = writeIds(tableName).filter { id ->
                        id in validBaselineIds && (b.columns.isEmpty() || b.columns.any { id in columnWriters(tableName, it) } ||
                            id in inserts && (!implicitNulls || !b.columns.all { id in omissions.getValue(it) }))
                    }
                    if (localWriters.isNotEmpty()) unknown("CONSTRAINT_CHANGED", "$tableName.$name", "A new foreign key may reject explicit or omitted input values from old writes to its referencing columns.", b.definition, localWriters)
                    newForeignKey(tableName, name, b)
                    continue
                }
                if (b == null && a?.type in setOf("u", "p") && referencesConflict(tableName).isEmpty()) {
                    finding(Verdict.SAFE, "UNIQUE_CONSTRAINT_REMOVED", "$tableName.$name", "Removing uniqueness expands accepted writes and no declared ON CONFLICT depends on it.", a.toString(), tableReferences)
                    continue
                }
                if (!columnRelevant && b == null) continue
                val enumCheck = b?.let(RuleProofs::enumCheck)
                if (enumCheck != null) {
                    val (column, accepted) = enumCheck
                    val mapped = contract.entities.filter { it.table == tableName && it.id in validBaselineIds }.flatMap { entity ->
                        entity.columns.filter { it.name == column && writesColumn(entity, it) && it.enumValues.isNotEmpty() && it.inputDomainComplete }.map { entity.id to it }
                    }
                    val excluded = mapped.flatMap { (id, mapping) -> mapping.enumValues.filter { value ->
                        value !in accepted && permitsEnumValue(old, column, value)
                    }.map { id to it } }
                    if (excluded.isNotEmpty()) {
                        finding(Verdict.BREAKING, "ENUM_VALUE_REJECTED", "$tableName.$column", "The new CHECK rejects an enum value the old mapping can write.",
                            "Excluded mapped values: ${excluded.map { it.second }.distinct().sorted()}; target CHECK=${b?.definition}.", excluded.map { it.first },
                            "Retain every enum value written by old applications.")
                        continue
                    }
                    val writers = columnWriters(tableName, column)
                    val supportedWriters = mapped.map { it.first }.toSet() + contract.queries.filter { it.source == QuerySource.HIBERNATE_MAPPING &&
                        queryTarget(it) == tableName && it.writeColumns.any { c -> c.column == column } }.map { it.id }
                    if (mapped.isNotEmpty() && supportedWriters.containsAll(writers) && mapped.all { accepted.containsAll(it.second.enumValues) }) {
                        finding(Verdict.SAFE, "ENUM_CHECK_ACCEPTS_MAPPED_VALUES", "$tableName.$name", "The supported CHECK accepts every enum value in the declared writable domain.",
                            "Mapped values=${mapped.flatMap { it.second.enumValues }.distinct().sorted()}; accepted values=${accepted.sorted()}.", writers)
                        continue
                    }
                }
                if (writeIds(tableName).isEmpty() && a?.type != "f" && b?.type != "f") continue
                unknown("CONSTRAINT_CHANGED", "$tableName.$name", "Constraint implication and accepted write inputs have not been proven equivalent.", "Baseline=$a; target=$b.", tableReferences)
            }
            for (name in (old.indexes.keys + new.indexes.keys).sorted()) {
                val a = old.indexes[name]
                val b = new.indexes[name]
                if (a == b || tableReferences.isEmpty()) continue
                val oldUnique = old.uniqueIndexes[name] ?: a?.startsWith("CREATE UNIQUE INDEX")
                val newUnique = new.uniqueIndexes[name] ?: b?.startsWith("CREATE UNIQUE INDEX")
                when {
                    b == null && oldUnique == true && referencesConflict(tableName).isEmpty() -> finding(Verdict.SAFE,
                        "UNIQUE_INDEX_REMOVED", "$tableName.$name", "Removing uniqueness expands accepted writes and no declared ON CONFLICT depends on it.", "Baseline=$a; target absent.", tableReferences)
                    b == null && oldUnique == false -> finding(Verdict.SAFE,
                        "NON_UNIQUE_INDEX_CHANGED", "$tableName.$name", "Removing a non-unique index preserves accepted writes and the declared result contract.", "Baseline=$a; target absent.", tableReferences)
                    newUnique == false && name in new.plainIndexes && (a == null || oldUnique == false) -> {
                        val indexWriters = contract.entities.filter { it.table == tableName && it.operations.any { op -> op in setOf(Operation.INSERT, Operation.UPDATE) } }.map { it.id } +
                            contract.queries.filter { queryTarget(it) == tableName && it.operation in setOf(Operation.INSERT, Operation.UPDATE) }.map { it.id }
                        if (indexWriters.isEmpty() || RuleProofs.boundedBtreeKey(new, name)) finding(Verdict.SAFE,
                            "NON_UNIQUE_INDEX_CHANGED", "$tableName.$name", "The non-unique index has no declared writers or a proven bounded built-in btree key domain.",
                            "Baseline=$a; target=$b; method=${new.indexMethods[name]}; columns=${new.indexColumns[name]}; supported PostgreSQL page size=8192 bytes.", tableReferences)
                        else unknown("INDEX_KEY_DOMAIN_UNPROVEN", "$tableName.$name", "Even a non-unique index may reject old writes when an index tuple exceeds PostgreSQL's size limit.",
                            "method=${new.indexMethods[name]}; columns=${new.indexColumns[name]}; the accepted key domain has no supported size bound.", indexWriters)
                    }
                    else -> unknown("INDEX_CHANGED", "$tableName.$name", "Index uniqueness, expressions, or conflict inference require additional verification.", "Baseline=$a; target=$b.", tableReferences)
                }
            }
        }
        for (name in (baseline.behaviors.keys + after.behaviors.keys).sorted()) {
            val a = baseline.behaviors[name]
            val b = after.behaviors[name]
            if (a != b) unknown("DATABASE_BEHAVIOR_CHANGED", name, "Database-side behavior is outside the supported equivalence model.", "Baseline=$a; target=$b.")
        }
        if (baseline.behaviors.isNotEmpty() && baseline != rawAfter) {
            // A transparent view preserves the supported structural contract, but arbitrary
            // preexisting code can still observe the real relation name or kind change.
            val changed = (baseline.tables.keys + rawAfter.tables.keys).filter { baseline.tables[it] != rawAfter.tables[it] }.sorted() +
                (baseline.sequences.keys + rawAfter.sequences.keys).filter { baseline.sequences[it] != rawAfter.sequences[it] }.sorted() +
                (baseline.enumTypes.keys + rawAfter.enumTypes.keys).filter { baseline.enumTypes[it] != rawAfter.enumTypes[it] }.sorted()
            if (changed.isNotEmpty()) unknown("DATABASE_BEHAVIOR_DEPENDENCY_UNPROVEN", "database",
                "New catalog changes can affect preexisting database-side behavior; accepting baseline health does not prove this interaction safe.",
                "Changed objects: ${changed.distinct().joinToString()}; unanalyzed behavior: ${baseline.behaviors.keys.sorted().joinToString()}")
        }
        for (name in (baseline.sequences.keys + after.sequences.keys).sorted()) {
            val a = baseline.sequences[name]
            val b = after.sequences[name]
            if (a == b) continue
            val owner = baseline.sequenceOwners[name] ?: after.sequenceOwners[name]
            val direct = contract.entities.filter { it.identifierSequence == name && Operation.INSERT in it.operations }.map { it.id }
            val ownerReferences = owner?.let { columnIds(it.table, it.column) }.orEmpty()
            val addedUnrelatedOwner = a == null && owner != null && owner.table !in baseline.tables && tableIds(owner.table).isEmpty()
            val removedUnrelatedOwner = b == null && owner != null && ownerReferences.isEmpty() &&
                after.tables[owner.table]?.columns?.containsKey(owner.column) != true
            if (direct.isEmpty() && (addedUnrelatedOwner || removedUnrelatedOwner)) continue
            val establishedDirect = direct.filter { it in validBaselineIds }
            if (b == null && establishedDirect.isNotEmpty() && a != null) finding(Verdict.BREAKING, "IDENTIFIER_SEQUENCE_DROPPED", name,
                "The identifier sequence required by old INSERT operations was removed.", "Baseline sequence=$a; target absent.", establishedDirect,
                "Retain the identifier sequence until old application versions are retired.")
            else unknown("SEQUENCE_CHANGED", name, "Identifier sequence behavior changed.", "Baseline=$a; target=$b.", direct + ownerReferences)
        }
        for (name in (baseline.enumTypes.keys + after.enumTypes.keys).sorted()) {
            val a = baseline.enumTypes[name]
            val b = after.enumTypes[name]
            if (a == b) continue
            val mappings = contract.entities.flatMap { entity -> entity.columns.filter { column ->
                val type = baseline.tables[entity.table]?.columns?.get(column.name)?.type
                type == name || type == name.removePrefix("public.")
            }.map { entity to it } }
            if (mappings.isEmpty()) {
                if (contract.queries.any { !it.dependenciesComplete || it.parameterTypes.any { t -> t == name || t == name.removePrefix("public.") } })
                    unknown("ENUM_TYPE_CHANGED", name, "The PostgreSQL enum changed without a complete mapped input domain.", "Baseline=$a; target=$b.")
                continue
            }
            val excluded = mappings.filter { (entity, column) -> writesColumn(entity, column) && column.inputDomainComplete }.flatMap { (entity, column) ->
                column.enumValues.filter { it in a.orEmpty() && it !in b.orEmpty() &&
                    baseline.tables[entity.table]?.let { table -> permitsEnumValue(table, column.name, it) } == true
                }.map { entity.id to it }
            }
            if (excluded.isNotEmpty()) finding(Verdict.BREAKING, "ENUM_VALUE_REJECTED", name,
                "The new PostgreSQL enum excludes a value written by the old mapping.", "Excluded values=${excluded.map { it.second }.distinct().sorted()}.", excluded.map { it.first }, "Preserve old mapped enum labels.")
            else unknown("ENUM_TYPE_CHANGED", name, "Enum ordering or new values may change old reads or unsupported writes.", "Baseline=$a; target=$b.", mappings.map { it.first.id })
        }
        for (name in (baseline.extensions.keys + after.extensions.keys).sorted()) {
            val a = baseline.extensions[name]
            val b = after.extensions[name]
            if (a != b) unknown("EXTENSION_CHANGED", name,
                "Installed PostgreSQL extension or version changed; its behavior requires separate verification.",
                "Baseline=$a; target=$b.")
        }
        if (findings.none { it.verdict != Verdict.SAFE } && findings.isEmpty()) {
            finding(Verdict.SAFE, "STRUCTURAL_CONTRACT_PRESERVED", "contract", "The supported structural dependencies are preserved.", "All declared baseline tables and columns remain compatible under the supported catalog rules.")
        }
        val stable = findings.map { if (it.verdict == Verdict.UNKNOWN && it.mitigation == null) it.copy(mitigation = unknownMitigation(it.ruleId)) else it }.distinct().sortedWith(compareBy({ severity(it.verdict) }, { it.ruleId }, { it.objectName }, { it.affectedContractIds.joinToString() }, { it.evidence }))
        return MigrationReport(migration, aggregate(stable.map { it.verdict }), stable)
    }

    /** Baseline evidence is independent of any pending migration and is emitted only once. */
    fun inspectBaseline(
        contract: DatabaseContract,
        snapshot: SchemaSnapshot,
        additionalFindings: List<Finding> = emptyList(),
        fingerprintContext: String = "",
    ): BaselineReport {
        val observations = additionalFindings.toMutableList()
        if (snapshot.behaviors.isNotEmpty()) observations += Finding(
            Verdict.UNKNOWN, "EXISTING_DATABASE_BEHAVIOR", "database",
            "Preexisting database-side behavior may respond differently to schema changes.",
            "Unanalyzed objects: ${snapshot.behaviors.keys.sorted().joinToString()}",
        )
        val findings = verifyStep(contract, snapshot, snapshot,
            MigrationInfo("baseline", "Baseline health"), observations).findings
            .filter { it.verdict != Verdict.SAFE }.map { it.copy(migration = null) }.distinct()
        return BaselineReport(
            verdict = aggregate(findings.map { it.verdict } + Verdict.SAFE),
            findings = findings,
            fingerprint = BaselineFingerprints.baseline(contract, snapshot, findings, fingerprintContext),
        )
    }

    fun report(
        contract: DatabaseContract,
        steps: List<MigrationReport>,
        baseline: BaselineReport? = null,
        acceptedBaseline: AcceptedBaseline? = null,
    ): CompatibilityReport {
        val baselineIdentities = baseline?.findings.orEmpty().map(BaselineFingerprints::identity).toSet()
        val invalidBaselineObjects = baseline?.findings.orEmpty()
            .filter { it.ruleId.startsWith("INVALID_BASELINE_") }.map { it.ruleId to it.objectName }.toSet()
        val deltas = if (baseline == null) steps else steps.map { step ->
            val findings = step.findings.filter {
                BaselineFingerprints.identity(it) !in baselineIdentities &&
                    (it.ruleId to it.objectName) !in invalidBaselineObjects
            }
            step.copy(verdict = aggregate(findings.map { it.verdict } + Verdict.SAFE), findings = findings)
        }
        val verdicts = deltas.map { it.verdict } + listOfNotNull(baseline?.verdict)
        val limitations = buildList {
            add("SAFE is limited to the declared structural contract; it does not prove application behavior or operational migration safety.")
            if (contract.queries.any { it.source == QuerySource.RUNTIME_OBSERVED || it.verificationStatus == VerificationStatus.OBSERVED }) {
                add("Runtime-observed SQL does not establish exhaustive query coverage.")
            }
            if (baseline?.verdict != null && baseline.verdict != Verdict.SAFE) {
                add("Migration findings exclude preexisting baseline problems; the overall verdict still includes baseline health.")
            }
        }
        val report = CompatibilityReport(
            applicationVersion = contract.applicationVersion,
            scope = contract.scope,
            verdict = aggregate(verdicts),
            migrations = deltas,
            limitations = limitations,
            baseline = baseline,
            ruleSummary = summarizeFindings(baseline, deltas).sortedWith(compareBy({ severity(it.verdict) }, { it.ruleId })),
            accessPaths = contract.accessPaths,
        )
        return if (acceptedBaseline == null) report else BaselineAcceptance.apply(report, acceptedBaseline)
    }

    companion object {
        private fun normalizeIdentifiers(contract: DatabaseContract): DatabaseContract {
            fun table(value: String): String = value.split('.').joinToString(".", transform = ::postgresIdentifier)
            fun column(value: ColumnReference) = value.copy(table = table(value.table), column = postgresIdentifier(value.column))
            return contract.copy(entities = contract.entities.map { entity -> entity.copy(
                table = table(entity.table), columns = entity.columns.map { it.copy(name = postgresIdentifier(it.name)) },
                identifierSequence = entity.identifierSequence?.let(::table),
            ) }, queries = contract.queries.map { query -> query.copy(
                tables = query.tables.map(::table), targetTable = query.targetTable?.let(::table),
                columns = query.columns.map(::column), writeColumns = query.writeColumns.map(::column),
            ) })
        }

        private fun severity(verdict: Verdict) = when (verdict) { Verdict.BREAKING -> 0; Verdict.UNKNOWN -> 1; Verdict.SAFE -> 2 }
        private fun unknownMitigation(rule: String): String = when {
            rule in setOf("NEW_REQUIRED_COLUMN_BEHAVIOR", "OMITTED_COLUMN_VALUE_UNPROVEN") ->
                "Establish the omitted value using PostgreSQL type/default and write-behavior facts, or provide an explicit default compatible with the old INSERTs and all domain constraints."
            rule == "RELATION_RENAME_EXPRESSION_UNPROVEN" -> "Preserve relation-name-dependent expression behavior or verify the stored default, CHECK, generated expression, and index behavior with old operations on every affected table."
            rule.contains("COVERAGE") || rule.contains("QUERY") -> "Supply complete SQL templates, parameter/result types, and exhaustive variants for the affected access paths."
            rule.contains("COLLATION") -> "Preserve the old column collation or verify equivalent comparison, ordering, and uniqueness behavior for the declared operations."
            rule.contains("TYPE") || rule.contains("NULLABILITY") -> "Declare the old input bounds and result mappings, then verify their inclusion in the target type and hydration domain."
            rule.contains("CONSTRAINT") || rule.contains("INDEX") -> "Provide accepted input constraints or a supported implication proof; replay a baseline-accepted counterexample to demonstrate breakage."
            rule.contains("BEHAVIOR") || rule.contains("RELATION") -> "Verify the affected view, trigger, function, or policy semantics explicitly; successful planning alone does not establish equivalence."
            rule.contains("DEFAULT") || rule.contains("GENERATION") || rule.contains("SEQUENCE") -> "Establish the generator/default dependency and prove that old inserts still produce valid identifiers and values."
            rule.startsWith("INVALID_BASELINE") -> "Supply a baseline that matches the old application contract and validate the affected operation there first."
            else -> "Supply the missing contract or database evidence identified above; unsupported equivalence remains UNKNOWN."
        }
        private fun queryTarget(query: QueryContract): String? = query.targetTable
            ?: query.writeColumns.map { it.table }.distinct().singleOrNull()
            ?: query.tables.singleOrNull()

        fun aggregate(verdicts: List<Verdict>): Verdict = when {
            Verdict.BREAKING in verdicts -> Verdict.BREAKING
            Verdict.UNKNOWN in verdicts || verdicts.isEmpty() -> Verdict.UNKNOWN
            else -> Verdict.SAFE
        }
    }
}
