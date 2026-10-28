# Opt-in public release validation: Keycloak 26.8.1

This Linux recipe requires Java 21, Python 3, network access to Maven Central on the first run, and an Epoch checkout. It extracts the published application's declared JPA module scope using its own Hibernate 7.4.9 runtime. It does not start Keycloak, connect to an application database, or verify production Liquibase migrations. Downloaded artifacts and generated files stay outside the checkout.

From the Epoch repository:

```bash
set -eu
epoch_checkout="$PWD"
epoch_validation_dir="$(mktemp -d)"
export epoch_validation_dir
./gradlew :cli:installDist
printf '%s\n' 'rootProject.name = "epoch-keycloak-public-validation"' > "$epoch_validation_dir/settings.gradle.kts"
cat > "$epoch_validation_dir/build.gradle.kts" <<'GRADLE'
import java.io.File
import java.security.MessageDigest

plugins { java }
repositories { mavenCentral() }
val applicationRuntime = configurations.getByName("runtimeClasspath")
dependencies {
    implementation(enforcedPlatform("org.keycloak:keycloak-parent:26.8.1"))
    runtimeOnly("org.keycloak:keycloak-model-jpa:26.8.1")
    // Declared provided by services; actually supplied by the published Quarkus server.
    runtimeOnly("io.quarkus.resteasy.reactive:resteasy-reactive")
    runtimeOnly("io.quarkus.resteasy.reactive:resteasy-reactive-common")
}
tasks.register("writeValidationClasspath") {
    doLast {
        val artifacts = applicationRuntime.resolvedConfiguration.resolvedArtifacts.sortedWith(
            compareBy({ it.moduleVersion.id.group }, { it.name }, { it.moduleVersion.id.version }))
        val hibernate = artifacts.filter { it.moduleVersion.id.group == "org.hibernate.orm" && it.name == "hibernate-core" }
        check(hibernate.size == 1 && hibernate.single().moduleVersion.id.version == "7.4.9.Final")
        layout.projectDirectory.file("resolved-classpath.txt").asFile.writeText(
            artifacts.joinToString(File.pathSeparator) { it.file.absolutePath } + "\n")
        layout.projectDirectory.file("resolved-artifacts.tsv").asFile.writeText(artifacts.joinToString("\n") {
            val id = it.moduleVersion.id
            val digest = MessageDigest.getInstance("SHA-256").digest(it.file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
            "${id.group}:${it.name}:${id.version}\t${it.file.name}\t$digest\t${it.file.absolutePath}"
        } + "\n")
    }
}
GRADLE
"$epoch_checkout/gradlew" -p "$epoch_validation_dir" --no-daemon writeValidationClasspath
```

Generate the entity inventory from the exact official source JAR, retaining every source-declared annotated entity. This inventory is explicit and version-pinned; source annotations are used only to select the inputs. Hibernate's actual runtime still supplies mapping and generated-SQL facts.

