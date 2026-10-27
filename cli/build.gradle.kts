plugins { kotlin("jvm"); application }
dependencies {
    implementation(project(":core"))
    implementation(project(":hibernate"))
    implementation("info.picocli:picocli:4.7.7")
}
application { mainClass.set("dev.epoch.cli.MainKt"); applicationName = "db-compat" }
