package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.hexToQColor
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
            val currentTheme = ThemeMngr.currentThemeIdValue
            if (cachedThemeId != currentTheme || palette == null) {
                val hexColors = RainbowBracketColorGenerator.loadOrGenerate(
                    currentTheme.ifBlank { "default" }
                )
                val formats = hexColors.map { hex ->
                    QTextCharFormat().apply { setForeground(QColor(hex)) }
                }
                val errorFormat = QTextCharFormat().apply {
                    setForeground(TColors.Error.hexToQColor())
                }
                palette = Palette(formats, errorFormat)
                cachedThemeId = currentTheme
            }
            return palette!!
        }

    private val closerToOpener = mapOf(')' to '(', ']' to '[', '}' to '{')

    fun highlight(textEdit: QTextEdit): List<QTextEdit.ExtraSelection> {
        if (!CoreSettingValues.editorRainbowBrackets) return emptyList()
        val doc = textEdit.document ?: return emptyList()
        val text = doc.toPlainText()
        if (text.isEmpty()) return emptyList()

        val p      = currentPalette
        val result = mutableListOf<QTextEdit.ExtraSelection>()
        val stack  = ArrayDeque<StackEntry>()

        var i = 0
        while(i < text.length) {
            when {
                // Skip line comments
                text.startsWith("//", i) -> {
                    i = (text.indexOf('\n', i).takeIf { it != -1 }?.plus(1)) ?: text.length
                }

                // Skip block comments
                text.startsWith("/*", i) -> {
                    i = (text.indexOf("*/", i + 2).takeIf { it != -1 }?.plus(2)) ?: text.length
                }

                // Skip strings
                text[i] == '"' -> {
                    i++
                    while(i < text.length && text[i] != '"') {
                        if(text[i] == '\\') i++
                        i++
                    }
                    i++
                }

                // Skip multiline strings
                text.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) i++
                    i += 3
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
                    result += selection(doc, i, i + 1, p.formats[depth % p.formats.size])
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

        for(entry in stack) result[entry.resultIdx] = selection(doc, entry.pos, entry.pos + 1, p.errorFormat)

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
