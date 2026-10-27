plugins { kotlin("jvm"); `java-library` }
dependencies {
    api(project(":core"))
    api("org.hibernate.orm:hibernate-core:6.6.13.Final")
    implementation("com.github.jsqlparser:jsqlparser:5.1")
    implementation("io.smallrye:jandex:3.2.0")
}
