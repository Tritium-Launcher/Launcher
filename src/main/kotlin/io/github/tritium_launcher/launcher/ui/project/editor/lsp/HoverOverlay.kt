package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.qt.core.QPoint
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.widgets.QLabel
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget

class HoverOverlay(parent: QWidget?) : QWidget(parent) {
    private val label = QLabel()

    init {
        setWindowFlags(Qt.WindowType.Popup)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)

        val layout = QVBoxLayout()
        layout.setContentsMargins(4, 4, 4, 4)
        setLayout(layout)

        label.font = QFont("JetBrains Mono", 10) //TODO: Use set font
        label.wordWrap = true
        layout.addWidget(label)

        maximumWidth = 400
    }

    fun showHover(text: String, globalPos: QPoint) {
        label.text = text
        adjustSize()
        move(globalPos.x() + 10, globalPos.y() + 10)
        raise()
        show()
        repaint()
    }

    fun cleanup() {
        label.clear()
        close()
    }
}
