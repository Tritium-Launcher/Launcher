/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultRenderer
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.QPaintEvent
import io.qt.gui.QPainter
import io.qt.gui.QPixmap
import io.qt.widgets.QLabel
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget

class FileSearchResultRenderer : SearchResultRenderer {
    override val id = "file"
    override val handledKinds = setOf("file", "config")

    override fun buildDetailPane(result: SearchResult, context: SearchDetailContext): QWidget {
        return QWidget().apply {
            objectName = "fileDetailBody"
            vBoxLayout(this) {
                contentsMargins = 16.m
                widgetSpacing = 8

                val ext = result.path.substringAfterLast('.', "")
                val fileTypeLabel = result.kind.replaceFirstChar { it.uppercase() } +
                    if (ext.isNotEmpty() && result.kind == "file") " \u00b7 .$ext File" else if (result.kind == "config") " File" else " File"

                addWidget(SearchIconLoader.subtextHeader(fileTypeLabel))

                if (result.kind == "file" && ext.lowercase() in TConstants.Lists.ImageExtensions) {
                    val pix = QPixmap(result.path)
                    if (!pix.isNull) {
                        addWidget(ImagePreview(pix))
                    }
                }

                addWidget(SearchIconLoader.subtextHeader("PATH"))
                addWidget(QLabel(result.path).apply {
                    wordWrap = true
                    styleSheet = "color: ${TColors.Text}; font-size: 12px; font-family: monospace;"
                })

                addStretch(1)
            }
        }
    }

}

private class ImagePreview(private val source: QPixmap) : QWidget() {
    private var displayPixmap: QPixmap? = null
    private var lastWidth: Int = 0

    init {
        setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
        setMinimumSize(100, 60)
    }

    override fun sizeHint(): QSize = QSize(source.width(), source.height())

    override fun hasHeightForWidth(): Boolean = true

    override fun heightForWidth(w: Int): Int {
        if (source.isNull || w <= 0) return minimumHeight()
        val h = w.toFloat() * source.height() / source.width()
        return h.toInt().coerceAtLeast(minimumHeight())
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        if (source.isNull) { painter.end(); return }

        val w = width()
        val h = height()
        if (w <= 0 || h <= 0) { painter.end(); return }

        if (w != lastWidth || displayPixmap == null) {
            displayPixmap = source.scaled(
                w, h, Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation
            )
            lastWidth = w
        }

        val dp = displayPixmap ?: return
        val dx = (w - dp.width()) / 2
        val dy = (h - dp.height()) / 2
        painter.drawPixmap(dx, dy, dp)
        painter.end()
    }
}
