package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.extension.kubejs.KubeJSIntelligenceService
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.lsp.LSPInstaller
import io.github.tritium_launcher.launcher.lsp.LSPMngr
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.RainbowBracketHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.LSPEditorAdapter
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.UniversalHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.ItemSlotInfo
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterEditorAdapter
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.qt.Nullable
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QFrame
import io.qt.widgets.QHBoxLayout
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class TextEditorPane(
    project: ProjectBase,
    file: VPath,
    private val lang: SyntaxLanguage?
): EditorPane(project, file) {
    private val paneFile: VPath get() = file!!
    private val textEdit = DragDropTextEdit()
    private val container = QFrame()
    private val font = QFont("JetBrains Mono", 11)
    private val highlighter: QSyntaxHighlighter?

    private val lspAdapter: LSPEditorAdapter?
    private val treeSitterAdapter: TreeSitterEditorAdapter?

    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var originalText: String = ""
    private var loading: Boolean = false
    private var rainbowTimer: QTimer? = null
    private var gutter: LineNumberGutter? = null
    private var cachedSlots: List<ItemSlotInfo> = emptyList()
    private var slotCacheJob: Job? = null
    private var rainbowSelections: List<QTextEdit.ExtraSelection> = emptyList()

    init {
        textEdit.font = font
        textEdit.lineWrapMode = QTextEdit.LineWrapMode.NoWrap
        textEdit.frameShape = QFrame.Shape.NoFrame
        AnimatedScrollController.attach(textEdit)

        gutter = LineNumberGutter(textEdit)

        container.frameShape = QFrame.Shape.NoFrame
        container.objectName = "editorPaneContainer"
        val layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(gutter)
        layout.addWidget(textEdit)

        if (TreeSitterService.isAvailable() && isJsLanguage(lang)) {
            logger.info("TextEditorPane: using TreeSitter for {}", paneFile.toAbsolute())
            treeSitterAdapter = TreeSitterEditorAdapter(paneFile, textEdit, project)
            lspAdapter = null
            highlighter = null
        } else {
            logger.info("TextEditorPane: TreeSitter not used (available={}, isJsLang={}) for {}",
                TreeSitterService.isAvailable(), isJsLanguage(lang), paneFile.toAbsolute())
            treeSitterAdapter = null
            val connection = LSPMngr.getOrStart(project, paneFile)
            if (connection != null) {
                lspAdapter = LSPEditorAdapter(paneFile, textEdit, connection)
                highlighter = lang?.let { UniversalHighlighter(textEdit.document!!, it) }
            } else {
                lspAdapter = null
                LSPInstaller.checkAndPromptInstallation(project, paneFile)
                highlighter = lang?.let { UniversalHighlighter(textEdit.document!!, it) }
                rainbowTimer = QTimer(textEdit).apply {
                    interval = 300
                    isSingleShot = true
                    timeout.connect {
                        rainbowSelections = RainbowBracketHighlighter.highlight(textEdit)
                        textEdit.setExtraSelections(rainbowSelections)
                    }
                }
                textEdit.textChanged.connect {
                    rainbowTimer?.start()
                }
            }
        }

        textEdit.textChanged.connect {
            if (!loading) {
                modified = true
                if (treeSitterAdapter != null) scheduleSlotRefresh()
            }
        }
    }

    private fun scheduleSlotRefresh() {
        slotCacheJob?.cancel()
        slotCacheJob = scope.launch(Dispatchers.Default) {
            delay(500.milliseconds)
            val text = textEdit.toPlainText()
            cachedSlots = KubeJSIntelligenceService.findAllItemSlots(project, text)
        }
    }

    private fun isJsLanguage(language: SyntaxLanguage?): Boolean {
        return language?.id == "kubescript"
    }

    private fun loadFile() {
        if (loading) return
        loading = true
        textEdit.isReadOnly = true
        textEdit.plainText = "Loading file..."

        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    if (paneFile.exists()) {
                        paneFile.readTextOr("")
                    } else {
                        ""
                    }
                }
                originalText = text
                textEdit.plainText = text
                lspAdapter?.openDocument(text)
                textEdit.document!!.isModified = false
                modified = false
            } catch (t: Throwable) {
                logger.warn("Failed to load file {}", paneFile.toAbsolute(), t)
                originalText = ""
                textEdit.plainText = ""
                lspAdapter?.openDocument("")
                textEdit.document!!.isModified = false
                modified = false
            } finally {
                loading = false
                textEdit.isReadOnly = false
            }
        }
    }

    override fun widget(): QWidget = container

    override fun onOpen() {
        loadFile()
    }

    override fun onClose() {
        scope.cancel()
        rainbowTimer?.stop()
        rainbowTimer = null
        lspAdapter?.close()
        treeSitterAdapter?.close()
    }

    override suspend fun save(): Boolean = try {
        val text = textEdit.toPlainText()
        paneFile.writeBytes(text.toByteArray())
        originalText = text
        textEdit.document!!.isModified = false
        modified = false
        true
    } catch (t: Throwable) {
        logger.error("Failed saving {}", paneFile.toAbsolute(), t)
        false
    }

    private inner class DragDropTextEdit : QTextEdit() {
        var currentDragSlot: ItemSlotInfo? = null
        private var lastDragSlots: List<ItemSlotInfo> = emptyList()

        override fun dragEnterEvent(event: @Nullable QDragEnterEvent?) {
            val ev = event ?: return
            if (ev.mimeData()?.hasText() == true && treeSitterAdapter != null) {
                lastDragSlots = cachedSlots
                ev.acceptProposedAction()
                return
            }
            super.dragEnterEvent(ev)
        }

        override fun dragMoveEvent(event: @Nullable QDragMoveEvent?) {
            val ev = event ?: return
            if (ev.mimeData()?.hasText() == true && treeSitterAdapter != null) {
                ev.acceptProposedAction()
                val cursor = cursorForPosition(ev.position().toPoint())
                val charPos = cursor.position()
                currentDragSlot = KubeJSIntelligenceService.findItemSlotAt(project, toPlainText(), charPos)
                if (currentDragSlot == null) {
                    currentDragSlot = lastDragSlots.firstOrNull {
                        charPos in it.exprStartByte..it.exprEndByte
                    }
                }
                val slot = currentDragSlot

                val slotSelections = mutableListOf<QTextEdit.ExtraSelection>()
                for (s in lastDragSlots) {
                    val alpha = if (s == slot) 60 else 30
                    slotSelections.add(makeSlotHighlight(s, alpha))
                }
                treeSitterAdapter.temporarySelections = slotSelections
                treeSitterAdapter.flushSelections()
                return
            }
            super.dragMoveEvent(ev)
        }

        override fun dropEvent(event: @Nullable QDropEvent?) {
            val ev = event ?: return
            if (ev.mimeData()?.hasText() == true && treeSitterAdapter != null) {
                val text = ev.mimeData()!!.text()
                val slot = currentDragSlot
                if (slot != null) {
                    val c = textCursor()
                    c.setPosition(slot.startByte)
                    c.setPosition(slot.endByte, QTextCursor.MoveMode.KeepAnchor)
                    c.insertText(text)
                } else {
                    val c = cursorForPosition(ev.position().toPoint())
                    c.insertText(text)
                }
                currentDragSlot = null
                lastDragSlots = emptyList()
                treeSitterAdapter.temporarySelections = emptyList()
                treeSitterAdapter.flushSelections()
                ev.acceptProposedAction()
                return
            }
            super.dropEvent(ev)
        }

        override fun dragLeaveEvent(event: @Nullable QDragLeaveEvent?) {
            currentDragSlot = null
            lastDragSlots = emptyList()
            treeSitterAdapter?.temporarySelections = emptyList()
            treeSitterAdapter?.flushSelections()
            super.dragLeaveEvent(event)
        }

        override fun keyPressEvent(event: @Nullable QKeyEvent?) {
            val ev = event ?: return super.keyPressEvent(event)
            val key = ev.key()

            if (CoreSettingValues.editorInsertPairCurlyOnEnter &&
                (key == Qt.Key.Key_Return.value() || key == Qt.Key.Key_Enter.value())
            ) {
                if (handleEnterWithBrace()) return
            }

            if (CoreSettingValues.editorInsertPairedBrackets &&
                key == Qt.Key.Key_Backspace.value()
            ) {
                if (handleBackspacePair()) return
            }

            val text = ev.text()
            if (text.length == 1 && CoreSettingValues.editorInsertPairedBrackets) {
                val closing = when (text[0]) {
                    '(' -> ')'; '[' -> ']'; '{' -> '}'; '<' -> '>'
                    '\'' -> '\''; '"' -> '"'; '`' -> '`'
                    else -> null
                }
                if (closing != null) {
                    val cursor = textCursor()
                    if (cursor.hasSelection()) {
                        cursor.insertText("${text[0]}${cursor.selectedText()}$closing")
                    } else {
                        val pos = cursor.position()
                        val doc = toPlainText()
                        if (text[0] in "'\"`") {
                            val before = doc.getOrNull(pos - 1)
                            val after = doc.getOrNull(pos)
                            val adjBefore = before != null && before != text[0] && !before.isWhitespace()
                            val adjAfter = after != null && after != text[0] && !after.isWhitespace()
                            if (adjBefore || adjAfter) {
                                cursor.insertText(text)
                                setTextCursor(cursor)
                                return
                            }

                            // Upgrade to triple-quote pair when two same quotes before cursor
                            if (pos >= 2 && doc.substring(pos - 2, pos) == "${text[0]}${text[0]}" &&
                                doc.getOrNull(pos) != text[0]
                            ) {
                                cursor.beginEditBlock()
                                cursor.setPosition(pos - 2)
                                cursor.setPosition(pos, QTextCursor.MoveMode.KeepAnchor)
                                cursor.removeSelectedText()
                                val triple = "${text[0]}${text[0]}${text[0]}"
                                cursor.insertText(triple)
                                val mid = cursor.position()
                                cursor.insertText(triple)
                                cursor.setPosition(mid)
                                cursor.endEditBlock()
                                setTextCursor(cursor)
                                return
                            }
                            // Skip past closing quote
                            if (pos < doc.length && doc[pos] == text[0]) {
                                cursor.setPosition(pos + 1)
                                setTextCursor(cursor)
                                return
                            }
                            if (before == text[0]) {
                                cursor.insertText(text)
                                setTextCursor(cursor)
                                return
                            }
                        }
                        cursor.beginEditBlock()
                        cursor.insertText(text)
                        val afterOpen = cursor.position()
                        cursor.insertText(closing.toString())
                        cursor.setPosition(afterOpen)
                        cursor.endEditBlock()
                        setTextCursor(cursor)
                    }
                    return
                }
            }

            super.keyPressEvent(ev)
        }

        private fun handleEnterWithBrace(): Boolean {
            val cursor = textCursor()
            val pos = cursor.position()
            val text = toPlainText()

            var scan = pos
            while (scan < text.length && text[scan].isWhitespace() && text[scan] != '\n') scan++
            if (scan < text.length && text[scan] == '}') {
                val currentIndent = cursor.block().text().takeWhile { it.isWhitespace() }.length
                val childIndent = currentIndent + 2
                cursor.beginEditBlock()
                val midPos = pos + 1 + childIndent
                cursor.insertText("\n" + " ".repeat(childIndent))
                cursor.insertText("\n" + " ".repeat(currentIndent))
                cursor.setPosition(midPos)
                cursor.endEditBlock()
                setTextCursor(cursor)
                return true
            }

            var depth = 0
            for (i in pos - 1 downTo 0) {
                when (text[i]) {
                    '}' -> depth++
                    '{' -> if (depth == 0) {
                        val curIndent = cursor.block().text().takeWhile { it.isWhitespace() }
                        cursor.beginEditBlock()
                        cursor.insertText("\n$curIndent")
                        cursor.endEditBlock()
                        setTextCursor(cursor)
                        return true
                    } else depth--
                }
            }

            return false
        }

        private fun handleBackspacePair(): Boolean {
            val cursor = textCursor()
            if (cursor.hasSelection()) return false
            val pos = cursor.position()
            val text = toPlainText()
            if (pos <= 0 || pos >= text.length) return false

            val before = text[pos - 1]
            val after = text[pos]
            val isPaired = when (before) {
                '(' -> after == ')'
                '[' -> after == ']'
                '{' -> after == '}'
                '<' -> after == '>'
                '\'' -> after == '\''
                '"' -> after == '"'
                '`' -> after == '`'
                else -> false
            }
            if (!isPaired) return false

            cursor.beginEditBlock()
            cursor.setPosition(pos - 1)
            cursor.setPosition(pos + 1, QTextCursor.MoveMode.KeepAnchor)
            cursor.removeSelectedText()
            cursor.endEditBlock()
            setTextCursor(cursor)
            return true
        }

        private fun makeSlotHighlight(slot: ItemSlotInfo, alpha: Int): ExtraSelection {
            return ExtraSelection().apply {
                cursor = QTextCursor(document()).apply {
                    setPosition(slot.startByte.coerceIn(0, document()!!.characterCount() - 1))
                    setPosition(slot.endByte.coerceIn(0, document()!!.characterCount() - 1), QTextCursor.MoveMode.KeepAnchor)
                }
                format = QTextCharFormat().apply {
                    setBackground(QColor(TColors.Accent).apply { setAlpha(alpha) })
                }
            }
        }
    }
}

