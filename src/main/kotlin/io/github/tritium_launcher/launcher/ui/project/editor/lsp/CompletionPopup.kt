package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItem
import io.qt.core.QMetaObject
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QFontMetrics
import io.qt.gui.QKeyEvent
import io.qt.widgets.*

/**
 * Popup widget that displays code completion suggestions in a frameless list.
 */
class CompletionPopup(parent: QWidget?) : QFrame(parent) {
    private val listWidget = QListWidget()
    var onSelected: ((CompletionItem) -> Unit)? = null
    private var completions: List<CompletionItem> = emptyList()

    init {
        setWindowFlags(Qt.WindowType.ToolTip, Qt.WindowType.FramelessWindowHint)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        lineWidth = 1
        focusPolicy = Qt.FocusPolicy.NoFocus

        val layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        setLayout(layout)

        listWidget.font = QFont("JetBrains Mono", 11) //TODO: Use set font
        listWidget.horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        listWidget.focusPolicy = Qt.FocusPolicy.NoFocus
        val slot = QMetaObject.Slot1<QListWidgetItem?> { item ->
            val idx = listWidget.row(item)
            if (idx in completions.indices) {
                onSelected?.invoke(completions[idx])
                hide()
            }
        }
        listWidget.itemClicked.connect(slot)
        layout.addWidget(listWidget)
        maximumHeight = 250
    }

    /**
     * Populates the popup with completion items and resizes to fit their content.
     *
     * @param items Completion suggestions to display.
     */
    fun setCompletions(items: List<CompletionItem>) {
        completions = items
        listWidget.clear()
        val fm = QFontMetrics(listWidget.font())
        var maxWidth = 200
        for (item in items) {
            val displayText = if (item.detail != null) "${item.label}  ·  ${item.detail}" else item.label
            val listItem = QListWidgetItem(displayText)
            if (item.documentation != null) {
                listItem.setToolTip(item.documentation)
            }
            listWidget.addItem(listItem)
            maxWidth = maxOf(maxWidth, fm.horizontalAdvance(displayText))
        }
        setFixedWidth((maxWidth + 30).coerceIn(200, 600))
        if (items.isNotEmpty()) {
            listWidget.currentRow = 0
        }
    }

    /**
     * Handles keyboard input for navigation and selection.
     *
     * @param event Incoming key event from the editor.
     * @return `true` if the event was handled, `false` to let it propagate.
     */
    fun handleKeyEvent(event: QKeyEvent): Boolean {
        when (event.key()) {
            Qt.Key.Key_Up.value() -> {
                val row = listWidget.currentRow()
                if (row > 0) listWidget.currentRow = row - 1
                return true
            }
            Qt.Key.Key_Down.value() -> {
                val row = listWidget.currentRow()
                if (row < listWidget.count() - 1) listWidget.currentRow = row + 1
                return true
            }
            Qt.Key.Key_Tab.value() -> {
                val item = listWidget.currentItem() ?: return true
                val idx = listWidget.row(item)
                if (idx in completions.indices) {
                    onSelected?.invoke(completions[idx])
                }
                hide()
                return true
            }
            Qt.Key.Key_Return.value(), Qt.Key.Key_Enter.value() -> {
                val item = listWidget.currentItem() ?: return true
                val idx = listWidget.row(item)
                if (idx in completions.indices) {
                    onSelected?.invoke(completions[idx])
                }
                hide()
                return true
            }
            Qt.Key.Key_Escape.value() -> {
                hide()
                return true
            }
        }
        return false
    }

    /**
     * Clears completion state and closes the popup.
     */
    fun cleanup() {
        listWidget.clear()
        completions = emptyList()
        close()
    }
}
