package io.github.tritium_launcher.launcher.keymap

import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.ui.project.menu.MenuItemKind

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
