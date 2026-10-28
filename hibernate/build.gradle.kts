plugins { kotlin("jvm"); `java-library` }
dependencies {
    api(project(":core"))
    api("org.hibernate.orm:hibernate-core:6.6.13.Final")
    implementation("com.github.jsqlparser:jsqlparser:5.1")
    implementation("io.smallrye:jandex:3.2.0")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:2.1.21")
    testImplementation("org.springframework.data:spring-data-jpa:3.4.5")
}

// A separate runtime verifies the isolated adapter without replacing the bundled Hibernate 6 dependency.
val hibernate7Runtime by configurations.creating
val hibernate70Runtime by configurations.creating
val hibernate749Runtime by configurations.creating
val panacheRuntime by configurations.creating { isTransitive = false }
// Compile application fixtures independently of main/test, whose Hibernate API is 6.6.
val hibernate7Fixtures by sourceSets.creating
dependencies {
    hibernate7Runtime("org.hibernate.orm:hibernate-core:7.1.4.Final")
    hibernate70Runtime("org.hibernate.orm:hibernate-core:7.0.10.Final")
    hibernate749Runtime("org.hibernate.orm:hibernate-core:7.4.9.Final")
    panacheRuntime("io.quarkus:quarkus-panache-hibernate-common:3.15.4")
    panacheRuntime("io.quarkus:quarkus-panache-common:3.15.4")
    add(hibernate7Fixtures.implementationConfigurationName, "org.hibernate.orm:hibernate-core:7.4.9.Final")
}
tasks.test {
    dependsOn(hibernate7Fixtures.classesTaskName)
    systemProperty("epoch.hibernate7.classpath", hibernate7Runtime.asPath)
    systemProperty("epoch.hibernate70.classpath", hibernate70Runtime.asPath)
    systemProperty("epoch.hibernate749.classpath", hibernate749Runtime.asPath)
    systemProperty("epoch.panache.classpath", panacheRuntime.asPath)
    systemProperty("epoch.hibernate7.fixture.classpath", hibernate7Fixtures.output.classesDirs.asPath)
    systemProperty("epoch.hibernate7.fixture.compile.classpath", hibernate7Fixtures.compileClasspath.asPath)
}
