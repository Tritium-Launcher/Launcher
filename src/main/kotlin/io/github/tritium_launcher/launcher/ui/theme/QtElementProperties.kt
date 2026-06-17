package io.github.tritium_launcher.launcher.ui.theme

import io.qt.core.QPoint
import io.qt.widgets.QToolTip
import io.qt.widgets.QWidget

/**
 * Adds a property to this [QWidget] declaring it invalid
 */
fun QWidget.setInvalid(state: Boolean, msg: String? = null) {
    this.setProperty("invalid", state)
    this.style()?.polish(this)
    this.update()

    if(state) {
        msg?.let {
            val pos = this.mapToGlobal(QPoint(this.width() / 2, 0))
            QToolTip.showText(pos, it, this)
        }
    }
}