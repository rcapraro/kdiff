pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kdiff"

include(
    "kdiff-annotations",
    "kdiff-runtime",
    "kdiff-processor",
    "kdiff-sample",
    "kdiff-tutorial",
    "kdiff-benchmarks",
    "kdiff-integration",
)
