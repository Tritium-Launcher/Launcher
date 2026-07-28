
/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.api.editor.intelligence.CompletionItem
import io.github.tritium_launcher.api.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
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
    private var snapshotDir: VPath? = null

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
     * @param snapshotDir Snapshot directory for loading item icons.
     */
    fun setCompletions(items: List<CompletionItem>, snapshotDir: VPath? = null) {
        val displayMode = CoreSettingValues.editorCompletionDisplayMode
        renderer.displayMode = displayMode
        completions = items
        this.snapshotDir = snapshotDir
        listWidget.clear()

        val fm = QFontMetrics(listWidget.font())
        var maxWidth = 200
        val iconWidth = 20
        val iconPadding = 6
        val rowHeightAdvanced = 24
        val rowHeightBasic = fm.height() + 4

        for (item in items) {
            val listItem = QListWidgetItem()
            val rowHeight: Int
            if (displayMode == CoreSettingValues.CompletionDisplayMode.Basic) {
                val suffix = kindLabel(item.kind)
                val displayText = if (suffix != null) "${item.label}  ($suffix)" else item.label
                listItem.setText(displayText)
                rowHeight = rowHeightBasic
                maxWidth = maxOf(maxWidth, fm.horizontalAdvance(displayText))
            } else {
                listItem.setText(item.label)
                listItem.setData(CompletionItemRenderer.DETAIL_ROLE, item.detail)
                rowHeight = rowHeightAdvanced
                val labelWidth = fm.horizontalAdvance(item.label)
                val detailWidth = if (item.detail != null) fm.horizontalAdvance(item.detail) else 0
                maxWidth = maxOf(maxWidth, labelWidth + detailWidth + 20 + iconWidth + iconPadding)
            }
            listItem.setSizeHint(QSize(0, rowHeight))
            if (item.documentation != null) {
                listItem.setToolTip(item.documentation)
            }
            listWidget.addItem(listItem)
        }

        setFixedWidth((maxWidth + 30).coerceIn(200, 600))

        if (items.isNotEmpty()) {
            if (snapshotDir != null) {
                val visibleCount = (maximumHeight / rowHeightAdvanced).coerceAtLeast(1)
                val eagerBatch = visibleCount * 3
                loadIconsForRange(0 until eagerBatch.coerceAtMost(items.size))
                connectLazyScroll()
            }
            listWidget.currentRow = 0
        }
    }

    private fun loadIconsForRange(range: IntRange) {
        for (i in range) {
            if (i !in completions.indices) break
            val item = listWidget.item(i) ?: continue
            if (item.data(CompletionItemRenderer.PIXMAP_ROLE) != null) continue
            val id = completions[i].label
            val pixmap = ItemPreviewWidget.loadItemIcon(id, null, snapshotDir, 16)
            if (pixmap != null) {
                item.setData(CompletionItemRenderer.PIXMAP_ROLE, pixmap)
            }
        }
        listWidget.viewport()?.update()
    }

    private var scrollConnected = false

    private fun connectLazyScroll() {
        if (scrollConnected) return
        scrollConnected = true
        val scrollBar = listWidget.verticalScrollBar() ?: return
        val slot = QMetaObject.Slot1<Int> {
            val viewport = listWidget.viewport() ?: return@Slot1
            val topLeft = viewport.rect().topLeft()
            val bottomLeft = viewport.rect().bottomLeft()
            val firstIndex = listWidget.indexAt(topLeft)
            val lastIndex = listWidget.indexAt(bottomLeft)
            if (firstIndex.isValid && lastIndex.isValid) {
                val visibleCount = lastIndex.row() - firstIndex.row() + 1
                val loadCount = (visibleCount * 3).coerceAtLeast(1)
                val startRow = firstIndex.row()
                val endRow = (startRow + loadCount).coerceAtMost(completions.size)
                loadIconsForRange(startRow until endRow)
            }
        }
        scrollBar.valueChanged.connect(slot)
    }

    private class CompletionItemRenderer : QStyledItemDelegate() {
        companion object {
            const val DETAIL_ROLE = Qt.ItemDataRole.UserRole
            const val PIXMAP_ROLE = Qt.ItemDataRole.UserRole + 1
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

            val pixmapRaw = index.data(PIXMAP_ROLE)
            val pixmap = pixmapRaw as? QPixmap

            val iconWidth = if (pixmap != null) 20 else 0
            if (pixmap != null) {
                val iconX = rect.left() + 2
                val iconY = rect.top() + (rect.height() - 16) / 2
                painter.drawPixmap(iconX, iconY, 16, 16, pixmap)
            }

            val leftX = rect.left() + 4 + iconWidth

            painter.setPen(TColors.Text.toQC())
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
                        painter.setPen(TColors.Subtext.toQC())
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
                        painter.setPen(TColors.Subtext.toQC())
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
                    painter.setPen(TColors.Subtext.toQC())
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
        snapshotDir = null
        scrollConnected = false
        close()
    }
}
