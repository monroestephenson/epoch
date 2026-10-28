plugins { kotlin("jvm"); `java-library` }
dependencies {
    api(project(":core"))
    api("org.hibernate.orm:hibernate-core:6.6.13.Final")
    implementation("com.github.jsqlparser:jsqlparser:5.1")
    implementation("io.smallrye:jandex:3.2.0")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.1.21")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.springframework.data:spring-data-jpa:3.4.5")
}

// A separate runtime verifies the isolated adapter without replacing the bundled Hibernate 6 dependency.
val hibernate7Runtime by configurations.creating
val hibernate70Runtime by configurations.creating
val hibernate749Runtime by configurations.creating
val applicationJacksonRuntime by configurations.creating
val panacheRuntime by configurations.creating { isTransitive = false }
// Compile application fixtures independently of main/test, whose Hibernate API is 6.6.
val hibernate7Fixtures by sourceSets.creating
// Actual independent application artifacts exercise normal multi-module runtime classpaths.
val hibernate7EntityFixtureJar by tasks.registering(Jar::class) {
    archiveClassifier.set("hibernate7-entity-fixture")
    from(hibernate7Fixtures.output) { include("dev/epoch/jarfixture/entity/**") }
}
val hibernate7RepositoryFixtureJar by tasks.registering(Jar::class) {
    archiveClassifier.set("hibernate7-repository-fixture")
    from(hibernate7Fixtures.output) { include("dev/epoch/jarfixture/repository/**") }
}
val hibernate7JarFixtures by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = true
}
artifacts {
    add(hibernate7JarFixtures.name, hibernate7EntityFixtureJar)
    add(hibernate7JarFixtures.name, hibernate7RepositoryFixtureJar)
}
dependencies {
    hibernate7Runtime("org.hibernate.orm:hibernate-core:7.1.4.Final")
    hibernate70Runtime("org.hibernate.orm:hibernate-core:7.0.10.Final")
    hibernate749Runtime("org.hibernate.orm:hibernate-core:7.4.9.Final")
    applicationJacksonRuntime("com.fasterxml.jackson.core:jackson-databind:2.21.7")
    applicationJacksonRuntime("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.21.7")
    panacheRuntime("io.quarkus:quarkus-panache-hibernate-common:3.15.4")
    panacheRuntime("io.quarkus:quarkus-panache-common:3.15.4")
    add(hibernate7Fixtures.implementationConfigurationName, "org.hibernate.orm:hibernate-core:7.4.9.Final")
}
tasks.test {
    dependsOn(hibernate7Fixtures.classesTaskName)
    systemProperty("epoch.hibernate7.classpath", hibernate7Runtime.asPath)
    systemProperty("epoch.hibernate70.classpath", hibernate70Runtime.asPath)
    systemProperty("epoch.hibernate749.classpath", hibernate749Runtime.asPath)
    systemProperty("epoch.application.jackson.classpath", applicationJacksonRuntime.asPath)
    systemProperty("epoch.panache.classpath", panacheRuntime.asPath)
    systemProperty("epoch.hibernate7.fixture.classpath", hibernate7Fixtures.output.classesDirs.asPath)
    systemProperty("epoch.hibernate7.fixture.compile.classpath", hibernate7Fixtures.compileClasspath.asPath)
}
