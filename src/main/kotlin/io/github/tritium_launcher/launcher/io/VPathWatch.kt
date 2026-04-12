package io.github.tritium_launcher.launcher.io

import io.github.tritium_launcher.launcher.io.VPath.Companion.parse
import io.github.tritium_launcher.launcher.logger
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

private val watchLogger = logger(VPath::class)

data class VWatchOptions(
    val recursive: Boolean = false,
    val followSymLinks: Boolean = false,
    val kinds: List<WatchEvent.Kind<Path>> = listOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
    val pollTimeoutMillis: Long = 0L,
    val flowBufferCapacity: Int = 512,
    val dropOldestOnBackpressure: Boolean = true,
    val rescanOnOverflow: Boolean = true,
    val logDroppedFlowEvents: Boolean = true
)

data class VWatchEvent(
    val kind: Kind,
    val path: VPath,
    val ctxPath: String? = null
) {
    enum class Kind { Create, Modify, Delete, Overflow }

    companion object {
        internal fun fromWatchEvent(kind: WatchEvent.Kind<Path>, base: VPath, ctx: WatchEvent<Path>?): VWatchEvent {
            return when(kind) {
                ENTRY_CREATE -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Create, p, ctx)
                }
                ENTRY_MODIFY -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Modify, p, ctx)
                }
                ENTRY_DELETE -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Delete, p, ctx)
                }

                OVERFLOW -> {
                    VWatchEvent(Kind.Overflow, base, null)
                }
                else -> VWatchEvent(Kind.Overflow, base, ctx?.context().toString())
            }
        }
    }
}

class VPathWatcher internal constructor(
    private val service: WatchService,
    private val job: Job,
    private val registeredKeys: Map<WatchKey, VPath>
): Closeable {
    override fun close() {
        try {
            job.cancel()
        } catch (_: Exception) {}
        try {
            service.close()
        } catch (_: Exception) {}
    }

    fun registeredDirectories(): List<VPath> = registeredKeys.values.toList()
}

private fun registerDirToService(dir: Path, service: WatchService, kinds: List<WatchEvent.Kind<Path>>): WatchKey {
    return dir.register(service, kinds.toTypedArray())
}

@Suppress("UNCHECKED_CAST")
private fun asPathEvent(event: WatchEvent<*>): WatchEvent<Path>? = event as? WatchEvent<Path>

@Suppress("UNCHECKED_CAST")
private fun asPathKind(kind: WatchEvent.Kind<*>): WatchEvent.Kind<Path> = kind as WatchEvent.Kind<Path>

