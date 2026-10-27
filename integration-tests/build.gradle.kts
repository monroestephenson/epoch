plugins { kotlin("jvm") }
dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":hibernate"))
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.postgresql:postgresql:42.7.5")
}
