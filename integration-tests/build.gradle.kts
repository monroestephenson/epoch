plugins { kotlin("jvm") }
dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":hibernate"))
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.postgresql:postgresql:42.7.5")
}
