/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.state

import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.io.VPath
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object UIStateMngr {

    private val configDir: VPath = fromTR("options")

    private val components = ConcurrentHashMap<String, Persistable>()

    private val immediateDirty = ConcurrentHashMap.newKeySet<String>()
    private val periodicDirty  = ConcurrentHashMap.newKeySet<String>()

    private val stateCache = ConcurrentHashMap<String, JsonObject>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init() {
        configDir.mkdirs()
        loadAllFromDisk()
        startFlushScheduler()
        eventBus()
    }

    fun shutdown() {
        scope.cancel()
        flushDirty(includeOnShutdown = true)
    }

    fun register(component: Persistable) {
        components[component.persistKey] = component
        stateCache[component.persistKey]?.let { component.restoreState(it) }
    }

    fun unregister(component: Persistable) {
        flushComponent(component)
        components.remove(component.persistKey)
    }

    fun markDirty(component: Persistable) = when(component.flushPolicy) {
        FlushPolicy.Immediate -> immediateDirty.add(component.persistKey)
        FlushPolicy.Periodic  -> periodicDirty.add(component.persistKey)
        FlushPolicy.Shutdown  -> { }
    }

    fun markDirtyImmediate(component: Persistable) { immediateDirty.add(component.persistKey) }

    private fun startFlushScheduler() {
        scope.launch {
            while(isActive) {
                delay(1000.milliseconds)
                if(immediateDirty.isNotEmpty()) flushSet(periodicDirty)
            }
        }

        scope.launch {
            while(isActive) {
                delay(5000.milliseconds)
                if(periodicDirty.isNotEmpty()) flushSet(periodicDirty)
            }
        }
    }

    private fun flushSet(dirtySet: MutableSet<String>) {
        val keys = dirtySet.toSet()
        dirtySet.clear()
        keys.forEach { key -> components[key]?.let { flushComponent(it) } }
    }

    private fun flushComponent(component: Persistable) {
        val state = component.captureState()
        stateCache[component.persistKey] = state
    }

    private fun flushDirty(includeOnShutdown: Boolean) {
        val keys = buildSet {
            addAll(immediateDirty)
            addAll(periodicDirty)
            if(includeOnShutdown) addAll(components.keys)
        }

        immediateDirty.clear()
        periodicDirty.clear()
        keys.forEach { key -> components[key]?.let { flushComponent(it) } }
        persistCacheToDisk()
    }

    private fun resolveFile(key: String): String = when {
        key.startsWith("window_geometry") -> "window_geometry.json"
        key.startsWith("panel_")          -> "panel_layout.json"
        key.startsWith("editor_")         -> "editor_session.json"
        key.startsWith("registry_")       -> "registry_browser.json"
        key.startsWith("recipe_")         -> "recipe_builder.json"
        key.startsWith("directory_marks") -> "directory_marks.json"
        else                              -> "${sanitizeFileName(key)}.json"
    }

    private fun sanitizeFileName(key: String): String =
        key.replace(Regex("[^a-zA-Z0-9_\\-.]"), "_")
            .replace(Regex("_{2,}"), "_")
            .trim('_')
            .ifEmpty { "unnamed" }

    private fun persistCacheToDisk() {
        val byFile = stateCache.entries.groupBy { resolveFile(it.key) }

        byFile.forEach { (fileName, entries) ->
            val json = buildJsonObject {
                entries.forEach { (key, obj) -> put(key, obj) }
            }
            atomicWrite(configDir.resolve(fileName), json)
        }
    }

    private fun loadAllFromDisk() {
        configDir.list().filter { it.extension() == "json" }.forEach { path ->
            runCatching {
                val json = Json.parseToJsonElement(path.readTextOr("")).jsonObject
                json.forEach { (k, v) -> if(v is JsonObject) stateCache[k] = v }
            }.onFailure {
                path.moveTo(
                    path.resolve("${path.fileName()}.bak"),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }

    private fun atomicWrite(target: VPath, json: JsonObject) {
        val tmp = target.resolve("${target.fileName()}.tmp")
        val pretty = Json { prettyPrint = true }
        tmp.writeBytes(pretty.encodeToString(JsonObject.serializer(), json).toByteArray())
        tmp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun eventBus() {
        scope.onEvent<TritiumEvent.AppQuitting> {
            shutdown()
        }
        scope.onEvent<TritiumEvent.ProjectClosing> {
            flushDirty(includeOnShutdown = true)
            persistCacheToDisk()
        }
        scope.onEvent<TritiumEvent.EditorOpened> {
            components["editor_session"]?.let { markDirtyImmediate(it) }
        }
        scope.onEvent<TritiumEvent.EditorClosed> {
            components["editor_session"]?.let { markDirtyImmediate(it) }
        }
        scope.onEvent<TritiumEvent.GameAttached> {
            flushDirty(includeOnShutdown = true)
            persistCacheToDisk()
        }
    }
}