private class LineNumberGutter(private val editor: QTextEdit) : QWidget() {
    private val gutterFont = QFont("JetBrains Mono", 11) //TODO: Use set font
    private val fm = QFontMetrics(gutterFont)
    private val gutterWidth = 48

    init {
        font = gutterFont
        setFixedWidth(gutterWidth)
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)

        editor.verticalScrollBar()?.valueChanged?.connect { repaint() }
        editor.textChanged.connect { repaint() }
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setFont(gutterFont)

        painter.fillRect(0, 0, width(), height(), QColor(TColors.Surface1))
        painter.setPen(QColor(TColors.Surface2))
        painter.drawLine(width() - 1, 0, width() - 1, height())

        val doc = editor.document() ?: run { painter.end(); return }
        val scrollBar = editor.verticalScrollBar() ?: run { painter.end(); return }
        val scrollPos = scrollBar.value()
        val vpHeight = editor.viewport()?.height() ?: run { painter.end(); return }

        painter.setPen(QColor(TColors.Subtext))
        val layout = doc.documentLayout()!!

        val lineHeight = fm.lineSpacing().toFloat().coerceAtLeast(1f)
        val firstVisibleLine = (scrollPos / lineHeight).toInt().coerceAtLeast(0)
        var block = doc.findBlockByNumber(firstVisibleLine)
        if (!block.isValid) { painter.end(); return }
        var lineNum = firstVisibleLine + 1

        val maxY = scrollPos + vpHeight
        while (block.isValid) {
            val rect = layout.blockBoundingRect(block)
            val blockTop = rect.y().toInt()
            if (blockTop > maxY) break
            val y = blockTop - scrollPos + fm.ascent()
            painter.drawText(6, y, lineNum.toString())
            lineNum++
            block = block.next()
        }

        painter.end()
    }
}
