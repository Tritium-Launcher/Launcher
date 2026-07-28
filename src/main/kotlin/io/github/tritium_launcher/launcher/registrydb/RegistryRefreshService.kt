/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.registrydb

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.formatDurationMs
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.io.watch
import io.github.tritium_launcher.api.logger
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
    private val projectWatchers = ConcurrentHashMap<String, Pair<ProjectBase, Closeable>>()
    private var eventListener: Job? = null

    private val _dbUpdated = MutableSharedFlow<ProjectBase>(extraBufferCapacity = 16)
    val dbUpdated = _dbUpdated.asSharedFlow()

    fun isRefreshing(project: ProjectBase): Boolean = refreshJobs.containsKey(project.projectDir.toString())

    fun isBuilding(project: ProjectBase): Boolean =
        refreshJobs.containsKey(project.projectDir.toString()) ||
            refreshJobs.containsKey("${project.projectDir.toString()}:build")

    /**
     * Starts watching for changes in the project's registryObjs directory and game_registry.db.
     */
    fun startWatching(project: ProjectBase) {
        val key = project.projectDir.toString()
        if (projectWatchers.containsKey(key)) return

        val root = project.projectDir.expandHome().resolve("registryObjs").toAbsolute()
        if (!root.exists()) {
            runCatching { root.mkdirs() }
        }

        if (root.exists()) {
            val watcher = root.watch({ event ->
                handleWatchEvent(project, event)
            })
            projectWatchers[key] = project to watcher
            logger.info("Started watching registry for project '{}'", project.name)
        }
        ensureEventListener()
    }

    fun stopWatching(project: ProjectBase) {
        val key = project.projectDir.toString()
        projectWatchers.remove(key)?.second?.close()
    }

    private fun ensureEventListener() {
        if (eventListener?.isActive == true) return
        eventListener = scope.launch {
            CompanionBridge.events.collect { event ->
                if (event.action == "dump_complete") {
                    logger.info("Received dump_complete event from Companion mod")
                    projectWatchers.forEach { (_, pair) ->
                        triggerBuild(pair.first)
                    }
                }
            }
        }
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

        if (refreshJobs.putIfAbsent(key, Job()) != null) {
            logger.info("Refresh already in progress for '{}'", project.name)
            return
        }

        scope.launch {
            try {
                performRefresh(project)
            } finally {
                refreshJobs.remove(key)
            }
        }
    }

    fun triggerBuild(project: ProjectBase) {
        if (refreshJobs.containsKey(project.projectDir.toString())) return

        val key = project.projectDir.toString() + ":build"
        if (refreshJobs.putIfAbsent(key, Job()) != null) return

        scope.launch {
            try {
                val locations = resolveRegistryLocations(project)
                if (runRegistryBuilder(project, locations)) {
                    RegistryDatabase.invalidateCachedConnection()
                    _dbUpdated.emit(project)
                }
            } finally {
                refreshJobs.remove(key)
            }
        }
    }

    private suspend fun performRefresh(project: ProjectBase) {
        val taskId = ProjectTaskMngr.start(
            projectPath = project.projectDir,
            title = "Refreshing Registry",
            detail = "Triggering dump from game...",
            progressPercent = 0.0
        )
        val totalStart = System.currentTimeMillis()

        try {
            // 1. Trigger dump
            val sendStart = System.currentTimeMillis()
            val bridgeResponse = CompanionBridge.sendCommand("dumpRegistry", timeoutMs = 10_000)
            if (bridgeResponse.ok) {
                logger.info("Dump registry command dispatched in {}", formatDurationMs(System.currentTimeMillis() - sendStart))
            } else {
                logger.warn("Dump registry dispatch result (may still proceed): {}", bridgeResponse.message)
            }

            ProjectTaskMngr.updateProgress(taskId, 20.0)
            ProjectTaskMngr.update(taskId, detail = "Waiting for dump to complete...")

            // 2. Wait for a complete dump snapshot instead of waiting for a DB that does not exist yet.
            val waitStart = System.currentTimeMillis()
            val dumpReady = awaitCompleteDump(project, timeoutMs = 10 * 60 * 1000L)  // 10 min for large packs
            if (!dumpReady) {
                ProjectTaskMngr.update(taskId, detail = "Timed out waiting for registry dump.")
                delay(3000.milliseconds)
                return
            }
            logger.info("Dump completed in {}", formatDurationMs(System.currentTimeMillis() - waitStart))

            ProjectTaskMngr.updateProgress(taskId, 50.0)
            ProjectTaskMngr.update(taskId, detail = "Building registry database (Rust)...")

            // 3. Run registry-builder (manual run to ensure progress tracking)
            val buildStart = System.currentTimeMillis()
            val locations = resolveRegistryLocations(project)
            val buildOk = runRegistryBuilder(project, locations)
            if (!buildOk) {
                ProjectTaskMngr.update(taskId, detail = "Failed to build registry database.")
                delay(3000.milliseconds)
                return
            }
            logger.info("Registry database built in {}", formatDurationMs(System.currentTimeMillis() - buildStart))
            RegistryDatabase.invalidateCachedConnection()

            val totalTime = System.currentTimeMillis() - totalStart
            logger.info("Registry refresh completed in {} total", formatDurationMs(totalTime))

            ProjectTaskMngr.updateProgress(taskId, 100.0)
            ProjectTaskMngr.update(taskId, detail = "Registry refresh complete (${formatDurationMs(totalTime)}).")
            
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
        val binaryPath = builderPath.resolve("target/release/registry-builder")
        val cmd = listOf(
            binaryPath.toAbsolute().expandHome().toString(),
            "--input", locations.registryObjs.toAbsolute().expandHome().toString(),
            "--output", locations.database.toAbsolute().expandHome().toString()
        )
        
        return try {
            logger.info("Running registry-builder: {}", cmd.joinToString(" "))
            val pb = ProcessBuilder(cmd)
            pb.directory(builderPath.toJFile())
            pb.redirectErrorStream(true)
            pb.environment().putAll(System.getenv())

            val process = pb.start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var exitCode: Int? = null

            while (true) {
                line = reader.readLine()
                if (line == null) {
                    exitCode = process.waitFor()
                    break
                }
                logger.info("registry-builder: {}", line)
            }

            if (exitCode != 0) {
                logger.error("registry-builder failed with exit code {}", exitCode)
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

        val initialSnapshotId = readLatestPointer(root.resolve("latest.json"))?.snapshotId

        return withTimeoutOrNull(timeoutMs.milliseconds) {
            callbackFlow {
                var lastSeenId: String? = initialSnapshotId

                fun check() {
                    val latestPointer = readLatestPointer(root.resolve("latest.json"))
                    if (latestPointer != null) {
                        if (latestPointer.snapshotId == lastSeenId) {
                            val manifestPath = root.resolve(latestPointer.path).resolve("manifest.json").toAbsolute()
                            val manifest = readManifest(manifestPath)
                            if (manifest?.complete == true) {
                                trySend(true)
                                close()
                            }
                            return
                        }
                        lastSeenId = latestPointer.snapshotId
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
                        if (event.path.fileName() == "latest.json") {
                            check()
                        }
                    }
                )


                val pollJob = launch {
                    while (isActive) {
                        delay(5000.milliseconds)
                        check()
                    }
                }

                awaitClose {
                    watcher.close()
                    pollJob.cancel()
                }
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
        val root = project.projectDir.expandHome().resolve("registryObjs").toAbsolute()
        return RegistryLocations(
            registryObjs = root,
            database = root.resolve("game_registry.db")
        )
    }

    private data class RegistryLocations(
        val registryObjs: VPath,
        val database: VPath
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
