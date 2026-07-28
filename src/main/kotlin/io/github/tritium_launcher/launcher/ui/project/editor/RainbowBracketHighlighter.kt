/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.qt.gui.QColor
import io.qt.gui.QTextCharFormat
import io.qt.gui.QTextCursor
import io.qt.gui.QTextDocument
import io.qt.widgets.QTextEdit

object RainbowBracketHighlighter {
    data class StackEntry(val char: Char, val depth: Int, val pos: Int, val resultIdx: Int)
    private data class Palette(
        val formats: List<QTextCharFormat>,
        val errorFormat: QTextCharFormat
    )

    private var palette: Palette? = null
    private var cachedThemeId: String? = null

    private val currentPalette: Palette
        get() {
            val currentTheme = ThemeMngr.currentColorThemeIdValue
            if (cachedThemeId != currentTheme || palette == null) {
                val hexColors = RainbowBracketColorGenerator.loadOrGenerate(
                    currentTheme.ifBlank { "default" }
                )
                val formats = hexColors.map { hex ->
                    QTextCharFormat().apply { setForeground(QColor(hex)) }
                }
                val errorFormat = QTextCharFormat().apply {
                    setForeground(TColors.Error.toQC())
                }
                palette = Palette(formats, errorFormat)
                cachedThemeId = currentTheme
            }
            return palette!!
        }

    private val closerToOpener = mapOf(')' to '(', ']' to '[', '}' to '{')

    data class SyntaxProfile(
        val lineCommentPrefix: String? = "//",
        val blockCommentOpen:  String? = "/*",
        val blockCommentClose: String? = "*/",
        val multilineStringDelimiter: String? = "\"\"\"",
        val stringDelimiters:  Set<Char> = setOf('"', '\'')
    )

    fun highlight(textEdit: QTextEdit, profile: SyntaxProfile = SyntaxProfile()): List<QTextEdit.ExtraSelection> {
        if (!CoreSettingValues.editorRainbowBrackets) return emptyList()
        val doc = textEdit.document ?: return emptyList()
        val text = doc.toPlainText()
        if (text.isEmpty()) return emptyList()

        val p      = currentPalette
        logger().warn("Palette size: ${p.formats.size}")
        val result = mutableListOf<QTextEdit.ExtraSelection>()
        val stack  = ArrayDeque<StackEntry>()

        var i = 0
        while(i < text.length) {
            when {
                // Skip line comments
                profile.lineCommentPrefix != null && text.startsWith(profile.lineCommentPrefix, i) -> {
                    i = (text.indexOf('\n').takeIf { it != -1 }?.plus(1)) ?: text.length
                }

                // Skip block comments
                profile.blockCommentOpen != null && text.startsWith(profile.blockCommentOpen, i) -> {
                    val close = profile.blockCommentClose ?: "*/"
                    i = (text.indexOf(close, i + profile.blockCommentOpen.length).takeIf { it != -1 }?.plus(close.length)) ?: text.length
                }

                // Skip multiline strings
                profile.multilineStringDelimiter != null && text.startsWith(profile.multilineStringDelimiter, i) -> {
                    val delim = profile.multilineStringDelimiter
                    i += delim.length
                    while(i < text.length && !text.startsWith(delim, i)) i++
                    i += delim.length
                }

                // Skip strings
                text[i] in profile.stringDelimiters -> {
                    val delim = text[i]
                    i++
                    while(i < text.length && text[i] != delim) {
                        if(text[i] == '\\') i++
                        i++
                    }
                    i++
                }

                // Skip chars
                text[i] == '\'' -> {
                    i++
                    while(i < text.length && text[i] != '\'') {
                        if(text[i] == '\\') i++
                        i++
                    }
                    i++
                }

                text[i] in "([{" -> {
                    val depth = stack.size
                    val resultIdx = result.size
                    val fmt = p.formats[depth % p.formats.size]
                    logger().warn("'${text[i]}' at line ${text.substring(0, i).count { it == '\n' } + 1} depth=$depth")
                    result += selection(doc, i, i + 1, fmt)
                    stack.addLast(StackEntry(text[i], depth, i, resultIdx))
                    i++
                }

                text[i] in ")]}" -> {
                    val expectedOpener = closerToOpener[text[i]]

                    when {
                        stack.isNotEmpty() && stack.last().char == expectedOpener -> {
                            val entry = stack.removeLast()
                            result.add(selection(doc, i, i + 1, p.formats[entry.depth % p.formats.size]))
                        }

                        else -> {
                            val matchIdx = stack.indexOfLast { it.char == expectedOpener }
                            if(matchIdx >= 0) {
                                while(stack.size > matchIdx + 1) {
                                    val orphan = stack.removeLast()
                                    result[orphan.resultIdx] =
                                        selection(doc, orphan.pos, orphan.pos + 1, p.errorFormat)
                                }
                                val entry = stack.removeLast()
                                result.add(selection(doc, i, i + 1, p.formats[entry.depth % p.formats.size]))
                            } else {
                                result.add(selection(doc, i, i + 1, p.errorFormat))
                            }
                        }
                    }
                    i++
                }

                else -> i++
            }
        }

        logger().warn("Palette size: ${p.formats.size}")

        for((_, _, pos, resultIdx) in stack) result[resultIdx] = selection(doc, pos, pos + 1, p.errorFormat)

        return result
    }

    private fun selection(doc: QTextDocument, start: Int, end: Int, format: QTextCharFormat): QTextEdit.ExtraSelection {
        val limit = (doc.characterCount() - 1).coerceAtLeast(0)
        return QTextEdit.ExtraSelection().apply {
            cursor = QTextCursor(doc).apply {
                setPosition(start.coerceIn(0, limit))
                setPosition(end.coerceIn(0, limit), QTextCursor.MoveMode.KeepAnchor)
            }
            this.format = format
        }
    }
}
