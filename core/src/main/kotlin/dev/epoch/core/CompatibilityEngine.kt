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
        val findings = additionalFindings.map { it.copy(migration = migration) }.toMutableList()
        fun finding(verdict: Verdict, rule: String, objectName: String, message: String,
                    evidence: String, ids: List<String> = emptyList(), mitigation: String? = null) {
            findings += Finding(verdict, rule, objectName, message, evidence, ids.distinct().sorted(), migration, mitigation)
        }
        fun unknown(rule: String, name: String, message: String, evidence: String, ids: List<String> = emptyList()) =
            finding(Verdict.UNKNOWN, rule, name, message, evidence, ids)

        if (contract.schemaVersion != 1) unknown("UNSUPPORTED_CONTRACT_VERSION", "contract", "Unsupported contract schema version.", "schemaVersion=${contract.schemaVersion}; supported=1")
        if (contract.dialect != "PostgreSQL") unknown("UNSUPPORTED_DIALECT", "contract", "Unsupported database dialect.", "dialect=${contract.dialect}; supported=PostgreSQL")
        if (contract.scope != VerificationScope.STRUCTURAL_CONTRACT) unknown("WHOLE_APPLICATION_UNPROVEN", "contract", "The MVP cannot prove whole-application compatibility.", "Only STRUCTURAL_CONTRACT is supported.")
        if (!contract.coverage.complete) unknown("INCOMPLETE_COVERAGE", "contract", "Extraction is incomplete for the declared contract.", contract.coverage.notes.joinToString("; ").ifBlank { "coverage.complete=false" })
        if (contract.entities.isEmpty() && contract.queries.isEmpty()) unknown("EMPTY_CONTRACT", "contract", "No database operations were supplied.", "An empty contract cannot establish compatibility.")
        val allIds = contract.entities.map { it.id } + contract.queries.map { it.id }
        if (allIds.distinct().size != allIds.size) unknown("DUPLICATE_CONTRACT_ID", "contract", "Contract identifiers are ambiguous.", "Entity and query IDs must be unique.")
        contract.unresolvedQueries.forEach { unknown("UNRESOLVED_QUERY", it.id, "A database-access path remains unresolved.", it.reason, listOf(it.id)) }
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
            if (entity.columns.isEmpty() || entity.operations.isEmpty()) unknown("INCOMPLETE_MAPPING", entity.table, "Mapping has no complete operation dependency set.", "Mapping ${entity.id} has ${entity.columns.size} columns and ${entity.operations.size} operations.", listOf(entity.id))
            val oldTable = baseline.tables[entity.table]
            if (oldTable == null) {
                unknown("INVALID_BASELINE_TABLE", entity.table, "A mapped table is absent from the baseline.", "The old operation is not established as valid against the baseline.", listOf(entity.id))
            } else {
                entity.columns.forEach { column ->
                    if (column.name !in oldTable.columns) unknown("INVALID_BASELINE_COLUMN", "${entity.table}.${column.name}", "A mapped column is absent from the baseline.", "The old operation is not established as valid against the baseline.", listOf(entity.id))
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
        val rejectedBaselineIds = additionalFindings.filter { it.ruleId == "BASELINE_SQL_UNVERIFIED" }.flatMap { it.affectedContractIds }.toSet() +
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

        for (tableName in (baseline.tables.keys + after.tables.keys).sorted()) {
            val old = baseline.tables[tableName]
            val new = after.tables[tableName]
            val tableReferences = tableIds(tableName)
            if (old == null) {
                if (tableReferences.isEmpty()) finding(Verdict.SAFE, "UNREFERENCED_TABLE_ADDED", tableName, "Added table is outside the declared structural dependencies.", "Table absent at baseline and present after migration.")
                if (new != null && tableReferences.isNotEmpty()) unknown("INVALID_BASELINE_TABLE", tableName, "Referenced table did not exist in the baseline.", "No valid baseline operation has been established.", tableReferences)
                new?.constraints?.filterValues { it.type == "f" }?.forEach { (name, constraint) ->
                    unknown("NEW_REFERENTIAL_CONSTRAINT", "$tableName.$name", "A new table's foreign key can restrict writes to existing referenced tables.", constraint.definition, allWriteIds)
                }
                continue
            }
            if (new == null) {
                if (tableReferences.any { it in validBaselineIds }) finding(Verdict.BREAKING, "REFERENCED_TABLE_DROPPED", tableName, "A table required by the old application no longer exists.", "Baseline table existed; target table is absent. A rename is a drop of the old name for old SQL.", tableReferences.filter { it in validBaselineIds }, "Keep the old table name available until all old application versions are retired.")
                else if (tableReferences.isEmpty()) finding(Verdict.SAFE, "UNREFERENCED_TABLE_DROPPED", tableName, "Dropped table is outside the declared structural dependencies.", "No declared entity or complete query references this table.")
                old.constraints.filterValues { it.type == "f" }.forEach { (name, constraint) ->
                    unknown("REMOVED_REFERENTIAL_CONSTRAINT", "$tableName.$name", "Removing a foreign key can alter the behavior of referenced-table writes.", constraint.definition, allWriteIds)
                }
                continue
            }
            for (columnName in (old.columns.keys + new.columns.keys).sorted()) {
                val a = old.columns[columnName]
                val b = new.columns[columnName]
                val objectName = "$tableName.$columnName"
                val columnReferences = columnIds(tableName, columnName)
                if (a == null && b != null) {
                    val inserts = insertIds(tableName).filter { it in validBaselineIds }
                    if (!b.nullable && b.defaultExpression == null && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && inserts.isNotEmpty()) {
                        finding(Verdict.BREAKING, "REQUIRED_COLUMN_OMITTED", objectName, "Old INSERT operations omit a new required column.", "New column is NOT NULL without a default, identity, or generated expression; old explicit INSERT column lists omit it.", inserts, "Introduce the column as nullable or provide a compatible default while old applications run.")
                    }
                    val shapeQueries = contract.queries.filter { tableName in it.tables && (!it.explicitColumnList || it.exactResultShape) }
                    shapeQueries.forEach { query ->
                        unknown("POSITIONAL_SHAPE_CHANGED", objectName, "A positional operation or result shape may change when a column is added.", query.sql, listOf(query.id))
                    }
                    if ((b.defaultExpression != null || !b.identity.isNullOrEmpty() || !b.generated.isNullOrEmpty()) && writeIds(tableName).isNotEmpty()) {
                        unknown("NEW_COLUMN_EXPRESSION", objectName, "A new default or generated expression can change write behavior.", "default=${b.defaultExpression}; identity=${b.identity}; generated=${b.generated}", writeIds(tableName))
                    }
                    if (b.nullable && b.defaultExpression == null && b.identity.isNullOrEmpty() && b.generated.isNullOrEmpty() && shapeQueries.isEmpty() && columnReferences.isEmpty()) {
                        finding(Verdict.SAFE, "NULLABLE_COLUMN_ADDED", objectName, "Nullable column addition preserves explicit-column operations.", "New column accepts NULL, has no default/generation behavior, and is outside the old column dependencies.", tableReferences)
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
                    if (a.type != b.type && (columnReferences.isNotEmpty() || writeIds(tableName).isNotEmpty())) unknown("COLUMN_TYPE_CHANGED", objectName, "Parameter binding and result hydration compatibility are not established for a changed type.", "Baseline type=${a.type}; target type=${b.type}.", columnReferences + writeIds(tableName))
                    if (a.nullable != b.nullable && (columnReferences.isNotEmpty() || writeIds(tableName).isNotEmpty())) unknown("NULLABILITY_CHANGED", objectName, "Accepted inputs or entity hydration may change with nullability.", "Baseline nullable=${a.nullable}; target nullable=${b.nullable}.", columnReferences + writeIds(tableName))
                    if (a.defaultExpression != b.defaultExpression && tableReferences.isNotEmpty()) unknown("DEFAULT_CHANGED", objectName, "Default changes may alter accepted writes or generated values.", "Baseline default=${a.defaultExpression}; target default=${b.defaultExpression}.", tableReferences)
                    if (a.identity != b.identity || a.generated != b.generated) unknown("GENERATION_CHANGED", objectName, "Generated values or identifier behavior changed.", "Baseline identity=${a.identity}, generated=${a.generated}; target identity=${b.identity}, generated=${b.generated}.", tableReferences)
                    if (a.ordinal != b.ordinal && contract.queries.any { tableName in it.tables && (!it.explicitColumnList || it.exactResultShape) }) unknown("COLUMN_ORDER_CHANGED", objectName, "Positional column order changed.", "Baseline ordinal=${a.ordinal}; target ordinal=${b.ordinal}.", tableReferences)
                }
            }
            for (name in (old.constraints.keys + new.constraints.keys).sorted()) {
                val a = old.constraints[name]
                val b = new.constraints[name]
                if (a != b) unknown("CONSTRAINT_CHANGED", "$tableName.$name", "Constraint implication and accepted write inputs have not been proven equivalent.", "Baseline=$a; target=$b.", tableReferences)
            }
            for (name in (old.indexes.keys + new.indexes.keys).sorted()) {
                val a = old.indexes[name]
                val b = new.indexes[name]
                if (a != b) unknown("INDEX_CHANGED", "$tableName.$name", "Index semantics, including uniqueness and expressions, require additional verification.", "Baseline=$a; target=$b.", tableReferences)
            }
        }
        for (name in (baseline.behaviors.keys + after.behaviors.keys).sorted()) {
            val a = baseline.behaviors[name]
            val b = after.behaviors[name]
            if (a != b) unknown("DATABASE_BEHAVIOR_CHANGED", name, "Database-side behavior is outside the supported equivalence model.", "Baseline=$a; target=$b.")
        }
        if (baseline.behaviors.isNotEmpty() && baseline != after) {
            unknown("EXISTING_DATABASE_BEHAVIOR", "database", "Preexisting database-side behavior may respond differently to schema changes.", "Unanalyzed objects: ${baseline.behaviors.keys.sorted().joinToString()}")
        }
        for (name in (baseline.sequences.keys + after.sequences.keys).sorted()) {
            val a = baseline.sequences[name]
            val b = after.sequences[name]
            if (a != b) unknown("SEQUENCE_CHANGED", name, "Identifier sequence behavior changed.", "Baseline=$a; target=$b.")
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
        val stable = findings.distinct().sortedWith(compareBy({ it.ruleId }, { it.objectName }, { it.affectedContractIds.joinToString() }, { it.evidence }))
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
            ruleSummary = summarizeFindings(baseline, deltas),
        )
        return if (acceptedBaseline == null) report else BaselineAcceptance.apply(report, acceptedBaseline)
    }

    companion object {
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
