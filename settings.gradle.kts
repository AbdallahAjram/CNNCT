pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()  // Moved to end
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)  // More flexible
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CNNCT"
include(":app")