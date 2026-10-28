plugins { kotlin("jvm") }
val hibernate7JarFixtures by configurations.creating { isCanBeConsumed = false }
val hibernate749Runtime by configurations.creating { isCanBeConsumed = false }
val applicationAsmCore by configurations.creating { isCanBeConsumed = false; isTransitive = false }
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
    hibernate7JarFixtures(project(path = ":hibernate", configuration = "hibernate7JarFixtures"))
    hibernate749Runtime("org.hibernate.orm:hibernate-core:7.4.9.Final")
    // Deliberately omit asm-tree/analysis: common app classpaths supply only ASM core.
    applicationAsmCore("org.ow2.asm:asm:9.7.1")
}

tasks.test {
    dependsOn(hibernate7JarFixtures)
    systemProperty("epoch.cli.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("epoch.hibernate7.jar.fixtures", hibernate7JarFixtures.asPath)
    systemProperty("epoch.hibernate749.classpath", hibernate749Runtime.asPath)
    systemProperty("epoch.application.asm.core", applicationAsmCore.asPath)
}
