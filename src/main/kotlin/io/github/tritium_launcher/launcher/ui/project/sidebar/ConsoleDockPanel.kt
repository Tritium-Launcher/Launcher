/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockPanelTitleBarAccessoryProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.runOnGuiThread
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.platform.CompanionBridge
import io.github.tritium_launcher.launcher.platform.GameLauncher
import io.github.tritium_launcher.launcher.platform.GameProcessMngr
import io.github.tritium_launcher.launcher.ui.theme.TCol
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.SuggestionPopup
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.qt.core.*
import io.qt.gui.QIcon
import io.qt.gui.QKeyEvent
import io.qt.gui.QTextCursor
import io.qt.gui.QTextOption
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class ProjectConsoleDockPanelProvider : DockPanelProvider, DockPanelTitleBarAccessoryProvider {
    override val id: String = "console"
    override val displayName: String = "Console"
    override var icon: QIcon? = TIcons.ConsoleIdle.icon
    override val order: Int = 10
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.BottomDockWidgetArea

    companion object {
        private val controllers = java.util.WeakHashMap<DockWidget, SearchBarState>()
        private const val DOCK_OBJECT_NAME = "console"
        private const val CONSOLE_VIEW_OBJECT_NAME = "consoleView"
        private const val MAX_LOG_BLOCKS = 8_000
        private const val SCROLL_BOTTOM_SNAP_PX = 2
        private const val FLUSH_INTERVAL_MS = 50

        fun focusConsole(window: QMainWindow): Boolean {
            val dock = window.findChildren(QDockWidget::class.java).firstOrNull { it.objectName == DOCK_OBJECT_NAME }
                ?: window.findChildren(QDockWidget::class.java).firstOrNull { it.windowTitle.equals("Console", ignoreCase = true) }
                ?: return false
            dock.show()
            dock.raise()
            window.activateWindow()
            val consoleView = dock.widget()?.findChild(QPlainTextEdit::class.java, CONSOLE_VIEW_OBJECT_NAME)
            consoleView?.setFocus()
            return true
        }
    }

    enum class LevelFilter(val label: String) {
        ALL("ALL"),
        INFO("INFO"),
        WARN("WARN"),
        ERR("ERR");

        private val infoRx = Regex("\\bINFO\\b")
        private val warnRx = Regex("\\bWARN(?:ING)?\\b")
        private val errRx = Regex("\\b(?:ERROR|FATAL)\\b")

        fun matches(line: String): Boolean = when (this) {
            ALL -> true
            INFO -> infoRx.containsMatchIn(line)
            WARN -> warnRx.containsMatchIn(line)
            ERR -> errRx.containsMatchIn(line)
        }

        val color: TCol get() = when (this) {
            ALL -> TColors.Log.All
            INFO -> TColors.Log.Info
            WARN -> TColors.Log.Warning
            ERR -> TColors.Log.Err
        }

        val bgColor: TCol get() = when (this) {
            ALL -> TColors.Log.AllBg
            INFO -> TColors.Log.InfoBg
            WARN -> TColors.Log.WarningBg
            ERR -> TColors.Log.ErrBg
        }
    }

    private class SearchBarState(
        val widget: QWidget,
        val input: QLineEdit,
        val onSearchHidden: () -> Unit,
        var activeLevels: Set<LevelFilter> = setOf(LevelFilter.ALL),
        var onFilterChanged: (() -> Unit)? = null
    )

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.objectName = DOCK_OBJECT_NAME
        dock.applyIcon(TIcons.ConsoleIdle.icon)
        val root = qWidget()
        val consoleView = QPlainTextEdit().apply {
            isReadOnly = true
            lineWrapMode = QPlainTextEdit.LineWrapMode.WidgetWidth
            setWordWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
            document()?.maximumBlockCount = MAX_LOG_BLOCKS
        }
        consoleView.objectName = CONSOLE_VIEW_OBJECT_NAME
        consoleView.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOn)
        val logHighlighter = consoleView.document()?.let { LogHighlighter(it) }

        val buffer = StringBuilder()
        var userPaused = false
        var isUpdating = false
        val lineBuffer = mutableListOf<String>()
        val lineLevels = mutableListOf<LevelFilter>()
        val projectScope = GameProcessMngr.resolveScope(project)
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        fun isAtBottom(scrollBar: QScrollBar?): Boolean {
            if (scrollBar == null) return true
            return scrollBar.value() >= (scrollBar.maximum() - SCROLL_BOTTOM_SNAP_PX)
        }

        fun flushBuffer() {
            val text: String
            synchronized(buffer) {
                if (buffer.isEmpty()) return
                text = buffer.toString()
                buffer.clear()
            }

            val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()

            synchronized(lineBuffer) {
                var currentLevel = lineLevels.lastOrNull() ?: LevelFilter.ALL
                for (line in lines) {
                    LevelFilter.values().firstOrNull { it != LevelFilter.ALL && it.matches(line) }?.let { currentLevel = it }
                    lineBuffer.add(line)
                    lineLevels.add(currentLevel)
                }
                if (lineBuffer.size > MAX_LOG_BLOCKS) {
                    val excess = lineBuffer.size - MAX_LOG_BLOCKS
                    lineBuffer.subList(0, excess).clear()
                    lineLevels.subList(0, excess).clear()
                }
            }

            val state = controllers[dock]

            if (state == null || LevelFilter.ALL in state.activeLevels) {
                val wasAtBottom = isAtBottom(consoleView.verticalScrollBar())
                val cursor = consoleView.textCursor()
                cursor.movePosition(QTextCursor.MoveOperation.End)
                cursor.insertText(text)
                if (wasAtBottom) {
                    isUpdating = true
                    consoleView.moveCursor(QTextCursor.MoveOperation.End)
                    isUpdating = false
                    userPaused = false
                }
            } else {
                val query = state.input.text()
                val filters = state.activeLevels
                val startIdx = lineBuffer.size - lines.size
                val matched = mutableListOf<String>()
                synchronized(lineBuffer) {
                    for (i in lines.indices) {
                        val idx = startIdx + i
                        val line = lineBuffer[idx]
                        val matchesSearch = query.isEmpty() || line.contains(query, ignoreCase = true)
                        val matchesLevel = filters.any { it == lineLevels.getOrElse(idx) { LevelFilter.ALL } }
                        if (matchesSearch && matchesLevel) {
                            matched.add(line)
                        }
                    }
                }
                if (matched.isNotEmpty()) {
                    val wasAtBottom = isAtBottom(consoleView.verticalScrollBar())
                    val cursor = consoleView.textCursor()
                    cursor.movePosition(QTextCursor.MoveOperation.End)
                    cursor.insertText(matched.joinToString("\n") + "\n")
                    if (wasAtBottom) {
                        isUpdating = true
                        consoleView.moveCursor(QTextCursor.MoveOperation.End)
                        isUpdating = false
                        userPaused = false
                    }
                }
            }
        }

        fun rebuildView() {
            val state = controllers[dock] ?: return
            val query = state.input.text()
            val filters = state.activeLevels
            val showAll = LevelFilter.ALL in filters

            val filtered: List<String>
            synchronized(lineBuffer) {
                filtered = lineBuffer.filterIndexed { i, line ->
                    val matchesSearch = query.isEmpty() || line.contains(query, ignoreCase = true)
                    val matchesLevel = showAll || filters.any { it == lineLevels.getOrElse(i) { LevelFilter.ALL } }
                    matchesSearch && matchesLevel
                }
            }

            consoleView.setPlainText(filtered.joinToString("\n"))
            consoleView.moveCursor(QTextCursor.MoveOperation.Start)

            logHighlighter?.searchText = query
        }

        val scrollBar = consoleView.verticalScrollBar()
        if (scrollBar != null) {
            scrollBar.valueChanged.connect {
                if (isUpdating) return@connect
                userPaused = !isAtBottom(scrollBar)
            }
        }

        val flushTimer = QTimer(root).apply {
            interval = FLUSH_INTERVAL_MS
            timeout.connect { flushBuffer() }
            start()
        }

        val collectJob = ioScope.launch {
            GameProcessMngr.outputFlow(project).collect { line ->
                synchronized(buffer) {
                    buffer.appendLine(line)
                }
            }
        }

        val initialSnapshot = GameProcessMngr.snapshot(project)
        if (initialSnapshot?.isRunning == true) {
            dock.applyIcon(TIcons.ConsoleRun.icon)
        }

        val eventJob = ioScope.launch {
            GameProcessMngr.events.collect { event ->
                if (event.context.projectScope != projectScope) return@collect
                val newIcon = when (event) {
                    is GameProcessMngr.GameProcessEvent.Attached -> TIcons.ConsoleRun.icon
                    is GameProcessMngr.GameProcessEvent.Exited -> if (event.exitCode == 0) TIcons.ConsoleIdle.icon else TIcons.ConsoleErr.icon
                    is GameProcessMngr.GameProcessEvent.Detached -> TIcons.ConsoleIdle.icon
                    else -> return@collect
                }
                runOnGuiThread { dock.applyIcon(newIcon) }
            }
        }

        data class SuggestionData(val start: Int, val length: Int, val text: String)

        val suggestionPopup = SuggestionPopup().apply { hide() }
        var suggestionDataList: List<SuggestionData> = emptyList()

        val commandInput = object : QLineEdit() {
            override fun keyPressEvent(event: QKeyEvent?) {
                if (event != null && suggestionPopup.isVisible && suggestionPopup.handleKeyEvent(event)) return
                super.keyPressEvent(event)
            }
            fun cursorGlobalBottomLeft(): Pair<Int, Int> {
                val cr = cursorRect()
                val p = mapToGlobal(cr.bottomLeft())
                return Pair(p.x(), p.y())
            }
        }.apply {
            placeholderText = "Run Command"
            setFixedHeight(28)
        }

        fun applySuggestion(data: SuggestionData) {
            val curText = commandInput.text()
            val cmdOffset = if (curText.startsWith("/") && data.start >= 0) 1 else 0
            val adjStart = data.start + cmdOffset
            val adjEnd = adjStart + data.length
            if (adjStart < cmdOffset || adjEnd > curText.length) return
            val newText = curText.substring(0, adjStart) + data.text + curText.substring(adjEnd)
            commandInput.setText(newText)
            commandInput.setCursorPosition(adjStart + data.text.length)
            suggestionPopup.hide()
        }

        fun repositionPopup() {
            if (!suggestionPopup.isVisible) return
            val (gx, gy) = commandInput.cursorGlobalBottomLeft()
            suggestionPopup.move(gx, gy - suggestionPopup.height() - 12)
        }

        suggestionPopup.onSelected = lambda@{ selectedText ->
            val data = suggestionDataList.find { it.text == selectedText } ?: return@lambda
            applySuggestion(data)
        }

        suspend fun fetchSuggestions() {
            val text = commandInput.text()
            if (text.isEmpty()) { runOnGuiThread { suggestionPopup.hide() }; return }
            val cursor = commandInput.cursorPosition()
            val hasSlash = text.startsWith("/")
            val requestInput = if (hasSlash) text.substring(1) else text
            val requestCursor = if (hasSlash) (cursor - 1).coerceAtLeast(0) else cursor
            val response = runCatching {
                CompanionBridge.request("command_suggestions", buildJsonObject {
                    put("input", requestInput)
                    put("cursor", requestCursor)
                }, timeoutMs = 5000L)
            }.getOrNull() ?: run { runOnGuiThread { suggestionPopup.hide() }; return }
            if (!response.ok) { runOnGuiThread { suggestionPopup.hide() }; return }

            val raw = response.data["suggestions"]?.jsonArray?.toList().orEmpty()
            if (raw.isEmpty()) { runOnGuiThread { suggestionPopup.hide() }; return }

            val dataList = mutableListOf<SuggestionData>()
            val labels = mutableListOf<String>()
            for (entry in raw) {
                val obj = entry.jsonObject
                val t = obj["text"]?.jsonPrimitive?.content ?: continue
                val s = obj["start"]?.jsonPrimitive?.int ?: 0
                val l = obj["length"]?.jsonPrimitive?.int ?: 0
                if (s < 0 || s + l > requestInput.length) continue
                dataList.add(SuggestionData(s, l, t))
                labels.add(t)
            }
            if (labels.isEmpty()) { runOnGuiThread { suggestionPopup.hide() }; return }
            suggestionDataList = dataList
            runOnGuiThread {
                suggestionPopup.showSuggestions(labels, commandInput, offsetX = 0)
                repositionPopup()
            }
        }

        val tabFilter = object : QObject(commandInput) {
            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                if (event?.type() == QEvent.Type.KeyPress) {
                    val ke = event as QKeyEvent
                    if (ke.key() == Qt.Key.Key_Tab.value() && suggestionPopup.isVisible) {
                        val text = suggestionPopup.currentSuggestion()
                        if (text != null) {
                            val data = suggestionDataList.find { it.text == text } ?: return false
                            applySuggestion(data)
                            return true
                        }
                    }
                }
                return super.eventFilter(watched, event)
            }
        }
        commandInput.installEventFilter(tabFilter)

        var suggestJob: Job? = null
        commandInput.textChanged.connect {
            suggestJob?.cancel()
            suggestJob = ioScope.launch {
                delay(200)
                fetchSuggestions()
            }
        }
        commandInput.cursorPositionChanged.connect { _: Int, _: Int ->
            repositionPopup()
        }

        commandInput.returnPressed.connect {
            suggestJob?.cancel()
            suggestionPopup.hide()
            val text = commandInput.text().trim()
            commandInput.clear()
            if (text.isNotEmpty()) {
                ioScope.launch {
                    CompanionBridge.sendCommand(text)
                }
            }
        }

        val searchBar = qWidget { hide() }
        val searchInput = QLineEdit().apply {
            placeholderText = "Search"
            setFixedHeight(24)
        }
        var searchJob: Job? = null
        searchInput.textChanged.connect {
            searchJob?.cancel()
            searchJob = ioScope.launch {
                delay(100)
                runOnGuiThread { rebuildView() }
            }
        }
        val searchCloseBtn = toolButton {
            text = "✕"
            autoRaise = true
            setFixedSize(20, 20)
            clicked.connect {
                searchJob?.cancel()
                searchInput.clear()
                searchBar.hide()
                rebuildView()
            }
        }
        hBoxLayout(searchBar) {
            setContentsMargins(4, 2, 4, 2)
            setSpacing(4)
            addWidget(searchInput, 1)
            addWidget(searchCloseBtn)
        }
        val state = SearchBarState(
            widget = searchBar,
            input = searchInput,
            onSearchHidden = {
                searchJob?.cancel()
                searchInput.clear()
                rebuildView()
            },
            onFilterChanged = { rebuildView() }
        )
        controllers[dock] = state

        vBoxLayout(root) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(searchBar)
            addWidget(consoleView)
            addWidget(commandInput)
        }

        root.setThemedStyle {
            selector("QPlainTextEdit") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
            }
            selector("QLineEdit") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(4, 8)
            }
            selector("QListWidget") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(2)
            }
        }

        dock.destroyed.connect {
            flushTimer.stop()
            collectJob.cancel()
            eventJob.cancel()
            ioScope.cancel()
            controllers.remove(dock)
        }

        dock.setWidget(root)
        return dock
    }

    override fun createTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget? {
        val container = qWidget()
        val layout = hBoxLayout(container) {
            setContentsMargins(0, 0, 6, 0)
            setSpacing(4)
        }

        val connectedLabel = label {
            hide()
            setThemedStyle {
                selector("QLabel") {
                    color(TColors.Subtext)
                    fontSize(10)
                }
            }
        }
        layout.addWidget(connectedLabel)

        val circle = label {
            setFixedSize(8, 8)
        }
        circle.setThemedStyle {
            selector("QLabel") {
                backgroundColor(TColors.Surface2)
                borderRadius(4)
                minWidth(8)
                minHeight(8)
            }
        }
        layout.addWidget(circle)

        fun updateStatus() {
            val isRunning = GameProcessMngr.isActive(project)
            val isConnected = CompanionBridge.isConnected
            val hostPort = "${CoreSettingValues.companionWsHost}:${CoreSettingValues.companionWsPort()}"
            connectedLabel.text = "Connected · $hostPort"
            connectedLabel.setVisible(isRunning && isConnected)
            val color = if (isRunning && isConnected) TColors.Green else TColors.Surface2
            circle.setThemedStyle {
                selector("QLabel") {
                    backgroundColor(color)
                    borderRadius(4)
                    minWidth(8)
                    minHeight(8)
                }
            }
        }

        updateStatus()

        QTimer(container).apply {
            interval = 2000
            timeout.connect { updateStatus() }
            start()
        }

        return container
    }

    override fun createLeftTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget? {
        val state = controllers[dock] ?: return null

        val container = qWidget()
        val layout = hBoxLayout(container) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(4)
        }

        // launch button
        val launchBtn = toolButton {
            icon = TIcons.Run.icon
            iconSize = QSize(16, 16)
            autoRaise = true
            toolTip = "Launch Game"
            clicked.connect {
                GameLauncher.launch(project)
            }
            setThemedStyle {
                selector("QToolButton") {
                    background("transparent")
                    border()
                    padding(0)
                    margin(0)
                }
                selector("QToolButton:hover") {
                    backgroundColor(TColors.Surface1)
                }
                selector("QToolButton:disabled") {
                    opacity(40)
                }
            }
        }

        fun updateLaunchBtn() {
            val running = GameProcessMngr.isActive(project)
            val launchable = GameLauncher.isLaunchable(project)
            launchBtn.isEnabled = launchable
            launchBtn.icon = if (running) TIcons.Rerun.icon else TIcons.Run.icon
            launchBtn.toolTip = when {
                running -> "Game is running"
                !launchable -> "Preparing runtime..."
                else -> "Launch Game"
            }
        }

        updateLaunchBtn()
        QTimer(launchBtn).apply {
            interval = 2000
            timeout.connect { updateLaunchBtn() }
            start()
        }
        layout.addWidget(launchBtn)

        layout.addWidget(toolButton {
            icon = TIcons.Search.icon
            iconSize = QSize(16, 16)
            autoRaise = true
            toolTip = "Toggle Search"
            setCheckable(true)
            clicked.connect {
                val visible = !state.widget.isVisible
                state.widget.setVisible(visible)
                if (visible) {
                    state.input.setFocus()
                    state.input.selectAll()
                } else {
                    state.onSearchHidden()
                }
                onStateChanged()
            }
            setThemedStyle {
                selector("QToolButton") {
                    background("transparent")
                    border()
                    padding(0)
                    margin(0)
                }
                selector("QToolButton:hover") {
                    backgroundColor(TColors.Surface1)
                }
                selector("QToolButton:checked") {
                    backgroundColor(TColors.Surface2)
                }
            }
        })

        layout.addWidget(qWidget {
            setFixedSize(1, 14)
            setThemedStyle {
                selector("QWidget") { backgroundColor(TColors.Surface2) }
            }
        })

        val levelEntries = LevelFilter.values().map { filter ->
            val btn = toolButton {
                text = filter.label
                setFixedHeight(18)
                autoRaise = true
                setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextOnly)
                setCheckable(true)
                if (filter == LevelFilter.ALL) isChecked = true
                setThemedStyle {
                    selector("QToolButton") {
                        backgroundColor(filter.bgColor)
                        border()
                        borderRadius(4)
                        padding(0)
                        margin(0)
                        fontSize(10)
                        color(filter.color)
                    }
                    selector("QToolButton:checked") {
                        border(1, filter.color)
                    }
                }
            }
            filter to btn
        }
        val levelMap = levelEntries.toMap()
        for ((filter, btn) in levelEntries) {
            btn.clicked.connect {
                state.activeLevels = setOf(filter)
                for ((f, b) in levelMap) {
                    b.isChecked = f in state.activeLevels
                }
                state.onFilterChanged?.invoke()
            }
            layout.addWidget(btn)
        }

        return container
    }
}
