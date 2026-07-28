/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.EditorPane
import io.github.tritium_launcher.api.editor.intelligence.EditorIntelligenceProvider
import io.github.tritium_launcher.api.editor.intelligence.ItemSlotInfo
import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.lsp.LSPInstaller
import io.github.tritium_launcher.launcher.lsp.LSPMngr
import io.github.tritium_launcher.launcher.ui.project.ProjectWindows
import io.github.tritium_launcher.launcher.ui.project.editor.RainbowBracketHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.inlay.Inlay
import io.github.tritium_launcher.launcher.ui.project.editor.inlay.InlayMngr
import io.github.tritium_launcher.launcher.ui.project.editor.inlay.InlayPainter
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.LSPEditorAdapter
import io.github.tritium_launcher.launcher.ui.project.editor.reflection.JavaReflectionEngine
import io.github.tritium_launcher.launcher.ui.project.editor.reflection.ReflectClassDialog
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.UniversalHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterEditorAdapter
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import io.github.tritium_launcher.launcher.ui.project.sidebar.RecipeBuilderWidget
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.qt.Nullable
import io.qt.core.QMimeData
import io.qt.core.QRect
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.milliseconds

class TextEditorPane(
    project: ProjectBase,
    file: VPath,
    private val lang: SyntaxLanguage?
): EditorPane(project, file) {
    override val isReadOnly: Boolean
        get() {
            val f = file ?: return false
            val registryDir = project.projectDir.resolve("registryObjs").toAbsoluteString()
            return f.toAbsoluteString().startsWith(registryDir)
        }
    override val allowAutoSave: Boolean get() = !isReadOnly

    private val isJsonFile: Boolean
        get() {
            val name = file?.fileName()?.lowercase() ?: return false
            return name.endsWith(".json")
        }
    private val prettyJson = Json { prettyPrint = true }

    private val paneFile: VPath get() = file!!
    private val textEdit = DragDropTextEdit()
    private val container = QFrame()
    private val font = CoreSettingValues.editorFont().let { (family, size) -> QFont(family, size) }
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
    private var gutterIconJob: Job? = null
    private var rainbowSelections: List<QTextEdit.ExtraSelection> = emptyList()
    private var inlayMngr: InlayMngr? = null
    private var reflectedVars: Map<String, String> = emptyMap()
    private var reflectInlayJob: Job? = null

    var onEscPressed: (() -> Unit)? = null
    var onTextChanged: (() -> Unit)? = null

    var linkedLineRange: IntRange? = null
        set(value) {
            field = value
            linkedRangeOverlay?.let {
                it.updatePosition()
                it.repaint()
                if (value != null) it.startAnimation() else it.stopAnimation()
            }
        }

    private var linkedRangeOverlay: LinkedRangeOverlay? = null

    init {
        textEdit.font = font
        textEdit.lineWrapMode = QTextEdit.LineWrapMode.NoWrap
        textEdit.frameShape = QFrame.Shape.NoFrame
        AnimatedScrollController.attach(textEdit)

        gutter = LineNumberGutter(textEdit)
        gutter?.onLineClicked = { lineNum, shiftHeld ->
            val fullText = textEdit.toPlainText()
            val line = fullText.lines().getOrNull(lineNum - 1)
            if (line != null) {
                val window = ProjectWindows.anyOpenWindow()
                if (window != null) {
                    val dock = window.dockPanelMngr.getDock("recipe_builder")
                    if (dock != null) {
                        val widget = dock.widget() as? RecipeBuilderWidget
                        if (widget != null) {
                            dock.show()
                            dock.raise()
                            widget.importFromLine(line, lineNum - 1, fullText, editor = this@TextEditorPane, link = !shiftHeld)
                        }
                    }
                }
            }
        }

        container.frameShape = QFrame.Shape.NoFrame
        container.objectName = "editorPaneContainer"
        val layout = QHBoxLayout(container)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(gutter)
        layout.addWidget(textEdit)

        linkedRangeOverlay = LinkedRangeOverlay(container, this@TextEditorPane, textEdit)

        if (TreeSitterService.isAvailable() && isJsLanguage(lang)) {
            logger.info("TextEditorPane: using TreeSitter for {}", paneFile.toAbsolute())
            treeSitterAdapter = TreeSitterEditorAdapter(paneFile, textEdit, project)
            inlayMngr = InlayMngr(textEdit)
            lspAdapter = null
            highlighter = lang?.let { UniversalHighlighter(textEdit.document!!, it) }
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
                if (treeSitterAdapter != null) {
                    scheduleSlotRefresh()
                    scheduleGutterIconRefresh()
                    scheduleReflectInlays()
                }
            }
            onTextChanged?.invoke()
        }
    }

    private fun scheduleSlotRefresh() {
        slotCacheJob?.cancel()
        slotCacheJob = scope.launch(Dispatchers.Default) {
            delay(500.milliseconds)
            val text = textEdit.toPlainText()
            cachedSlots = EditorIntelligenceProvider.instance?.findAllItemSlots(project, text) ?: emptyList()
        }
    }

    private fun scheduleGutterIconRefresh() {
        gutterIconJob?.cancel()
        gutterIconJob = scope.launch(Dispatchers.Default) {
            delay(500.milliseconds)
            val textLines = textEdit.toPlainText().lines()
            val anyRecipeLine = textLines.any { recipeCallRegex.containsMatchIn(it) }
            if (!anyRecipeLine) {
                withContext(Dispatchers.Main) {
                    gutter?.lineIcons = emptyMap()
                    gutter?.repaint()
                }
                return@launch
            }
            val icons = mutableMapOf<Int, GutterIcon>()
            textLines.forEachIndexed { index, line ->
                if (recipeCallRegex.containsMatchIn(line)) {
                    icons[index + 1] = GutterIcon.RecipeBuilder
                }
            }
            withContext(Dispatchers.Main) {
                gutter?.lineIcons = icons
                gutter?.repaint()
            }
        }
    }

    private fun scheduleReflectInlays() {
        reflectInlayJob?.cancel()
        reflectInlayJob = scope.launch(Dispatchers.Default) {
            delay(500.milliseconds)
            val text = textEdit.toPlainText()
            val calls = mutableListOf<JavaLoadClassCall>()
            val vars = mutableMapOf<String, String>()
            for (match in javaLoadClassRegex.findAll(text)) {
                val className = match.groupValues[1]
                val openParenOffset = match.range.first + match.value.indexOf('(')
                calls.add(JavaLoadClassCall(className, match.range.first, openParenOffset))

                // Find variable assignment: let/var/const X = Java.loadClass('...')
                val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it == -1) 0 else it + 1 }
                val line = text.substring(lineStart, match.range.first)
                val varMatch = javaLoadClassAssignRegex.find(line)
                if (varMatch != null) {
                    vars[varMatch.groupValues[1]] = className
                }
            }
            reflectedVars = vars

            val inlayList = calls.map { call ->
                val hasCache = JavaReflectionEngine.loadCachedClass(project, call.className) != null
                val label = if (hasCache) "Re-scan" else "Scan"
                Inlay.Label(
                    offset = call.openParenOffset,
                    text = label,
                    color = TColors.Subtext.toQC(),
                    onClick = {
                        triggerReflection(call.className)
                    },
                    painter = scanButtonPainter,
                )
            }
            withContext(Dispatchers.Main) {
                inlayMngr?.inlays = inlayList
            }
        }
    }

    private fun triggerReflection(className: String) {
        scope.launch(Dispatchers.Default) {
            logger.info("Reflecting class: {}", className)
            val reflected = JavaReflectionEngine.reflectClass(project, className)
            if (reflected != null) {
                withContext(Dispatchers.Main) {
                    val dialog = ReflectClassDialog(textEdit, className, reflected)
                    if (dialog.exec() == QDialog.DialogCode.Accepted.value()) {
                        val selection = dialog.selectedClass()
                        if (selection != null) {
                            JavaReflectionEngine.saveCachedClass(project, selection)
                            logger.info("Reflected {}: {} methods, {} fields, {} constructors cached",
                                className, selection.methods.size, selection.fields.size, selection.constructors.size)
                        }
                    }
                    scheduleReflectInlays()
                }
            } else {
                withContext(Dispatchers.Main) {
                    logger.warn("Failed to reflect class: {}", className)
                }
            }
        }
    }

    private fun isJsLanguage(language: SyntaxLanguage?): Boolean {
        return language?.id in setOf("kubescript", "javascript")
    }

    private fun loadFile() {
        if (loading) return
        loading = true
        textEdit.isReadOnly = true
        textEdit.plainText = "Loading file..."

        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    if (paneFile.exists()) {
                        paneFile.readTextOr("")
                    } else {
                        ""
                    }
                }
                val text = if (isReadOnly && isJsonFile && raw.isNotBlank()) {
                    runCatching {
                        val element = Json.parseToJsonElement(raw)
                        prettyJson.encodeToString(JsonElement.serializer(), element)
                    }.getOrDefault(raw)
                } else {
                    raw
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
                textEdit.isReadOnly = isReadOnly
                scheduleGutterIconRefresh()
            }
        }
    }

    override fun widget(): QWidget = container

    /** The current text content. */
    val textContent: String get() = textEdit.toPlainText()

    /** Re-read the file from disk and update the editor. */
    fun reload() {
        loadFile()
    }

    override fun onOpen() {
        loadFile()
    }

    fun cursorLineNumber(): Int {
        val cursor = textEdit.textCursor()
        return cursor.block().blockNumber()
    }

    fun replaceLines(lineStart: Int, lineCount: Int, newText: String) {
        val doc = textEdit.document() ?: return
        val blockCount = doc.blockCount()
        if (lineStart >= blockCount) return
        val endLine = (lineStart + lineCount - 1).coerceAtMost(blockCount - 1)
        val cursor = QTextCursor(doc)
        cursor.beginEditBlock()
        val startBlock = doc.findBlockByNumber(lineStart)
        val endBlock = doc.findBlockByNumber(endLine)
        cursor.setPosition(startBlock.position(), QTextCursor.MoveMode.MoveAnchor)
        cursor.setPosition(endBlock.position() + endBlock.length(), QTextCursor.MoveMode.KeepAnchor)
        cursor.removeSelectedText()
        cursor.insertText(newText)
        cursor.endEditBlock()
    }

    fun rehighlight() {
        treeSitterAdapter?.forceParse()
    }

    override fun onClose() {
        scope.cancel()
        rainbowTimer?.stop()
        rainbowTimer = null
        lspAdapter?.close()
        treeSitterAdapter?.close()
        inlayMngr?.close()
    }

    override suspend fun save(): Boolean {
        if (isReadOnly) return true
        return try {
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
    }

    private inner class DragDropTextEdit : QTextEdit() {
        var currentDragSlot: ItemSlotInfo? = null
        private var lastDragSlots: List<ItemSlotInfo> = emptyList()

        override fun dragEnterEvent(event: @Nullable QDragEnterEvent?) {
            val ev = event ?: return
            if (linkedLineRange != null) {
                ev.ignore()
                return
            }
            if (ev.mimeData()?.hasText() == true && treeSitterAdapter != null) {
                lastDragSlots = cachedSlots
                ev.acceptProposedAction()
                return
            }
            super.dragEnterEvent(ev)
        }

        override fun dragMoveEvent(event: @Nullable QDragMoveEvent?) {
            val ev = event ?: return
            if (linkedLineRange != null) {
                ev.ignore()
                return
            }
            if (ev.mimeData()?.hasText() == true && treeSitterAdapter != null) {
                ev.acceptProposedAction()
                val cursor = cursorForPosition(ev.position().toPoint())
                val charPos = cursor.position()
                currentDragSlot = EditorIntelligenceProvider.instance?.findItemSlotAt(project, toPlainText(), charPos)
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
            if (linkedLineRange != null) {
                ev.ignore()
                return
            }
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

        override fun insertFromMimeData(source: QMimeData?) {
            val src = source ?: run { super.insertFromMimeData(null); return }
            if (!src.hasText()) {
                super.insertFromMimeData(src)
                return
            }
            val text = src.text()
            val lines = text.split('\n')
            if (lines.size <= 1) {
                super.insertFromMimeData(src)
                return
            }

            val cursor = textCursor()
            val blockText = cursor.block().text()
            val currentIndent = blockText.length - blockText.trimStart().length

            val firstNonBlank = lines.firstOrNull { it.isNotBlank() } ?: run {
                super.insertFromMimeData(src); return
            }
            val firstLen = firstNonBlank.length
            val firstTrimmed = firstNonBlank.trimStart()
            val firstIndent = firstLen - firstTrimmed.length
            val delta = currentIndent - firstIndent

            val adjusted = lines.mapIndexed { i, line ->
                if (i == 0) {
                    line.trimStart()
                } else if (line.isBlank()) {
                    line
                } else {
                    val trimmed = line.trimStart()
                    val cur = line.length - trimmed.length
                    " ".repeat((cur + delta).coerceAtLeast(0)) + trimmed
                }
            }.joinToString("\n")

            val md = QMimeData()
            md.setText(adjusted)
            super.insertFromMimeData(md)
        }

        override fun keyPressEvent(event: @Nullable QKeyEvent?) {
            val ev = event ?: return super.keyPressEvent(event)
            val key = ev.key()

            if (key == Qt.Key.Key_Escape.value()) {
                onEscPressed?.invoke()
                return
            }

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
                            val adjBefore = before != null && before != text[0] && (before.isLetterOrDigit() || before == '_' || before == '$')
                            val adjAfter = after != null && after != text[0] && (after.isLetterOrDigit() || after == '_' || after == '$')
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
                    setBackground(TColors.Accent.toQC { setAlpha(alpha) })
                }
            }
        }
    }
}

