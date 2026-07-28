/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.qt.core.QPoint
import io.qt.core.Qt
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget

class EditorContentPopup(parent: QWidget?) : QWidget(parent) {
    private val contentLayout = QVBoxLayout(this)
    private var currentContent: QWidget? = null

    init {
        setWindowFlags(Qt.WindowType.ToolTip, Qt.WindowType.FramelessWindowHint)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        contentLayout.setContentsMargins(0, 0, 0, 0)
        contentLayout.setSpacing(0)
    }

    fun setContent(widget: QWidget) {
        if (widget == currentContent) return
        currentContent?.let { contentLayout.removeWidget(it) }
        currentContent = widget
        contentLayout.addWidget(widget)
    }

    fun showAt(globalPos: QPoint) {
        move(globalPos)
        adjustSize()
        show()
        repaint()
    }
}
