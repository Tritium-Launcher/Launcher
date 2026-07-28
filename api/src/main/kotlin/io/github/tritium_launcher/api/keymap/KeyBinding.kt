/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.keymap

import io.qt.gui.QKeySequence

sealed class KeyBinding {
    data object None : KeyBinding()
    data class Single(val stroke: Keystroke): KeyBinding()
    data class Chord(val first: Keystroke, val second: Keystroke): KeyBinding()
    data class Mouse(val stroke: MouseStroke): KeyBinding()

    fun toQKeySequences(): List<QKeySequence> = when(this) {
        is None   -> emptyList()
        is Single -> listOf(stroke.toQKeySequence())
        is Chord  -> listOf(QKeySequence("$first, $second"))
        is Mouse  -> emptyList()
    }

    fun displayString(): String = when(this) {
        is None   -> "-"
        is Single -> stroke.toString()
        is Chord  -> "$first $second"
        is Mouse  -> stroke.toString()
    }
}
