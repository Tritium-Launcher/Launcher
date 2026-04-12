package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.qt.Nullable
import io.qt.core.QRect
import io.qt.gui.QPaintEvent
import io.qt.gui.QPainter
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QSizePolicy.Policy.Fixed
import io.qt.widgets.QWidget

/**
 * An element used for displaying information.
 */
class InformationWidget(val text: String, parent: QWidget? = null): QWidget(parent) {

    init {
        toolTip = text
        setFixedSize(18, 18)
        sizePolicy = QSizePolicy(Fixed, Fixed)
    }

    override fun paintEvent(event: @Nullable QPaintEvent?) {
        val painter = QPainter(this)
        val pixmap = TIcons.QuestionMark
        val target = QRect(0, 0, width, height)
        painter.drawPixmap(target, pixmap)
    }
}