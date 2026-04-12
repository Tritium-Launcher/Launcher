package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.io.VPath
import io.qt.core.QPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persists freeform layout coordinates per project path. */
class LayoutStore(private val file: VPath) {
    @Serializable
    private data class Entry(val x: Int, val y: Int)

    private val json = Json { prettyPrint = true }
    private val cache = mutableMapOf<String, QPoint>()

    init {
        load()
    }

    /** Loads persisted positions into memory. */
    private fun load() {
        try {
            if (!file.exists()) return
            val text = file.readTextOrNull() ?: return
            val decoded: Map<String, Entry> = json.decodeFromString(text)
            cache.clear()
            decoded.forEach { (k, v) -> cache[k] = QPoint(v.x, v.y) }
        } catch (_: Throwable) {}
    }

    /** Returns a stored position for the given project path, if any. */
    fun get(path: String): QPoint? = cache[path]?.let { QPoint(it) }

    /** Saves a position for the given project path. */
    fun put(path: String, point: QPoint) {
        cache[path] = QPoint(point)
        persist()
    }

    /** Removes a stored position. */
    fun remove(path: String) {
        cache.remove(path)
        persist()
    }

    /** Writes the layout map to disk. */
    private fun persist() {
        try {
            val encoded = cache.mapValues { Entry(it.value.x(), it.value.y()) }
            file.parent().mkdirs()
            file.writeBytesAtomic(json.encodeToString(encoded).toByteArray())
        } catch (_: Throwable) {}
    }
}

/** Persists user-created groups and assignments. */
class GroupStore(private val file: VPath) {
    @Serializable
    private data class Payload(val groups: List<GroupPayload> = emptyList(), val order: List<String> = emptyList())
    @Serializable
    private data class GroupPayload(val name: String, val projects: List<String>)

    private val json = Json { prettyPrint = true }
    private val groups = mutableMapOf<String, MutableSet<String>>()
    private val order = mutableListOf<String>()

    init {
        load()
    }

    /** Returns all group names in user-defined order. */
    fun groupNames(): List<String> {
        val known = groups.keys
        val ordered = order.filter { known.contains(it) }.toMutableList()
        val missing = known.filterNot { order.contains(it) }.sorted()
        ordered.addAll(missing)
        return ordered
    }

    /** Returns the group for a project path, or null. */
    fun groupOf(path: String): String? = groups.entries.firstOrNull { it.value.contains(path) }?.key

    /** Creates a group if it doesn't exist. */
    fun addGroup(name: String) {
        if (name.isBlank()) return
        val key = name.trim()
        groups.putIfAbsent(key, mutableSetOf())
        if (!order.contains(key)) order.add(key)
        persist()
    }

    /** Moves a group up or down in the order list. */
    fun moveGroup(name: String, delta: Int) {
        if(!order.contains(name) && groups.containsKey(name)) order.add(name)

        val missing = groups.keys.filterNot { order.contains(it) }.sorted()
        if (missing.isNotEmpty()) order.addAll(missing)

        val idx = order.indexOf(name)
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, order.size - 1)
        if (idx == target) return
        order.removeAt(idx)
        order.add(target, name)
        persist()
    }

    /** Assigns a project to a group, removing it from previous groups. */
    fun assign(path: String, group: String?) {
        groups.values.forEach { it.remove(path) }
        val target = group?.trim().orEmpty()
        if (target.isNotBlank()) {
            groups.putIfAbsent(target, mutableSetOf())
            groups[target]?.add(path)
        }
        persist()
    }

    /** Reads group data from disk. */
    private fun load() {
        try {
            if (!file.exists()) return
            val text = file.readTextOrNull() ?: return
            val payload = json.decodeFromString<Payload>(text)
            groups.clear()
            payload.groups.forEach { g -> groups[g.name] = g.projects.toMutableSet() }
            order.clear()
            order.addAll(payload.order)
        } catch (_: Throwable) {}
    }

    /** Writes group data from disk. */
    private fun persist() {
        try {
            val payload = Payload(groups.map { GroupPayload(it.key, it.value.toList()) }, order.toList())
            file.parent().mkdirs()
            file.writeBytesAtomic(json.encodeToString(payload).toByteArray())
        } catch (_: Throwable) {}
    }
}
