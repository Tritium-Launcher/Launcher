pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

rootProject.name = "Tritium Minecraft Launcher"

include(":api")
