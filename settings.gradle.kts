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

rootProject.name = "krida"

include(":shared")

// CI without an Android SDK can run: ./gradlew :shared:jvmTest -PsharedOnly=true
if (providers.gradleProperty("sharedOnly").orNull != "true") {
    include(":androidApp")
}
