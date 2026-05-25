package io.github.tritium_launcher.launcher.keymap

import io.qt.core.Qt.KeyboardModifier.*

data class MouseStroke(
    val button: Int,
    val modifiers: Int
) {
    override fun toString(): String = buildString {
        if(modifiers and ControlModifier.value() != 0) append("Ctrl+")
        if(modifiers and AltModifier.value()     != 0) append("Alt+")
        if(modifiers and ShiftModifier.value()   != 0) append("Shift+")
        if(modifiers and MetaModifier.value()    != 0) append("Meta+")
        append(
            when (button) {
                io.qt.core.Qt.MouseButton.LeftButton.value() -> "MouseLeft"
                io.qt.core.Qt.MouseButton.RightButton.value() -> "MouseRight"
                io.qt.core.Qt.MouseButton.MiddleButton.value() -> "MouseMiddle"
                io.qt.core.Qt.MouseButton.BackButton.value() -> "MouseBack"
                io.qt.core.Qt.MouseButton.ForwardButton.value() -> "MouseForward"
                else -> "MouseButton$button"
            }
        )
    }
}