private val scanButtonPainter = InlayPainter { painter, fm, lineEnd, inlay, setHitRect ->
    val textWidth = fm.horizontalAdvance(inlay.text)
    val textHeight = fm.height()
    val padH = (fm.averageCharWidth() * 0.8f).toInt().coerceAtLeast(3)
    val padV = (textHeight * 0.2f).toInt().coerceAtLeast(1)
    val borderW = textWidth + padH * 2
    val borderH = textHeight + padV * 2
    val x = lineEnd.x() + 16
    val y = lineEnd.y()
    val rect = QRect(x, y, borderW, borderH)
    val baseColor = inlay.color
    painter.setPen(QPen(baseColor, 1.0))
    val bg = QColor(baseColor.red(), baseColor.green(), baseColor.blue(), 30)
    painter.setBrush(QBrush(bg))
    painter.drawRoundedRect(rect, 3.0, 3.0)
    val textX = x + padH
    val textY = y + padV + fm.ascent()
    painter.setPen(baseColor)
    painter.drawText(textX, textY, inlay.text)
    setHitRect(rect)
}

private val javaLoadClassRegex = Regex("""Java\.loadClass\s*\(\s*['"]([^'"]+)['"]\s*\)""")
private val javaLoadClassAssignRegex = Regex("""(?:let|var|const)\s+(\w+)\s*=\s*$""")

