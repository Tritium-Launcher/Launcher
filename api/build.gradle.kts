/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    `maven-publish`
}


group = "io.github.tritium_launcher"
version = rootProject.version

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.logback.classic)
    api(libs.koin)
    compileOnly(libs.qtjambi) // Native runtime is not required
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.github.tritium_launcher"
            artifactId = "api"
            version = project.version.toString()
            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Tritium-Launcher/Launcher")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
