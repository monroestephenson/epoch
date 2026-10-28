# Compiled repository API inventory

Repository discovery records database API invocations separately from extracted SQL templates. The inventory is restricted to the supplied compiled roots, selected `repositoryPackages`, and the scanner's known API families. It does not prove that reflective calls, generated runtime code, unscanned modules, or an unknown framework perform no database access.

An ordinary call-site ID uses the ordinal among all JVM method-call instructions in its method, including non-database helper calls. Adding support for another API therefore does not renumber later calls. This replaces the older recognized-calls-only numbering; regenerated contracts may have different IDs and accepted-baseline fingerprints. Compiler or source changes that change the instruction sequence can still change IDs.

## What the counts mean

The contract's optional `apiInventory` contains:

- Inspected method-call instructions and known database API invocations, including bindings, modifiers and lifecycle helpers.
- Query-capable invocation paths and their links to the existing access-path inventory.
- Repository/annotation declaration IDs, separate from compiled invocations.
- Direct database API method-reference IDs, retained as unresolved potential access paths with call sites. They are neither ordinary executed method calls nor declarations.
- Unreadable classes/archives, failed method analyses and incomplete owner hierarchies, retaining affected source or call-site IDs.
- Whether the declared known-API scope could be inventoried completely.

One factory followed by one execution is two call sites linked to one SQL template. A runtime stack observation is neither a compiled call nor a declaration. Unreadable classes and failed method analyses have unknown database-call extent; they cannot be counted as zero calls or complete coverage. Old contracts without `apiInventory` retain the legacy discovered-method report.

Human reports show raw roles, query-capable coverage, declarations and inventory gaps separately. `--details` includes located gaps and individual paths. Multi-contract reports retain persistence-unit prefixes on call sites and class groups.

## Inherited repository methods and query origins

The scanner reads effective ancestor headers from supplied dependencies even when their packages are not selected for method-body scanning. It uses the defining artifact, including multi-release JAR selection, and the same shared-class policy as the isolated Hibernate runtime.

Generic parameters are substituted along the actual repository ancestry to the designated entity argument of PanacheRepository/PanacheRepositoryBase or Spring Repository. An unrelated first type argument is not assumed to be the entity. Missing, erased, wildcard, conflicting or bounded-out ancestry remains unresolved. Custom application overrides are not expanded as if they were framework defaults.

Fixed Panache `find(...).list()` and `find(...).stream()` calls link to the factory's SQL contract IDs. The same identity survives local variables, casts and bounded finite control-flow merges. Equal SQL text from separate factories does not make them the same origin. A terminal with no established producer is unresolved rather than described as lacking a constant String argument.

Projection, page/range, sorting, filters, locks, unknown binding cardinalities and escaping query objects remain explicit unsupported variants. Their effects attach to the affected origin rather than every query in the method. Initial origin tracking conservatively treats a later mutation as affecting earlier executions of that origin too; it does not infer an exact temporal alias model. Panache count/first/single terminals remain separate unresolved SQL/result variants.

Fixed nonnegative `setFirstResult` and positive `setMaxResults` values on a locally created HQL query are passed to the application's real Hibernate query. The SQL AST translator supplies limit/offset SQL and JDBC types; Epoch does not append SQL text. Straight-line methods with local query execution enumerate each observed pagination state, including a base execution followed by a limited execution. Automatic discovery retains unresolved entries for branch-dependent or dynamic limits, native/named-query call pagination, query escapes, collection fetches and other query-shape modifiers. `maxResults=0` remains unresolved because Hibernate can return an empty result without executing SQL. Unsupported terminal variants such as Panache `find(...).count()` also retain their factory as unresolved instead of inventing an executed base SELECT. Explicit descriptor `hqlQueryPagination` declarations are a separate fixed-HQL input, not proof of automatic discovery coverage. This is a bounded literal-pagination model, not a claim to cover every pagination API.

Direct method references such as `entityManager::createQuery` store a target method in a LambdaMetafactory bootstrap handle. Their eventual arguments and invocations are unresolved. These paths are reported individually and make the known-API extent incomplete; their count is displayed separately rather than inflating ordinary method-call coverage. Selected compiler-generated lambda bodies still undergo ordinary bytecode scanning.

Receiver alternatives retain visible application overrides through NULL branches, casts and the bounded type inventory. A proven NULL receiver contributes no successful invocation target; an always-NULL invocation stays unresolved. Unknown non-NULL targets and overflow remain uncertain instead of reverting to framework dispatch.

Query-origin identity and origin-set completeness are separate facts. When a locally created query merges with an external or otherwise unestablished query, the known factory remains tracked: modifiers, argument escapes and returns still mark it unresolved. The merged execution remains unresolved even if the known branch has translated SQL. NULL contributes no possible successful producer. At most 16 factory origins are retained per bytecode value; exceeding this bound marks every factory in that method unresolved, including later factories, so discarded aliases cannot hide effects. Other methods remain independent.