```bash
python3 - <<'PY'
import hashlib
import json
import os
import re
import urllib.request
import zipfile
from pathlib import Path

root = Path(os.environ["epoch_validation_dir"])
source = root / "keycloak-model-jpa-26.8.1-sources.jar"
url = "https://repo.maven.apache.org/maven2/org/keycloak/keycloak-model-jpa/26.8.1/" + source.name
source.write_bytes(urllib.request.urlopen(url, timeout=60).read())
expected = "6fa2a29edbd0cb86d4b05f1f8298569e11664e18c1184e590d1d032ea762ea9a"
assert hashlib.sha256(source.read_bytes()).hexdigest() == expected
with zipfile.ZipFile(source) as archive:
    entities = sorted(name.removesuffix(".java").replace("/", ".")
        for name in archive.namelist()
        if name.endswith(".java") and re.search(r"(?m)^\s*@Entity(?:\s*\(|\s*$)", archive.read(name).decode()))
assert len(entities) == len(set(entities)) == 75
request = {
    "applicationVersion": "Keycloak 26.8.1 released model-jpa module (explicit annotated entity scope)",
    "entityClasses": entities,
    "classpath": (root / "resolved-classpath.txt").read_text().strip().split(os.pathsep),
    "repositoryPackages": ["org.keycloak"],
    "discoverRepositories": True,
    "keepGoing": True,
    "hibernateProperties": {
        "hibernate.jdbc.batch_size": "32",
        "hibernate.jdbc.fetch_size": "64",
        "hibernate.default_batch_fetch_size": "8",
        "hibernate.query.in_clause_parameter_padding": "true",
        "hibernate.order_inserts": "true",
        "hibernate.query.startup_check": "false",
    },
    "unresolvedDynamicQueries": {
        "public-validation/runtime-classpath": "Published model-jpa transitive runtime dependencies plus official server RESTEasy modules; not the complete augmented Quarkus runtime or deployment classpath. No entities or packages are pruned.",
        "public-validation/quarkus-augmentation": "Standalone Hibernate extraction does not reproduce Quarkus augmentation, feature-dependent entities, external providers or every runtime query variant.",
        "public-validation/liquibase": "Keycloak uses Liquibase; this extraction does not verify its production migrations or data.",
    },
}
(root / "keycloak-extraction.json").write_text(json.dumps(request, indent=2) + "\n")
print("Descriptor retains all", len(entities), "declared entities; classpath JARs:", len(request["classpath"]))
PY

timeout 240s env JAVA_OPTS=-Xmx768m "$epoch_checkout/cli/build/install/db-compat/bin/db-compat" extract \
  --descriptor "$epoch_validation_dir/keycloak-extraction.json" \
  --output "$epoch_validation_dir/contract-1.json" --keep-going --verbose \
  > "$epoch_validation_dir/extract-1.stdout" 2> "$epoch_validation_dir/extract-1.stderr"
timeout 240s env JAVA_OPTS=-Xmx768m "$epoch_checkout/cli/build/install/db-compat/bin/db-compat" extract \
  --descriptor "$epoch_validation_dir/keycloak-extraction.json" \
  --output "$epoch_validation_dir/contract-2.json" --keep-going --verbose \
  > "$epoch_validation_dir/extract-2.stdout" 2> "$epoch_validation_dir/extract-2.stderr"
```

Inspect the complete evidence and compare identical local inputs. A successful extraction exit does not mean compatibility is SAFE or coverage complete.

```bash
python3 - <<'PY'
import collections
import hashlib
import json
import os
from pathlib import Path

root = Path(os.environ["epoch_validation_dir"])
request = json.loads((root / "keycloak-extraction.json").read_text())
first = (root / "contract-1.json").read_bytes()
second = (root / "contract-2.json").read_bytes()
contract = json.loads(first)
assert set(request["entityClasses"]) <= {entity["id"] for entity in contract["entities"]}
assert first == second, "Repeated extraction changed despite identical pinned inputs"
assert contract["coverage"]["complete"] is False
assert not any("ClassCastException" in item["reason"] or "ServiceConfigurationError" in item["reason"]
               for item in contract["unresolvedQueries"])
print("Mapping entries:", len(contract["entities"]), "SQL templates:", len(contract["queries"]))
print("Access-path statuses:", dict(collections.Counter(path["status"] for path in contract["accessPaths"])))
print("Unresolved diagnostic records:", len(contract["unresolvedQueries"]))
print("Contract SHA-256:", hashlib.sha256(first).hexdigest())
print("Preserved evidence:", root)
PY
```

The recipe's descriptive scope-note wording and absolute paths can differ from the recorded run, so compare the two local outputs with each other. Do not require their full-file checksum to match a different checkout's contract. Preserve the exact descriptor, resolved coordinate/checksum list, logs and both JSON contracts. Do not discard unresolved entities or queries to improve the counts.
