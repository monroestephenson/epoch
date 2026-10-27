plugins { kotlin("jvm") }
dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":hibernate"))
    testImplementation(project(":cli"))
    testImplementation("info.picocli:picocli:4.7.7")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.5")
    // Compile a real Panache repository without pulling in a second application runtime.
    testImplementation("io.quarkus:quarkus-hibernate-orm-panache:3.15.4") { isTransitive = false }
    testImplementation("io.quarkus:quarkus-panache-hibernate-common:3.15.4") { isTransitive = false }
    testImplementation("io.quarkus:quarkus-panache-common:3.15.4") { isTransitive = false }
}
