package io.github.tritium_launcher.launcher.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Shared bounded IO dispatchers to avoid oversaturating global IO threads during bulk work.
 */
object IODispatchers {
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

    val FileIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism((cores * 2).coerceAtMost(16))
    val BulkIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism((cores * 4).coerceAtMost(24))
    val WatchIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
}
