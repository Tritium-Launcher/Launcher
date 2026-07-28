/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.util.*
import kotlin.math.roundToInt

/**
 * Runtime manager for project-scoped background tasks.
 */
object ProjectTaskMngr {
    private val logger = logger()
    private val lock = Any()
    private val tasksById = LinkedHashMap<String, ProjectTaskEntry>()
    
    private val _tasks = MutableStateFlow<List<ProjectTaskEntry>>(emptyList())
    
    /**
     * Observable flow of all currently active background tasks.
     */
    val tasks: StateFlow<List<ProjectTaskEntry>> = _tasks.asStateFlow()

    /**
     * Compatibility flow that emits [Unit] whenever tasks change.
     */
    val taskChanges: Flow<Unit> = tasks.map { }

    /**
     * Runtime snapshot of a task currently tracked by [ProjectTaskMngr].
     */
    data class ProjectTaskEntry(
        val taskId: String,
        val projectPath: String,
        val title: String,
        val detail: String,
        val progressPercent: Int?,
        val startedAtEpochMs: Long
    )

    /**
     * Starts a new task for [projectPath].
     *
     * @return Runtime task id used for updates and completion.
     */
    fun start(
        projectPath: VPath,
        title: String,
        detail: String = "",
        progressPercent: Double? = null
    ): String {
        val task = ProjectTaskEntry(
            taskId = UUID.randomUUID().toString(),
            projectPath = scopeOf(projectPath),
            title = normalizeTitle(title),
            detail = normalizeDetail(detail),
            progressPercent = normalizeProgress(progressPercent),
            startedAtEpochMs = System.currentTimeMillis()
        )
        synchronized(lock) {
            tasksById[task.taskId] = task
        }
        emitChanged()
        return task.taskId
    }

    /**
     * Updates task text fields.
     *
     * @return `true` if task was found and changed.
     */
    fun update(taskId: String, title: String? = null, detail: String? = null): Boolean {
        var changed = false
        synchronized(lock) {
            val existing = tasksById[taskId] ?: return@synchronized
            val updated = existing.copy(
                title = title?.let { normalizeTitle(it) } ?: existing.title,
                detail = detail?.let { normalizeDetail(it) } ?: existing.detail
            )
            if (updated != existing) {
                tasksById[taskId] = updated
                changed = true
            }
        }
        if (changed) emitChanged()
        return changed
    }

    /**
     * Updates task progress.
     *
     * Use `null` for an indeterminate animated bar.
     *
     * @return `true` if task was found and changed.
     */
    fun updateProgress(taskId: String, progressPercent: Double?): Boolean {
        var changed = false
        val normalized = normalizeProgress(progressPercent)
        synchronized(lock) {
            val existing = tasksById[taskId] ?: return@synchronized
            if (existing.progressPercent != normalized) {
                tasksById[taskId] = existing.copy(progressPercent = normalized)
                changed = true
            }
        }
        if (changed) emitChanged()
        return changed
    }

    /**
     * Finishes and removes a task.
     *
     * @return Removed task, or `null` when task did not exist.
     */
    fun finish(taskId: String): ProjectTaskEntry? {
        val removed = synchronized(lock) { tasksById.remove(taskId) }
        if (removed != null) emitChanged()
        return removed
    }

    /**
     * Returns active tasks for [project], oldest first.
     */
    fun activeForProject(project: ProjectBase): List<ProjectTaskEntry> = activeForPath(project.path)

    /**
     * Returns active tasks for [projectPath], oldest first.
     */
    fun activeForPath(projectPath: VPath): List<ProjectTaskEntry> {
        val scope = scopeOf(projectPath)
        return synchronized(lock) {
            tasksById.values
                .asSequence()
                .filter { it.projectPath == scope }
                .sortedBy { it.startedAtEpochMs }
                .toList()
        }
    }

    /**
     * Updates the StateFlow with the current list of active tasks.
     */
    private fun emitChanged() {
        val currentTasks = synchronized(lock) {
            tasksById.values.toList()
        }
        _tasks.value = currentTasks
    }

    /**
     * Returns filesystem scope for provided [VPath]
     */
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

    /**
     * Trim task name
     */
    private fun normalizeTitle(raw: String): String = raw.trim().ifBlank { "Background task" }

    /**
     * Trim task detail
     */
    private fun normalizeDetail(raw: String): String = raw.trim()

    /**
     * Round progress to [Int]
     */
    private fun normalizeProgress(progressPercent: Double?): Int? {
        if (progressPercent == null || !progressPercent.isFinite()) return null
        return progressPercent.coerceIn(0.0, 100.0).roundToInt()
    }
}