private data class JavaLoadClassCall(
    val className: String,
    val exprStart: Int,
    val openParenOffset: Int,
)

private val recipeCallRegex = Regex("""event\.\w+\s*\(""", RegexOption.IGNORE_CASE)

sealed class GutterIcon {
    abstract val pixmap: QPixmap
    object RecipeBuilder : GutterIcon() {
        override val pixmap by lazy { TIcons.pix("ui/recipe_builder", 14) }
    }
}

private class LineNumberGutter(private val editor: QTextEdit) : QWidget() {
    private val gutterFont = QFont("JetBrains Mono", 11)
    private val fm = QFontMetrics(gutterFont)
    private val gutterWidth = 64
    var onLineClicked: ((lineNum: Int, shiftHeld: Boolean) -> Unit)? = null

    var lineIcons: Map<Int, GutterIcon> = emptyMap()

    init {
        font = gutterFont
        setFixedWidth(gutterWidth)

        editor.verticalScrollBar()?.valueChanged?.connect { repaint() }
        editor.textChanged.connect { repaint() }
    }

    override fun mousePressEvent(event: QMouseEvent?) {
        val ev = event ?: return
        val doc = editor.document() ?: return
        val layout = doc.documentLayout() ?: return
        val scrollBar = editor.verticalScrollBar() ?: return
        val scrollPos = scrollBar.value()
        val clickY = ev.pos().y() + scrollPos

        val blockCount = doc.blockCount()
        if (blockCount == 0) return

        var low = 0
        var high = blockCount - 1
        while (low < high) {
            val mid = (low + high) / 2
            val midBlock = doc.findBlockByNumber(mid)
            val midRect = layout.blockBoundingRect(midBlock)
            if (midRect.y().toInt() + midRect.height().toInt() <= clickY) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        val lineNum = low + 1
        if (lineIcons.containsKey(lineNum)) {
            val shiftHeld = ev.modifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
            onLineClicked?.invoke(lineNum, shiftHeld)
        }
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setFont(gutterFont)

        painter.fillRect(0, 0, width(), height(), TColors.Surface1.toQC())
        painter.setPen(TColors.Surface2.toQC())
        painter.drawLine(width() - 1, 0, width() - 1, height())

        val doc = editor.document() ?: run { painter.end(); return }
        val scrollBar = editor.verticalScrollBar() ?: run { painter.end(); return }
        val scrollPos = scrollBar.value()
        val vpHeight = editor.viewport()?.height() ?: run { painter.end(); return }

        painter.setPen(TColors.Subtext.toQC())
        val layout = doc.documentLayout()!!

        val blockCount = doc.blockCount()
        if (blockCount == 0) { painter.end(); return }

        var low = 0
        var high = blockCount - 1
        while (low < high) {
            val mid = (low + high) / 2
            val midBlock = doc.findBlockByNumber(mid)
            val midRect = layout.blockBoundingRect(midBlock)
            if (midRect.y().toInt() + midRect.height().toInt() <= scrollPos) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        var block = doc.findBlockByNumber(low)
        if (!block.isValid) { painter.end(); return }

        val lineHeight = fm.lineSpacing().toFloat().coerceAtLeast(1f)
        val maxY = scrollPos + vpHeight
        val iconSize = 14
        val iconRight = width() - 1 - iconSize - 2
        var lineNum = low + 1
        while (block.isValid) {
            val rect = layout.blockBoundingRect(block)
            val blockTop = rect.y().toInt()
            if (blockTop > maxY) break
            val y = blockTop - scrollPos + fm.ascent()
            painter.drawText(6, y, lineNum.toString())
            val icon = lineIcons[lineNum]
            if (icon != null) {
                painter.drawPixmap(iconRight,
                    (blockTop - scrollPos + (lineHeight - iconSize) / 2).toInt(), iconSize, iconSize, icon.pixmap)
            }
            lineNum++
            block = block.next()
        }

        painter.end()
    }
}

private class LinkedRangeOverlay(
    parent: QWidget,
    private val pane: TextEditorPane,
    private val textEdit: QTextEdit
) : QWidget(parent) {
    private val animTimer = QTimer(this)
    private val borderColor = QColor(80, 140, 255)
    private var dashOffset = 0.0

    init {
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
        setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, true)
        animTimer.interval = 180
        animTimer.timeout.connect {
            dashOffset += 1.0
            repaint()
        }
    }

    fun updatePosition() {
        setGeometry(0, 0, parentWidget()?.width() ?: width(), parentWidget()?.height() ?: height())
    }

    fun startAnimation() {
        updatePosition()
        if (!animTimer.isActive) animTimer.start()
        raise()
        show()
    }

    fun stopAnimation() {
        animTimer.stop()
        hide()
    }

    override fun paintEvent(event: QPaintEvent?) {
        val range = pane.linkedLineRange ?: return
        val doc = textEdit.document() ?: return
        val layout = doc.documentLayout() ?: return
        val vScrollBar = textEdit.verticalScrollBar() ?: return
        val hScrollBar = textEdit.horizontalScrollBar() ?: return
        val scrollY = vScrollBar.value()
        val scrollX = hScrollBar.value()
        val vpHeight = textEdit.viewport()?.height() ?: return

        var unionLeft = Int.MAX_VALUE
        var unionRight = 0
        var unionTop = Int.MAX_VALUE
        var unionBottom = 0
        var anyValid = false
        for (i in range.first..range.last) {
            val block = doc.findBlockByNumber(i)
            if (!block.isValid) continue
            val br = layout.blockBoundingRect(block)
            unionLeft = minOf(unionLeft, br.x().toInt())
            unionRight = maxOf(unionRight, (br.x() + br.width()).toInt())
            unionTop = minOf(unionTop, br.y().toInt())
            unionBottom = maxOf(unionBottom, (br.y() + br.height()).toInt())
            anyValid = true
        }
        if (!anyValid) return

        val y = unionTop - scrollY
        val h = unionBottom - unionTop
        if (y + h < 0 || y > vpHeight) return

        val x = textEdit.x() + unionLeft - scrollX - 2
        val w = unionRight - unionLeft + 4

        val p = QPainter(this)
        val pen = QPen(borderColor, 2.0)
        pen.setDashPattern(listOf(5.0, 4.0))
        pen.setDashOffset(dashOffset)
        p.setPen(pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawRect(x, y - 2, w, h + 4)
        p.end()
    }
}
