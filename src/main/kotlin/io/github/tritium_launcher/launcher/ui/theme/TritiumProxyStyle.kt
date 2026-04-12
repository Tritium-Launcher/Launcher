package io.github.tritium_launcher.launcher.ui.theme

import io.qt.NonNull
import io.qt.Nullable
import io.qt.core.QObject
import io.qt.core.QRectF
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*

class TritiumProxyStyle(style: QStyle?): QProxyStyle(style) {

    private fun QWidget.propDouble(name: String, fallback: Double): Double {
        val v = this.property(name) ?: return fallback
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: fallback
            else -> fallback
        }
    }

    private fun QWidget.propBool(name: String, fallback: Boolean): Boolean {
        val v = this.property(name) ?: return fallback
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> v.equals("true", ignoreCase = true)
            else -> fallback
        }
    }

    private fun QWidget.propColor(name: String, fallback: QColor): QColor {
        val v = this.property(name) ?: return fallback
        return when (v) {
            is QColor -> v
            is String -> QColor(v)
            else -> fallback
        }
    }

    private fun parseColor(value: Any?): QColor? {
        return when (value) {
            is QColor -> value
            is String -> QColor(value)
            else -> null
        }
    }

    private fun findColorProperty(start: QObject?, key: String): QColor? {
        var current = start
        while (current != null) {
            parseColor(current.property(key))?.let { return it }
            current = current.parent()
        }
        return null
    }

    override fun drawPrimitive(
        element: @NonNull PrimitiveElement,
        option: @Nullable QStyleOption?,
        painter: @Nullable QPainter?,
        widget: @Nullable QWidget?
    ) {
        if(widget != null && option != null && painter != null) {
            if (
                element == PrimitiveElement.PE_IndicatorDockWidgetResizeHandle ||
                element == PrimitiveElement.PE_IndicatorToolBarSeparator
            ) {
                val fill = findColorProperty(widget, "sidebar.separatorColor")
                    ?: ThemeMngr.getQColor("Surface1")
                    ?: widget.palette().color(QPalette.ColorRole.Window)
                painter.fillRect(option.rect, fill)
                return
            }

            if(drawBorder(widget, element, option, painter)) return

            if(element == PrimitiveElement.PE_FrameFocusRect) {
                val enabledFocusSkip = widget.property("customBorderEnabled") as? Boolean ?: false
                if(enabledFocusSkip) return
            }

            if (element == PrimitiveElement.PE_FrameLineEdit && widget is QLineEdit) {
                val isInvalid = widget.property("invalid") as? Boolean ?: false
                if (isInvalid) {
                    painter.save()
                    try {
                        painter.setRenderHint(QPainter.RenderHint.Antialiasing)

                        val r = option.rect
                        val xf = r.x().toDouble()
                        val yf = r.y().toDouble()
                        val wf = r.width().toDouble()
                        val hf = r.height().toDouble()

                        val rectF = QRectF(xf + 0.5, yf + 0.5, wf - 1.0, hf - 1.0)

                        val radius = 2.0

                        val bgColor = ThemeMngr.getQColor("LineEdit.Bg") ?: widget.palette().color(QPalette.ColorRole.Base)
                        painter.setBrush(QBrush(bgColor))

                        val pen = QPen( ThemeMngr.getQColor("Invalid") ?: QColor("#ff4d6d") )
                        pen.setWidthF(1.0)
                        pen.isCosmetic = true
                        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
                        pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
                        painter.setPen(pen)

                        val path = QPainterPath()
                        path.addRoundedRect(rectF, radius, radius)
                        painter.drawPath(path)
                    } finally {
                        painter.restore()
                    }
                    return
                }
            }
        }

        super.drawPrimitive(element, option, painter, widget)
    }

    private fun drawBorder(
        w: QWidget,
        pe: PrimitiveElement,
        opt: QStyleOption,
        p: QPainter
    ): Boolean {
        if(pe != PrimitiveElement.PE_PanelButtonCommand && pe != PrimitiveElement.PE_Frame) return false

        val enabled = w.property("customBorderEnabled") as? Boolean ?: false
        if(!enabled) return false

        val radius = w.propDouble("customBorderRadius", 6.0)
        val stroke = w.propDouble("customBorderWidth", 1.0).coerceAtLeast(0.0)
        val color = w.propColor("customBorderColor", QColor(0, 0, 0, 255))
        val showOnHover = w.propBool("customBorderShowOnHover", false)
        val showOnPress = w.propBool("customBorderShowOnPress", true)

        val isHovered = w.underMouse()
        val isPressed = when (w) {
            is QAbstractButton -> w.isDown
            else -> false
        }

        val shouldShowBorder = when {
            isPressed && showOnPress -> true
            isHovered && showOnHover -> true
            !showOnHover && !showOnPress -> true
            else -> false
        }

        p.save()
        p.setRenderHint(QPainter.RenderHint.Antialiasing, true)

        val pal = w.palette()
        val bgColor = if (w.isEnabled) pal.color(QPalette.ColorRole.Button) else pal.color(QPalette.ColorRole.Button).darker(120)

        val inset = stroke / 2.0
        val rectF = QRectF(opt.rect)
        rectF.adjust(inset, inset, -inset, -inset)

        val path = QPainterPath()
        path.addRoundedRect(rectF, radius, radius)
        p.setBrush(QBrush(bgColor))
        p.setPen(QPen(Qt.PenStyle.NoPen))
        p.drawPath(path)

        if (shouldShowBorder && stroke > 0.0) {
            val outerPen = QPen(color, stroke)
            outerPen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            outerPen.setCapStyle(Qt.PenCapStyle.RoundCap)
            outerPen.isCosmetic = stroke <= 1.0
            p.setPen(outerPen)
            p.setBrush(QBrush())
            p.drawPath(path)
        }

        p.restore()
        return true
    }

    override fun pixelMetric(
        metric: @NonNull PixelMetric,
        option: @Nullable QStyleOption?,
        widget: @Nullable QWidget?
    ): Int {
        return when (metric) {
            PixelMetric.PM_DockWidgetSeparatorExtent,
            PixelMetric.PM_SplitterWidth -> 1
            else -> super.pixelMetric(metric, option, widget)
        }
    }


}

fun QWidget.enableCustomBorder(
    radius: Double = 6.0,
    borderColor: QColor = QColor(0, 0, 0, 80),
    borderWidth: Double = 1.0,
    showOnHover: Boolean = false,
    showOnPress: Boolean = true
) {
    this.setAttribute(Qt.WidgetAttribute.WA_Hover, true)
    this.mouseTracking = true

    this.setProperty("customBorderEnabled", true)
    this.setProperty("customBorderRadius", radius)
    this.setProperty("customBorderWidth", borderWidth)
    this.setProperty("customBorderColor", borderColor)
    this.setProperty("customBorderShowOnHover", showOnHover)
    this.setProperty("customBorderShowOnPress", showOnPress)
    this.update()
}

fun QWidget.disableCustomBorder() {
    this.setProperty("customBorderEnabled", false)
    this.update()
}
