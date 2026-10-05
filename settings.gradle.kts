/*
 * Pollster depends on Jetlin as a local build, not a published artifact: Jetlin isn't on Maven yet.
 * Both repositories have to be checked out side by side, so that Jetlin is at ../jetlin.
 *
 * An included build compiles Jetlin from source as part of this build. A change made in ../jetlin is
 * picked up by the next `./gradlew run` here, with no publish step in between.
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Jetlin compiles for Java 24, so Pollster has to as well. This downloads a JDK 24 if the machine
    // doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        google {
            content { includeGroupByRegex("androidx\\..*") }
        }
    }
    // Use Jetlin's own version catalog, so Pollster's Kotlin, Compose, KSP, and Ktor versions always
    // match the ones Jetlin was compiled with. Mismatched Kotlin or Compose compiler versions between
    // a library and its user fail in confusing ways.
    versionCatalogs {
        create("libs") {
            from(files("../jetlin/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "pollster"

// The `jetlin.db` plugin (dbDiff, dbMigrate, dbVerify) is a build of its own inside Jetlin. Jetlin
// includes it the same way, so both builds share one copy of it.
includeBuild("../jetlin/jetlin-db-gradle")

includeBuild("../jetlin") {
    // Jetlin's projects don't declare Maven coordinates, so map the coordinates that build.gradle.kts
    // uses to the projects that provide them.
    dependencySubstitution {
        for (module in listOf(
            "jetlin-server-ktor",
            "jetlin-server-ktor-auth",
            "jetlin-db",
            "jetlin-db-ksp",
            "jetlin-testing",
            "jetlin-db-testing",
        )) {
            substitute(module("jetlin:$module")).using(project(":$module"))
        }
    }
}
