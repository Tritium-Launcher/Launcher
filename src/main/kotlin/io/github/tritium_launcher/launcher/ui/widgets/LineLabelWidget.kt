package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.qt.Nullable
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QWidget
import kotlin.math.roundToInt

/**
 * Displays Text with lines on either side to act as a separator.
 */
class LineLabelWidget(
    labelText: String,
    var positionPercent: Float = 0.5f,
    var spacingPx: Int = 12,
    var thickness: Int = 1,
    var lineColor: QColor = QColor.fromRgb(255, 255, 255),
    parent: QWidget? = null,

): QWidget(parent) {

    val label = label(labelText, this) {
        setAlignment(Qt.AlignmentFlag.AlignCenter)
        styleSheet = qtStyle {
            selector("QLabel") {
                fontSize(13)
                padding(0, 6, 0, 6)
            }
        }.toStyleSheet()
    }

    init {
        minimumHeight = 28
    }

    override fun paintEvent(event: @Nullable QPaintEvent?) {
        super.paintEvent(event)
        val p = QPainter(this)
        try {
            val pen = QPen(lineColor).apply {
                setWidth(thickness)
            }
            p.setPen(pen)

            val leftPadding = 8
            val rightPadding = 8
            val totalW = width - leftPadding - rightPadding
            val labelW = label.sizeHint.width().coerceAtMost(totalW)
            val available = (totalW - labelW).coerceAtLeast(0)

            val leftX = leftPadding + (available * positionPercent).roundToInt()
            val labelY = (height - label.sizeHint.height()) / 2

            val gap = spacingPx
            val leftLineEndX = (leftX - gap).coerceAtLeast(leftPadding)
            val rightLineStartX = (leftX + labelW + gap).coerceAtMost(width - rightPadding)
            val rightLineEndX = width - rightPadding

            val y = height / 2

            // Left line
            if (leftLineEndX > leftPadding) p.drawLine(leftPadding, y, leftLineEndX, y)
            // Right line
            if (rightLineEndX > rightLineStartX) p.drawLine(rightLineStartX, y, rightLineEndX, y)

            // position label widget
            label.setGeometry(leftX, labelY, labelW, label.sizeHint.height())
        } finally {
            p.end()
        }
    }

    override fun resizeEvent(event: @Nullable QResizeEvent?) {
        update()
        super.resizeEvent(event)
    }

    fun setText(text: String) {
        label.text = text
        update()
    }
}
