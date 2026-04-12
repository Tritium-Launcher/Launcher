package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.hexToQColor
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.lsp.LSPConnection
import io.github.tritium_launcher.launcher.lsp.LSPEventBus
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.gui.QColor
import io.qt.gui.QTextCharFormat
import io.qt.gui.QTextCursor
import io.qt.gui.QTextDocument
import io.qt.widgets.QTextEdit
import org.eclipse.lsp4j.*

/**
 * Bridges a QTextEdit instance to the LSP textDocument notifications and
 * renders diagnostics as underlines in the editor.
 */
class LSPEditorAdapter(
    val file: VPath,
    val textEdit: QTextEdit,
    val connection: LSPConnection
) {
    private val uri = file.toFileUriEncoded().toString()
    private var version = 0
    private val listenerId: Int
    private var isReady = false

    init {
        connection.ready.thenRun {
            runOnGuiThread {
                isReady = true
                val text = textEdit.toPlainText()
                sendDidOpen(text)
            }
        }

        textEdit.textChanged.connect {
            if(!isReady) {
                return@connect
            }
            sendDidChange(textEdit.toPlainText())
        }

        listenerId = LSPEventBus.subscribe { params ->
            if(params.uri == uri) applyDiagnostics(params.diagnostics)
        }
    }

    /**
     * Unregisters diagnostics listener and notifies the server that the document closed.
     */
    fun close() {
        if(isReady) {
            connection.server.textDocumentService.didClose(
                DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
            )
        }
        textEdit.setExtraSelections(emptyList())
        LSPEventBus.unsubscribe(listenerId)
        io.github.tritium_launcher.launcher.lsp.LSPMngr.release(connection.project, connection.langId)
    }

    private fun sendDidOpen(text: String) {
        connection.server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, connection.langId, version++, text))
        )
    }

    private fun sendDidChange(text: String) {
        connection.server.textDocumentService.didChange(DidChangeTextDocumentParams().apply {
            textDocument = VersionedTextDocumentIdentifier(uri, version++)
            contentChanges = listOf(TextDocumentContentChangeEvent(text))
        })
    }

    /**
     * Applies diagnostics as underline selections in the editor.
     */
    private fun applyDiagnostics(diagnostics: List<Diagnostic>) {
        runOnGuiThread {
            val doc = textEdit.document ?: return@runOnGuiThread
            val selections = diagnostics.map { diag ->
                val start = getOffset(doc, diag.range.start)
                val end = getOffset(doc, diag.range.end)
                val color = when(diag.severity) {
                    DiagnosticSeverity.Error -> TColors.Syntax.Error.hexToQColor()
                    DiagnosticSeverity.Warning -> TColors.Syntax.Warning.hexToQColor()
                    DiagnosticSeverity.Information -> TColors.Syntax.Information.hexToQColor()
                    else -> QColor("gray")
                }

                QTextEdit.ExtraSelection().apply {
                    cursor = textEdit.textCursor().apply {
                        setPosition(start)
                        setPosition(end, QTextCursor.MoveMode.KeepAnchor)
                    }
                    format = QTextCharFormat().apply {
                        setUnderlineStyle(QTextCharFormat.UnderlineStyle.SpellCheckUnderline)
                        setUnderlineColor(color)
                    }
                }
            }
            textEdit.setExtraSelections(selections)
        }
    }

    /**
     * Converts LSP line/character positions into Qt document offsets.
     */
    private fun getOffset(doc: QTextDocument, pos: Position): Int {
        val block = doc.findBlockByLineNumber(pos.line)
        if(!block.isValid) return 0
        return (block.position() + pos.character).coerceIn(0, doc.characterCount() - 1)
    }
}
