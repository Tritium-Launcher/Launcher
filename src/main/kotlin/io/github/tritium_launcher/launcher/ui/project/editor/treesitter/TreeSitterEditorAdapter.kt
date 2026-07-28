/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.treesitter

import io.github.treesitter.ktreesitter.Node
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.TritiumEventBus
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.intelligence.CompletionItem
import io.github.tritium_launcher.api.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.api.editor.intelligence.EditorIntelligenceProvider
import io.github.tritium_launcher.api.inspection.FixGenerator
import io.github.tritium_launcher.api.inspection.InspectionFix
import io.github.tritium_launcher.api.inspection.Problem
import io.github.tritium_launcher.api.inspection.Severity
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.registrydb.RegistryDbStatus
import io.github.tritium_launcher.launcher.ui.project.editor.RainbowBracketHighlighter
import io.github.tritium_launcher.launcher.ui.project.editor.inspection.InspectionEngine
import io.github.tritium_launcher.launcher.ui.project.editor.lsp.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.QLabel
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
    private var problemCache: Map<Int, Problem> = emptyMap()

    private val completionPopup = CompletionPopup(textEdit)
    private val hoverOverlay = HoverOverlay(textEdit.window())
    private val editorContentPopup = EditorContentPopup(textEdit.window())
    private val itemPreviewWidget = ItemPreviewWidget()
    private var itemPreviewJob: Job? = null
    private var currentItemId: String? = null
    private var snapshotDir: VPath? = null
    private val tickDurationWidget = TickDurationWidget()
    private var tickPreviewJob: Job? = null
    private var currentTickValue: Int? = null
    private var rainbowSelections: List<QTextEdit.ExtraSelection> = emptyList()

    private data class RawSelection(val start: Int, val end: Int, val color: QColor)

    private val inspectionWidget = InspectionHoverWidget()
    private var inspectionJob: Job? = null
    private var currentInspectionPos: Int? = null
    private val signatureLabel: QLabel = QLabel().apply {
        setWindowFlags(Qt.WindowType.ToolTip, Qt.WindowType.FramelessWindowHint)
        setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        styleSheet = "background: palette(window); padding: 6px; border: 1px solid palette(mid);"
        font = QFont("JetBrains Mono", 11) //TODO
        wordWrap = false
    }
    private var textChangedSinceCursorMoved = false
    private var currentHoverSymbol: String? = null

    private val hoverHideTimer = QTimer()

    private val syntaxColorCache = mapOf(
        "Comment" to TColors.Syntax.Comment.toQC(),
        "String" to TColors.Syntax.String.toQC(),
        "Number" to TColors.Syntax.Number.toQC(),
        "Function" to TColors.Syntax.Function.toQC(),
        "Property" to TColors.Syntax.Property.toQC(),
        "Keyword" to TColors.Syntax.Keyword.toQC(),
        "Operator" to TColors.Syntax.Operator.toQC(),
        "Variable" to TColors.Syntax.Variable.toQC(),
        "Module" to TColors.Syntax.Namespace.toQC()
    )

    init {
        rainbowSelections = RainbowBracketHighlighter.highlight(textEdit)
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
            scheduleParse(text)
            rainbowSelections = RainbowBracketHighlighter.highlight(textEdit)
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
            if (signatureLabel.isVisible) {
                val cursorRect = textEdit.cursorRect()
                val topLeft = textEdit.viewport()?.mapToGlobal(cursorRect.topLeft())
                if (topLeft != null) {
                    signatureLabel.move(topLeft.x(), topLeft.y() - 4 - signatureLabel.height())
                }
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
                        val viewport = textEdit.viewport() ?: return false
                        val globalPos = viewport.mapToGlobal(mouseEvent.pos())
                        if (!cursor.isNull) {
                            val id = extractNamespacedIdAt(cursor)
                            if (id != null) {
                                editorContentPopup.hide()
                                inspectionJob?.cancel()
                                currentInspectionPos = null
                                currentItemId = null
                                hideOverlay()
                                scheduleItemPreview(cursor, globalPos)
                        } else {
                            itemPreviewJob?.cancel()
                            currentItemId = null
                            if (findInspectionAt(cursor)) {
                                tickPreviewJob?.cancel()
                                currentTickValue = null
                                hideOverlay()
                                scheduleInspectionPreview(cursor, globalPos)
                            } else {
                                tickPreviewJob?.cancel()
                                currentTickValue = null
                                val number = extractNumberAt(cursor)
                                if (number != null) {
                                    editorContentPopup.hide()
                                    inspectionJob?.cancel()
                                    currentInspectionPos = null
                                    hideOverlay()
                                    scheduleTickDurationPreview(cursor, globalPos, number)
                                } else if (!isMouseOverPopup()) {
                                    editorContentPopup.hide()
                                    inspectionJob?.cancel()
                                    currentInspectionPos = null
                                    val symbol = extractSymbolAt(cursor)
                                    if (symbol != null) {
                                        scheduleHoverRequest(cursor, globalPos)
                                    } else {
                                        hideOverlay()
                                    }
                                }
                            }
                        }
                        } else {
                            hideOverlay()
                            itemPreviewJob?.cancel()
                            currentItemId = null
                            tickPreviewJob?.cancel()
                            currentTickValue = null
                            if (!isMouseOverPopup()) {
                                editorContentPopup.hide()
                                inspectionJob?.cancel()
                                currentInspectionPos = null
                            }
                        }
                    }
                    QEvent.Type.MouseButtonPress -> {
                        completionPopup.hide()
                        signatureLabel.hide()
                        editorContentPopup.hide()
                        itemPreviewJob?.cancel()
                        currentItemId = null
                        tickPreviewJob?.cancel()
                        currentTickValue = null
                        inspectionJob?.cancel()
                        currentInspectionPos = null
                        hideOverlay()
                        QToolTip.hideText()
                        val mouseEvent = event as QMouseEvent
                        if (mouseEvent.modifiers().testFlag(Qt.KeyboardModifier.ControlModifier) && mouseEvent.button() == Qt.MouseButton.LeftButton) {
                            val cursor = textEdit.cursorForPosition(mouseEvent.pos())
                            val id = extractNamespacedIdAt(cursor)
                            if (id != null) {
                                TritiumEventBus.publish(
                                    TritiumEvent.RegistryFocusRequest(id)
                                )
                                return true
                            }
                        }
                    }
                    QEvent.Type.FocusOut -> {
                        hideOverlay()
                        completionPopup.hide()
                        signatureLabel.hide()
                        editorContentPopup.hide()
                        itemPreviewJob?.cancel()
                        currentItemId = null
                        tickPreviewJob?.cancel()
                        currentTickValue = null
                        inspectionJob?.cancel()
                        currentInspectionPos = null
                        QToolTip.hideText()
                    }
                    else -> {}
                }
                return super.eventFilter(watched, event)
            }
        }

        inspectionWidget.onApplyFix = { fix, problem ->
            applyFix(fix, problem)
        }

        textEdit.installEventFilter(eventFilter)
        textEdit.viewport()?.installEventFilter(eventFilter)
    }

    fun close() {
        itemPreviewJob?.cancel()
        tickPreviewJob?.cancel()
        inspectionJob?.cancel()
        scope.cancel()
        bgDispatcher.close()
        hoverHideTimer.stop()
        completionPopup.cleanup()
        hoverOverlay.cleanup()
        editorContentPopup.hide()
    }

    fun forceParse() {
        parseJob?.cancel()
        val text = textEdit.toPlainText()
        val parseResult = TreeSitterService.parse(text) ?: return
        val semanticRaws = walkForHighlight(parseResult.rootNode)
        val doc = textEdit.document ?: return
        rainbowSelections = RainbowBracketHighlighter.highlight(textEdit)
        semanticSelections = semanticRaws.map { doc.toSelection(it) }
        flushSelections()
    }

    private fun scheduleParse(text: String) {
        parseJob?.cancel()

        parseJob = scope.launch(bgDispatcher) {
            delay(50.milliseconds)
            TreeSitterService.parse(text)?.let { parseResult ->
                val semanticRaws = walkForHighlight(parseResult.rootNode)

                val newProblemCache = mutableMapOf<Int, Problem>()
                val problems = InspectionEngine.run(project, file, text, parseResult.tree)
                val diagnosticRaws = problems.mapNotNull { problem ->
                    problemToSelection(problem)?.also {
                        newProblemCache[problem.startByte.toInt()] = problem
                    }
                }

                withContext(Dispatchers.Main) {
                    val d = textEdit.document ?: return@withContext
                    rainbowSelections = RainbowBracketHighlighter.highlight(textEdit)
                    semanticSelections = semanticRaws.map { d.toSelection(it) }
                    diagnosticSelections = diagnosticRaws.map { d.toErrorSelection(it) }
                    problemCache = newProblemCache
                    flushSelections()
                }
            }
        }
    }

    private fun problemToSelection(problem: Problem): RawSelection? {
        val start = problem.startByte.toInt()
        val end = problem.endByte.toInt()
        if (start !in 0..<end) return null
        val color = when (problem.severity) {
            Severity.ERROR -> TColors.Syntax.Error.toQC()
            Severity.WARNING -> TColors.Syntax.Warning.toQC()
            Severity.INFO -> TColors.Syntax.Information.toQC()
            Severity.HINT -> TColors.Syntax.Default.toQC()
            Severity.IGNORE -> return null
        }
        return RawSelection(start, end, color)
    }

    private fun QTextDocument.toErrorSelection(raw: RawSelection): QTextEdit.ExtraSelection {
        return QTextEdit.ExtraSelection().apply {
            cursor = QTextCursor(this@toErrorSelection).apply {
                setPosition(raw.start.coerceIn(0, characterCount() - 1))
                setPosition(raw.end.coerceIn(0, characterCount() - 1), QTextCursor.MoveMode.KeepAnchor)
            }
            format = QTextCharFormat().apply {
                setUnderlineStyle(QTextCharFormat.UnderlineStyle.SpellCheckUnderline)
                setUnderlineColor(raw.color)
            }
        }
    }

    private fun walkForHighlight(node: Node): List<RawSelection> {
        val results = mutableListOf<RawSelection>()
        walkForHighlight(node, results)
        return results
    }

    private fun walkForHighlight(node: Node, selections: MutableList<RawSelection>) {
        val tokenName = JavaScriptNodeTypes.tokenName(node.type)
        if (tokenName == "String" || tokenName == "Comment" || tokenName == "Number") {
            val start = node.startByte.toInt()
            val end = node.endByte.toInt()
            if (start >= end) return
            val color = tokenColorFromName(tokenName)
            selections += makeSelection(start, end, color)
            return
        }
        if (node.type == "identifier") {
            val token = contextToken(node)
            if (token != null) {
                val color = tokenColorFromName(token)
                val start = node.startByte.toInt()
                val end = node.endByte.toInt()
                if (start >= end) return
                selections += makeSelection(start, end, color)
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
            selections += makeSelection(start, end, color)
        } else {
            for (i in 0 until childCount) {
                val child = node.child(i.toUInt()) ?: continue
                walkForHighlight(child, selections)
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
        syntaxColorCache[name] ?: TColors.Syntax.Default.toQC()

    @Deprecated("Replaced by InspectionEngine + SyntaxErrorRule", level = DeprecationLevel.ERROR)
    private fun walkForErrors(node: Node, doc: QTextDocument, selections: MutableList<QTextEdit.ExtraSelection>) {
    }

    private fun tokenColor(type: String): QColor? {
        val tokenName = JavaScriptNodeTypes.tokenName(type)
        if (tokenName != null) return tokenColorFromName(tokenName)
        return when (type) {
            in JavaScriptNodeTypes.keywordTypes -> TColors.Syntax.Keyword.toQC()
            in JavaScriptNodeTypes.operatorTypes -> TColors.Syntax.Operator.toQC()
            else -> TColors.Syntax.Default.toQC()
        }
    }

    private fun makeSelection(start: Int, end: Int, color: QColor): RawSelection {
        return RawSelection(start, end, color)
    }

    private fun QTextDocument.toSelection(raw: RawSelection): QTextEdit.ExtraSelection {
        return QTextEdit.ExtraSelection().apply {
            cursor = QTextCursor(this@toSelection).apply {
                setPosition(raw.start.coerceIn(0, characterCount() - 1))
                setPosition(raw.end.coerceIn(0, characterCount() - 1), QTextCursor.MoveMode.KeepAnchor)
            }
            format = QTextCharFormat().apply { setForeground(raw.color) }
        }
    }

    @Deprecated("Replaced by problemToSelection", level = DeprecationLevel.ERROR)
    private fun makeErrorSelection(doc: QTextDocument, start: Int, end: Int): QTextEdit.ExtraSelection {
        error("Not used")
    }

    private fun scheduleCompletionRefresh(cursor: QTextCursor, text: String) {
        completionJob?.cancel()
        val prefix = extractPrefix(cursor)
        val hasDot = hasDotBeforeCursor(cursor)
        val lineText = cursor.block().text()
        val column = cursor.position() - cursor.block().position()
        val cursorPosition = cursor.position()

        completionJob = scope.launch(bgDispatcher) {
            if (!hasDot) delay(200.milliseconds)
            try {
                requestCompletionsInternal(prefix, hasDot, lineText, column, cursorPosition, text)
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
        val fullText = textEdit.toPlainText()

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
        val parseResult = TreeSitterService.parse(fullText)
        if (parseResult != null) {
            var node = parseResult.findNodeAt(cursorPosition)
            while (node != null && node.type !in setOf("string", "template_string", "template_literal", "comment", "line_comment", "block_comment", "program")) {
                node = node.parent
            }
            if (node != null && node.type in setOf("comment", "line_comment", "block_comment")) {
                scope.launch(Dispatchers.Main) { completionPopup.hide() }
                return
            }
            if (node != null && node.type in setOf("string", "template_string", "template_literal")) {
                handleStringCompletion(node, fullText, cursorPosition, prefix)
                return
            }
        }
        if (prefix.isEmpty() && !hasDot) {
            scope.launch(Dispatchers.Main) { completionPopup.hide() }
            return
        }
        try {
            val items = if (hasDot) {
                log.info("requestCompletions: contextual path hasDot=true line='{}' col={} fullTextLen={}", lineText, column, fullText.length)
                EditorIntelligenceProvider.instance?.getContextualCompletions(project, fullText, cursorPosition) ?: emptyList()
            } else {
                log.info("requestCompletions: line-only path line='{}' col={} prefix='{}'", lineText, column, prefix)
                val globalItems = EditorIntelligenceProvider.instance?.getCompletions(project, lineText, column) ?: emptyList()
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

    private fun handleStringCompletion(node: Node, fullText: String, cursorPosition: Int, _prefix: String) {
        val slot = EditorIntelligenceProvider.instance?.findItemSlotAt(project, fullText, cursorPosition)
        if (slot != null) {
            val stringStart = node.startByte.toInt() + 1
            val stringEnd = node.endByte.toInt() - 1
            if (stringStart >= stringEnd) {
                scope.launch(Dispatchers.Main) { completionPopup.hide() }
                return
            }
            val stringContent = fullText.substring(stringStart, stringEnd)
            val cursorOffset = (cursorPosition - stringStart).coerceIn(0, stringContent.length)
            val itemPrefix = stringContent.substring(0, cursorOffset)

            val items = RegistryDatabase.searchItems(project, itemPrefix, limit = 100)
            val completions = items.map {
                CompletionItem(label = it.id, kind = CompletionItemKind.Text, insertText = it.id, detail = it.displayName)
            }

            scope.launch(Dispatchers.Main) {
                if (completions.isEmpty()) {
                    completionPopup.hide()
                } else {
                    ensureSnapshotDir()
                    completionPopup.setCompletions(completions, snapshotDir)
                    val cursorRect = textEdit.cursorRect()
                    val globalPos = textEdit.viewport()?.mapToGlobal(cursorRect.bottomLeft())
                    if (globalPos != null) completionPopup.move(globalPos)
                    completionPopup.show()
                }
            }
        } else {
            scope.launch(Dispatchers.Main) { completionPopup.hide() }
        }
    }

    private fun scheduleSignatureHelp(cursor: QTextCursor, text: String) {
        signatureJob?.cancel()
        val cursorPos = cursor.position()
        log.info("scheduleSignatureHelp: cursorPos={} fullTextLen={}", cursorPos, text.length)

        signatureJob = scope.launch(bgDispatcher) {
            delay(200.milliseconds)
            try {
                val signature = EditorIntelligenceProvider.instance?.getSignatureHelp(project, text, cursorPos)
                log.info("scheduleSignatureHelp: getSignatureHelp returned '{}'", signature)
                if (signature == null) {
                    launch(Dispatchers.Main) { signatureLabel.hide() }
                    return@launch
                }
                launch(Dispatchers.Main) {
                    try {
                        signatureLabel.text = signature
                        signatureLabel.adjustSize()
                        val cursorRect = textEdit.cursorRect()
                        val topLeft = textEdit.viewport()?.mapToGlobal(cursorRect.topLeft()) ?: return@launch
                        val x = topLeft.x()
                        val y = topLeft.y() - 4 - signatureLabel.height()
                        signatureLabel.move(x, y)
                        signatureLabel.show()
                        signatureLabel.repaint()
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
            val hover = EditorIntelligenceProvider.instance?.getHover(project, symbol) ?: return@launch
            launch(Dispatchers.Main) {
                hoverOverlay.showHover(hover.markdown, globalPos)
                hoverHideTimer.start()
            }
        }
    }

    private fun ensureSnapshotDir() {
        if (snapshotDir != null) return
        val status = RegistryDatabase.status(project)
        if (status is RegistryDbStatus.Ready) {
            snapshotDir = status.manifestPath.parent()
        }
    }

    private fun scheduleItemPreview(cursor: QTextCursor, globalPos: QPoint) {
        val id = extractNamespacedIdAt(cursor) ?: return
        if (editorContentPopup.isVisible && id == currentItemId) return
        currentItemId = id
        itemPreviewJob?.cancel()
        itemPreviewJob = scope.launch(bgDispatcher) {
            delay(300.milliseconds)
            ensureSnapshotDir()
            val detail = try {
                RegistryDatabase.itemDetail(project, id)
            } catch (t: Throwable) {
                log.error("itemDetail failed for '{}'", id, t)
                null
            }
            if (detail == null) {
                launch(Dispatchers.Main) { editorContentPopup.hide() }
                return@launch
            }
            launch(Dispatchers.Main) {
                itemPreviewWidget.setItem(detail, snapshotDir, project, scope)
                editorContentPopup.setContent(itemPreviewWidget)
                editorContentPopup.showAt(globalPos + QPoint(12, 12))
            }
        }
    }

    private fun extractNumberAt(cursor: QTextCursor): Int? {
        val block = cursor.block()
        val text = block.text()
        val pos = cursor.position() - block.position()
        if (pos < 0 || pos > text.length) return null
        var start = pos
        while (start > 0 && text[start - 1].isDigit()) start--
        var end = pos
        while (end < text.length && text[end].isDigit()) end++
        val numStr = text.substring(start, end)
        return numStr.toIntOrNull()
    }

    private fun scheduleTickDurationPreview(cursor: QTextCursor, globalPos: QPoint, value: Int) {
        if (currentTickValue == value && editorContentPopup.isVisible) return
        currentTickValue = value
        tickPreviewJob?.cancel()
        val cursorPos = cursor.position()
        tickPreviewJob = scope.launch(bgDispatcher) {
            delay(200.milliseconds)
            val fullText = textEdit.toPlainText()
            val found = try {
                EditorIntelligenceProvider.instance?.findTickDurationAt(project, fullText, cursorPos)
            } catch (t: Throwable) {
                log.error("findTickDurationAt failed", t)
                null
            }
            if (found == null) {
                launch(Dispatchers.Main) {
                    if (currentTickValue == value) editorContentPopup.hide()
                }
                return@launch
            }
            launch(Dispatchers.Main) {
                tickDurationWidget.setTicks(found)
                editorContentPopup.setContent(tickDurationWidget)
                editorContentPopup.showAt(globalPos + QPoint(12, 12))
            }
        }
    }

    private fun findInspectionAt(cursor: QTextCursor): Boolean {
        val cursorPos = cursor.position()
        return problemCache.any { (startByte, problem) ->
            cursorPos in startByte until problem.endByte.toInt()
        }
    }

    private fun problemAt(cursorPos: Int): Problem? {
        return problemCache.entries.firstOrNull { (startByte, problem) ->
            cursorPos in startByte until problem.endByte.toInt()
        }?.value
    }

    private fun isMouseOverPopup(): Boolean {
        if (!editorContentPopup.isVisible) return false
        val hitMargin = 20
        return editorContentPopup.frameGeometry().adjusted(-hitMargin, -hitMargin, hitMargin, hitMargin)
            .contains(QCursor.pos())
    }

    private fun scheduleInspectionPreview(cursor: QTextCursor, globalPos: QPoint) {
        val cursorPos = cursor.position()
        // Don't reposition if already showing a popup for the same problem range
        if (editorContentPopup.isVisible && currentInspectionPos != null) {
            val currentProblem = problemAt(currentInspectionPos!!)
            if (currentProblem != null &&
                cursorPos in currentProblem.startByte.toInt() until currentProblem.endByte.toInt()
            ) return
        }
        currentInspectionPos = cursorPos
        inspectionJob?.cancel()
        inspectionJob = scope.launch(bgDispatcher) {
            delay(200.milliseconds)
            val problem = problemAt(cursorPos) ?: return@launch
            val sourceLine = try {
                val block = textEdit.document()?.findBlock(cursorPos)
                block?.text()?.trim()?.take(80)
            } catch (_: Exception) { null }
            launch(Dispatchers.Main) {
                inspectionWidget.showProblem(problem, sourceLine)
                editorContentPopup.setContent(inspectionWidget)
                editorContentPopup.showAt(globalPos + QPoint(12, 12))
            }
        }
    }

    @Deprecated("Moved to SyntaxErrorRule in inspections.builtin", level = DeprecationLevel.ERROR)
    private fun describeMissingNode(node: Node, parentType: String): String {
        error("Not used")
    }

    @Deprecated("Moved to SyntaxErrorRule in inspections.builtin", level = DeprecationLevel.ERROR)
    private fun inferErrorFromParent(node: Node): String? {
        error("Not used")
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

    fun applyFix(fix: InspectionFix, problem: Problem) {
        val doc = textEdit.document ?: return
        val newText = when (val gen = fix.generator) {
            is FixGenerator.Replace -> gen.newText
            is FixGenerator.CaptureTemplate -> interpolateFixTemplate(gen.template, problem.matchedCaptures)
            is FixGenerator.Dynamic -> {
                val fullText = textEdit.toPlainText()
                val matchedText = fullText.substring(problem.startByte.toInt(), problem.endByte.toInt())
                gen.compute(matchedText, problem.matchedCaptures, fullText)
            }
        }
        val cursor = QTextCursor(doc)
        cursor.setPosition(problem.startByte.toInt())
        cursor.setPosition(problem.endByte.toInt(), QTextCursor.MoveMode.KeepAnchor)
        cursor.insertText(newText)
    }

    private fun interpolateFixTemplate(template: String, captures: Map<String, String>): String {
        var result = template
        for ((key, value) in captures) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    private fun applyCompletion(item: CompletionItem) {
        val cursor = textEdit.textCursor()
        val text = cursor.block().text()
        val pos = cursor.position() - cursor.block().position()
        var start = pos
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_' || text[start - 1] == '$' || text[start - 1] == ':')) start--
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
        textEdit.setExtraSelections(rainbowSelections + semanticSelections + diagnosticSelections)
    }

    fun getHighlightSelections(): List<QTextEdit.ExtraSelection> =
        semanticSelections + diagnosticSelections
}
