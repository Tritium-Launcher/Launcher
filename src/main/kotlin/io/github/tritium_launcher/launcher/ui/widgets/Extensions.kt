package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.qt.core.QEasingCurve
import io.qt.core.QPropertyAnimation
import io.qt.core.QTimer
import io.qt.widgets.QGraphicsOpacityEffect
import io.qt.widgets.QWidget

/**
 * Applies an opacity effect to temporarily show a [QWidget]
 */
fun QWidget.showThenFade(
    showDurationMs: Int = 1200,
    fadeDurationMs: Int = 600
) {
    this.show()
    this.raise()

    val effect = QGraphicsOpacityEffect(this)
    this.setGraphicsEffect(effect)
    effect.opacity = 1.0

    QTimer.singleShot(showDurationMs) {
        val anim = QPropertyAnimation(effect, "opacity").apply {
            duration = fadeDurationMs
            startValue = 1.0
            endValue = 0.0
            setEasingCurve(QEasingCurve.Type.InOutQuad)
        }

        anim.finished.connect {
            this.hide()
            this.setGraphicsEffect(null)
        }

        anim.start()
    }
}