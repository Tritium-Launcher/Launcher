/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme.qt

import io.qt.core.Qt
import io.qt.gui.*

/**
 * Makes a [QIcon] from this [QPixmap]
 */
val QPixmap.icon: QIcon get() = QIcon(this)

/**
 * Returns a copy of this icon with a color overlay applied.
 */
fun QIcon.withColorOverlay(
    color: QColor,
    alpha: Int = 140,
    width: Int = 16,
    height: Int = width
): QIcon {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val base = this.pixmap(safeWidth, safeHeight)
    if (base == null || base.isNull) return QIcon()

    val out = QPixmap(base.size())
    out.setDevicePixelRatio(base.devicePixelRatio())
    out.fill(Qt.GlobalColor.transparent)

    val painter = QPainter(out)
    painter.drawPixmap(0, 0, base)
    painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceAtop)
    val overlay = QColor(color.red(), color.green(), color.blue(), alpha.coerceIn(0, 255))
    painter.fillRect(out.rect(), overlay)
    painter.end()

    return QIcon(out)
}

/**
 * Returns a copy of this icon with a gray overlay applied.
 */
fun QIcon.grayOverlay(alpha: Int = 140, width: Int = 16, height: Int = width): QIcon =
    withColorOverlay(QColor(128, 128, 128), alpha, width, height)

fun QImage.dominantColor(): QColor {
    val small = scaled(16,16, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
    val colorCounts = HashMap<Int,Int>()

    for (y in 0 until small.height()) {
        for (x in 0 until small.width()) {
            val rgb = small.pixel(x, y)
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            val a = (rgb ushr 24) and 0xFF
            if (a < 128) continue

            val quantized = ((r / 32) shl 10) or ((g / 32) shl 5) or (b / 32)
            colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
        }
    }

    val dominant = colorCounts.maxByOrNull { it.value }?.key ?: return QColor("gray")
    return QColor(
        ((dominant shr 10) and 0x1F) * 32,
        ((dominant shr 5) and 0x1F) * 32,
        dominant and 0x1F * 32
    )
}
