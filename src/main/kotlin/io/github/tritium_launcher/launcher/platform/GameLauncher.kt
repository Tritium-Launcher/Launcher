package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth
import io.github.tritium_launcher.launcher.accounts.ProfileMngr
import io.github.tritium_launcher.launcher.core.modloader.LaunchContext
import io.github.tritium_launcher.launcher.core.modloader.ModLoader
import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.IODispatchers
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.redactUserPath
import io.qt.gui.QGuiApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile

/**
 * Responsible for preparing and launching a Minecraft instance for a project:
 * - Ensures base MC + loader assets exist
 * - Builds merged version JSON + classpath/arguments
 * - Spawns the JVM process and streams early output
 */
object GameLauncher {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pathSeparator = File.pathSeparator ?: ":"
    private val ansiRegex = Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]")
    private const val MAX_MISSING_LIB_LOG = 12
    private const val MODPACK_PROJECT_TYPE_ID = "modpack"
    private const val DEFAULT_MAX_HEAP_MB = 2_048
    private val runtimePreparingScopes = ConcurrentHashMap<String, RuntimePreparationContext>()
    private val runtimePreparationListeners = CopyOnWriteArrayList<(RuntimePreparationEvent) -> Unit>()

    data class RuntimePreparationContext(
        val projectScope: String,
        val projectName: String,
        val startedAtEpochMs: Long
    )

    data class RuntimePreparationEvent(
        val type: Type,
        val context: RuntimePreparationContext
    ) {
        enum class Type {
            Started,
            Finished
        }
    }

    private data class LaunchSpec(
        val meta: ModpackMeta,
        val loader: ModLoader,
        val mcVersion: String,
        val loaderId: String,
        val loaderVersion: String,
        val mergedId: String
    )

    /**
     * Attach project process tracking to an already-running process by [pid].
     */
    fun attachToRunningProcess(project: ProjectBase, pid: Long): Boolean =
        GameProcessMngr.attachToPid(project, pid)

    /**
     * Returns tracked game process metadata for [project], or null when none is attached.
     */
    fun gameProcessSnapshot(project: ProjectBase): GameProcessMngr.GameProcessContext? =
        GameProcessMngr.snapshot(project)

    /**
     * Returns true when a tracked game process is currently running for [project].
     */
    fun isGameRunning(project: ProjectBase): Boolean =
        GameProcessMngr.isActive(project)

    /**
     * Request game process termination for [project].
     */
    fun killGameProcess(project: ProjectBase, force: Boolean = true): Boolean =
        GameProcessMngr.kill(project, force)

    /**
     * Stop tracking the game process for [project] without terminating it.
     */
    fun detachGameProcess(project: ProjectBase): Boolean =
        GameProcessMngr.detach(project)

    /**
     * Subscribe to game process lifecycle events.
     */
    fun addGameProcessListener(listener: (GameProcessMngr.GameProcessEvent) -> Unit): () -> Unit =
        GameProcessMngr.addListener(listener)

    /**
     * Subscribe to runtime preparation state changes.
     */
    fun addRuntimePreparationListener(listener: (RuntimePreparationEvent) -> Unit): () -> Unit {
        runtimePreparationListeners += listener
        return { runtimePreparationListeners -= listener }
    }

    /**
     * Returns true while runtime setup/download is active for [project].
     */
    fun isRuntimePreparationActive(project: ProjectBase): Boolean =
        runtimePreparingScopes.containsKey(scopeOf(project.path))

    /**
     * Returns true when runtime files are missing and need to be downloaded/materialized.
     */
    fun needsRuntimeDownload(project: ProjectBase): Boolean {
        val spec = resolveLaunchSpec(project, logFailures = false) ?: return false
        return hasMissingRuntime(spec, project.projectDir)
    }

    /**
     * Ensure MC + loader runtime files exist for [project] without launching the game.
     */
    fun prepareRuntime(project: ProjectBase) {
        val prepareCtx = beginRuntimePreparation(project) ?: return
        scope.launch {
            try {
                prepareRuntimeInternal(project)
            } catch (t: Throwable) {
                logger.error("Runtime preparation failed for {}", project.name, t)
            } finally {
                endRuntimePreparation(prepareCtx)
            }
        }
    }

    /**
    * Public entry point for launching a project; executes asynchronously on IO scope.
     */
    fun launch(project: ProjectBase) {
        val prepareCtx = beginRuntimePreparation(project) ?: return
        scope.launch {
            try {
                launchInternal(project)
            } catch (t: Throwable) {
                logger.error("Launch failed for {}", project.name, t)
            } finally {
                endRuntimePreparation(prepareCtx)
            }
        }
    }

    /**
    * Resolve metadata, ensure required files, and start the game process for a modpack project.
     */
    private suspend fun launchInternal(project: ProjectBase) {
        val spec = resolveLaunchSpec(project, logFailures = true) ?: return
        if (!ensureBaseAndLoader(spec.meta, project.projectDir, spec.loader)) {
            logger.warn("Cannot launch; required game files not ready for {}", project.name)
            return
        }
        val mcVersion = spec.mcVersion
        val loaderVersion = spec.loaderVersion
        val mergedId = spec.mergedId
        val loader = spec.loader

        val versionsDir = project.projectDir.resolve(".tr").resolve("versions")
        val mergedJson = versionsDir.resolve(mergedId).resolve("$mergedId.json")
        val baseJson = versionsDir.resolve(mcVersion).resolve("$mcVersion.json")
        val versionJsonFile = if (mergedJson.exists()) mergedJson else baseJson
        val versionJsonText = versionJsonFile.readTextOrNull()
        if (versionJsonText.isNullOrBlank()) {
            logger.warn("Cannot launch; version json missing for project '{}'", project.name)
            return
        }

        val versionObj = json.parseToJsonElement(versionJsonText).jsonObject
        val mainClass = readMainClass(versionObj)
        if (mainClass.isNullOrBlank()) {
            logger.warn("Cannot launch; mainClass missing for project '{}'", project.name)
            return
        }

        val classpathResult = buildClasspathEntries(project.projectDir, versionObj, mergedId, mcVersion)
        val classpathEntries = classpathResult.entries
        if (classpathResult.missingEntries.isNotEmpty()) {
            logMissingLibraries(
                project.name,
                classpathResult.missingEntries,
                "Cannot launch; required libraries are missing before launch"
            )
            return
        }
        val context = LaunchContext(project.projectDir, mcVersion, loaderVersion, mergedId, versionObj)
        loader.prepareLaunchClasspath(context, classpathEntries)
        val missingAfterLoader = findMissingClasspathEntries(classpathEntries)
        if (missingAfterLoader.isNotEmpty()) {
            logMissingLibraries(
                project.name,
                missingAfterLoader,
                "Cannot launch; classpath contains missing or invalid library entries"
            )
            return
        }
        if (classpathEntries.isEmpty()) {
            logger.warn("Cannot launch; classpath is empty for {}", project.name)
            return
        }
        val classpath = classpathEntries.joinToString(pathSeparator)
        val assetsDir = project.projectDir.resolve(".tr").resolve("assets")
        val assetIndexId = versionObj["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val nativesDir = project.projectDir.resolve(".tr").resolve("natives").resolve(mcVersion)

        val profile = ProfileMngr.Cache.get()
        val username = profile?.name
        val uuid = profile?.id
        var accessToken = ProfileMngr.Cache.getToken()
        if (accessToken.isNullOrBlank()) {
            accessToken = MicrosoftAuth.getActiveMcTokenOrNull()
        }

        if (username.isNullOrBlank() || uuid.isNullOrBlank() || accessToken.isNullOrBlank()) {
            logger.warn(
                "Cannot launch; missing account info (usernamePresent={}, uuidPresent={}, tokenPresent={})",
                !username.isNullOrBlank(),
                !uuid.isNullOrBlank(),
                !accessToken.isNullOrBlank()
            )
            return
        }

        val requiredJavaMajor = Minecraft.requiredJavaMajor(mcVersion)
        val javaExec = resolveJavaExecutableForMinecraft(mcVersion)
        if (javaExec == null) {
            logger.warn(
                "Cannot launch; no Java {} runtime available for Minecraft {}. Configure java.path.{} in Settings.",
                requiredJavaMajor,
                mcVersion,
                requiredJavaMajor
            )
            return
        }

        val gameArgs = buildGameArgs(
            versionObj = versionObj,
            gameDir = project.projectDir,
            assetsDir = assetsDir,
            assetIndexId = assetIndexId,
            mcVersion = mcVersion,
            username = username,
            uuid = uuid,
            accessToken = accessToken,
            mergedId = mergedId,
            launchMaximized = CoreSettingValues.gameLaunchMaximized()
        )
        val jvmArgs = buildJvmArgs(versionObj, project.projectDir, nativesDir, classpath).toMutableList()
        loader.prepareLaunchJvmArgs(context, classpathEntries, jvmArgs)
        if (loader.shouldStripMinecraftClientArtifacts(context)) {
            stripMinecraftArtifactsFromModulePath(jvmArgs)
        }
        jvmArgs += parseAdditionalJvmArgs(project)
        val finalJvmArgs = ensureClasspathArg(stripUnsupportedArgs(jvmArgs), classpath)
        val companionToken = UUID.randomUUID().toString().replace("-", "")
        CompanionBridge.setSessionToken(companionToken)
        val launchJvmArgs = finalJvmArgs.toMutableList().apply {
            add("-Dtritium.companion.ws.port=${CoreSettingValues.companionWsPort()}")
        }

        val maxHeapMb = resolveMaxHeapMb(project)
        val minHeapMb = minOf(1024, maxHeapMb)
        val command = mutableListOf<String>()
        command += javaExec.toAbsolute().toString()
        command += listOf(
            "-Xms${minHeapMb}M",
            "-Xmx${maxHeapMb}M"
        )
        // Strip duplicate main-jar entries from the classpath
        val cpEntries = classpathEntries.filter { it.isNotBlank() }
        val dedupCp = cpEntries.distinct()
        val classpathToUse = dedupCp.joinToString(pathSeparator)
        val cpIdx = launchJvmArgs.indexOf("-cp")
        if (cpIdx >= 0) {
            if (cpIdx + 1 < launchJvmArgs.size) {
                launchJvmArgs[cpIdx + 1] = classpathToUse
            } else {
                launchJvmArgs += classpathToUse
            }
        } else {
            launchJvmArgs += listOf("-cp", classpathToUse)
        }
        command += launchJvmArgs
        command += mainClass
        command += gameArgs

        logger.info(
            "Launching Minecraft process (project='{}', mainClass='{}', classpathEntries={}, jvmArgs={}, gameArgs={})",
            project.name,
            mainClass,
            dedupCp.size,
            launchJvmArgs.size,
            gameArgs.size
        )
        try {
            val processBuilder = ProcessBuilder(command)
                .directory(project.projectDir.toJFile())
                .redirectErrorStream(true)
            processBuilder.environment()["TRITIUM_COMPANION_WS_TOKEN"] = companionToken
            val process = withContext(IODispatchers.FileIO) { runInterruptible { processBuilder.start() } }
            GameProcessMngr.attachLaunched(project, process)

            val outputJob = scope.launch(IODispatchers.FileIO) {
                try {
                    runInterruptible {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                logger.info("MC: {}", sanitizeMcOutputLine(line))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("Failed reading Minecraft process output stream", t)
                }
            }

            scope.launch(IODispatchers.FileIO) {
                val exit = runInterruptible { process.waitFor() }
                outputJob.join()
                logger.info("Minecraft exited with code {}", exit)
                CompanionBridge.clearSessionToken()
            }
        } catch (t: Throwable) {
            CompanionBridge.clearSessionToken()
            logger.error("Failed to start Minecraft process", t)
        }
    }

    /**
     * Resolves the Java executable for a Minecraft version.
     *
     * Resolution order:
     * 1. Java path configured in Settings for the required runtime
     * 2. Auto-detected installed runtime matching the required major
     */
    private fun resolveJavaExecutableForMinecraft(mcVersion: String): VPath? {
        val requiredMajor = Minecraft.requiredJavaMajor(mcVersion)
        val configured = CoreSettingValues.javaPathForMajor(requiredMajor)
        val configuredExec = Java.resolveJavaExecutable(configured)
        if (configuredExec != null) {
            return configuredExec
        }
        if (!configured.isNullOrBlank()) {
            logger.warn("Configured java.path.{} is invalid: {}", requiredMajor, configured.redactUserPath())
        }

        val detected = Java.findDetectedExecutableForMajor(requiredMajor)
        if (detected != null) {
            return detected
        }
        return null
    }

    private fun resolveMaxHeapMb(project: ProjectBase): Int {
        if (project.typeId != MODPACK_PROJECT_TYPE_ID) {
            return DEFAULT_MAX_HEAP_MB
        }
        return CoreSettingValues.modpackMemoryMb().coerceAtLeast(512)
    }

    private fun parseAdditionalJvmArgs(project: ProjectBase): List<String> {
        if (project.typeId != MODPACK_PROJECT_TYPE_ID) {
            return emptyList()
        }
        val raw = CoreSettingValues.modpackJvmArgs()?.trim().orEmpty()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return splitArgs(raw)
    }

    private fun splitArgs(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        value.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' && quote != null -> escaping = true
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (escaping) {
            current.append('\\')
        }
        if (current.isNotEmpty()) {
            out += current.toString()
        }
        return out
    }

    /**
     * Build the launch classpath entries from the merged version JSON (libraries + main jar)
     */
    private data class ClasspathBuildResult(
        val entries: MutableList<String>,
        val missingEntries: List<String>
    )

    private fun buildClasspathEntries(projectDir: VPath, versionObj: JsonObject, mergedId: String, mcVersion: String): ClasspathBuildResult {
        val libsDir = projectDir.resolve(".tr").resolve("libraries")
        val libs = versionObj["libraries"]?.jsonArray ?: JsonArray(emptyList())
        val entries = mutableListOf<String>()
        val missing = linkedSetOf<String>()
        for (el in libs) {
            val obj = el as? JsonObject ?: continue
            if(!rulesAllow(obj["rules"]?.jsonArray)) continue
            val path = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
                ?.get("path")?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.let { mavenPath(it) }
            if (path != null) {
                // Avoid Minecraft client artifacts, launch with the merged jar
                if (path.contains("net/minecraft/client/")) continue
                if (!path.endsWith(".jar")) continue
                val jar = libsDir.resolve(path)
                if (isUsableLibraryFile(jar, expectedSize = null)) {
                    entries.add(jar.toAbsolute().toString())
                } else {
                    missing += jar.toAbsolute().toString()
                }
            }
        }

        val versionsDir = projectDir.resolve(".tr").resolve("versions")
        val mergedJar = versionsDir.resolve(mergedId).resolve("$mergedId.jar")
        val baseJar = versionsDir.resolve(mcVersion).resolve("$mcVersion.jar")
        val mainJar = if (mergedJar.exists()) mergedJar else baseJar
        if (isUsableLibraryFile(mainJar, expectedSize = null)) {
            entries.add(0, mainJar.toAbsolute().toString())
        } else {
            missing += mainJar.toAbsolute().toString()
        }

        return ClasspathBuildResult(
            entries = entries,
            missingEntries = missing.toList()
        )
    }

    /**
     * Construct Minecraft game arguments from the merged version JSON, replacing tokens
     * and stripping quickplay/demo/placeholder entries
     */
    private fun buildGameArgs(
        versionObj: JsonObject,
        gameDir: VPath,
        assetsDir: VPath,
        assetIndexId: String,
        mcVersion: String,
        username: String,
        uuid: String,
        accessToken: String,
        mergedId: String,
        launchMaximized: Boolean
    ): List<String> {
        val launchResolution = resolveLaunchResolution(launchMaximized)
        val replacements = mapOf(
            "\${auth_player_name}" to username,
            "\${version_name}" to mergedId,
            "\${game_directory}" to gameDir.toAbsolute().toString(),
            "\${assets_root}" to assetsDir.toAbsolute().toString(),
            "\${assets_index_name}" to assetIndexId,
            "\${auth_uuid}" to uuid,
            "\${auth_access_token}" to accessToken,
            "\${user_type}" to "msa",
            "\${version_type}" to "release",
            "\${launcher_name}" to "Tritium",
            "\${launcher_version}" to TConstants.VERSION,
            "\${clientid}" to "",
            "\${auth_xuid}" to "",
            "\${resolution_width}" to launchResolution.first.toString(),
            "\${resolution_height}" to launchResolution.second.toString(),
            "\${quickPlayPath}" to ""
        )

        val args = mutableListOf<String>()
        val argumentsObj = versionObj["arguments"]?.jsonObject
        if (argumentsObj != null) {
            val gameArr = argumentsObj["game"]?.jsonArray ?: JsonArray(emptyList())
            for (entry in gameArr) {
                args += parseArgEntry(entry, replacements)
            }
            return ensureWindowSizeArgs(stripUnsupportedArgs(removeQuickPlayArgs(args)), launchResolution)
        }

        val legacy = versionObj["minecraftArguments"]?.jsonPrimitive?.contentOrNull
        if (!legacy.isNullOrBlank()) {
            val split = legacy.split(" ")
            split.filter { it.isNotBlank() }.forEach { args += replaceTokens(it, replacements) }
            return ensureWindowSizeArgs(stripUnsupportedArgs(removeQuickPlayArgs(args)), launchResolution)
        }

        args += listOf(
            "--username", username,
            "--version", mergedId,
            "--gameDir", gameDir.toAbsolute().toString(),
            "--assetsDir", assetsDir.toAbsolute().toString(),
            "--assetIndex", assetIndexId,
            "--uuid", uuid,
            "--userType", "msa",
            "--versionType", "release"
        )
        return ensureWindowSizeArgs(stripUnsupportedArgs(removeQuickPlayArgs(args)), launchResolution)
    }

    /**
     * Resolves effective launch resolution from settings.
     *
     * When [launchMaximized] is enabled, this returns the primary screen's available geometry
     * to approximate a maximized window while keeping a regular windowed launch.
     */
    private fun resolveLaunchResolution(launchMaximized: Boolean): Pair<Int, Int> {
        val configured = CoreSettingValues.gameLaunchResolution()
        if (!launchMaximized) return configured

        return try {
            val screen = QGuiApplication.primaryScreen()
            val available = screen?.availableGeometry()
            val width = available?.width() ?: 0
            val height = available?.height() ?: 0
            if (width > 0 && height > 0) {
                width to height
            } else {
                configured
            }
        } catch (t: Throwable) {
            logger.debug("Failed to read primary screen geometry for maximized launch size", t)
            configured
        }
    }

    /**
     * Ensures game args contain exactly one width/height pair.
     *
     * Existing width/height arguments are removed first so runtime-selected launch sizing
     * (settings or maximized resolution) always wins.
     */
    private fun ensureWindowSizeArgs(args: List<String>, launchResolution: Pair<Int, Int>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val normalized = a.lowercase()
            if (a == "--width" || a == "--height") {
                i += if (i + 1 < args.size) 2 else 1
                continue
            }
            if (normalized.startsWith("--width=") || normalized.startsWith("--height=")) {
                i++
                continue
            }
            out += a
            i++
        }

        out += listOf(
            "--width", launchResolution.first.toString(),
            "--height", launchResolution.second.toString()
        )
        return out
    }

    /**
     * Build JVM arguments from the version JSON, applying token replacements.
     */
    private fun buildJvmArgs(versionObj: JsonObject, gameDir: VPath, nativesDir: VPath, classpath: String): List<String> {
        val args = mutableListOf<String>()
        args += "-Djava.library.path=${nativesDir.toAbsolute()}"
        args += "-Dorg.lwjgl.librarypath=${nativesDir.toAbsolute()}"

        val argumentsObj = versionObj["arguments"]?.jsonObject
        if (argumentsObj != null) {
            val mergedId = findMergedIdFromClasspath(classpath)
            val replacements = mapOf(
                "\${natives_directory}" to nativesDir.toAbsolute().toString(),
                "\${classpath_separator}" to pathSeparator,
                "\${library_directory}" to gameDir.resolve(".tr").resolve("libraries").toAbsolute().toString(),
                "\${classpath}" to classpath,
                "\${launcher_name}" to "Tritium",
                "\${launcher_version}" to TConstants.VERSION,
                "\${version_name}" to (mergedId ?: ""),
                "\${version_id}" to (mergedId ?: "")
            )
            val jvmArr = argumentsObj["jvm"]?.jsonArray ?: JsonArray(emptyList())
            for (entry in jvmArr) {
                args += parseArgEntry(entry, replacements)
            }
        }

        return args
    }

    /**
     * Interpret an argument entry from the version JSON (supports rules + arrays).
     */
    private fun parseArgEntry(entry: JsonElement, replacements: Map<String, String>): List<String> {
        val primitive = entry.jsonPrimitiveOrNull()
        if (primitive != null && !primitive.isString) return emptyList()
        if (primitive != null && primitive.isString) {
            return listOf(replaceTokens(primitive.content, replacements))
        }

        val obj = entry as? JsonObject ?: return emptyList()
        val rules = obj["rules"]?.jsonArray
        if(!rulesAllow(rules)) return emptyList()
        val valueEl = obj["value"] ?: return emptyList()
        return when (valueEl) {
            is JsonPrimitive -> listOf(replaceTokens(valueEl.content, replacements))
            is JsonArray -> valueEl.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
                .map { replaceTokens(it, replacements) }
            else -> emptyList()
        }
    }

    /**
     * Evaluate rule blocks from version JSON to decide whether an argument/library applies.
     */
    private fun rulesAllow(rules: JsonArray?): Boolean {
        if (rules == null) return true
        var allowed = false
        for (ruleEl in rules) {
            val rule = ruleEl as? JsonObject ?: continue
            val action = rule["action"]?.jsonPrimitive?.contentOrNull ?: "allow"
            val osObj = rule["os"]?.jsonObject
            if (osObj != null && !osMatches(osObj)) continue
            allowed = action == "allow"
        }
        return allowed
    }

    /**
     * Check whether the supplied OS rule matches the current platform.
     */
    private fun osMatches(os: JsonObject): Boolean {
        val name = os["name"]?.jsonPrimitive?.contentOrNull
        val arch = os["arch"]?.jsonPrimitive?.contentOrNull
        if (name != null) {
            val ok = when (name) {
                "windows" -> Platform.isWindows
                "osx" -> Platform.isMacOS
                "linux" -> Platform.isLinux
                else -> false
            }
            if (!ok) return false
        }
        if (arch != null) {
            val a = System.getProperty("os.arch") ?: ""
            val is64 = a.contains("64")
            if (arch == "x86" && is64) return false
            if (arch == "x64" && !is64) return false
        }
        return true
    }

    /**
     * Replace argument tokens with resolved values.
     */
    private fun replaceTokens(value: String, replacements: Map<String, String>): String {
        var out = value
        for ((k, v) in replacements) {
            out = out.replace(k, v)
        }
        return out
    }

    /**
     * Remove quick play arguments (and their values) from the launch list.
     */
    private fun removeQuickPlayArgs(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val isQuickPlay = a == "--quickPlaySingleplayer" || a == "--quickPlayMultiplayer" || a == "--quickPlayRealms"
            if (isQuickPlay) {
                i += if (i + 1 < args.size) 2 else 1
                continue
            }
            out.add(a)
            i++
        }
        return out
    }

    /**
     * Remove unsupported or unresolved arguments before launch.
     */
    private fun stripUnsupportedArgs(args: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--demo") { i++; continue }
            if (a.contains("\${")) { i++; continue }
            if (a == "--quickPlayPath") { i += if (i + 1 < args.size) 2 else 1; continue }
            out.add(a)
            i++
        }
        return out
    }

    /**
     * Ensure an existing classpath argument has a valid value, if present.
     */
    private fun ensureClasspathArg(args: List<String>, classpath: String): List<String> {
        val out = args.toMutableList()
        val idx = out.indexOf("-cp")
        if (idx >= 0) {
            val next = out.getOrNull(idx + 1)
            if (next.isNullOrBlank()) {
                if (idx + 1 < out.size) out[idx + 1] = classpath else out.add(classpath)
            }
            return out
        }
        return out
    }

    /**
     * Remove slim/srg client artifacts from the JVM module path argument.
     */
    private fun stripMinecraftArtifactsFromModulePath(jvmArgs: MutableList<String>) {
        val pIdx = jvmArgs.indexOf("-p")
        if (pIdx < 0 || pIdx + 1 >= jvmArgs.size) return
        val current = jvmArgs[pIdx + 1]
        val cleaned = removeMinecraftSlimArtifacts(current)
        if (cleaned != current) {
            jvmArgs[pIdx + 1] = cleaned
        }
    }

    /**
     * Extract the merged version id from a classpath string when present.
     */
    private fun findMergedIdFromClasspath(classpath: String): String? {
        val entries = classpath.split(pathSeparator)
        for (entry in entries) {
            val path = VPath.parse(entry)
            val segments = path.segments()
            val idx = segments.indexOf(".tr")
            if (idx >= 0 && segments.getOrNull(idx + 1) == "versions") {
                val fileName = path.fileName()
                if (fileName.endsWith(".jar")) return fileName.removeSuffix(".jar")
            }
        }
        return null
    }

    /**
     * Remove slim/srg artifacts from a module path list.
     */
    private fun removeMinecraftSlimArtifacts(paths: String): String {
        if (paths.isBlank()) return paths
        return paths.split(pathSeparator)
            .filterNot { it.contains("net/minecraft/client/") && (it.contains("-srg.jar") || it.contains("-slim.jar") || it.contains("-extra.jar")) }
            .joinToString(pathSeparator)
    }

    /**
     * Remove cached Minecraft intermediates for non-NeoForge loaders.
     */
    private fun cleanupMinecraftIntermediates(projectDir: VPath, mcVersion: String, loaderId: String) {
        if (loaderId == "neoforge") return
        val clientRoot = projectDir.resolve(".tr").resolve("libraries").resolve("net/minecraft/client")
        if (!clientRoot.exists()) return
        val dirs = clientRoot.listFiles { it.isDir() && it.fileName().startsWith(mcVersion) }
        for (dir in dirs) {
            val toDelete = dir.listFiles { path ->
                val name = path.fileName()
                name.contains("-srg") || name.contains("-slim") || name.contains("-extra") || name.endsWith(".cache")
            }
            toDelete.forEach { f ->
                try {
                    if (f.delete()) {
                        logger.info("Removed intermediate MC artifact {}", f.toAbsolute().toString().redactUserPath())
                    }
                } catch (_: Throwable) {
                    logger.debug("Failed to remove intermediate MC artifact {}", f.toAbsolute().toString().redactUserPath())
                }
            }
        }
    }

    /**
     * Convert a Maven coordinate into a repository path.
     */
    private fun mavenPath(name: String): String {
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        val file = if (classifier != null) "$artifact-$version-$classifier.jar" else "$artifact-$version.jar"
        return "$group/$artifact/$version/$file"
    }

    /**
     * Return the JsonPrimitive if the element is a primitive, otherwise null.
     */
    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? {
        return this as? JsonPrimitive
    }

    /**
     * Remove ANSI escape sequences from captured MC stdout/stderr for cleaner launcher logs.
     */
    private fun sanitizeMcOutputLine(line: String): String = ansiRegex.replace(line, "")

    /**
     * Read a mainClass which may be a string or an object keyed by side.
     */
    private fun readMainClass(versionObj: JsonObject): String? {
        val el = versionObj["mainClass"] ?: return null
        val primitive = el as? JsonPrimitive
        if (primitive != null) return primitive.contentOrNull
        val obj = el as? JsonObject ?: return null
        return obj["client"]?.jsonPrimitive?.contentOrNull
            ?: obj["main"]?.jsonPrimitive?.contentOrNull
            ?: obj["default"]?.jsonPrimitive?.contentOrNull
    }

    /**
     * Ensure the base Minecraft version and selected loader are installed for the project,
     * writing a merged version JSON used for launching.
     */
    private suspend fun ensureBaseAndLoader(meta: ModpackMeta, projectDir: VPath, loader: ModLoader): Boolean {
        val mcVersion = meta.minecraftVersion
        val loaderId = meta.loader
        val loaderVersion = meta.loaderVersion

        val baseJson = projectDir.resolve(".tr").resolve("versions").resolve(mcVersion).resolve("$mcVersion.json")
        val baseNeedsRefresh = if (!baseJson.exists()) {
            true
        } else {
            !isBaseLibrariesHealthy(projectDir, baseJson) ||
                !isNativeRuntimeHealthy(projectDir, mcVersion) ||
                !isAssetsHealthy(projectDir, baseJson, logIssues = false)
        }
        if (baseNeedsRefresh) {
            logger.info("Refreshing base Minecraft {} files and libraries", mcVersion)
            if (!MicrosoftAuth.setupMinecraftInstance(mcVersion, projectDir)) {
                logger.warn("Failed to setup Minecraft {}", mcVersion)
                return false
            }
        }
        if (
            !isBaseLibrariesHealthy(projectDir, baseJson) ||
            !isNativeRuntimeHealthy(projectDir, mcVersion) ||
            !isAssetsHealthy(projectDir, baseJson)
        ) {
            logger.warn("Base Minecraft runtime is still unhealthy after refresh for {}", mcVersion)
            return false
        }

        logger.info("Ensuring loader installed: {} {}", loaderId, loaderVersion)
        if (!loader.installClient(loaderVersion, mcVersion, projectDir)) {
            logger.warn("Loader install failed for {} {}", loaderId, loaderVersion)
            return false
        }

        cleanupMinecraftIntermediates(projectDir, mcVersion, loaderId)

        val merged = MicrosoftAuth.writeMergedVersionJson(mcVersion, loaderId, loaderVersion, projectDir)
        if (merged == null || !merged.exists()) {
            logger.warn("Merged version json missing for {} {}", loaderId, loaderVersion)
            return false
        }

        return true
    }

    /**
     * Validate that all applicable base-version libraries exist and look usable.
     *
     * This catches stale/broken cache links (for example truncated jars) so setup can
     * re-materialize libraries before launch.
     */
    private fun isBaseLibrariesHealthy(projectDir: VPath, baseJsonFile: VPath, logIssues: Boolean = true): Boolean {
        val baseText = baseJsonFile.readTextOrNull() ?: return false
        val baseObj = runCatching { json.parseToJsonElement(baseText).jsonObject }.getOrNull() ?: return false
        val libs = baseObj["libraries"]?.jsonArray ?: return true
        val libsDir = projectDir.resolve(".tr").resolve("libraries")

        for (el in libs) {
            val obj = el as? JsonObject ?: continue
            if (!rulesAllow(obj["rules"]?.jsonArray)) continue

            val artifact = obj["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject
            val path = artifact?.get("path")?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull?.let { mavenPath(it) }
                ?: continue
            val expectedSize = artifact?.get("size")?.jsonPrimitive?.longOrNull
            val file = libsDir.resolve(path)

            if (!isUsableLibraryFile(file, expectedSize)) {
                if (logIssues) {
                    logger.warn("Base library is missing/corrupt: {}", file.toAbsolute().toString().redactUserPath())
                }
                return false
            }
        }

        return true
    }

    /**
     * Validate that extracted native runtime binaries exist for the active platform.
     */
    private fun isNativeRuntimeHealthy(projectDir: VPath, mcVersion: String, logIssues: Boolean = true): Boolean {
        val nativesDir = projectDir.resolve(".tr").resolve("natives").resolve(mcVersion)
        if (!nativesDir.exists() || !nativesDir.isDir()) {
            if (logIssues) {
                logger.warn("Native runtime directory missing for Minecraft {}", mcVersion)
            }
            return false
        }

        val requiredExt = when {
            Platform.isWindows -> ".dll"
            Platform.isMacOS -> ".dylib"
            Platform.isLinux -> ".so"
            else -> {
                if (logIssues) {
                    logger.warn("Unknown platform '{}' while validating native runtime", Platform.name)
                }
                return true
            }
        }

        val hasNative = try {
            nativesDir.walk(recursive = true).any { it.isFile() && it.fileName().lowercase().endsWith(requiredExt) }
        } catch (t: Throwable) {
            if (logIssues) {
                logger.warn("Failed to validate native runtime directory {}", nativesDir.toAbsolute().toString().redactUserPath(), t)
            }
            false
        }

        if (!hasNative) {
            if (logIssues) {
                logger.warn(
                    "Native runtime for Minecraft {} is missing platform binaries (expected '*{}')",
                    mcVersion,
                    requiredExt
                )
            }
        }
        return hasNative
    }

    private fun isUsableLibraryFile(file: VPath, expectedSize: Long?): Boolean {
        if (!file.exists()) return false
        val size = file.sizeOrNull() ?: return false
        if (size <= 0L) return false
        if (expectedSize != null && expectedSize > 0L && size != expectedSize) return false
        if (!file.fileName().endsWith(".jar", ignoreCase = true)) return true

        return try {
            JarFile(file.toJFile()).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun findMissingClasspathEntries(classpathEntries: List<String>): List<String> {
        val missing = linkedSetOf<String>()
        for (entry in classpathEntries) {
            if (entry.isBlank()) continue
            val file = VPath.parse(entry)
            if (!isUsableLibraryFile(file, expectedSize = null)) {
                missing += file.toAbsolute().toString()
            }
        }
        return missing.toList()
    }

    private fun logMissingLibraries(projectName: String, missing: List<String>, reason: String) {
        if (missing.isEmpty()) return
        val shown = missing.take(MAX_MISSING_LIB_LOG)
        logger.warn("{} for '{}': {} missing entries", reason, projectName, missing.size)
        shown.forEach { path ->
            logger.warn("Missing library: {}", path.redactUserPath())
        }
        val remaining = missing.size - shown.size
        if (remaining > 0) {
            logger.warn("... and {} more missing entries", remaining)
        }
        logger.warn("Tip: Use File -> Invalidate Caches, then launch again to regenerate runtime libraries.")
    }

    private suspend fun prepareRuntimeInternal(project: ProjectBase): Boolean {
        val spec = resolveLaunchSpec(project, logFailures = true) ?: return false
        val ready = ensureBaseAndLoader(spec.meta, project.projectDir, spec.loader)
        if (!ready) {
            logger.warn("Runtime preparation failed for {}", project.name)
        } else {
            val stillMissingReason = hasMissingRuntimeReason(spec, project.projectDir)
            if (stillMissingReason != null) {
                logger.warn(
                    "Runtime preparation finished but runtime still reported as missing for '{}' (reason={})",
                    project.name,
                    stillMissingReason
                )
            } else {
                logger.info("Runtime preparation finished for '{}'", project.name)
            }
        }
        return ready
    }

    private fun resolveLaunchSpec(project: ProjectBase, logFailures: Boolean): LaunchSpec? {
        if (project.typeId != "modpack") {
            if (logFailures) {
                logger.warn("Launch is only supported for modpack projects (type={})", project.typeId)
            }
            return null
        }

        val metaFile = project.projectDir.resolve("trmodpack.json")
        val metaText = metaFile.readTextOrNull()
        if (metaText.isNullOrBlank()) {
            if (logFailures) {
                logger.warn("Cannot launch; trmodpack.json missing for project '{}'", project.name)
            }
            return null
        }

        val meta = runCatching { json.decodeFromString(ModpackMeta.serializer(), metaText) }.getOrNull()
        if (meta == null) {
            if (logFailures) {
                logger.warn("Cannot launch; failed to parse trmodpack.json for project '{}'", project.name)
            }
            return null
        }

        val mcVersion = meta.minecraftVersion
        val loaderId = meta.loader
        val loaderVersion = meta.loaderVersion
        val mergedId = "$mcVersion-$loaderId-$loaderVersion"
        val loader = BuiltinRegistries.ModLoader.all().find { it.id == loaderId }
        if (loader == null) {
            if (logFailures) {
                logger.warn("No mod loader registered for id={}", loaderId)
            }
            return null
        }

        return LaunchSpec(
            meta = meta,
            loader = loader,
            mcVersion = mcVersion,
            loaderId = loaderId,
            loaderVersion = loaderVersion,
            mergedId = mergedId
        )
    }

    private fun hasMissingRuntime(spec: LaunchSpec, projectDir: VPath): Boolean {
        return hasMissingRuntimeReason(spec, projectDir) != null
    }

    private fun hasMissingRuntimeReason(spec: LaunchSpec, projectDir: VPath): String? {
        val mcVersion = spec.mcVersion
        val mergedId = spec.mergedId
        val versionsDir = projectDir.resolve(".tr").resolve("versions")
        val baseJson = versionsDir.resolve(mcVersion).resolve("$mcVersion.json")
        if (!baseJson.exists()) return "base_version_json_missing"
        if (!isBaseLibrariesHealthy(projectDir, baseJson, logIssues = false)) return "base_libraries_missing_or_corrupt"
        if (!isNativeRuntimeHealthy(projectDir, mcVersion, logIssues = false)) return "native_runtime_missing_or_invalid"
        if (!isAssetsHealthy(projectDir, baseJson, logIssues = false)) return "assets_missing_or_invalid"

        val mergedJson = versionsDir.resolve(mergedId).resolve("$mergedId.json")
        if (!mergedJson.exists()) return "merged_version_json_missing"

        val mergedJar = versionsDir.resolve(mergedId).resolve("$mergedId.jar")
        val baseJar = versionsDir.resolve(mcVersion).resolve("$mcVersion.jar")
        val mainJar = if (isUsableLibraryFile(mergedJar, expectedSize = null)) mergedJar else baseJar
        if (!isUsableLibraryFile(mainJar, expectedSize = null)) return "main_jar_missing_or_invalid"

        val mergedText = mergedJson.readTextOrNull()
        if (mergedText.isNullOrBlank()) return "merged_version_json_empty"
        val mergedObj = runCatching { json.parseToJsonElement(mergedText).jsonObject }.getOrNull()
            ?: return "merged_version_json_invalid"
        if (readMainClass(mergedObj).isNullOrBlank()) return "merged_main_class_missing"
        if (!isLoaderMetadataPresent(spec.loaderId, projectDir)) return "loader_metadata_missing"

        return null
    }

    private fun isLoaderMetadataPresent(loaderId: String, projectDir: VPath): Boolean {
        val loaderRoot = projectDir.resolve(".tr").resolve("loader").resolve(loaderId)
        if (!loaderRoot.exists() || !loaderRoot.isDir()) return false

        return when (loaderId) {
            "fabric" -> loaderRoot.resolve("launcher-meta.json").exists()
            "neoforge" -> {
                loaderRoot.resolve("version.json").exists() &&
                    loaderRoot.resolve("install_profile.json").exists()
            }
            else -> loaderRoot.listFiles { it.isFile() }.isNotEmpty()
        }
    }

    private fun isAssetsHealthy(projectDir: VPath, baseJsonFile: VPath, logIssues: Boolean = true): Boolean {
        val baseText = baseJsonFile.readTextOrNull()
        if (baseText.isNullOrBlank()) {
            if (logIssues) logger.warn("Cannot validate assets; base version JSON missing: {}", baseJsonFile.toAbsolute().toString().redactUserPath())
            return false
        }
        val baseObj = runCatching { json.parseToJsonElement(baseText).jsonObject }.getOrNull()
        if (baseObj == null) {
            if (logIssues) logger.warn("Cannot validate assets; base version JSON is invalid: {}", baseJsonFile.toAbsolute().toString().redactUserPath())
            return false
        }

        val assetIndexId = baseObj["assetIndex"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        if (assetIndexId.isNullOrBlank()) {
            if (logIssues) logger.warn("Cannot validate assets; assetIndex.id missing in {}", baseJsonFile.toAbsolute().toString().redactUserPath())
            return false
        }

        val assetsDir = projectDir.resolve(".tr").resolve("assets")
        val indexFile = assetsDir.resolve("indexes").resolve("$assetIndexId.json")
        val indexText = indexFile.readTextOrNull()
        if (indexText.isNullOrBlank()) {
            if (logIssues) logger.warn("Asset index {} is missing for runtime assets check", assetIndexId)
            return false
        }

        val indexObj = runCatching { json.parseToJsonElement(indexText).jsonObject }.getOrNull()
        if (indexObj == null) {
            if (logIssues) logger.warn("Asset index {} is invalid JSON", assetIndexId)
            return false
        }

        val objects = indexObj["objects"]?.jsonObject
        if (objects == null) {
            if (logIssues) logger.warn("Asset index {} has no objects map", assetIndexId)
            return false
        }

        val objectsDir = assetsDir.resolve("objects")
        for ((_, value) in objects) {
            val obj = value as? JsonObject ?: return false
            val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: return false
            if (hash.length < 2) return false
            val expectedSize = obj["size"]?.jsonPrimitive?.longOrNull
            val file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash)
            val size = file.sizeOrNull()
            if (size == null || size <= 0L) return false
            if (expectedSize != null && expectedSize > 0L && size != expectedSize) return false
        }

        return true
    }

    private fun beginRuntimePreparation(project: ProjectBase): RuntimePreparationContext? {
        val scope = scopeOf(project.path)
        val ctx = RuntimePreparationContext(
            projectScope = scope,
            projectName = project.name,
            startedAtEpochMs = System.currentTimeMillis()
        )
        val existing = runtimePreparingScopes.putIfAbsent(scope, ctx)
        if (existing != null) {
            logger.debug("Runtime preparation already active for project '{}'", project.name)
            return null
        }
        emitRuntimePreparation(RuntimePreparationEvent(RuntimePreparationEvent.Type.Started, ctx))
        return ctx
    }

    private fun endRuntimePreparation(context: RuntimePreparationContext) {
        val removed = runtimePreparingScopes.remove(context.projectScope, context)
        if (removed) {
            emitRuntimePreparation(RuntimePreparationEvent(RuntimePreparationEvent.Type.Finished, context))
        }
    }

    private fun emitRuntimePreparation(event: RuntimePreparationEvent) {
        runtimePreparationListeners.forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                logger.warn("Runtime preparation listener failed", t)
            }
        }
    }

    private fun scopeOf(path: VPath): String {
        val abs = path.toAbsolute().normalize()
        return try {
            val jPath = abs.toJPath()
            val canonical = if (Files.exists(jPath)) {
                jPath.toRealPath()
            } else {
                jPath.toAbsolutePath().normalize()
            }
            canonical.toString().trim()
        } catch (_: Throwable) {
            abs.toString().trim()
        }
    }
}
