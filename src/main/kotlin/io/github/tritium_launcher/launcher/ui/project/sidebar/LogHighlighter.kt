/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.gui.QColor
import io.qt.gui.QSyntaxHighlighter
import io.qt.gui.QTextCharFormat
import io.qt.gui.QTextDocument

class LogHighlighter(doc: QTextDocument) : QSyntaxHighlighter(doc) {
    var searchText: String = ""
        set(value) {
            if (field != value) {
                field = value
                rehighlight()
            }
        }

    private val errorFmt = QTextCharFormat().apply { setForeground(TColors.Error.toQB()) }
    private val warnFmt = QTextCharFormat().apply { setForeground(TColors.Warning.toQB()) }
    private val infoFmt = QTextCharFormat().apply { setForeground(TColors.Syntax.Information.toQB()) }
    private val debugFmt = QTextCharFormat().apply { setForeground(TColors.Subtext.toQB()) }
    private val tsFmt = QTextCharFormat().apply { setForeground(TColors.Syntax.Comment.toQB()) }

    private val errorRx = Regex("\\b(ERROR|FATAL)\\b")
    private val warnRx = Regex("\\b(WARN(?:ING)?)\\b")
    private val infoRx = Regex("\\b(INFO)\\b")
    private val debugRx = Regex("\\b(DEBUG|TRACE)\\b")
    private val timeRx = Regex("\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?")
    private val levelRx = Regex("\\b(ERROR|FATAL|WARN(?:ING)?|INFO|DEBUG|TRACE)\\b")

    override fun highlightBlock(text: String) {
        data class FR(val start: Int, val length: Int, val fmt: QTextCharFormat)
        val logRanges = mutableListOf<FR>()

        fun addMatch(text: String, rx: Regex, fmt: QTextCharFormat) {
            rx.findAll(text).forEach { m ->
                logRanges.add(FR(m.range.first, m.range.last - m.range.first + 1, fmt))
            }
        }

        addMatch(text, errorRx, errorFmt)
        addMatch(text, warnRx, warnFmt)
        addMatch(text, infoRx, infoFmt)
        addMatch(text, debugRx, debugFmt)
        addMatch(text, timeRx, tsFmt)

        val m = levelRx.find(text)
        if (m != null) {
            val fmt = levelFormat(m.value)
            if (fmt != null) {
                val end = prefixEnd(text, m.range.last)
                logRanges.add(FR(0, end, fmt))
            }
        }

        for (r in logRanges) {
            setFormat(r.start, r.length, r.fmt)
        }

        if (searchText.isNotEmpty()) {
            val lower = text.lowercase()
            val needle = searchText.lowercase()
            var idx = lower.indexOf(needle)
            while (idx >= 0) {
                val overlap = logRanges.firstOrNull { r ->
                    idx < r.start + r.length && idx + searchText.length > r.start
                }
                val merged = QTextCharFormat().apply {
                    setBackground(QColor(255, 220, 40, 80))
                    if (overlap != null) setForeground(overlap.fmt.foreground())
                }
                setFormat(idx, searchText.length, merged)
                idx = lower.indexOf(needle, idx + searchText.length)
            }
        }
    }

    private fun prefixEnd(text: String, levelEnd: Int): Int {
        val idx = text.indexOf(": ", levelEnd + 1)
        return if (idx >= 0) idx + 2 else text.length
    }

    private fun levelFormat(level: String): QTextCharFormat? = when (level) {
        in arrayOf("ERROR", "FATAL") -> errorFmt
        in arrayOf("WARN", "WARNING") -> warnFmt
        "INFO" -> infoFmt
        in arrayOf("DEBUG", "TRACE") -> debugFmt
        else -> null
    }
}
