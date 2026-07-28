/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.widgets

import io.qt.core.QMetaObject
import io.qt.core.QPoint
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.core.Qt.WindowType.*
import io.qt.gui.QFontMetrics
import io.qt.gui.QKeyEvent
import io.qt.widgets.*

class SuggestionPopup: QFrame(null as QWidget?) {
    private val listWidget = QListWidget()
    var onSelected: ((String) -> Unit)? = null
    private var items: List<String> = emptyList()

    init {
        setWindowFlags(
            ToolTip,
            FramelessWindowHint,
            WindowStaysOnTopHint
        )
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        focusPolicy = Qt.FocusPolicy.NoFocus

        lineWidth = 1
        setStyleSheet("background-color: #2b2b2b; border: 1px solid #555;")

        val layout = QVBoxLayout()
        layout.setContentsMargins(0, 0, 0, 0)
        setLayout(layout)

        listWidget.focusPolicy = Qt.FocusPolicy.NoFocus
        listWidget.itemClicked.connect( { item ->
            val idx = listWidget.row(item)
            if (idx in items.indices) {
                onSelected?.invoke(items[idx])
                hide()
            }
        })
        layout.addWidget(listWidget)

        setMaximumHeight(200)
        setMinimumWidth(120)
        setMinimumHeight(0)

        QApplication.instance()?.applicationStateChanged?.connect(
            QMetaObject.Slot1 { state ->
                if (state != Qt.ApplicationState.ApplicationActive) hide()
            }
        )
    }

    fun showSuggestions(suggestions: List<String>, anchor: QWidget, offsetX: Int = 0) {
        items = suggestions
        listWidget.clear()

        if (suggestions.isEmpty()) {
            hide()
            return
        }

        val fm = QFontMetrics(listWidget.font())
        val itemHeight = fm.height() + 6
        var maxWidth = 120
        for (s in suggestions) {
            val listItem = QListWidgetItem(s)
            listItem.setSizeHint(QSize(0, itemHeight))
            listWidget.addItem(listItem)
            maxWidth = maxOf(maxWidth, fm.horizontalAdvance(s) + 20)
        }

        val popupHeight = (suggestions.size * itemHeight + 4).coerceAtMost(200)
        val popupWidth = maxWidth.coerceIn(120, 500)
        resize(popupWidth, popupHeight)

        listWidget.currentRow = 0

        val cursorGlobalPos = (anchor as? QTextEdit)?.let { textEdit ->
            val cursorRect = textEdit.cursorRect()
            textEdit.mapToGlobal(cursorRect.bottomLeft())
        } ?: anchor.mapToGlobal(QPoint(0, anchor.height()))

        move(cursorGlobalPos.x() + offsetX, cursorGlobalPos.y() - height() - 12)
        show()
    }

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
            Qt.Key.Key_Return.value(), Qt.Key.Key_Enter.value() -> {
                val item = listWidget.currentItem()
                if (item != null) {
                    val idx = listWidget.row(item)
                    if (idx in items.indices) {
                        onSelected?.invoke(items[idx])
                        hide()
                    }
                }
                return true
            }
            Qt.Key.Key_Escape.value() -> {
                hide()
                return true
            }
        }
        return false
    }

    fun currentSuggestion(): String? {
        val item = listWidget.currentItem() ?: return null
        val idx = listWidget.row(item)
        return items.getOrNull(idx)
    }
}
