/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.TritiumEventBus
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Files

/**
 * Tracks a single active game process per project path scope.
 */
object GameProcessMngr {
    private val logger = logger()
    private val lock = Any()
    private val trackedByScope = LinkedHashMap<String, TrackedProcess>()
    private val _events = MutableSharedFlow<GameProcessEvent>(replay = 0, extraBufferCapacity = 1)
    val events: SharedFlow<GameProcessEvent> = _events.asSharedFlow()
    private val _outputFlows = mutableMapOf<String, MutableSharedFlow<String>>()

    fun outputFlow(projectPath: VPath): SharedFlow<String> {
        val scope = scopeOf(projectPath)
        return synchronized(lock) {
            _outputFlows.getOrPut(scope) {
                MutableSharedFlow(replay = 1000, extraBufferCapacity = 500)
            }
        }.asSharedFlow()
    }

    fun outputFlow(project: ProjectBase): SharedFlow<String> = outputFlow(project.path)

    fun emitOutput(projectPath: VPath, line: String) {
        val scope = scopeOf(projectPath)
        synchronized(lock) {
            _outputFlows[scope]?.tryEmit(line)
        }
    }

    enum class Source {
        Launch,
        Attach
    }

    data class GameProcessContext(
        val projectScope: String,
        val projectName: String,
        val pid: Long,
        val isRunning: Boolean,
        val isAttached: Boolean,
        val source: Source,
        val attachedAtEpochMs: Long
    )
    
    /**
     * Events emitted during the lifecycle of a tracked game process.
     */
    sealed interface GameProcessEvent {
        val context: GameProcessContext

        data class Attached(override val context: GameProcessContext) : GameProcessEvent
        data class Detached(override val context: GameProcessContext) : GameProcessEvent
        data class Exited(override val context: GameProcessContext, val exitCode: Int) : GameProcessEvent
        data class KillRequested(override val context: GameProcessContext) : GameProcessEvent
        data class KillFailed(override val context: GameProcessContext) : GameProcessEvent
    }

    /**
     * Attach a launched Java [process] to [project] tracking.
     */
    fun attachLaunched(project: ProjectBase, process: Process): GameProcessContext {
        val scope = scopeOf(project.path)
        return attachInternal(
            scope = scope,
            projectName = project.name,
            handle = process.toHandle(),
            process = process,
            source = Source.Launch
        )
    }

