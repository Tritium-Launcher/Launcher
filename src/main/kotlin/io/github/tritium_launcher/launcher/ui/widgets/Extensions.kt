package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.hexToQColor
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.*
import io.qt.widgets.*

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

fun QLineEdit.showErrorIfEmpty(msg: String) {
    if(this.text != "") return

    val alreadyApplied = (this.property("validationOutlineApplied") as? Boolean) == true
    if(!alreadyApplied) {
        try {
            val outline = QGraphicsDropShadowEffect(this).apply {
                blurRadius = 10.0
                offset = QPointF(0.0, 0.0)
                color = TColors.Error.hexToQColor()
            }
            this.setGraphicsEffect(outline)
            this.setProperty("validationOutlineApplied", true)
        } catch (t: Throwable) {
            logger().debug("Issue", t)
        }

        val globalPos = mapToGlobal(QPoint(width / 2, 0))
        QToolTip.showText(globalPos, msg, this)
    }

    val globalPos = this.mapToGlobal(QPoint(this.width() / 2, 0))

    QToolTip.showText(globalPos, msg, this)
}