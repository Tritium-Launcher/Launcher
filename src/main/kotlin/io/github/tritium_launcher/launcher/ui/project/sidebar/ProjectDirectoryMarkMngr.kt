/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.TritiumEventBus
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.ProjectDirectoryMark
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.state.FlushPolicy
import io.github.tritium_launcher.api.state.Persistable
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

class ProjectDirectoryMarkMngr(private val project: ProjectBase) : Persistable {

    private val logger = logger()
    private val marks = ConcurrentHashMap<String, MutableSet<String>>()

    override val persistKey: String = "directory_marks_${project.projectDir.toAbsoluteString()}"
    override val flushPolicy: FlushPolicy = FlushPolicy.Immediate

    fun getMarks(path: VPath): Set<String> =
        marks[path.toAbsoluteString()]?.toSet() ?: emptySet()

    fun getMarks(path: String): Set<String> =
        marks[path]?.toSet() ?: emptySet()

    fun hasMark(path: VPath, markId: String): Boolean =
        marks[path.toAbsoluteString()]?.contains(markId) == true

    fun allMarkedPaths(): Map<String, Set<String>> = marks.entries.associate { it.key to it.value.toSet() }

    private fun findMark(markId: String): ProjectDirectoryMark? =
        BuiltinRegistries.ProjectDirectoryMark.all().firstOrNull { it.id == markId }

    fun setMark(path: VPath, markId: String): Boolean {
        val abs = path.toAbsoluteString()
        val markDef = findMark(markId)
        if (markDef == null) {
            logger.warn("Unknown directory mark '{}'", markId)
            return false
        }

        val currentMarks = marks.getOrPut(abs) { mutableSetOf() }
        val incompatible = markDef.incompatibleWith.toSet()
        currentMarks.removeAll { it in incompatible }

        if (!currentMarks.add(markId)) return false

        markDirty()
        TritiumEventBus.publish(TritiumEvent.DirectoryMarksChanged(project))
        markDef.onMarkApplied(project, path)
        return true
    }

    fun removeMark(path: VPath, markId: String): Boolean {
        val abs = path.toAbsoluteString()
        val currentMarks = marks[abs] ?: return false
        if (!currentMarks.remove(markId)) return false
        if (currentMarks.isEmpty()) marks.remove(abs)
        markDirty()
        TritiumEventBus.publish(TritiumEvent.DirectoryMarksChanged(project))
        findMark(markId)?.onMarkRemoved(project, path)
        return true
    }

    fun toggleMark(path: VPath, markId: String): Boolean {
        return if (hasMark(path, markId)) {
            removeMark(path, markId)
            false
        } else {
            setMark(path, markId)
            true
        }
    }

    fun clearMarks(path: VPath) {
        val abs = path.toAbsoluteString()
        if (marks.remove(abs) != null) {
            markDirty()
            TritiumEventBus.publish(TritiumEvent.DirectoryMarksChanged(project))
        }
    }

    fun filterWatchEvent(path: VPath, event: VWatchEvent): Boolean {
        var current: VPath? = path.toAbsolute().normalize()
        while (current != null) {
            for (markId in getMarks(current)) {
                val mark = findMark(markId) ?: continue
                if (!mark.filterWatchEvent(path, event)) return false
            }
            if (current.isRoot() || current.isEmpty()) break
            current = runCatching { current.parent() }.getOrNull()
        }
        return true
    }

    fun isPathMarked(path: VPath, markId: String): Boolean {
        var current: VPath? = path.toAbsolute().normalize()
        while (current != null) {
            if (hasMark(current, markId)) return true
            if (current.isRoot() || current.isEmpty()) break
            current = runCatching { current.parent() }.getOrNull()
        }
        return false
    }

    fun highestPriorityMark(path: VPath): ProjectDirectoryMark? {
        var current: VPath? = path.toAbsolute().normalize()
        val allMarks = BuiltinRegistries.ProjectDirectoryMark.all().sortedBy { it.order }
        while (current != null) {
            val pathMarks = marks[current.toAbsoluteString()] ?: emptySet()
            for (m in allMarks) {
                if (m.id in pathMarks) return m
            }
            if (current.isRoot() || current.isEmpty()) break
            current = runCatching { current.parent() }.getOrNull()
        }
        return null
    }

    override fun captureState(): JsonObject {
        return buildJsonObject {
            for ((path, markIds) in marks) {
                put(path, JsonArray(markIds.map { JsonPrimitive(it) }))
            }
        }
    }

    override fun restoreState(state: JsonObject) {
        marks.clear()
        for ((path, elem) in state) {
            val arr = elem.jsonArray
            marks[path] = arr.map { it.jsonPrimitive.content }.toMutableSet()
        }
    }
}
