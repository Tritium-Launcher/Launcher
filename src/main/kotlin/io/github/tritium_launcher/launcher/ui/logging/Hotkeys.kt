package io.github.tritium_launcher.launcher.ui.logging

import io.github.tritium_launcher.launcher.connect
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.Qt
import io.qt.gui.QKeyEvent
import io.qt.widgets.QApplication

/**
 * Global hotkey handler.
 *
 * TODO: Keymap
 */
object Hotkeys {
    private var installed = false
    private var eventFilter: QObject? = null
    private var dialog: LogDialog? = null

    /**
     * Installs a global key handler
     */
    fun install() {
        if (installed) return
        val app = QApplication.instance() ?: return

        val filter = object : QObject(app) {
            override fun eventFilter(
                watched: @Nullable QObject?,
                event: @Nullable QEvent?
            ): Boolean {
                if (event?.type() != QEvent.Type.KeyPress) {
                    return super.eventFilter(watched, event)
                }
                val keyEvent = event as? QKeyEvent ?: return super.eventFilter(watched, event)
                if (keyEvent.isAutoRepeat) return super.eventFilter(watched, event)

                if (matchesOpenLogShortcut(keyEvent)) {
                    openDialog()
                    return true
                }
                return super.eventFilter(watched, event)
            }
        }

        app.installEventFilter(filter)
        eventFilter = filter
        installed = true
    }

    /**
     * Opens the log viewer dialog.
     */
    fun openDialog() {
        val existing = dialog
        if (existing != null) {
            existing.openAndFocus()
            return
        }

        val created = LogDialog()
        created.destroyed.connect {
            if (dialog === created) dialog = null
        }
        dialog = created
        created.openAndFocus()
    }

    /**
     * Matches Ctrl+Shift+I
     */
    private fun matchesOpenLogShortcut(event: QKeyEvent): Boolean {
        if (event.key() != Qt.Key.Key_I.value()) return false
        val mods = event.modifiers()
        val ctrl = mods.testFlag(Qt.KeyboardModifier.ControlModifier)
        val shift = mods.testFlag(Qt.KeyboardModifier.ShiftModifier)
        val alt = mods.testFlag(Qt.KeyboardModifier.AltModifier)
        val meta = mods.testFlag(Qt.KeyboardModifier.MetaModifier)
        return ctrl && shift && !alt && !meta
    }
}
