package io.github.tritium_launcher.launcher.ui.project.editor.treesitter

import io.github.treesitter.ktreesitter.Node
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.kubejs.KubeJSIntelligenceService
import io.github.tritium_launcher.launcher.hexToQColor
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.project.editor.RainbowBracketHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItem
import io.github.tritium_launcher.launcher.ui.project.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.CompletionPopup
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.HoverOverlay
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.QTextEdit
import io.qt.widgets.QToolTip
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class TreeSitterEditorAdapter(
    val file: VPath,
    val textEdit: QTextEdit,
    val project: ProjectBase
) {
    private val log = logger()
    private var parseJob: Job? = null
    private var hoverJob: Job? = null
    private var completionJob: Job? = null
    private var signatureJob: Job? = null
    private val bgDispatcher = newSingleThreadContext("TreeSitterBG")
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var semanticSelections: List<QTextEdit.ExtraSelection> = emptyList()
    private var diagnosticSelections: List<QTextEdit.ExtraSelection> = emptyList()
    var temporarySelections: List<QTextEdit.ExtraSelection> = emptyList()

    private val completionPopup = CompletionPopup(textEdit)
    private val hoverOverlay = HoverOverlay(textEdit.window())
    private var textChangedSinceCursorMoved = false
    private var currentHoverSymbol: String? = null

    private val hoverHideTimer = QTimer()

    private var bracketSelections: List<QTextEdit.ExtraSelection> = emptyList()

    private val syntaxColorCache = mapOf(
        "Comment" to TColors.Syntax.Comment.hexToQColor(),
        "String" to TColors.Syntax.String.hexToQColor(),
        "Number" to TColors.Syntax.Number.hexToQColor(),
        "Function" to TColors.Syntax.Function.hexToQColor(),
        "Property" to TColors.Syntax.Property.hexToQColor(),
        "Keyword" to TColors.Syntax.Keyword.hexToQColor(),
        "Operator" to TColors.Syntax.Operator.hexToQColor(),
        "Variable" to TColors.Syntax.Variable.hexToQColor(),
        "Module" to TColors.Syntax.Namespace.hexToQColor()
    )

    init {
        bracketSelections = RainbowBracketHighlighter.highlight(textEdit)
        hoverHideTimer.interval = 100
        hoverHideTimer.timeout.connect { checkHoverShouldHide() }
        textEdit.tabChangesFocus = false
        completionPopup.onSelected = { item ->
            applyCompletion(item)
        }

        textEdit.textChanged.connect {
            textChangedSinceCursorMoved = true
            val cursor = textEdit.textCursor()
            val text = textEdit.toPlainText()
            val doc = textEdit.document ?: return@connect
            scheduleParse(text, doc)
            flushSelections()
            scheduleCompletionRefresh(cursor, text)
            if (hasUnclosedParenBeforeCursor(cursor)) {
                scheduleSignatureHelp(cursor, text)
            }
        }

        textEdit.cursorPositionChanged.connect {
            if (completionPopup.isVisible && !textChangedSinceCursorMoved) {
                completionJob?.cancel()
                completionPopup.hide()
            }
            textChangedSinceCursorMoved = false
        }

        val eventFilter = object : QObject() {
            override fun eventFilter(
                watched: @Nullable QObject?,
                event: @Nullable QEvent?
            ): Boolean {
                if (event == null) return false

                when (event.type()) {
                    QEvent.Type.Show -> flushSelections()
                    QEvent.Type.KeyPress -> {
                        val keyEvent = event as QKeyEvent
                        hideOverlay()
                        QToolTip.hideText()
                        if (completionPopup.isVisible && completionPopup.handleKeyEvent(keyEvent)) {
                            return true
                        }

                        val ctrl = keyEvent.modifiers().testFlag(Qt.KeyboardModifier.ControlModifier)
                        if (keyEvent.text() == "(") {
                            completionJob?.cancel()
                            signatureJob?.cancel()
                            completionPopup.hide()
                            val sigCursor = textEdit.textCursor()
                            scheduleSignatureHelp(sigCursor, textEdit.toPlainText())
                        } else if (keyEvent.text() == "." || (ctrl && (keyEvent.key() == Qt.Key.Key_Space.value() || keyEvent.key() == Qt.Key.Key_Return.value() || keyEvent.key() == Qt.Key.Key_Enter.value()))) {
                            completionJob?.cancel()
                            if (keyEvent.text() != ".") {
                                requestCompletions(force = true)
                                return true
                            }
                        } else if (completionPopup.isVisible && keyEvent.key() == Qt.Key.Key_Backspace.value()) {
                            completionJob?.cancel()
                            requestCompletions()
                        } else if (keyEvent.key() == Qt.Key.Key_Tab.value() && !completionPopup.isVisible) {
                            textEdit.textCursor().insertText("  ")
                            return true
                        }
                    }
                    QEvent.Type.MouseMove -> {
                        val mouseEvent = event as QMouseEvent
                        val cursor = textEdit.cursorForPosition(mouseEvent.pos())
                        if (!cursor.isNull) {
                            val symbol = extractSymbolAt(cursor)
                            if (symbol != null) {
                                scheduleHoverRequest(cursor, textEdit.viewport()!!.mapToGlobal(mouseEvent.pos()))
                            } else {
                                hideOverlay()
                            }
                        } else {
                            hideOverlay()
                        }
                    }
                    QEvent.Type.MouseButtonPress -> {
                        completionPopup.hide()
                        hideOverlay()
                        QToolTip.hideText()
                        val mouseEvent = event as QMouseEvent
                        if (mouseEvent.modifiers().testFlag(Qt.KeyboardModifier.ControlModifier) && mouseEvent.button() == Qt.MouseButton.LeftButton) {
                            val cursor = textEdit.cursorForPosition(mouseEvent.pos())
                            val id = extractNamespacedIdAt(cursor)
                            if (id != null) {
                                io.github.tritium_launcher.launcher.core.TritiumEventBus.publish(
                                    io.github.tritium_launcher.launcher.core.TritiumEvent.RegistryFocusRequest(id)
                                )
                                return true
                            }
                        }
                    }
                    QEvent.Type.FocusOut -> {
                        hideOverlay()
                        completionPopup.hide()
                        QToolTip.hideText()
                    }
                    else -> {}
                }
                return super.eventFilter(watched, event)
            }
        }

        textEdit.installEventFilter(eventFilter)
        textEdit.viewport()?.installEventFilter(eventFilter)
    }

    fun close() {
        scope.cancel()
        bgDispatcher.close()
        hoverHideTimer.stop()
        completionPopup.cleanup()
        hoverOverlay.cleanup()
    }

    private fun scheduleParse(text: String, doc: QTextDocument) {
        parseJob?.cancel()

        parseJob = scope.launch(bgDispatcher) {
            delay(300.milliseconds)
            TreeSitterService.parse(text)?.let { parseResult ->
                val selections = mutableListOf<QTextEdit.ExtraSelection>()
                walkForHighlight(parseResult.rootNode, doc, selections)
                val errorSelections = mutableListOf<QTextEdit.ExtraSelection>()
                walkForErrors(parseResult.rootNode, doc, errorSelections)
                val bracketSelections = RainbowBracketHighlighter.highlight(textEdit)
                withContext(Dispatchers.Main) {
                    this@TreeSitterEditorAdapter.bracketSelections = bracketSelections
                    semanticSelections = selections
                    diagnosticSelections = errorSelections
                    flushSelections()
                }
            }
        }
    }

    private fun walkForHighlight(node: Node, doc: QTextDocument, selections: MutableList<QTextEdit.ExtraSelection>) {
        val tokenName = JavaScriptNodeTypes.tokenName(node.type)
        if (tokenName == "String" || tokenName == "Comment" || tokenName == "Number") {
            val start = node.startByte.toInt()
            val end = node.endByte.toInt()
            if (start >= end) return
            val color = tokenColorFromName(tokenName)
            selections += makeSelection(doc, start, end, color)
            return
        }
        if (node.type == "identifier") {
            val token = contextToken(node)
            if (token != null) {
                val color = tokenColorFromName(token)
                val start = node.startByte.toInt()
                val end = node.endByte.toInt()
                if (start >= end) return
                selections += makeSelection(doc, start, end, color)
                return
            }
        }
        val childCount = node.childCount.toInt()
        if (childCount == 0) {
            if (node.type.length == 1 && node.type[0] in "(){}[]") return
            val color = tokenColor(node.type) ?: return
            val start = node.startByte.toInt()
            val end = node.endByte.toInt()
            if (start >= end) return
            selections += makeSelection(doc, start, end, color)
        } else {
            for (i in 0 until childCount) {
                val child = node.child(i.toUInt()) ?: continue
                walkForHighlight(child, doc, selections)
            }
        }
    }

    private fun contextToken(node: Node): String? {
        val parent = node.parent ?: return null
        return when (parent.type) {
            "member_expression" -> {
                val firstChild = parent.child(0u)
                if (firstChild != null && firstChild.startByte == node.startByte && firstChild.endByte == node.endByte) {
                    "Module"
                } else null
            }
            else -> null
        }
    }

    private fun tokenColorFromName(name: String): QColor =
        syntaxColorCache[name] ?: TColors.Syntax.Default.hexToQColor()

    private fun walkForErrors(node: Node, doc: QTextDocument, selections: MutableList<QTextEdit.ExtraSelection>) {
        if (node.isError || node.isMissing) {
            val start = node.startByte.toInt()
            val end = node.endByte.toInt()
            if (start in 0..<end && end <= doc.characterCount() - 1) {
                selections += makeErrorSelection(doc, start, end)
            }
        }
        for (child in node.children) {
            walkForErrors(child, doc, selections)
        }
    }

    private fun tokenColor(type: String): QColor? {
        val tokenName = JavaScriptNodeTypes.tokenName(type)
        if (tokenName != null) return tokenColorFromName(tokenName)
        return when {
            type in JavaScriptNodeTypes.keywordTypes -> TColors.Syntax.Keyword.hexToQColor()
            type in JavaScriptNodeTypes.operatorTypes -> TColors.Syntax.Operator.hexToQColor()
            else -> TColors.Syntax.Default.hexToQColor()
        }
    }

    private fun makeSelection(doc: QTextDocument, start: Int, end: Int, color: QColor): QTextEdit.ExtraSelection {
        return QTextEdit.ExtraSelection().apply {
            cursor = QTextCursor(doc).apply {
                setPosition(start.coerceIn(0, doc.characterCount() - 1))
                setPosition(end.coerceIn(0, doc.characterCount() - 1), QTextCursor.MoveMode.KeepAnchor)
            }
            format = QTextCharFormat().apply { setForeground(color) }
        }
    }

    private fun makeErrorSelection(doc: QTextDocument, start: Int, end: Int): QTextEdit.ExtraSelection {
        return QTextEdit.ExtraSelection().apply {
            cursor = QTextCursor(doc).apply {
                setPosition(start.coerceIn(0, doc.characterCount() - 1))
                setPosition(end.coerceIn(0, doc.characterCount() - 1), QTextCursor.MoveMode.KeepAnchor)
            }
            format = QTextCharFormat().apply {
                setUnderlineStyle(QTextCharFormat.UnderlineStyle.SpellCheckUnderline)
                setUnderlineColor(TColors.Syntax.Error.hexToQColor())
            }
        }
    }

    private fun scheduleCompletionRefresh(cursor: QTextCursor, text: String) {
        completionJob?.cancel()
        val prefix = extractPrefix(cursor)
        val hasDot = hasDotBeforeCursor(cursor)
        val lineText = cursor.block().text()
        val column = cursor.position() - cursor.block().position()
        val cursorPosition = cursor.position()
        val fullText = if (hasDot) text else ""

        completionJob = scope.launch(bgDispatcher) {
            if (!hasDot) delay(200.milliseconds)
            try {
                requestCompletionsInternal(prefix, hasDot, lineText, column, cursorPosition, fullText)
            } catch (t: Throwable) {
                log.error("scheduleCompletionRefresh: exception in completion task", t)
            }
        }
    }

    private fun requestCompletions(force: Boolean = false) {
        val cursor = textEdit.textCursor()
        val prefix = extractPrefix(cursor)
        val hasDot = hasDotBeforeCursor(cursor)
        if (!force && prefix.isEmpty() && !hasDot) return
        val lineText = cursor.block().text()
        val column = cursor.position() - cursor.block().position()
        val cursorPosition = cursor.position()
        val fullText = if (hasDot) textEdit.toPlainText() else ""

        scope.launch(bgDispatcher) {
            requestCompletionsInternal(prefix, hasDot, lineText, column, cursorPosition, fullText)
        }
    }

    /**
     * Walks the Tree-sitter AST of [fullText] for [let], [const], and [var] declarations,
     * returning those whose block/script scope contains [cursorPos].
     */
    private fun extractLocalVariables(fullText: String, cursorPos: Int): List<CompletionItem> {
        val parseResult = TreeSitterService.parse(fullText) ?: return emptyList()
        val root = parseResult.rootNode
        val items = mutableListOf<CompletionItem>()

        fun findScope(node: Node): Node? {
            var current = node.parent
            while (current != null) {
                if (current.type == "program" || current.type == "statement_block") return current
                current = current.parent
            }
            return null
        }

        fun walk(node: Node) {
            if (node.type == "variable_declarator") {
                val idNode = node.children.firstOrNull { it.type == "identifier" }
                if (idNode != null) {
                    val scope = findScope(node)
                    if (scope != null) {
                        val s = scope.startByte.toInt()
                        val e = scope.endByte.toInt()
                        if (cursorPos in s until e) {
                            idNode.text()?.let { name ->
                                items.add(CompletionItem(
                                    label = name.toString(),
                                    kind = CompletionItemKind.Variable,
                                    detail = "local variable"
                                ))
                            }
                        }
                    }
                }
            }
            for (child in node.children) {
                walk(child)
            }
        }

        walk(root)
        return items
    }

    private fun requestCompletionsInternal(prefix: String, hasDot: Boolean, lineText: String, column: Int, cursorPosition: Int, fullText: String) {
        if (prefix.isEmpty() && !hasDot) {
            scope.launch(Dispatchers.Main) { completionPopup.hide() }
            return
        }
        try {
            val items = if (hasDot) {
                log.info("requestCompletions: contextual path hasDot=true line='{}' col={} fullTextLen={}", lineText, column, fullText.length)
                KubeJSIntelligenceService.getContextualCompletions(project, fullText, cursorPosition)
            } else {
                log.info("requestCompletions: line-only path line='{}' col={} prefix='{}'", lineText, column, prefix)
                val globalItems = KubeJSIntelligenceService.getCompletions(project, lineText, column)
                val localVars = extractLocalVariables(fullText, cursorPosition)
                globalItems + localVars
            }
            if (items.isEmpty()) log.info("requestCompletions: 0 items (line='{}', col={})", lineText, column)
            else log.info("requestCompletions: {} items (line='{}', col={})", items.size, lineText, column)

            val filtered = if (prefix.isNotEmpty()) {
                items.filter { it.label.lowercase().startsWith(prefix.lowercase()) }
            } else if (hasDot) {
                items
            } else {
                items.take(50)
            }

            scope.launch(Dispatchers.Main) {
                log.info("requestCompletions GUI: filtered={}, popupVisible={}", filtered.size, completionPopup.isVisible)
                if (filtered.isEmpty()) {
                    if (!hasDot) {
                        log.info("requestCompletions GUI: hiding popup (no dot)")
                        completionPopup.hide()
                    } else {
                        log.info("requestCompletions GUI: keeping popup (hasDot)")
                    }
                } else {
                    log.info("requestCompletions GUI: showing popup with {} items", filtered.size)
                    completionPopup.setCompletions(filtered)
                    val cursorRect = textEdit.cursorRect()
                    val globalPos = textEdit.viewport()?.mapToGlobal(cursorRect.bottomLeft())
                    log.info("requestCompletions GUI: cursorRect={} globalPos={}", cursorRect, globalPos)
                    if (globalPos != null) {
                        completionPopup.move(globalPos)
                    }
                    completionPopup.show()
                    log.info("requestCompletions GUI: after show, popupVisible={}", completionPopup.isVisible)
                }
            }
        } catch (t: Throwable) {
            log.error("requestCompletions exception", t)
        }
    }

    private fun scheduleSignatureHelp(cursor: QTextCursor, text: String) {
        signatureJob?.cancel()
        val cursorPos = cursor.position()
        log.info("scheduleSignatureHelp: cursorPos={} fullTextLen={}", cursorPos, text.length)

        signatureJob = scope.launch(bgDispatcher) {
            delay(200.milliseconds)
            try {
                val signature = KubeJSIntelligenceService.getSignatureHelp(project, text, cursorPos)
                log.info("scheduleSignatureHelp: getSignatureHelp returned '{}'", signature)
                if (signature == null) return@launch
                launch(Dispatchers.Main) {
                    try {
                        val cursorRect = textEdit.cursorRect()
                        val globalPos = textEdit.viewport()?.mapToGlobal(cursorRect.bottomLeft()) ?: return@launch
                        QToolTip.showText(globalPos, signature, textEdit.viewport())
                    } catch (t: Throwable) {
                        log.error("scheduleSignatureHelp: failed to show tooltip", t)
                    }
                }
            } catch (t: Throwable) {
                log.error("signatureHelp exception", t)
            }
        }
    }

    private fun hideOverlay() {
        hoverJob?.cancel()
        currentHoverSymbol = null
        hoverHideTimer.stop()
        hoverOverlay.hide()
    }

    private fun checkHoverShouldHide() {
        val globalPos = QCursor.pos()
        val viewport = textEdit.viewport() ?: return
        val viewportPos = viewport.mapFromGlobal(globalPos)
        val cursor = textEdit.cursorForPosition(viewportPos)
        if (cursor.isNull || extractSymbolAt(cursor) == null) {
            hideOverlay()
        }
    }

    private fun scheduleHoverRequest(cursor: QTextCursor, globalPos: QPoint) {
        hoverJob?.cancel()
        val symbol = extractSymbolAt(cursor) ?: return
        if (hoverOverlay.isVisible && symbol == currentHoverSymbol) return
        currentHoverSymbol = symbol
        hoverJob = scope.launch(bgDispatcher) {
            delay(500.milliseconds)
            val hover = KubeJSIntelligenceService.getHover(project, symbol) ?: return@launch
            launch(Dispatchers.Main) {
                hoverOverlay.showHover(hover.markdown, globalPos)
                hoverHideTimer.start()
            }
        }
    }

    private fun extractPrefix(cursor: QTextCursor): String {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        if (pos <= 0 || pos > text.length) return ""
        var start = pos
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '$')) start--
        return text.substring(start, pos)
    }

    private fun hasDotBeforeCursor(cursor: QTextCursor): Boolean {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        if (pos <= 0) return false
        if (pos <= text.length && text[pos - 1] == '.') return true
        var i = pos
        while (i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_' || text[i - 1] == '$')) i--
        return i > 0 && text[i - 1] == '.'
    }

    private fun hasUnclosedParenBeforeCursor(cursor: QTextCursor): Boolean {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        var depth = 0
        for (i in 0 until pos.coerceAtMost(text.length)) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
        }
        return depth > 0
    }

    private fun extractSymbolAt(cursor: QTextCursor): String? {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        if (pos < 0 || pos > text.length) return null
        var start = pos
        while (start > 0 && text[start - 1].isJavaIdentifierPart()) start--
        var end = pos
        while (end < text.length && text[end].isJavaIdentifierPart()) end++
        return if (start < end) text.substring(start, end) else null
    }

    private fun applyCompletion(item: CompletionItem) {
        val cursor = textEdit.textCursor()
        val text = cursor.block().text()
        val pos = cursor.position() - cursor.block().position()
        var start = pos
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '$')) start--
        cursor.setPosition(cursor.block().position() + start)
        cursor.setPosition(cursor.block().position() + pos, QTextCursor.MoveMode.KeepAnchor)
        cursor.insertText(item.insertText ?: item.label)
    }

    private fun extractNamespacedIdAt(cursor: QTextCursor): String? {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        if (pos < 0 || pos >= text.length) return null

        fun isValidIdChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '.' || c == '-' || c == '/' || c == ':'

        var start = pos
        while (start > 0 && isValidIdChar(text[start - 1])) start--
        var end = pos
        while (end < text.length && isValidIdChar(text[end])) end++
        val candidate = text.substring(start, end)
        return if (candidate.contains(':')) candidate else null
    }

    fun flushSelections() {
        textEdit.setExtraSelections(temporarySelections + semanticSelections + diagnosticSelections + bracketSelections)
    }

    fun getHighlightSelections(): List<QTextEdit.ExtraSelection> =
        semanticSelections + diagnosticSelections
}
