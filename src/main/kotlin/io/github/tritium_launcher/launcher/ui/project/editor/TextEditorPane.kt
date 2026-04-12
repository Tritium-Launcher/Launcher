package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.lsp.LSPMngr
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.LSPEditorAdapter
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.UniversalHighlighter
import io.qt.gui.QFont
import io.qt.gui.QSyntaxHighlighter
import io.qt.widgets.QFrame
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget

/**
 * The default code editor pane for all files in the Editor.
 */
class TextEditorPane(
    project: ProjectBase,
    file: VPath,
    language: SyntaxLanguage?,
): EditorPane(project, file) {
    private val textEdit = QTextEdit()
    private val font = QFont("JetBrains Mono", 11)
    private val highlighter: QSyntaxHighlighter?
    private val lspAdapter: LSPEditorAdapter?

    private val logger = logger()

    init {
        textEdit.font = font
        textEdit.lineWrapMode = QTextEdit.LineWrapMode.NoWrap
        textEdit.frameShape = QFrame.Shape.NoFrame
        textEdit.viewport()?.setContentsMargins(0, 0, 0, 0)
        highlighter = language?.let {
            UniversalHighlighter(textEdit.document!!, it)// TODO: Cannot leave assert
        }

        lspAdapter = LSPMngr.getOrStart(project, file)?.let { connection ->
            LSPEditorAdapter(file, textEdit, connection)
        }

        loadFile()
    }

    private fun loadFile() {
        try {
            if(file.exists()) {
                textEdit.plainText = file.readTextOr("")
            } else {
                textEdit.plainText = ""
            }
        } catch (t: Throwable) {
            logger.warn("Failed to load file {}", file.toAbsolute(), t)
            textEdit.plainText = ""
        }
    }

    override fun widget(): QWidget = textEdit

    override fun onClose() {
        lspAdapter?.close()
    }

    override suspend fun save(): Boolean = try {
        val text = textEdit.toPlainText()
        file.writeBytes(text.toByteArray())
        true
    } catch (t: Throwable) {
        logger.error("Failed saving {}", file.toAbsolute(), t)
        false
    }
}
