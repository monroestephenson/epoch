plugins {
    kotlin("jvm") version "2.1.21" apply false
}
allprojects {
    group = "dev.epoch"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); maxParallelForks = 1; testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = false } }
}
