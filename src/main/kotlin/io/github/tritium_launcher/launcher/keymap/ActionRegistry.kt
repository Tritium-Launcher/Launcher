/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.keymap

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.keymap.ActionHandler
import io.github.tritium_launcher.api.keymap.ActionId
import io.github.tritium_launcher.api.keymap.Keymap
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qAction
import io.qt.gui.QAction
import io.qt.gui.QIcon

enum class ShortcutKind { Keyboard, Mouse }

object ActionRegistry {
    private val actions = mutableMapOf<ActionId, RegisteredAction>()

    data class RegisteredAction(
        val qAction: QAction?,
        val label: String?,
        val allowedShortcutKinds: Set<ShortcutKind>,
        val focusGroups: Set<String>,
        val handler: ActionHandler,
        val executesOnRelease: Boolean = false,
        val pressHandler: ActionHandler? = null
    )

    fun register(
        id: ActionId,
        label: String,
        icon: QIcon? = null,
        allowKeyboardShortcuts: Boolean = true,
        allowMouseShortcuts: Boolean = true,
        focusGroups: Set<String> = setOf(KeymapFocusMngr.GLOBAL),
        executesOnRelease: Boolean = false,
        pressHandler: ActionHandler? = null,
        handler: ActionHandler
    ): QAction {
        val qAction = qAction(label, icon) {
            triggered.connect { handler() }
        }
        actions[id] = RegisteredAction(
            qAction = qAction,
            label = label,
            allowedShortcutKinds = allowedKinds(allowKeyboardShortcuts, allowMouseShortcuts),
            focusGroups = focusGroups.ifEmpty { setOf(KeymapFocusMngr.GLOBAL) },
            handler = handler,
            executesOnRelease = executesOnRelease,
            pressHandler = pressHandler
        )
        return qAction
    }

    fun registerHandler(
        id: ActionId,
        allowKeyboardShortcuts: Boolean = true,
        allowMouseShortcuts: Boolean = true,
        focusGroups: Set<String> = setOf(KeymapFocusMngr.GLOBAL),
        executesOnRelease: Boolean = false,
        pressHandler: ActionHandler? = null,
        handler: ActionHandler
    ) {
        val existingAction = actions[id]?.qAction
        val existingLabel = actions[id]?.label ?: existingAction?.text()
        actions[id] = RegisteredAction(
            qAction = existingAction,
            label = existingLabel,
            allowedShortcutKinds = allowedKinds(allowKeyboardShortcuts, allowMouseShortcuts),
            focusGroups = focusGroups.ifEmpty { setOf(KeymapFocusMngr.GLOBAL) },
            handler = handler,
            executesOnRelease = executesOnRelease,
            pressHandler = pressHandler
        )
    }

    operator fun get(id: ActionId): QAction? = actions[id]?.qAction

    fun contains(id: ActionId): Boolean = id in actions

    fun execute(id: ActionId) { actions[id]?.handler?.invoke() }

    fun executePress(id: ActionId) { actions[id]?.pressHandler?.invoke() }

    fun allows(id: ActionId, kind: ShortcutKind): Boolean =
        actions[id]?.allowedShortcutKinds?.contains(kind) ?: true

    fun focusGroups(id: ActionId): Set<String> =
        actions[id]?.focusGroups ?: setOf(KeymapFocusMngr.GLOBAL)

    fun executesOnRelease(id: ActionId): Boolean =
        actions[id]?.executesOnRelease ?: false

    fun actionIds(): Set<ActionId> = actions.keys

    fun actionLabel(id: ActionId): String = actions[id]?.label?.takeIf { it.isNotBlank() } ?: id

    fun syncShortcuts(keymap: Keymap) {
        actions.forEach { (id, registered) ->
            val sequences = keymap.getBindings(id).flatMap { it.toQKeySequences() }
            if (sequences.isEmpty()) {
                registered.qAction?.setShortcuts(emptyList())
            } else {
                registered.qAction?.setShortcuts(sequences)
            }
        }
    }

    private fun allowedKinds(allowKeyboard: Boolean, allowMouse: Boolean): Set<ShortcutKind> = buildSet {
        if (allowKeyboard) add(ShortcutKind.Keyboard)
        if (allowMouse) add(ShortcutKind.Mouse)
    }
}
