package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.QRectF
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QPushButton
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class LongPressButton(parent: QWidget? = null) : QPushButton(parent) {
    private val holdMs = 600L
    private var pressStart = 0L
    private var animProgress = 0f
    private var animActive = false
    private var longPressFired = false
    private var holdActive = false

    var onNormalClick: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private var holdJob: Job? = null

    override fun mousePressEvent(event: QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            val shiftHeld = QGuiApplication.queryKeyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
            if (shiftHeld) {
                holdActive = true
                longPressFired = false
                animActive = false
                animProgress = 0f
                pressStart = System.currentTimeMillis()
                holdJob?.cancel()
                holdJob = pressScope.launch {
                    while (isActive) {
                        val elapsed = System.currentTimeMillis() - pressStart
                        if (elapsed >= holdMs) {
                            animActive = false
                            animProgress = 1f
                            update()
                            onLongPress?.invoke()
                            longPressFired = true
                            return@launch
                        }
                        animActive = true
                        animProgress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
                        update()
                        delay(16.milliseconds)
                    }
                }
            } else {
                holdActive = false
            }
        }
        super.mousePressEvent(event)
    }

    override fun mouseReleaseEvent(event: QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            if (holdActive) {
                holdActive = false
                holdJob?.cancel()
                holdJob = null
                animActive = false
                animProgress = 0f
                update()
                if (!longPressFired) {
                    onNormalClick?.invoke()
                }
                blockSignals(true)
                super.mouseReleaseEvent(event)
                blockSignals(false)
                return
            }
            onNormalClick?.invoke()
        }
        super.mouseReleaseEvent(event)
    }

    override fun paintEvent(event: QPaintEvent?) {
        super.paintEvent(event)
        if (animActive && animProgress > 0.01f) {
            val painter = QPainter(this)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing)
            val pen = QPen(QColor(TColors.Accent), 2.5)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            painter.setPen(pen)
            val w = width()
            val h = height()
            val diameter = minOf(w, h) - 8
            val r = diameter / 2.0
            val cx = w / 2.0
            val cy = h / 2.0
            val rect = QRectF(cx - r, cy - r, diameter.toDouble(), diameter.toDouble())
            val startAngle = 90 * 16
            val span = -(360.0 * animProgress * 16).toInt()
            painter.drawArc(rect, startAngle, span)
            painter.end()
        }
    }

    companion object {
        private val pressScope = CoroutineScope(Dispatchers.Main)
    }
}
