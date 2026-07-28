/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.search

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.search.IndexableDocument
import io.github.tritium_launcher.api.search.SearchIndexContributor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension

class ProjectIndexWatcher(
    private val project: ProjectBase,
    private val contributors: List<SearchIndexContributor>
) {
    private val log = logger()
    private val debounceMs = 500L
    private val watcherDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchJob: Job? = null
    private val changeChannel = Channel<String>(Channel.UNLIMITED)
    private val excludedDirs = setOf(".git", "build", "run", "logs", "crash-reports", "node_modules")
    private val binaryExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "ogg", "mp3", "wav",
        "mp4", "avi", "mov", "class", "jar", "zip", "7z", "gz", "dll", "so", "dylib", "ico")

    @Volatile
    private var running = false
    private var watchService: WatchService? = null

    fun start() {
        watchJob = scope.launch {
            coroutineScope {
                // Initial full index
                launch {
                    val projectPath = project.projectDir.toAbsolute().toString()
                    val batchSize = 1000
                    contributors.forEach { contributor ->
                        log.debug("Initial index starting contributor: {}", contributor.id)
                        var count = 0
                        try {
                            val batch = mutableListOf<IndexableDocument>()
                            contributor.contributeDocuments(projectPath)
                                .buffer(Channel.UNLIMITED)
                                .collect { doc ->
                                    batch.add(doc)
                                    if (batch.size >= batchSize) {
                                        TritiumSearchService.addDocuments(batch.toList())
                                        count += batch.size
                                        batch.clear()
                                    }
                                }
                            if (batch.isNotEmpty()) {
                                TritiumSearchService.addDocuments(batch.toList())
                                count += batch.size
                                batch.clear()
                            }
                            TritiumSearchService.commit()
                            log.debug("Initial index finished contributor {} ({} docs)", contributor.id, count)
                        } catch (e: Exception) {
                            log.error("Contributor {} failed during initial index after {} docs", contributor.id, count, e)
                        }
                    }
                    log.debug("Initial index complete for project: {}", project.name)
                }

                launch(watcherDispatcher) {
                    running = true
                    watchLoop()
                }

                launch {
                    val pending = mutableMapOf<String, Job>()
                    for (filePath in changeChannel) {
                        pending[filePath]?.cancel()
                        pending[filePath] = launch {
                            delay(debounceMs)
                            try {
                                handleChange(filePath)
                            } catch (e: Exception) {
                                log.error("Error handling change for {}", filePath, e)
                            }
                            pending.remove(filePath)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        watchService?.close()
        scope.cancel()
    }

    private fun watchLoop() {
        val ws = try { FileSystems.getDefault().newWatchService() } catch (e: Exception) {
            log.error("Failed to create watch service", e)
            return
        }
        watchService = ws
        val root = project.projectDir.toJPath()
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel = root.relativize(dir)
                    val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name in excludedDirs) return FileVisitResult.SKIP_SUBTREE
                    if (rel.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                    try {
                        dir.register(ws,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE)
                    } catch (_: Exception) {}
                    return FileVisitResult.CONTINUE
                }
            })

            while (running) {
                try {
                    val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    for (event in key.pollEvents()) {
                        val relPath = event.context() as? Path ?: continue
                        val fullPath = (key.watchable() as Path).resolve(relPath)
                        val name = fullPath.fileName?.toString() ?: continue
                        if (name in excludedDirs) continue
                        if (name.startsWith(".")) continue
                        val ext = fullPath.extension.lowercase()
                        if (binaryExtensions.contains(ext)) continue
                        if (!Files.exists(fullPath)) continue
                        changeChannel.trySend(fullPath.toString())
                    }
                    key.reset()
                } catch (_: ClosedWatchServiceException) {
                    break
                }
            }
        } catch (e: Exception) {
            log.error("File watcher error", e)
        } finally {
            try { ws.close() } catch (_: Exception) {}
            watchService = null
        }
    }

    private suspend fun handleChange(filePath: String) {
        val projectPath = project.projectDir.toAbsolute().toString()
        TritiumSearchService.deleteDocument("file:$filePath")

        for (contributor in contributors) {
            val flow = contributor.contributeFile(projectPath, filePath)
            if (flow != null) {
                flow.collect { doc ->
                    TritiumSearchService.addDocuments(listOf(doc))
                }
                break
            }
        }
        TritiumSearchService.commit()
    }
}
