/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.keymap

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.menu.MenuItemKind

object KeymapBootstrap {

    private const val MENU_ACTION_PREFIX = "menu."

    fun initializeDefaults() {
        BuiltinRegistries.MenuItem.all()
            .asSequence()
            .filter { it.kind == MenuItemKind.ACTION }
            .forEach { item ->
                if (!item.allowKeyboardShortcuts) return@forEach
                val shortcut = item.shortcut?.trim().orEmpty()
                if (shortcut.isBlank()) return@forEach
                val binding = KeymapMngr.parseBindingString(shortcut) ?: return@forEach
                val actionId = item.shortcutActionId ?: "$MENU_ACTION_PREFIX${item.id}"
                KeymapMngr.declareDefault(actionId, binding)
            }
    }
}
