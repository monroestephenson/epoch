# Persistence-unit descriptors

`db-compat descriptors` reads a supported subset of framework configuration and writes one ordinary extraction descriptor per persistence unit. It does not start the application or connect to its database. Use the compiled application's classes and runtime dependencies, including its Hibernate version, as repeated `--classpath` entries.

```bash
db-compat descriptors \
  --quarkus-properties ./application.properties \
  --profile prod \
  --classpath ./app/build/classes/kotlin/main \
  --classpath ./dependencies/hibernate-core-7.4.9.Final.jar \
  --classpath ./dependencies/other-required-runtime.jar \
  --repository-package com.example.repositories \
  --application-version 1.4.0 \
  --output-directory ./generated-descriptors
```

The classpath entries are examples; supply the application's complete runtime classpath. Relative input paths resolve from the invocation directory. Generated descriptor classpaths are absolute. Missing compiled roots are skipped with a diagnostic; at least one usable root is required. Existing output directories are refused. All descriptors and the manifest are staged together before the new output directory is published.

`manifest.json` lists each unit's name, relative descriptor filename, configuration completeness, and unresolved-setting count. Descriptor filenames are deterministic, including a safe ordinal, name slug, and hash. Pass those files to the existing `extract --descriptor FILE --output CONTRACT` command. Generation does not extract a contract or change normal repository discovery.

For persistence units that share a physical database, pass every generated contract to one `verify` invocation using repeated `--contract`. Epoch applies the baseline once and retains unit namespaces in findings. Configuration cannot establish database topology: verify units backed by different databases separately. It also cannot establish which repository belongs to which unit; `--repository-package` applies the supplied package selectors to every generated descriptor, and the resulting extraction coverage must be reviewed.

## Quarkus properties

Supported input is UTF-8 Java properties, including continuations and escapes. Epoch reads explicit `quarkus.hibernate-orm.packages` and `quarkus.hibernate-orm."unit".packages`. Both default and named units are supported, including names containing dots when quoted. A global `quarkus.hibernate-orm.enabled=true` does not create a default unit. A false value stops generation with an explicit disabled-ORM message.

The mapping subset includes:

- Physical and implicit naming strategies.
- Default schema and catalog.
- JDBC statement batch/fetch sizes, fetch batch size, and IN-list parameter padding.
- Allowlisted Hibernate type preferences, timezone settings, ordering, and query startup checks supplied through `unsupported-properties`.

Default-unit mapping settings are not copied into named units. Each selected unit requires an explicit package inventory. Unknown mapping or bootstrap settings are recorded individually in the descriptor's unresolved sources. Datasource URLs, credentials, schema-generation controls, and connection settings are omitted without evaluating their values.

Use `--profile NAME` when ORM configuration has profile variants. Exactly that profile overlays the base properties. Other profiles do not contribute units. Profile groups, parent-profile inheritance, external configuration locations, and arbitrary configuration-source discovery are unsupported. Use repeated `--unit NAME` to generate only selected declared units; the default unit's identifier is `default`.

Simple `${key}` and `${key:default}` substitutions use only this input file and explicit `--define KEY=VALUE` substitutions. No environment or system properties are read. Definitions replace referenced values, not literal configuration settings. Cyclic, nested, secret-handler, sensitive-key, or excessively expanded expressions fail with a located diagnostic rather than importing a guessed scope. Expansion depth and intermediate/final values are bounded. Do not put secrets in `--define`; connection data is unnecessary for extraction.

## Standard persistence XML

```bash
db-compat descriptors \
  --persistence-xml ./META-INF/persistence.xml \
  --classpath ./app/build/classes/kotlin/main \
  --classpath ./dependencies/application-runtime.jar \
  --output-directory ./generated-descriptors
```

Epoch accepts standard Jakarta persistence 3.0–3.2 and JPA 1.0–2.2 namespaces with explicit annotated `<class>` entries and Hibernate's provider (or no explicit provider). Set `<exclude-unlisted-classes>true</exclude-unlisted-classes>` when those entries are the intended complete inventory. Each unit retains its own supported naming and mapping properties. Connection settings, JTA/non-JTA datasource names, validation mode, and schema-generation settings are not copied.

Implicit archive scanning, `<mapping-file>`, `<jar-file>`, and unknown provider properties remain explicit unresolved boundaries. A default `META-INF/orm.xml` resource in any supplied directory or archive also marks configuration incomplete, because annotated extraction does not apply that XML. DTDs, external entities, and XInclude are rejected, and no external XML resource is fetched.

This supports a Spring application that supplies standard `persistence.xml`. Programmatic Spring entity-manager factories, Spring package registration in Java/Kotlin configuration, Quarkus build-time augmentation, package-level persistence-unit annotations, custom configuration sources, and ORM XML mappings are not reconstructed.

## Meaning of completeness

`configurationComplete` describes this configuration parser's supported mapping subset. It is not an assertion of whole-application database-access coverage. An incomplete generated descriptor carries located unresolved sources into ordinary extraction and verification, where they cannot silently produce a whole-application SAFE verdict. Inspect the extracted entities, SQL, access paths, and unresolved sources as for a handwritten descriptor.