fun VPath.watch(
    callback: (VWatchEvent) -> Unit,
    options: VWatchOptions = VWatchOptions(),
    ctx: CoroutineContext = IODispatchers.WatchIO
): VPathWatcher {
    val rootJ = this.toJPath()

    if(!Files.exists(rootJ) || !Files.isDirectory(rootJ)) {
        throw IllegalArgumentException("Path is not an existing directory: '$this'")
    }

    val service = FileSystems.getDefault().newWatchService()

    val registered = HashMap<WatchKey, VPath>()
    val registeredDirs = HashSet<Path>()

    fun register(startPath: Path) {
        if(!Files.isDirectory(startPath)) return
        Files.walkFileTree(startPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                if(!options.followSymLinks && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                val normalized = dir.toAbsolutePath().normalize()
                if (!registeredDirs.add(normalized)) return FileVisitResult.CONTINUE
                val key = registerDirToService(dir, service, options.kinds)
                registered[key] = parse(dir.toString()).toAbsolute().normalize()
                return FileVisitResult.CONTINUE
            }
        })
    }

    if(options.recursive) {
        register(rootJ)
    } else {
        val key = registerDirToService(rootJ, service, options.kinds)
        registered[key] = this.toAbsolute()
        registeredDirs.add(rootJ.toAbsolutePath().normalize())
    }

    val scope = CoroutineScope(ctx + SupervisorJob())
    val job = scope.launch {
        try {
            while(isActive) {
                val key = try {
                    if (options.pollTimeoutMillis > 0) {
                        runInterruptible { service.poll(options.pollTimeoutMillis, TimeUnit.MILLISECONDS) }
                    } else {
                        runInterruptible { service.take() }
                    }
                } catch (_: InterruptedException) {
                    null
                } catch (_: ClosedWatchServiceException) {
                    null
                } ?: continue

                val watchedBaseV = registered[key] ?: this@watch.toAbsolute()

                for(e in key.pollEvents()) {
                    val ePath = asPathEvent(e)
                    val kind = e.kind()
                    val ve = VWatchEvent.fromWatchEvent(asPathKind(kind), watchedBaseV, ePath)
                    try {
                        callback(ve)
                    } catch (t: Throwable) {
                        watchLogger.error("Exception in watch callback for event {}", ve, t)
                    }

                    if (options.recursive && kind == OVERFLOW && options.rescanOnOverflow) {
                        runCatching { register(watchedBaseV.toJPath()) }
                            .onFailure { watchLogger.warn("Failed to rescan watch tree after overflow for {}", watchedBaseV, it) }
                        continue
                    }

                    if(options.recursive && kind == ENTRY_CREATE) {
                        val ctx = ePath?.context()
                        if(ctx != null) {
                            val childV = watchedBaseV.resolve(ctx.toString()).toAbsolute()
                            try {
                                val childJ = childV.toJPath()
                                if(Files.isDirectory(childJ) && (!Files.isSymbolicLink(childJ) || options.followSymLinks)) {
                                    register(childJ)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                val valid = key.reset()
                if(!valid) {
                    val removed = registered.remove(key)
                    if (removed != null) {
                        runCatching { registeredDirs.remove(removed.toJPath().toAbsolutePath().normalize()) }
                    }
                    if(registered.isEmpty()) break
                }
            }
        } catch (t: Throwable) {
            watchLogger.warn("VPath watch loop terminated due to error", t)
        } finally {

        }
    }

    return VPathWatcher(service, job, registered)
}

fun VPath.watchAsFlow(options: VWatchOptions = VWatchOptions()): Flow<VWatchEvent> = callbackFlow {
    val service = FileSystems.getDefault().newWatchService()
    val registered = HashMap<WatchKey, VPath>()
    val registeredDirs = HashSet<Path>()
    var droppedEvents = 0L

    fun sendEvent(event: VWatchEvent) {
        val sent = trySend(event).isSuccess
        if (sent) {
            if (droppedEvents > 0 && options.logDroppedFlowEvents) {
                watchLogger.warn(
                    "Watch flow recovered after dropping {} events for {}",
                    droppedEvents,
                    this@watchAsFlow.toAbsolute()
                )
            }
            droppedEvents = 0L
            return
        }

        droppedEvents++
        if (options.logDroppedFlowEvents && (droppedEvents == 1L || droppedEvents % 128L == 0L)) {
            watchLogger.warn(
                "Watch flow dropped {} events for {} due to backpressure",
                droppedEvents,
                this@watchAsFlow.toAbsolute()
            )
        }
    }

    fun register(startPath: Path) {
        if (!Files.isDirectory(startPath)) return
        Files.walkFileTree(startPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!options.followSymLinks && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                val normalized = dir.toAbsolutePath().normalize()
                if (!registeredDirs.add(normalized)) return FileVisitResult.CONTINUE
                val key = registerDirToService(dir, service, options.kinds)
                registered[key] = parse(dir.toString()).toAbsolute().normalize()
                return FileVisitResult.CONTINUE
            }
        })
    }

    if (options.recursive) {
        register(this@watchAsFlow.toJPath())
    } else {
        val key = registerDirToService(this@watchAsFlow.toJPath(), service, options.kinds)
        registered[key] = this@watchAsFlow.toAbsolute()
        registeredDirs.add(this@watchAsFlow.toJPath().toAbsolutePath().normalize())
    }

    val watcherJob = CoroutineScope(IODispatchers.WatchIO + SupervisorJob()).launch {
        try {
            while (isActive) {
                val key = try {
                    if (options.pollTimeoutMillis > 0) {
                        runInterruptible { service.poll(options.pollTimeoutMillis, TimeUnit.MILLISECONDS) }
                    } else {
                        runInterruptible { service.take() }
                    }
                } catch (_: InterruptedException) {
                    null
                } catch (_: ClosedWatchServiceException) {
                    null
                } ?: continue

                val base = registered[key] ?: this@watchAsFlow.toAbsolute()

                for (ev in key.pollEvents()) {
                    val evPath = asPathEvent(ev)
                    val kind = ev.kind()
                    val vpe = VWatchEvent.fromWatchEvent(asPathKind(kind), base, evPath)
                    sendEvent(vpe)

                    if (options.recursive && kind == OVERFLOW && options.rescanOnOverflow) {
                        runCatching { register(base.toJPath()) }
                            .onFailure { watchLogger.warn("Failed to rescan watch tree after overflow for {}", base, it) }
                        continue
                    }

                    if (options.recursive && kind == ENTRY_CREATE) {
                        val ctx = evPath?.context()
                        if (ctx != null) {
                            val childVp = base.resolve(ctx.toString()).toAbsolute()
                            try {
                                val childJvm = childVp.toJPath()
                                if (Files.isDirectory(childJvm) && (!Files.isSymbolicLink(childJvm) || options.followSymLinks)) {
                                    register(childJvm)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                val valid = key.reset()
                if (!valid) {
                    val removed = registered.remove(key)
                    if (removed != null) {
                        runCatching { registeredDirs.remove(removed.toJPath().toAbsolutePath().normalize()) }
                    }
                    if (registered.isEmpty()) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            close(t)
        } finally {
        }
    }

    awaitClose {
        try { watcherJob.cancel() } catch (_: Exception) {}
        try { service.close() } catch (_: Exception) {}
    }
}
    .buffer(
        capacity = options.flowBufferCapacity.coerceAtLeast(1),
        onBufferOverflow = if (options.dropOldestOnBackpressure) BufferOverflow.DROP_OLDEST else BufferOverflow.SUSPEND
    )
    .flowOn(IODispatchers.WatchIO)
