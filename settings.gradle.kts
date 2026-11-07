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

rootProject.name = "MesCourses"

// Logique métier en Kotlin/JVM pur (testable sans SDK Android) : ./gradlew -p core test
includeBuild("core")
include(":app")
