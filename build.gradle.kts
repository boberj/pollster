plugins {
    alias(libs.plugins.kotlin.jvm)
    // Composables run on the server, but they're still Compose code, so they need the Compose compiler.
    alias(libs.plugins.kotlin.compose)
    // The sign-in session is @Serializable, which is how Ktor stores sessions.
    alias(libs.plugins.kotlin.serialization)
    // KSP generates the database tables and the policy-checked accessors from the @Entity classes.
    alias(libs.plugins.ksp)
    // Adds dbDiff, dbMigrate, and dbVerify, and makes `check` fail if the entities and db/schema.json
    // disagree.
    id("jetlin.db")
    application
}

kotlin {
    jvmToolchain(24)
}

dependencies {
    implementation("jetlin:jetlin-server-ktor")
    implementation("jetlin:jetlin-server-ktor-auth")
    implementation("jetlin:jetlin-db")
    ksp("jetlin:jetlin-db-ksp")

    implementation(libs.ktor.server.netty)
    implementation(libs.slf4j.simple)

    testImplementation("jetlin:jetlin-testing")
    testImplementation("jetlin:jetlin-db-testing")
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("pollster.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Throws when a principal reads a record it never obtained through a policy check, which is how a
    // leaked reference shows up. Jetlin's own tests run with it on.
    systemProperty("jetlin.db.leakDetector", "true")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

jetlinDb {
    // The same file that main() opens by default, so `./gradlew dbMigrate` migrates the database the
    // app actually uses. Set POLLSTER_DATA to move both.
    database.set(layout.projectDirectory.file((System.getenv("POLLSTER_DATA") ?: "data") + "/pollster.db"))
}
