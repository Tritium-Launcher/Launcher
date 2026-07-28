/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.font

import io.qt.gui.QFontDatabase

// TODO: Might not actually include this
object Fonts {
    val Monocraft: String by lazy {
        val id = QFontDatabase.addApplicationFont(":/fonts/Monocraft.ttf")
        QFontDatabase.applicationFontFamilies(id).firstOrNull() ?: "monospace"
    }
}
