/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inlay

import io.github.tritium_launcher.api.connect
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget

class InlayMngr(private val textEdit: QTextEdit) {
    var inlays: List<Inlay> = emptyList()
        set(value) {
            field = value
            positionsDirty = true
            overlay.update()
        }

    private val overlay = InlayOverlay(textEdit.viewport()!!)
    private var positionsDirty = true
    private val positionCache = mutableMapOf<Int, QPoint>()
    private val lineEndCache = mutableMapOf<Int, QPoint>()
    private val hitRects = mutableMapOf<Int, QRect>()

    init {
        overlay.resize(textEdit.viewport()!!.size())

        val resizeFilter = object : QObject() {
            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                if (event?.type() == QEvent.Type.Resize) {
                    overlay.resize((event as QResizeEvent).size())
                    positionsDirty = true
                }
                return false
            }
        }
        textEdit.viewport()!!.installEventFilter(resizeFilter)

        val clickFilter = object : QObject() {
            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                if (event?.type() == QEvent.Type.MouseButtonPress) {
                    val me = event as QMouseEvent
                    if (me.button() == Qt.MouseButton.LeftButton) {
                        val pos = me.pos()
                        for ((offset, rect) in hitRects) {
                            if (rect.contains(pos)) {
                                val inlay = inlays.find { it.offset == offset } as? Inlay.Label ?: continue
                                inlay.onClick?.invoke()
                                overlay.update()
                                return true
                            }
                        }
                    }
                }
                return false
            }
        }
        textEdit.viewport()!!.installEventFilter(clickFilter)

        textEdit.verticalScrollBar()?.valueChanged?.connect { _: Int ->
            positionsDirty = true
            overlay.update()
        }
        textEdit.horizontalScrollBar()?.valueChanged?.connect { _: Int ->
            positionsDirty = true
            overlay.update()
        }
    }

    fun close() {
        overlay.hide()
        overlay.setParent(null)
    }

    fun offsetToViewportPos(offset: Int): QPoint? {
        val doc = textEdit.document() ?: return null
        val clamped = offset.coerceIn(0, doc.characterCount() - 1)
        val cursor = QTextCursor(doc).apply { setPosition(clamped) }
        val rect = textEdit.cursorRect(cursor)
        return QPoint(rect.x(), rect.y())
    }

    private fun blockEndViewportPos(offset: Int): QPoint? {
        val doc = textEdit.document() ?: return null
        val block = doc.findBlock(offset)
        if (!block.isValid) return null
        val blockEnd = (block.position() + block.length() - 1).coerceIn(0, doc.characterCount() - 1)
        val cursor = QTextCursor(doc).apply { setPosition(blockEnd) }
        val rect = textEdit.cursorRect(cursor)
        return QPoint(rect.x(), rect.y())
    }

    private inner class InlayOverlay(viewport: QWidget) : QWidget(viewport) {
        init {
            setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
            setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        }

        override fun paintEvent(event: QPaintEvent?) {
            val painter = QPainter(this)
            if (positionsDirty) {
                positionCache.clear()
                lineEndCache.clear()
                hitRects.clear()
                positionsDirty = false
            }
            for (inlay in inlays) {
                val pos = positionCache[inlay.offset]
                    ?: offsetToViewportPos(inlay.offset) ?: continue
                positionCache.putIfAbsent(inlay.offset, pos)
                doPaint(painter, inlay, pos)
            }
            painter.end()
        }

        private fun doPaint(painter: QPainter, inlay: Inlay, cursorPos: QPoint) {
            when (inlay) {
                is Inlay.Label -> {
                    val lineEnd = lineEndCache[inlay.offset]
                        ?: blockEndViewportPos(inlay.offset) ?: return
                    lineEndCache.putIfAbsent(inlay.offset, lineEnd)
                    val x = lineEnd.x() + 16
                    if (x > width()) return
                    val fm = painter.fontMetrics()
                    if (inlay.painter != null) {
                        inlay.painter.paint(painter, fm, lineEnd, inlay) { hitRects[inlay.offset] = it }
                        return
                    }
                    painter.setPen(inlay.color)
                    val textWidth = fm.horizontalAdvance(inlay.text)
                    val y = lineEnd.y() + fm.ascent()
                    painter.drawText(x, y, inlay.text)
                    if (inlay.onClick != null) {
                        hitRects[inlay.offset] = QRect(x, lineEnd.y(), textWidth + 4, fm.height())
                    }
                }
            }
        }
    }
}
