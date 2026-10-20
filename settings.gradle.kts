
@file:Suppress("UnstableApiUsage")

// CLEANUP conflicting environment variables to allow building on this system
System.clearProperty("ANDROID_PREFS_ROOT")

rootProject.name = "ChatApp"
include(":app")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    // Allow module-level repositories
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}
