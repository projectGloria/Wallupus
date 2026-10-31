// Only Google + Maven Central. No JitPack, no random GitHub-release artifacts:
// every dependency comes from a repo with immutable, signed releases.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Fail the build if any module tries to sneak in its own repository.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Wallupus"
include(":app")
