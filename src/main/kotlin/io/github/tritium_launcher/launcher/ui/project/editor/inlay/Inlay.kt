/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inlay

import io.qt.core.QPoint
import io.qt.core.QRect
import io.qt.gui.QColor
import io.qt.gui.QFontMetrics
import io.qt.gui.QPainter

sealed class Inlay(val offset: Int) {
    class Label(
        offset: Int,
        val text: String,
        val color: QColor,
        val onClick: (() -> Unit)? = null,
        val painter: InlayPainter? = null,
    ) : Inlay(offset)
}

fun interface InlayPainter {
    fun paint(
        painter: QPainter,
        fm: QFontMetrics,
        lineEnd: QPoint,
        inlay: Inlay.Label,
        setHitRect: (QRect) -> Unit,
    )
}
