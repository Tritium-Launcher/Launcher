/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.widgets

import io.qt.core.QPoint
import io.qt.core.QRect
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QApplication
import io.qt.widgets.QWidget
import kotlin.math.max

data class TTooltipStyle(
    val background: QColor = QColor(16, 0, 16, 240),
    val borderTop: QColor = QColor(80, 0, 255, 110),
    val borderBottom: QColor = QColor(40, 0, 127, 110),
    val text: QColor = QColor(255, 255, 255)
)

object TTooltip {
    private val popup = TTooltipPopup()

    fun show(globalPos: QPoint, text: String, style: TTooltipStyle = TTooltipStyle()) {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            hide()
            return
        }

        popup.setContent(lines, style)
        popup.adjustSize()

        val screen = QApplication.screenAt(globalPos) ?: QApplication.primaryScreen()
        val available = screen?.availableGeometry()
        var x = globalPos.x() + 12
        var y = globalPos.y() + 12

        if (available != null) {
            if (x + popup.width() > available.right()) {
                x = globalPos.x() - popup.width() - 12
            }
            if (y + popup.height() > available.bottom()) {
                y = globalPos.y() - popup.height() - 12
            }
            x = x.coerceIn(available.left(), max(available.left(), available.right() - popup.width()))
            y = y.coerceIn(available.top(), max(available.top(), available.bottom() - popup.height()))
        }

        popup.move(x, y)
        popup.show()
        popup.raise()
    }

    fun renderPixmap(
        text: String,
        style: TTooltipStyle = TTooltipStyle(),
        icon: QPixmap? = null,
        iconSize: Int = 16
    ): QPixmap {
        val lines = text.lines().filter { it.isNotBlank() }.ifEmpty { listOf(text) }
        return TTooltipPopup.render(lines, style, icon, iconSize)
    }

    fun show(event: QHelpEvent, text: String, style: TTooltipStyle = TTooltipStyle(), globalPos: QPoint = event.globalPos()) {
        show(globalPos, text, style)
    }

    fun hide() {
        popup.hide()
    }
}

private class TTooltipPopup : QWidget(null as QWidget?) {
    private var lines: List<String> = emptyList()
    private var tooltipStyle: TTooltipStyle = TTooltipStyle()
    private val tooltipFont = QFont("SansSerif", 10)

    init {
        setWindowFlag(Qt.WindowType.ToolTip)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, true)
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
    }

    fun setContent(nextLines: List<String>, style: TTooltipStyle) {
        lines = nextLines
        tooltipStyle = style
        updateGeometry()
        update()
    }

    override fun sizeHint(): QSize {
        return sizeFor(lines, tooltipFont)
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        paint(painter, width(), height(), lines, tooltipFont, tooltipStyle)
        painter.end()
    }

    companion object {
        private const val PADDING_X = 8
        private const val PADDING_Y = 6

        fun render(lines: List<String>, style: TTooltipStyle, icon: QPixmap? = null, iconSize: Int = 16): QPixmap {
            val font = QFont("SansSerif", 10)
            val size = sizeFor(lines, font, icon, iconSize)
            val pixmap = QPixmap(size)
            pixmap.fill(QColor(0, 0, 0, 0))
            val painter = QPainter(pixmap)
            paint(painter, size.width(), size.height(), lines, font, style, icon, iconSize)
            painter.end()
            return pixmap
        }

        private fun sizeFor(lines: List<String>, font: QFont, icon: QPixmap? = null, iconSize: Int = 16): QSize {
            val metrics = QFontMetrics(font)
            val width = lines.maxOfOrNull { metrics.horizontalAdvance(it) } ?: 0
            val lineHeight = metrics.height()
            val gap = if (lines.size > 1) (lines.size - 1) * 2 else 0
            val iconWidth = if (icon != null) iconSize + 5 else 0
            val contentHeight = maxOf(lineHeight * lines.size + gap, if (icon != null) iconSize else 0)
            return QSize(width + iconWidth + PADDING_X * 2, contentHeight + PADDING_Y * 2)
        }

        private fun paint(
            painter: QPainter,
            width: Int,
            height: Int,
            lines: List<String>,
            font: QFont,
            style: TTooltipStyle,
            icon: QPixmap? = null,
            iconSize: Int = 16
        ) {
            painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)
            painter.setRenderHint(QPainter.RenderHint.TextAntialiasing, false)

            painter.fillRect(2, 2, width - 4, height - 4, style.background)
            painter.fillRect(3, 1, width - 6, 1, style.borderTop)
            painter.fillRect(3, height - 2, width - 6, 1, style.borderBottom)
            painter.fillRect(1, 3, 1, height - 6, style.borderTop)
            painter.fillRect(width - 2, 3, 1, height - 6, style.borderBottom)
            painter.fillRect(2, 2, 1, 1, style.borderTop)
            painter.fillRect(width - 3, 2, 1, 1, style.borderTop)
            painter.fillRect(2, height - 3, 1, 1, style.borderBottom)
            painter.fillRect(width - 3, height - 3, 1, 1, style.borderBottom)

            painter.setFont(font)
            painter.setPen(style.text)
            val metrics = QFontMetrics(font)
            val textX = PADDING_X + if (icon != null) iconSize + 5 else 0
            if (icon != null) {
                val iconY = (height - iconSize) / 2
                painter.drawPixmap(
                    PADDING_X,
                    iconY,
                    icon.scaled(
                        iconSize,
                        iconSize,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.FastTransformation
                    )
                )
            }

            var y = PADDING_Y + metrics.ascent()
            for (line in lines) {
                painter.drawText(
                    QRect(textX, y - metrics.ascent(), width - textX - PADDING_X, metrics.height()),
                    0,
                    line
                )
                y += metrics.height() + 2
            }
        }
    }
}
