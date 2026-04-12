package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.coroutines.UIDispatcher
import io.github.tritium_launcher.launcher.debugLogging
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.VWatchEvent
import io.github.tritium_launcher.launcher.io.VWatchOptions
import io.github.tritium_launcher.launcher.io.watchAsFlow
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.*

/**
 * Watches a project directory and debounces change notifications.
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

    private var job: Job? = null

    /**
     * Start watching the directory.
     *
     * @param onChange Called after a debounced change.
     * @param debounceMillis Debounce interval in milliseconds.
     */
    fun start(onChange: () -> Unit, debounceMillis: Long = 200L) {
        debug("Starting ProjectDirWatcher for ${rootDir.toAbsolute()}")

        job?.cancel()
        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var debounceJob: Job? = null
            try {
                val flow = rootDir.watchAsFlow(VWatchOptions(true))

                flow.collect { e ->
                    if(e.kind == VWatchEvent.Kind.Overflow) {
                        debounceJob?.cancel()
                        debounceJob = null
                        withContext(onChangeDispatcher) { onChange() }
                        return@collect
                    }

                    debounceJob?.cancel()
                    debounceJob = launch {
                        delay(debounceMillis)
                        withContext(onChangeDispatcher) { onChange() }
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
            job?.cancel()
            job = null
        } catch (e: Exception) {
            logger.warn("Exception stopping ProjectDirWatcher", e)
        }
    }
}
