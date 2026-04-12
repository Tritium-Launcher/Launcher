package io.github.tritium_launcher.launcher.bootstrap

import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth
import io.github.tritium_launcher.launcher.ktor.BackgroundTaskQueue
import io.github.tritium_launcher.launcher.mainLogger
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun runLowPriorityTasks() {
    initUserAgent()
    scheduleRuntimeCacheMaintenance()
}

internal suspend fun initUserAgent() = enqueueGlobalBackgroundTask {
    mainLogger.info("User-Agent: ${ClientIdentity.userAgent}")
}

internal suspend fun scheduleRuntimeCacheMaintenance() = enqueueGlobalBackgroundTask {
    runCatching { MicrosoftAuth.runSharedCacheMaintenanceIfDue() }
        .onFailure { mainLogger.debug("Runtime cache maintenance task failed", it) }
}

internal val globalBackgroundTaskQueue: BackgroundTaskQueue = BackgroundTaskQueue("tritium-low", 1024, 1, 250L.milliseconds)

internal suspend fun enqueueGlobalBackgroundTask(task: suspend () -> Unit) {
    globalBackgroundTaskQueue.enqueue(task)
}
