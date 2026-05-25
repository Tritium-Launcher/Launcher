package io.github.tritium_launcher.launcher.registrydb

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.VWatchEvent
import io.github.tritium_launcher.launcher.io.watch
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.CompanionBridge
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Orchestrates the registry dump and database build pipeline.
 */
object RegistryRefreshService {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshJobs = ConcurrentHashMap<String, Job>()
    private val projectWatchers = ConcurrentHashMap<String, Closeable>()
    
    private val _dbUpdated = MutableSharedFlow<ProjectBase>(extraBufferCapacity = 16)
    val dbUpdated = _dbUpdated.asSharedFlow()

    fun isRefreshing(project: ProjectBase): Boolean = refreshJobs.containsKey(project.projectDir.toString())

    /**
     * Starts watching for changes in the project's registryObjs directory and game_registry.db.
     */
    fun startWatching(project: ProjectBase) {
        val key = project.projectDir.toString()
        if (projectWatchers.containsKey(key)) return

        val root = project.projectDir.resolve("registryObjs").toAbsolute()
        if (!root.exists()) {
            runCatching { root.mkdirs() }
        }

        if (root.exists()) {
            val watcher = root.watch({ event ->
                handleWatchEvent(project, event)
            })
            projectWatchers[key] = watcher
            logger.info("Started watching registry for project '{}'", project.name)
        }
    }

    fun stopWatching(project: ProjectBase) {
        val key = project.projectDir.toString()
        projectWatchers.remove(key)?.close()
    }

    private fun handleWatchEvent(project: ProjectBase, event: VWatchEvent) {
        val fileName = event.path.fileName()
        if (fileName == "latest.json" && (event.kind == VWatchEvent.Kind.Create || event.kind == VWatchEvent.Kind.Modify)) {
            logger.info("Detected change in latest.json for '{}', triggering build", project.name)
            triggerBuild(project)
        }
    }

    fun triggerRefresh(project: ProjectBase) {
        val key = project.projectDir.toString()
        if (refreshJobs.containsKey(key)) {
            logger.info("Refresh already in progress for '{}'", project.name)
            return
        }

        val job = scope.launch {
            try {
                performRefresh(project)
            } finally {
                refreshJobs.remove(key)
            }
        }
        refreshJobs[key] = job
    }

    private fun triggerBuild(project: ProjectBase) {
        val key = project.projectDir.toString() + ":build"
        if (refreshJobs.containsKey(key) || refreshJobs.containsKey(project.projectDir.toString())) return

        val job = scope.launch {
            try {
                val locations = resolveRegistryLocations(project)
                if (runRegistryBuilder(project, locations)) {
                    _dbUpdated.emit(project)
                }
            } finally {
                refreshJobs.remove(key)
            }
        }
        refreshJobs[key] = job
    }

    private suspend fun performRefresh(project: ProjectBase) {
        val taskId = ProjectTaskMngr.start(
            projectPath = project.projectDir,
            title = "Refreshing Registry",
            detail = "Triggering dump from game...",
            progressPercent = 0.0
        )

        try {
            // 1. Trigger dump
            val bridgeResponse = CompanionBridge.sendCommand("dumpRegistry")
            if (!bridgeResponse.ok) {
                ProjectTaskMngr.update(taskId, detail = "Failed to trigger dump: ${bridgeResponse.message}")
                delay(3000.milliseconds)
                return
            }

            ProjectTaskMngr.updateProgress(taskId, 20.0)
            ProjectTaskMngr.update(taskId, detail = "Waiting for dump to complete...")

            // 2. Wait for a complete dump snapshot instead of waiting for a DB that does not exist yet.
            val dumpReady = awaitCompleteDump(project, timeoutMs = 60_000)
            if (!dumpReady) {
                ProjectTaskMngr.update(taskId, detail = "Timed out waiting for registry dump.")
                delay(3000.milliseconds)
                return
            }

            ProjectTaskMngr.updateProgress(taskId, 50.0)
            ProjectTaskMngr.update(taskId, detail = "Building registry database (Rust)...")

            // 3. Run registry-builder (manual run to ensure progress tracking)
            val locations = resolveRegistryLocations(project)
            val buildOk = runRegistryBuilder(project, locations)
            if (!buildOk) {
                ProjectTaskMngr.update(taskId, detail = "Failed to build registry database.")
                delay(3000.milliseconds)
                return
            }

            ProjectTaskMngr.updateProgress(taskId, 100.0)
            ProjectTaskMngr.update(taskId, detail = "Registry refresh complete.")
            
            // 4. Notify UI (though the watcher might have already done it)
            _dbUpdated.emit(project)
            
            delay(1000.milliseconds)
        } catch (e: Exception) {
            logger.error("Registry refresh failed for '{}'", project.name, e)
            ProjectTaskMngr.update(taskId, detail = "Refresh failed: ${e.message}")
            delay(3000.milliseconds)
        } finally {
            ProjectTaskMngr.finish(taskId)
        }
    }