    /**
     * Attach to an already-running process by [pid] for [project].
     */
    fun attachToPid(project: ProjectBase, pid: Long): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        val scope = scopeOf(project.path)
        attachInternal(scope, project.name, handle, process = null, source = Source.Attach)
        return true
    }

    /**
     * Detach process tracking for [project] without terminating the process.
     */
    fun detach(project: ProjectBase): Boolean = detach(project.path)

    /**
     * Detach process tracking for [projectPath] without terminating the process.
     */
    fun detach(projectPath: VPath): Boolean {
        val scope = scopeOf(projectPath)
        val removed = synchronized(lock) { trackedByScope.remove(scope) } ?: return false
        emit(GameProcessEvent.Detached(removed.toContext(isAttached = false)))
        return true
    }

    /**
     * Request process termination for [project].
     *
     * When [force] is true, a forced kill is attempted if graceful termination does not complete promptly.
     */
    fun kill(project: ProjectBase, force: Boolean = true): Boolean = kill(project.path, force)

    /**
     * Request process termination for [projectPath].
     */
    fun kill(projectPath: VPath, force: Boolean = true): Boolean {
        val scope = scopeOf(projectPath)
        return killByScope(scope, force)
    }

    /**
     * Request process termination for a canonical [projectScope].
     */
    fun killByScope(projectScope: String, force: Boolean = true): Boolean {
        val scope = projectScope.trim()
        val tracked = synchronized(lock) { trackedByScope[scope] } ?: return false
        emit(GameProcessEvent.KillRequested(tracked.toContext()))
        return try {
            if (tracked.process != null) {
                tracked.process.destroy()
                if (force && tracked.handle.isAlive) {
                    tracked.process.destroyForcibly()
                }
                true
            } else {
                if (force) {
                    val soft = tracked.handle.destroy()
                    if (!soft && tracked.handle.isAlive) tracked.handle.destroyForcibly() else soft
                } else {
                    tracked.handle.destroy()
                }
            }
        } catch (t: Throwable) {
            logger.warn("Failed to kill tracked game process (pid={})", tracked.handle.pid(), t)
            emit(GameProcessEvent.KillFailed(tracked.toContext()))
            false
        }
    }

    /**
     * Returns true if a tracked process is running for [project].
     */
    fun isActive(project: ProjectBase): Boolean = snapshot(project)?.isRunning == true

    /**
     * Suspends the game process for [project] by sending SIGSTOP.
     */
    fun suspend(project: ProjectBase): Boolean {
        val ctx = snapshot(project) ?: return false
        if (!ctx.isRunning) return false
        return try {
            ProcessBuilder("kill", "-STOP", ctx.pid.toString())
                .inheritIO()
                .start()
                .waitFor() == 0
        } catch (t: Throwable) {
            logger.warn("Failed to suspend game process (pid={})", ctx.pid, t)
            false
        }
    }

    /**
     * Resumes the game process for [project] by sending SIGCONT.
     */
    fun resume(project: ProjectBase): Boolean {
        val ctx = snapshot(project) ?: return false
        if (!ctx.isRunning) return false
        return try {
            ProcessBuilder("kill", "-CONT", ctx.pid.toString())
                .inheritIO()
                .start()
                .waitFor() == 0
        } catch (t: Throwable) {
            logger.warn("Failed to resume game process (pid={})", ctx.pid, t)
            false
        }
    }

    /**
     * Returns tracked process context for [project], or null if none.
     */
    fun snapshot(project: ProjectBase): GameProcessContext? = snapshot(project.path)

    /**
     * Returns tracked process context for [projectPath], or null if none.
     */
    fun resolveScope(project: ProjectBase): String = scopeOf(project.path)

    fun resolveScope(path: VPath): String = scopeOf(path)

    fun snapshot(projectPath: VPath): GameProcessContext? {
        val scope = scopeOf(projectPath)
        return synchronized(lock) { trackedByScope[scope]?.toContext() }
    }

    /**
     * Returns all currently tracked contexts.
     */
    fun active(): List<GameProcessContext> {
        return synchronized(lock) { trackedByScope.values.map { it.toContext() } }
    }

    private fun attachInternal(
        scope: String,
        projectName: String,
        handle: ProcessHandle,
        process: Process?,
        source: Source
    ): GameProcessContext {
        val now = System.currentTimeMillis()
        val tracked = TrackedProcess(
            scope = scope,
            projectName = projectName,
            handle = handle,
            process = process,
            source = source,
            attachedAtEpochMs = now
        )

        val displaced = synchronized(lock) { trackedByScope.put(scope, tracked) }
        if (displaced != null && displaced !== tracked) {
            emit(GameProcessEvent.Detached(displaced.toContext(isAttached = false)))
        }

        emit(GameProcessEvent.Attached(tracked.toContext()))
        tracked.handle.onExit().whenComplete { _, throwable ->
            if (throwable != null) {
                logger.debug("Game process exit watcher raised error (pid={})", tracked.handle.pid(), throwable)
            }
            val exitCode = tracked.process?.let { runCatching { it.exitValue() }.getOrNull() }
            onProcessExited(tracked, exitCode)
        }
        return tracked.toContext()
    }

    private fun onProcessExited(tracked: TrackedProcess, exitCode: Int?) {
        val shouldEmit = synchronized(lock) {
            val current = trackedByScope[tracked.scope]
            if (current !== tracked) {
                false
            } else {
                trackedByScope.remove(tracked.scope)
                true
            }
        }
        if (!shouldEmit) return
        _outputFlows.remove(tracked.scope)
        emit(
            GameProcessEvent.Exited(
                context = tracked.toContext(isRunning = false, isAttached = false),
                exitCode = exitCode ?: 0
            )
        )
    }

    private fun emit(event: GameProcessEvent) {
        _events.tryEmit(event)
        when (event) {
            is GameProcessEvent.Attached -> TritiumEventBus.publish(
                TritiumEvent.GameAttached(event.context.projectScope, event.context.projectName, event.context.pid)
            )
            is GameProcessEvent.Detached -> TritiumEventBus.publish(
                TritiumEvent.GameDetached(event.context.projectScope, event.context.projectName, event.context.pid)
            )
            is GameProcessEvent.Exited -> TritiumEventBus.publish(
                TritiumEvent.GameExited(event.context.projectScope, event.context.projectName, event.context.pid, event.exitCode)
            )
            else -> {}
        }
    }

    private fun scopeOf(path: VPath): String {
        val abs = path.toAbsolute().normalize()
        return try {
            val jPath = abs.toJPath()
            val canonical = if (Files.exists(jPath)) {
                jPath.toRealPath()
            } else {
                jPath.toAbsolutePath().normalize()
            }
            canonical.toString().trim()
        } catch (_: Throwable) {
            abs.toString().trim()
        }
    }

    private data class TrackedProcess(
        val scope: String,
        val projectName: String,
        val handle: ProcessHandle,
        val process: Process?,
        val source: Source,
        val attachedAtEpochMs: Long
    ) {
        fun toContext(
            isRunning: Boolean = handle.isAlive,
            isAttached: Boolean = true
        ): GameProcessContext {
            return GameProcessContext(
                projectScope = scope,
                projectName = projectName,
                pid = handle.pid(),
                isRunning = isRunning,
                isAttached = isAttached,
                source = source,
                attachedAtEpochMs = attachedAtEpochMs
            )
        }
    }
}
