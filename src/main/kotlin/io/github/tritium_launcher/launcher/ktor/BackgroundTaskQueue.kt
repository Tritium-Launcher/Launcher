package io.github.tritium_launcher.launcher.ktor

import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Background task queue backed by a coroutine channel and a fixed thread pool.
 *
 * Tasks run on daemon, low-priority threads with up to [maxConcurrency] workers. Task failures are
 * logged and do not stop the queue. When [betweenTaskDelay] is non-zero, each worker sleeps after
 * completing a task to throttle throughput.
 *
 * @param name Thread name prefix for worker threads.
 * @param bufferCap Channel capacity for queued tasks; use [Channel.UNLIMITED] for an unbounded queue.
 * @param maxConcurrency Number of worker threads executing tasks concurrently.
 * @param betweenTaskDelay Delay applied after each task per worker.
 */
class BackgroundTaskQueue(
    private val name: String = "bg-queue",
    bufferCap: Int = Channel.UNLIMITED,
    maxConcurrency: Int = 1,
    private val betweenTaskDelay: Duration = 0.seconds
): AutoCloseable {

    private val channel: Channel<suspend () -> Unit> = Channel(bufferCap)
    private val executor = Executors.newFixedThreadPool(
        maxConcurrency
    ) { runnable ->
        Thread(runnable, "$name-worker").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val workerJobs = mutableListOf<Job>()
    private val isClosing = CompletableDeferred<Unit>()

    private val logger = logger()

    init {
        repeat(maxConcurrency) { idx ->
            val job = scope.launch {
                consumeTasks(channel, idx)
            }
            workerJobs += job
        }
    }

    private suspend fun consumeTasks(ch: ReceiveChannel<suspend () -> Unit>, workerIdx: Int) {
        try {
            ch.consumeEach { task ->
                try {
                    try {
                        task()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        logger.error("[$name][worker-$workerIdx] task failed", t)
                    }
                } finally {
                    if(betweenTaskDelay.inWholeMilliseconds != 0L) {
                        delay(betweenTaskDelay)
                    }
                }
            }
        } catch (ce: CancellationException) {

        } finally {

        }
    }

    /**
     * Enqueue a task, suspending if the queue is full.
     *
     * @throws IllegalStateException if shutdown/close has started.
     */
    suspend fun enqueue(task: suspend () -> Unit) {
        if(isClosing.isCompleted) throw IllegalStateException("BackgroundTaskQueue is closing")
        channel.send(task)
    }

    /**
     * Enqueue a task without blocking; falls back to a coroutine send if the queue is full.
     *
     * @throws IllegalStateException if shutdown/close has started.
     */
    fun enqueueAndForget(task: suspend () -> Unit) {
        if(isClosing.isCompleted) throw IllegalStateException("BackgroundTaskQueue is closing")
        val offered = channel.trySend(task)
        if(offered.isFailure) {
            scope.launch { channel.send(task) }
        }
    }

    /**
     * @param drain True = Attempt to process all queued tasks before returning. False = Immediate cancel of pending tasks
     */
    suspend fun shutdown(drain: Boolean = true, timeout: Duration = 10.seconds) {
        channel.close()

        if(!drain) {
            scope.cancel()
            isClosing.complete(Unit)
            executor.shutdownNow()
            return
        }

        try {
            withTimeout(timeout) {
                workerJobs.joinAll()
            }
        } catch (_: TimeoutCancellationException) {
            scope.cancel()
        } finally {
            isClosing.complete(Unit)
            executor.shutdown()
        }
    }

    /**
     * Immediately cancels workers, closes the channel, and shuts down threads.
     *
     * Use [shutdown] for a graceful drain.
     */
    override fun close() {
        scope.cancel()
        channel.close()
        executor.shutdownNow()
    }
}
