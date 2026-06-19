package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItem
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.core.QMetaObject
import io.qt.core.QModelIndex
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*

/**
 * Popup widget that displays code completion suggestions in a frameless list.
 */
class CompletionPopup(parent: QWidget?) : QFrame(parent) {
    private val listWidget = QListWidget()
    private val renderer = CompletionItemRenderer()
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
        listWidget.setItemDelegate(renderer)
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
        val displayMode = CoreSettingValues.editorCompletionDisplayMode
        renderer.displayMode = displayMode
        completions = items
        listWidget.clear()
        val fm = QFontMetrics(listWidget.font())
        var maxWidth = 200

        for (item in items) {
            val listItem = QListWidgetItem()
            val rowHeight: Int
            if (displayMode == CoreSettingValues.CompletionDisplayMode.Basic) {
                val suffix = kindLabel(item.kind)
                val displayText = if (suffix != null) "${item.label}  ($suffix)" else item.label
                listItem.setText(displayText)
                rowHeight = fm.height() + 4
                maxWidth = maxOf(maxWidth, fm.horizontalAdvance(displayText))
            } else {
                listItem.setText(item.label)
                listItem.setData(CompletionItemRenderer.DETAIL_ROLE, item.detail)
                rowHeight = 22
                maxWidth = maxOf(
                    maxWidth,
                    fm.horizontalAdvance(item.label) +
                        (if (item.detail != null) fm.horizontalAdvance(item.detail) else 0) + 20
                )
            }
            listItem.setSizeHint(QSize(0, rowHeight))
            if (item.documentation != null) {
                listItem.setToolTip(item.documentation)
            }
            listWidget.addItem(listItem)
        }

        setFixedWidth((maxWidth + 30).coerceIn(200, 600))
        if (items.isNotEmpty()) {
            listWidget.currentRow = 0
        }
    }

    private class CompletionItemRenderer : QStyledItemDelegate() {
        companion object {
            const val DETAIL_ROLE = Qt.ItemDataRole.UserRole
        }

        var displayMode: CoreSettingValues.CompletionDisplayMode = CoreSettingValues.CompletionDisplayMode.Advanced

        override fun paint(painter: QPainter?, option: QStyleOptionViewItem, index: QModelIndex) {
            if (displayMode == CoreSettingValues.CompletionDisplayMode.Basic) {
                super.paint(painter, option, index)
                return
            }

            val label = index.data(Qt.ItemDataRole.DisplayRole)?.toString() ?: return
            val detail = index.data(DETAIL_ROLE)?.toString()
            val rect = option.rect

            val bg = if (option.state.testFlag(QStyle.StateFlag.State_Selected))
                option.palette.color(QPalette.ColorRole.Highlight)
            else
                option.palette.color(QPalette.ColorRole.Base)
            if(painter == null) return
            painter.fillRect(rect, bg)

            painter.setFont(option.font)
            val fm = painter.fontMetrics()
            val textY = rect.top() + (rect.height() - fm.height()) / 2 + fm.ascent()
            val leftX = rect.left() + 4

            painter.setPen(QColor(TColors.Text))
            painter.drawText(leftX, textY, label)

            if (detail != null && detail.startsWith("(")) {
                val closeParen = detail.indexOf("): ")
                if (closeParen >= 0) {
                    val paramsText = detail.substring(0, closeParen + 1)
                    val returnType = detail.substring(closeParen + 3)
                    val labelWidth = fm.horizontalAdvance(label)
                    val returnWidth = if (returnType.isNotEmpty()) fm.horizontalAdvance(returnType) else 0
                    val paramsStart = leftX + labelWidth
                    val returnStart = rect.right() - returnWidth - 4
                    val gap = 8

                    if (returnWidth > 0) {
                        painter.setPen(QColor(TColors.Subtext))
                        painter.drawText(returnStart, textY, returnType)
                    }

                    val maxParamsWidth = returnStart - gap - paramsStart
                    if (maxParamsWidth > 0) {
                        val paramsWidth = fm.horizontalAdvance(paramsText)
                        val displayParams = if (paramsWidth > maxParamsWidth) {
                            val ellipsis = "..."
                            val ellipsisWidth = fm.horizontalAdvance(ellipsis)
                            var truncateLen = paramsText.length
                            while (truncateLen > 0 && fm.horizontalAdvance(paramsText.substring(0, truncateLen)) + ellipsisWidth > maxParamsWidth) {
                                truncateLen--
                            }
                            if (truncateLen > 0) paramsText.substring(0, truncateLen) + ellipsis else paramsText
                        } else {
                            paramsText
                        }
                        painter.setPen(QColor(TColors.Subtext))
                        painter.drawText(paramsStart, textY, displayParams)
                    }
                }
            } else if (detail != null) {
                val labelWidth = fm.horizontalAdvance(label)
                val detailStart = leftX + labelWidth + 8
                val maxDetailWidth = rect.right() - 4 - detailStart
                if (maxDetailWidth > 0) {
                    val detailWidth = fm.horizontalAdvance(detail)
                    val displayDetail = if (detailWidth > maxDetailWidth) {
                        val ellipsis = "..."
                        val ellipsisWidth = fm.horizontalAdvance(ellipsis)
                        var truncateLen = detail.length
                        while (truncateLen > 0 && fm.horizontalAdvance(detail.substring(0, truncateLen)) + ellipsisWidth > maxDetailWidth) {
                            truncateLen--
                        }
                        if (truncateLen > 0) detail.substring(0, truncateLen) + ellipsis else detail
                    } else {
                        detail
                    }
                    painter.setPen(QColor(TColors.Subtext))
                    painter.drawText(detailStart, textY, displayDetail)
                }
            }
        }
    }

    private fun kindLabel(kind: CompletionItemKind): String? = when (kind) {
        CompletionItemKind.Method -> "method"
        CompletionItemKind.Function -> "function"
        CompletionItemKind.Field -> "field"
        CompletionItemKind.Variable -> "variable"
        CompletionItemKind.Class -> "class"
        CompletionItemKind.Property -> "property"
        CompletionItemKind.Keyword -> "keyword"
        CompletionItemKind.Module -> "module"
        CompletionItemKind.Text -> null
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
