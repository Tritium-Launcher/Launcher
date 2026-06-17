package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.qt.core.QPoint
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.widgets.QLabel
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

class HoverOverlay(parent: QWidget?) : QWidget(parent) {
    private val label = QLabel()

    private val markdownParser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
    private val markdownRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .escapeHtml(false)
        .build()

    init {
        setWindowFlags(Qt.WindowType.Popup)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)

        val layout = QVBoxLayout()
        layout.setContentsMargins(4, 4, 4, 4)
        setLayout(layout)

        label.wordWrap = true
        layout.addWidget(label)

        maximumWidth = 400
    }

    fun showHover(text: String, globalPos: QPoint) {
        val (family, size) = CoreSettingValues.editorFont()
        label.font = QFont(family, size)
        val doc = markdownParser.parse(text)
        val body = markdownRenderer.render(doc)
        label.text = "<div style=\"font-family: '$family', monospace; font-size: ${size}pt;\">$body</div>"
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
