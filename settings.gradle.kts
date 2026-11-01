// Standalone build, used to develop and test the library on its own.
// The apps don't use this file: they include :limo-realtime from their own settings
// (git submodule + `project(":limo-realtime").projectDir = …`), so the module builds with
// each app's own Android Gradle plugin and Kotlin version.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "limo-realtime-android"
include(":limo-realtime")
