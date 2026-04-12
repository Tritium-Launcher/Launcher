package io.github.tritium_launcher.launcher.ui.theme.qt

import io.qt.core.Qt
import io.qt.gui.QColor
import io.qt.gui.QIcon
import io.qt.gui.QPainter
import io.qt.gui.QPixmap

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
