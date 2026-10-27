plugins { kotlin("jvm"); `java-library` }
dependencies {
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    implementation("com.github.jsqlparser:jsqlparser:5.1")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
