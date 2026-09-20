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
        // injekt-core (Tachiyomi/Mihon's DI lib used by source-api's HttpSource) is only
        // published here, not on Maven Central.
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "moku-mobile"
include(":app")
