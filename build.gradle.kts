
import com.github.jengelman.gradle.plugins.shadow.transformers.XmlAppendingTransformer
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktreesitter)
    idea
    id("com.gradleup.shadow") version "9.2.0"
}

ksp { arg("verbose", "true") }

val grammarDir = layout.projectDirectory.dir("tree-sitter-javascript").asFile

grammar {
    grammarName.set("javascript")
    baseDir.set(grammarDir)
    className.set("TreeSitterJavascript")
    packageName.set("io.github.tritium_launcher.launcher.ui.project.editor.treesitter.grammar")
}

group = "io.github.tritium_launcher.launcher"
version = "0.1.7d"
val tritiumVersion = project.version.toString()

val os: OperatingSystem = OperatingSystem.current()
val arch: String = System.getProperty("os.arch").lowercase()
val isArm64: Boolean = arch.contains("aarch64") || arch.contains("arm64")
val qtOs = when {
    os.isWindows -> if (isArm64) "windows-arm64" else "windows-x64"
    os.isMacOsX -> "macos"
    os.isLinux -> if (isArm64) "linux-arm64" else "linux-x64"
    else -> "unknown"
}

dependencyLocking { lockAllConfigurations() }

repositories {
    mavenLocal()
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy {
        // lsp4j declares gson as a version range; pin it for deterministic resolution.
        force("com.google.code.gson:gson:2.13.2")
        eachDependency {
            if (requested.group == "com.google.code.gson" && requested.name == "gson") {
                useVersion("2.13.2")
                because("version range metadata can resolve nondeterministically")
            }
        }
    }
}

dependencies {
    // Extension API
    implementation(project(":api"))

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.hocon)
    implementation(libs.kotlinx.serialization.properties)
    implementation(libs.kotlin.json5)
    implementation(libs.ktoml.core)
    implementation(libs.ktoml.file)
    implementation(libs.yamlkt)
    implementation(libs.knbt)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // KSP
    ksp(libs.autoservice.ksp)
    implementation(libs.autoservice.annotations)
    compileOnly(libs.koin)
    implementation(libs.koin.slf4j)
    implementation(libs.koin.annotations)

    // QtJambi
    implementation(libs.qtjambi)
    implementation(libs.qtjambi.svg)
    if(qtOs != "unknown") {
        runtimeOnly("io.qtjambi:qtjambi-native-$qtOs:${libs.versions.qt.get()}")
        runtimeOnly("io.qtjambi:qtjambi-svg-native-$qtOs:${libs.versions.qt.get()}")
    }

    // Ktor
    val ktor = libs.ktor
    implementation(ktor.client.core)
    implementation(ktor.client.cio)
    implementation(ktor.client.auth)
    implementation(ktor.client.json)
    implementation(ktor.client.logging)
    implementation(ktor.client.content.negotiation)
    implementation(ktor.client.serialization)
    implementation(ktor.client.websockets)
    implementation(ktor.serialization.kotlinx.json)

    implementation(ktor.server.core)
    implementation(ktor.server.netty)

    // MSAL4j
    implementation(libs.msal4j)
    implementation(libs.jultoslf4j)

    // Logback
    implementation(libs.logback.classic)

    // LSP
    implementation(libs.lsp4j)

    // JNA
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // CommonMark
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.image.attributes)
    implementation(libs.sqlite.jdbc)
    implementation(libs.flatbuffers)

    // Kotlin
    implementation(libs.kotlin.reflect)

    // KTreeSitter
    implementation(libs.ktreesitter)

    // Tritium Mod API
    implementation("io.github.tritium_launcher:tritium-mod-api:0.1.3-SNAPSHOT") {
        isChanging = true
    }

    /* Test */

    testImplementation(libs.bundles.test)
}

val nativeLibDir = layout.buildDirectory.dir("native/grammar")
val generatedGrammarDir = layout.buildDirectory.dir("generated")
val grammarCmakeLists = generatedGrammarDir.map { it.file("CMakeLists.txt") }
val grammarSourceDir = layout.projectDirectory.dir("tree-sitter-javascript/src")
val nativeGrammarLibName: String = when {
    os.isWindows -> "ktreesitter-javascript.dll"
    os.isMacOsX  -> "libktreesitter-javascript.dylib"
    else         -> "libktreesitter-javascript.so"
}

val patchCmakeLists by tasks.registering {
    description = "Fix CMakeLists.txt include path for tree-sitter header"
    dependsOn(tasks.named("generateGrammarFiles"))
    inputs.file(grammarCmakeLists)
    outputs.file(grammarCmakeLists)
    doLast {
        val cmakeFile = grammarCmakeLists.get().asFile
        val content = cmakeFile.readText().replace('\\', '/')
        val fixed = content.replace(
            "../../tree-sitter-javascript/bindings/c",
            "../../tree-sitter-javascript/bindings/c ../../tree-sitter-javascript/bindings/c/tree_sitter"
        )
        if (fixed != content) {
            cmakeFile.writeText(fixed)
        }
    }
}

