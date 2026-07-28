/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.keymap

import io.qt.core.Qt
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
                Qt.MouseButton.LeftButton.value() -> "MouseLeft"
                Qt.MouseButton.RightButton.value() -> "MouseRight"
                Qt.MouseButton.MiddleButton.value() -> "MouseMiddle"
                Qt.MouseButton.BackButton.value() -> "MouseBack"
                Qt.MouseButton.ForwardButton.value() -> "MouseForward"
                else -> "MouseButton$button"
            }
        )
    }
}
