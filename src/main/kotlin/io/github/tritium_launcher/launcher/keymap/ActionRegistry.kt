package io.github.tritium_launcher.launcher.keymap

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qAction
import io.qt.gui.QAction
import io.qt.gui.QIcon

typealias ActionId = String
typealias ActionHandler = () -> Unit

enum class ShortcutKind { Keyboard, Mouse }

object ActionRegistry {
    private val actions = mutableMapOf<ActionId, RegisteredAction>()

    data class RegisteredAction(
        val qAction: QAction?,
        val label: String?,
        val allowedShortcutKinds: Set<ShortcutKind>,
        val focusGroups: Set<String>,
        val handler: ActionHandler
    )

    fun register(
        id: ActionId,
        label: String,
        icon: QIcon? = null,
        allowKeyboardShortcuts: Boolean = true,
        allowMouseShortcuts: Boolean = true,
        focusGroups: Set<String> = setOf(KeymapFocusMngr.GLOBAL),
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
            handler = handler
        )
        return qAction
    }

    fun registerHandler(
        id: ActionId,
        allowKeyboardShortcuts: Boolean = true,
        allowMouseShortcuts: Boolean = true,
        focusGroups: Set<String> = setOf(KeymapFocusMngr.GLOBAL),
        handler: ActionHandler
    ) {
        val existingAction = actions[id]?.qAction
        val existingLabel = actions[id]?.label ?: existingAction?.text()
        actions[id] = RegisteredAction(
            qAction = existingAction,
            label = existingLabel,
            allowedShortcutKinds = allowedKinds(allowKeyboardShortcuts, allowMouseShortcuts),
            focusGroups = focusGroups.ifEmpty { setOf(KeymapFocusMngr.GLOBAL) },
            handler = handler
        )
    }

    operator fun get(id: ActionId): QAction? = actions[id]?.qAction

    fun contains(id: ActionId): Boolean = id in actions

    fun execute(id: ActionId) { actions[id]?.handler?.invoke() }

    fun allows(id: ActionId, kind: ShortcutKind): Boolean =
        actions[id]?.allowedShortcutKinds?.contains(kind) ?: true

    fun focusGroups(id: ActionId): Set<String> =
        actions[id]?.focusGroups ?: setOf(KeymapFocusMngr.GLOBAL)

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
