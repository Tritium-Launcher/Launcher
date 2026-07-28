/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.extension

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.serialization.json.Json

object ExtensionStateMngr {
    private val file: VPath = fromTR(TConstants.Dirs.SETTINGS, "extensions-state.json")
    private val logger = logger()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): Map<String, Boolean> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val text = file.readTextOrNull() ?: return emptyMap()
            json.decodeFromString<Map<String, Boolean>>(text)
        }.getOrElse {
            logger.error("Failed to load extension state from $file")
            emptyMap()
        }
    }

    fun save(state: Map<String, Boolean>) {
        runCatching {
            file.parent().mkdirs()
            file.writeTextAtomic(json.encodeToString(state))
        }.onFailure {
            logger.error("Failed to save extension state to $file", it)
        }
    }

    fun setEnabled(namespace: String, enabled: Boolean) {
        val state = load().toMutableMap()
        state[namespace] = enabled
        save(state)
    }
}
