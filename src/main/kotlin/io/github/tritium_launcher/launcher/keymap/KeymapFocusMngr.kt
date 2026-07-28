/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.keymap

object KeymapFocusMngr {
    const val GLOBAL = "global"

    private val listeners = mutableListOf<(String) -> Unit>()
    private val resolvers = mutableMapOf<String, () -> String?>()
    private val focusStack = ArrayDeque<String>()

    private var explicitFocusGroup: String = GLOBAL

    fun currentGroup(): String {
        focusStack.lastOrNull()?.let { return it }
        resolvers.values.forEach { resolver ->
            val resolved = resolver()?.trim()
            if (!resolved.isNullOrBlank()) return resolved
        }
        return explicitFocusGroup
    }

    fun set(group: String) {
        val normalized = group.trim().ifBlank { GLOBAL }
        explicitFocusGroup = normalized
        listeners.forEach { it(normalized) }
    }

    fun push(group: String) {
        val normalized = group.trim().ifBlank { GLOBAL }
        focusStack.addLast(normalized)
        listeners.forEach { it(currentGroup()) }
    }

    fun pop() {
        if (focusStack.isNotEmpty()) {
            focusStack.removeLast()
            listeners.forEach { it(currentGroup()) }
        }
    }

    fun registerResolver(id: String, resolver: () -> String?) {
        resolvers[id] = resolver
    }

    fun unregisterResolver(id: String) {
        resolvers.remove(id)
    }

    fun addListener(listener: (String) -> Unit) { listeners += listener }
    fun removeListener(listener: (String) -> Unit) { listeners -= listener }
}
