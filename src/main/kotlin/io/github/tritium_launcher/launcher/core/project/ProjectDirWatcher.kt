/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.api.UIDispatcher
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.io.VWatchOptions
import io.github.tritium_launcher.api.io.watchAsFlow
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.debugLogging
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches a project directory and debounce change notifications.
 *
 * @param rootDir Project root to watch.
 * @param onChangeDispatcher Dispatcher for UI callbacks.
 */
class ProjectDirWatcher(
    private val rootDir: VPath,
    private val onChangeDispatcher: CoroutineDispatcher = UIDispatcher,
) {
    private val logger = logger()
    private fun debug(msg: String, throwable: Throwable, condition: Boolean = debugLogging) { if(condition) logger.debug(msg, throwable) }
    private fun debug(msg: String, condition: Boolean = debugLogging) { if(condition) logger.debug(msg) }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /**
     * Start watching the directory.
     *
     * @param onChange Called after a debounced change.
     * @param filter Optional filter — events that return false are dropped.
     * @param debounceMillis Debounce interval in milliseconds.
     */
    fun start(onChange: (VWatchEvent) -> Unit, filter: ((VWatchEvent) -> Boolean)? = null, debounceMillis: Long = 200L) {
        debug("Starting ProjectDirWatcher for ${rootDir.toAbsolute()}")

        job?.cancel()
        job = scope.launch {
            var debounceJob: Job? = null
            try {
                val flow = rootDir.watchAsFlow(VWatchOptions(true))

                flow.collect { e ->
                    if (filter != null && !filter(e)) return@collect

                    if(e.kind == VWatchEvent.Kind.Overflow) {
                        debounceJob?.cancel()
                        debounceJob = null
                        withContext(onChangeDispatcher) { onChange(e) }
                        return@collect
                    }

                    debounceJob?.cancel()
                    debounceJob = launch {
                        delay(debounceMillis.milliseconds)
                        withContext(onChangeDispatcher) { onChange(e) }
                    }
                }
            } catch (e: CancellationException) {
                debug("ProjectDirWatcher cancelled for '$rootDir'", e)
            } catch (t: Throwable) {
                logger.warn("ProjectDirWatcher loop terminated due to exception", t)
            }
        }
    }

    /**
     * Stop watching the directory.
     */
    fun stop() {
        debug("Stopping ProjectDirWatcher for '$rootDir'")
        try {
            scope.cancel()
            job?.cancel()
            job = null
        } catch (e: Exception) {
            logger.warn("Exception stopping ProjectDirWatcher", e)
        }
    }
}