val compileGrammarNative by tasks.registering {
    description = "Compile tree-sitter JavaScript grammar via CMake"
    dependsOn(tasks.named("generateGrammarFiles"), patchCmakeLists)
    val cmakeBuildDir = nativeLibDir.get().asFile
    val srcDir = generatedGrammarDir.get().asFile
    inputs.dir(grammarSourceDir).optional()
    inputs.dir(srcDir.resolve("src/jni"))
    inputs.file(srcDir.resolve("CMakeLists.txt"))
    outputs.file(cmakeBuildDir.resolve(nativeGrammarLibName))
    onlyIf {
        grammarSourceDir.asFile.exists()
    }
    doLast {
        cmakeBuildDir.mkdirs()
        val javaHome = System.getProperty("java.home")
        val cmakeEnv = mapOf("JAVA_HOME" to javaHome)
        val configure = ProcessBuilder(
            "cmake", srcDir.path,
            "-DCMAKE_BUILD_TYPE=Release"
        )
            .directory(cmakeBuildDir)
            .inheritIO()
            .apply { environment().putAll(cmakeEnv) }
            .start()
        if (configure.waitFor() != 0) {
            throw GradleException("cmake configuration failed for tree-sitter-javascript grammar")
        }
        val build = ProcessBuilder("cmake", "--build", ".", "--config", "Release", "--target", "ktreesitter-javascript", "--parallel")
            .directory(cmakeBuildDir)
            .inheritIO()
            .start()
        if (build.waitFor() != 0) {
            throw GradleException("cmake build failed for tree-sitter-javascript grammar")
        }

        val libFile = cmakeBuildDir.resolve(nativeGrammarLibName)
        if (!libFile.exists()) {
            val found = cmakeBuildDir.walkTopDown().maxDepth(3).find { it.name == nativeGrammarLibName }
            if (found != null) {
                found.copyTo(libFile, overwrite = true)
                logger.lifecycle("Copied native grammar library from {} to {}", found, libFile)
            } else {
                throw GradleException(
                    "Native grammar library was not produced at expected path: ${libFile}. " +
                    "Searched subdirectories up to 3 levels deep. cmake exited successfully but output is missing."
                )
            }
        }
    }
}

sourceSets["main"].java.srcDirs("src/main/kotlin")

idea {
    module {
        sourceDirs = sourceDirs + file("build/generated/ksp/main/kotlin")
        generatedSourceDirs = generatedSourceDirs + file("build/generated/ksp/main/kotlin")
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    dependsOn(compileGrammarNative)

    val nativeSubdir = when {
        os.isWindows -> if (isArm64) "windows/arm64" else "windows/x64"
        os.isMacOsX  -> if (isArm64) "macos/arm64"   else "macos/x64"
        else         -> if (isArm64) "linux/arm64"   else "linux/x64"
    }

    doFirst {
        val libFile = nativeLibDir.get().file(nativeGrammarLibName).asFile
        if (!libFile.exists()) {
            throw GradleException(
                "Native grammar library not found at ${libFile}. " +
                "The compileGrammarNative task should have produced it."
            )
        }
    }

    inputs.property("version", tritiumVersion)
    filesMatching("version.txt") {
        expand("version" to tritiumVersion)
    }

    from(nativeLibDir.map { it.file(nativeGrammarLibName) }) {
        into("lib/$nativeSubdir")
    }
}

tasks.jar {
    archiveBaseName.set("tritium-app")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.github.tritium_launcher.launcher.Main"
        attributes["Implementation-Version"] = tritiumVersion
    }
}

tasks.shadowJar {
    archiveBaseName.set("tritium")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.github.tritium_launcher.launcher.Main"
        attributes["Implementation-Version"] = tritiumVersion
    }
    transform(XmlAppendingTransformer::class.java){
        resource = "META-INF/qtjambi-deployment.xml"
    }
}

val preparePackageInput by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("package-input"))
    from(tasks.jar.flatMap { it.archiveFile })
    from(configurations.runtimeClasspath.map { files ->
        files.filter { it.isFile && it.extension == "jar" }
    })
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.apply {
    compilerOptions {
        incremental = true
        freeCompilerArgs.set(listOf("-Xcontext-parameters", "-progressive"))
    }
}

val compileJava: JavaCompile by tasks
compileJava.apply {
    options.isIncremental = true
}

tasks.clean {
    delete(nativeLibDir)
    delete(generatedGrammarDir)
}
