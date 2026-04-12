package io.github.tritium_launcher.launcher.ui.project.editor.syntax

import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.qt.NonNull
import io.qt.gui.QSyntaxHighlighter
import io.qt.gui.QTextCharFormat
import io.qt.gui.QTextDocument

class UniversalHighlighter(doc: QTextDocument, private val language: SyntaxLanguage): QSyntaxHighlighter(doc) {
    private val formats = mutableMapOf<String, QTextCharFormat>()

    override fun highlightBlock(text: @NonNull String) {
        for(rule in language.rules) {
            val matches = rule.pattern.findAll(text)
            for(match in matches) {
                val format = getFormatForToken(rule.tokenType)
                setFormat(match.range.first, match.range.last - match.range.first + 1, format)
            }
        }
    }

    private fun getFormatForToken(tokenType: String): QTextCharFormat {
        return formats.getOrPut(tokenType) {
            QTextCharFormat().apply {
                val color = ThemeMngr.getQColor("Syntax.$tokenType")
                    ?: ThemeMngr.getQColor("Syntax.Default")
                if(color != null) setForeground(color)
            }
        }
    }
}