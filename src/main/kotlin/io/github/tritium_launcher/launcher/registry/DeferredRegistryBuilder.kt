package io.github.tritium_launcher.launcher.registry

import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Defers registry consumption until it is frozen, then builds from a stable snapshot.
 *
 * Registries are populated by extensions and later frozen by bootstrap. UI code often needs
 * the complete set (e.g., file type matchers) and should not render partial data while
 * extensions are still registering. This helper waits until the registry is frozen and then
 * invokes [onBuild] on the GUI thread with a full snapshot.
 *
 * @param registry Registry to wait on.
 * @param scope Coroutine scope for polling; defaults to a background scope.
 * @param pollInterval How often to check for freeze completion.
 * @param onBuild Callback invoked with a snapshot once the registry is frozen.
 */
class DeferredRegistryBuilder<T: Registrable>(
    private val registry: Registry<T>,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val pollInterval: Duration = 100.toDuration(DurationUnit.MILLISECONDS),
    private val onBuild: (List<T>) -> Unit
) {
    private val logger = logger()

    private val accumulated = CopyOnWriteArrayList<T>()
    private val listener = object : RegistryListener<T> {
        override fun onRegister(fullId: String, entry: T) {
            accumulated.add(entry)
        }
    }

    init {
        if(registry.isFrozen) {
            onBuild(registry.all().toList())
        } else {
            registry.addListener(listener)
            scope.launch {
                try {
                    while (!registry.isFrozen) {
                        delay(pollInterval)
                    }
                    val snapshot = registry.all().toList()
                    runOnGuiThread {
                        onBuild(snapshot)
                    }
                } catch (t: Throwable) {
                    logger.warn("Polling failed", t)
                } finally {
                    registry.removeListener(listener)
                }
            }
        }
    }
}
