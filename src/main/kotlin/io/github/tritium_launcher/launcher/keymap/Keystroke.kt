package io.github.tritium_launcher.launcher.keymap

import io.qt.core.Qt.KeyboardModifier.*
import io.qt.gui.QKeySequence

data class Keystroke(
    val key: Int,
    val modifiers: Int
) {
    fun toQKeySequence(): QKeySequence = QKeySequence(modifiers or key)

    override fun toString(): String = buildString {
        if(modifiers and ControlModifier.value() != 0) append("Ctrl+")
        if(modifiers and AltModifier.value()     != 0) append("Alt+")
        if(modifiers and ShiftModifier.value()   != 0) append("Shift+")
        if(modifiers and MetaModifier.value()    != 0) append("Meta+")
        append(QKeySequence(key).toString())
    }

    companion object {
        fun ctrl(key: Int)      = Keystroke(key, ControlModifier.value())
        fun ctrlShift(key: Int) = Keystroke(key, ControlModifier.value() or ShiftModifier.value())

        fun meta(key: Int)      = Keystroke(key, MetaModifier.value())
        fun metaShift(key: Int) = Keystroke(key, MetaModifier.value() or ShiftModifier.value())

        fun plain(key: Int)     = Keystroke(key, 0)
        fun alt(key: Int)       = Keystroke(key, AltModifier.value())
    }
}
