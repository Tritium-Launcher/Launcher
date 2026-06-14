package io.github.tritium_launcher.launcher.keymap

import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.Qt
import io.qt.core.Qt.Key.*
import io.qt.gui.QKeyEvent
import io.qt.gui.QMouseEvent
import io.qt.widgets.QApplication
import io.qt.widgets.QLineEdit
import io.qt.widgets.QPlainTextEdit
import io.qt.widgets.QTextEdit

/**
 * Dispatches Key events
 */
class KeymapDispatcher(
    private val registry: ActionRegistry
): QObject() {

    private var pendingChordFirst: Keystroke? = null

    override fun eventFilter(
        watched: @Nullable QObject?,
        event: @Nullable QEvent?
    ): Boolean {
        event ?: return false

        if(event.type() == QEvent.Type.ShortcutOverride) return false
        if (event.type() == QEvent.Type.MouseButtonPress) {
            return handleMousePress(event as? QMouseEvent ?: return false)
        }
        if(event.type() != QEvent.Type.KeyPress) return false

        val keyEvent = event as? QKeyEvent ?: return false

        val key = keyEvent.key()
        if(isModifierKey(key)) return false

        if (isTextEditKeystroke(key) || keyEvent.modifiers().value() == Qt.KeyboardModifier.ControlModifier.value() && key in textEditCtrlKeys) {
            val focusWidget = QApplication.focusWidget()
            if (focusWidget is QLineEdit || focusWidget is QTextEdit || focusWidget is QPlainTextEdit) {
                return false
            }
        }

        val stroke = Keystroke(key, keyEvent.modifiers().value())
        val keymap = KeymapMngr.activeKeymap
        val activeFocusGroup = KeymapFocusMngr.currentGroup()

        val pending = pendingChordFirst
        if(pending != null) {
            pendingChordFirst = null
            val chordActionId = keymap.resolveChordAction(pending, stroke)
            if(chordActionId != null) {
                if (activeFocusGroup !in registry.focusGroups(chordActionId)) return false
                registry.execute(chordActionId)
                return true
            }
        }

        if(keymap.isChordPrefix(stroke)) {
            pendingChordFirst = stroke
            return true
        }

        val actionId = keymap.resolveAction(stroke)
        if(actionId != null) {
            if (!registry.allows(actionId, ShortcutKind.Keyboard)) return false
            if (activeFocusGroup !in registry.focusGroups(actionId)) return false
            val qAction = registry[actionId]
            if(qAction == null || qAction.shortcuts().isEmpty) {
                registry.execute(actionId)
                return true
            }
        }

        return false
    }

    fun cancelPendingChord() { pendingChordFirst = null }

    private fun handleMousePress(event: QMouseEvent): Boolean {
        val keymap = KeymapMngr.activeKeymap
        val activeFocusGroup = KeymapFocusMngr.currentGroup()
        val stroke = MouseStroke(
            button = event.button().value(),
            modifiers = event.modifiers().value()
        )
        val actionId = keymap.resolveMouseAction(stroke) ?: return false
        if (!registry.allows(actionId, ShortcutKind.Mouse)) return false
        if (activeFocusGroup !in registry.focusGroups(actionId)) return false
        registry.execute(actionId)
        return true
    }

    private fun isModifierKey(key: Int): Boolean = key in setOf(
        Key_Control.value(),
        Key_Shift.value(),
        Key_Alt.value(),
        Key_Meta.value(),
    )

    private companion object {
        private val textEditCtrlKeys = setOf(
            Key_A.value(),   // Select All
            Key_C.value(),   // Copy
            Key_V.value(),   // Paste
            Key_X.value(),   // Cut
            Key_Z.value(),   // Undo
            Key_Y.value(),   // Redo
            Key_Slash.value(),
        )

        private val textEditStandaloneKeys = setOf(
            Key_Delete.value(),
            Key_Backspace.value(),
        )
    }

    private fun isTextEditKeystroke(key: Int): Boolean =
        key in textEditStandaloneKeys
}