    private fun runRegistryBuilder(project: ProjectBase, locations: RegistryLocations): Boolean {
        val rootDir = VPath.get("").toAbsolute() // Project root
        val builderPath = rootDir.resolve("tools/registry-builder")
        
        val cmd = listOf(
            "cargo", "run", "--release", "--",
            "--input", locations.registryObjs.toAbsolute().toString(),
            "--output", locations.database.toAbsolute().toString(),
            "--typings-output", locations.typingsDb.toAbsolute().toString()
        )

        logger.info("Running registry-builder: {}", cmd.joinToString(" "))
        
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(builderPath.toJFile())
            pb.redirectErrorStream(true)
            val process = pb.start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                logger.error("registry-builder failed with exit code {}:\n{}", exitCode, output)
                false
            } else {
                logger.info("registry-builder finished successfully.")
                true
            }
        } catch (e: Exception) {
            logger.error("Failed to execute registry-builder", e)
            false
        }
    }

    private suspend fun awaitCompleteDump(project: ProjectBase, timeoutMs: Long): Boolean {
        val locations = resolveRegistryLocations(project)
        val root = locations.registryObjs

        return withTimeoutOrNull(timeoutMs.milliseconds) {
            callbackFlow {
                fun check() {
                    val latestPointer = readLatestPointer(root.resolve("latest.json"))
                    if (latestPointer != null) {
                        val manifestPath = root.resolve(latestPointer.path).resolve("manifest.json").toAbsolute()
                        val manifest = readManifest(manifestPath)
                        if (manifest?.complete == true) {
                            trySend(true)
                            close()
                        }
                    }
                }

                // Initial check
                check()

                val watcher = root.watch(
                    callback = { event: VWatchEvent ->
                        // Watch for latest.json changes in the root
                        if (event.path.fileName() == "latest.json") {
                            check()
                        }
                    }
                )

                awaitClose { watcher.close() }
            }.first()
        } ?: false
    }

    private fun readLatestPointer(path: VPath): RegistryLatestPointer? = runCatching {
        json.decodeFromString<RegistryLatestPointer>(path.readTextOrNull() ?: return null)
    }.onFailure { t ->
        logger.warn("Failed reading registry latest pointer '{}'", path.toAbsolute(), t)
    }.getOrNull()

    private fun readManifest(path: VPath): RegistryDumpManifest? = runCatching {
        json.decodeFromString<RegistryDumpManifest>(path.readTextOrNull() ?: return null)
    }.onFailure { t ->
        logger.warn("Failed reading registry manifest '{}'", path.toAbsolute(), t)
    }.getOrNull()

    private fun resolveRegistryLocations(project: ProjectBase): RegistryLocations {
        val root = project.projectDir.resolve("registryObjs").toAbsolute()
        return RegistryLocations(
            registryObjs = root,
            database = root.resolve("game_registry.db"),
            typingsDb = root.resolve("kubejs_typings.db")
        )
    }

    private data class RegistryLocations(
        val registryObjs: VPath,
        val database: VPath,
        val typingsDb: VPath
    )

    @Serializable
    private data class RegistryLatestPointer(
        val path: String,
        @SerialName("snapshotId")
        val snapshotId: String
    )

    @Serializable
    private data class RegistryDumpManifest(
        val complete: Boolean
    )
}
