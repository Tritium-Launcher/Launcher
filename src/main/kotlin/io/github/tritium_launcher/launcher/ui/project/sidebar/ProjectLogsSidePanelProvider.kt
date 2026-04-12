package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.platform.GameLauncher
import io.github.tritium_launcher.launcher.redactUserPath
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.time.ExperimentalTime

/**
 * Side panel for active Minecraft logs in a project.
 */
@OptIn(ExperimentalTime::class)
class ProjectLogsSidePanelProvider : SidePanelProvider {
    override val id: String = "mc_logs"
    override val displayName: String = "MC Logs"
    override val icon: QIcon = TIcons.Log.icon
    override val order: Int = 10

    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.BottomDockWidgetArea

    companion object {
        private const val DOCK_OBJECT_NAME = "mc_logs"
        private const val LATEST_COMBO_OBJECT_NAME = "mcLogsLatestCombo"
        private const val DEBUG_COMBO_OBJECT_NAME = "mcLogsDebugCombo"
        private const val LOG_VIEW_OBJECT_NAME = "mcLogsView"
        private const val STATUS_DOT_SIZE = 10
        private const val MAX_LOG_BLOCKS = 8_000
        private const val TAIL_MAX_BYTES = 256 * 1024
        private const val GZIP_TAIL_MAX_CHARS = 200_000
        private const val LOG_REFRESH_INTERVAL_MS = 1_500
        private const val LIST_REFRESH_INTERVAL_MS = 5_000
        private const val SCROLL_BOTTOM_SNAP_PX = 2
        private const val COMBO_EXTRA_CHROME = 56
        private const val COMBO_MIN_WIDTH = 140

        private val latestArchivedNameRx = Regex("^\\d{4}-\\d{2}-\\d{2}-\\d+\\.log\\.gz$")
        private val debugArchivedNameRx = Regex("^debug-\\d+\\.log\\.gz$")
        private val latestSortNameRx = Regex("^(\\d{4}-\\d{2}-\\d{2})-(\\d+)\\.log\\.gz$")
        private val debugSortNameRx = Regex("^debug-(\\d+)\\.log\\.gz$")

        fun hasDebugLog(project: ProjectBase): Boolean {
            return project.projectDir.resolve("logs").resolve("debug.log").exists()
        }

        fun focusLatestLog(window: QMainWindow): Boolean = focusNamedLog(window, "latest.log")

        fun focusDebugLog(window: QMainWindow): Boolean = focusNamedLog(window, "debug.log")

        private fun focusNamedLog(window: QMainWindow, fileName: String): Boolean {
            val dock = window.findChildren(QDockWidget::class.java).firstOrNull { it.objectName == DOCK_OBJECT_NAME }
                ?: window.findChildren(QDockWidget::class.java).firstOrNull { it.windowTitle.equals("MC Logs", ignoreCase = true) }
                ?: return false
            dock.show()
            dock.raise()
            window.activateWindow()

            val root = dock.widget() ?: return false
            val targetComboName = if (fileName.equals("debug.log", ignoreCase = true)) {
                DEBUG_COMBO_OBJECT_NAME
            } else {
                LATEST_COMBO_OBJECT_NAME
            }
            val combo = root.findChild(QComboBox::class.java, targetComboName) ?: return false
            val selected = selectComboByFileName(combo, fileName)
            if (selected) {
                root.findChild(QPlainTextEdit::class.java, LOG_VIEW_OBJECT_NAME)?.setFocus()
            }
            return selected
        }

        private fun selectComboByFileName(combo: QComboBox, fileName: String): Boolean {
            val target = fileName.lowercase()
            for (i in 0 until combo.count) {
                val raw = combo.itemData(i, Qt.ItemDataRole.UserRole) as? String ?: continue
                val normalized = raw.replace('\\', '/').lowercase()
                if (normalized.endsWith("/$target")) {
                    combo.currentIndex = i
                    return true
                }
            }
            for (i in 0 until combo.count) {
                if (combo.itemText(i).equals(fileName, ignoreCase = true)) {
                    combo.currentIndex = i
                    return true
                }
            }
            return false
        }
    }

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.objectName = DOCK_OBJECT_NAME
        val root = qWidget()
        val headerLabel = label()
        val gameRunningDot = label()
        val latestLabel = label("Latest:")
        val debugLabel = label("Debug:")
        val latestCombo = TComboBox()
        val debugCombo = TComboBox()
        val logView = QPlainTextEdit().apply {
            isReadOnly = true
            lineWrapMode = QPlainTextEdit.LineWrapMode.WidgetWidth
            setWordWrapMode(QTextOption.WrapMode.WrapAtWordBoundaryOrAnywhere)
            document()?.maximumBlockCount = MAX_LOG_BLOCKS
        }

        val logsDir = project.projectDir.resolve("logs")
        var activePath = logsDir.resolve("latest.log")
        var activeIsCompressed = false
        var lastStamp: Long? = null
        var lastSize: Long? = null
        var userPaused = false
        var isUpdating = false
        var refreshVersion = 0L
        val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        headerLabel.wordWrap = true
        headerLabel.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        configureStatusDot(gameRunningDot)
        latestCombo.sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
        debugCombo.sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
        latestCombo.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        debugCombo.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        latestCombo.objectName = LATEST_COMBO_OBJECT_NAME
        debugCombo.objectName = DEBUG_COMBO_OBJECT_NAME
        logView.objectName = LOG_VIEW_OBJECT_NAME
        logView.document()?.let { LogHighlighter(it) }

        fun updateHeader() {
            updateHeaderLabel(headerLabel, activePath)
        }

        fun updateGameRunningIndicator() {
            updateGameRunningIndicator(project, gameRunningDot)
        }

        fun applyContentWidth(combo: QComboBox) {
            val metrics = combo.fontMetrics()
            var longestLabelWidth = 0
            for (i in 0 until combo.count) {
                val width = metrics.horizontalAdvance(combo.itemText(i))
                if (width > longestLabelWidth) longestLabelWidth = width
            }
            val width = (longestLabelWidth + COMBO_EXTRA_CHROME).coerceAtLeast(COMBO_MIN_WIDTH)
            combo.minimumWidth = width
            combo.maximumWidth = width
            combo.adjustSize()
        }

        fun refresh(force: Boolean = false) {
            if (!activePath.exists() || !activePath.isFile()) {
                logView.plainText = "Log not found: ${activePath.toString().redactUserPath()}"
                return
            }
            if (activeIsCompressed && !force) return
            if (!force && userPaused) return

            val modified = activePath.lastModifiedOrNull()?.toEpochMilliseconds()
            val size = activePath.sizeOrNull()

            val previousSize = lastSize
            if (!force && modified == lastStamp && size == previousSize) return

            val requestVersion = ++refreshVersion
            val scrollBar = logView.verticalScrollBar()
            val prevValue = scrollBar?.value() ?: 0
            val wasAtBottom = isAtBottom(scrollBar)

            try {
                if (!activeIsCompressed && !force && previousSize != null && size != null && size > previousSize) {
                    val text = try {
                        readDeltaFromOffset(activePath, previousSize)
                    } catch (_: Throwable) {
                        ""
                    }

                    if (text.isNotEmpty()) {
                        isUpdating = true
                        scrollBar?.blockSignals(true)
                        try {
                            if (wasAtBottom) {
                                logView.appendPlainText(text)
                                logView.moveCursor(QTextCursor.MoveOperation.End)
                                userPaused = false
                            } else {
                                val prevMax = scrollBar?.maximum() ?: 0
                                logView.appendPlainText(text)
                                scrollBar?.value = prevValue.coerceAtMost(prevMax)
                            }
                            lastStamp = modified
                            lastSize = size
                        } finally {
                            scrollBar?.blockSignals(false)
                            isUpdating = false
                        }
                        return
                    }
                }

                val targetPath = activePath
                val targetCompressed = activeIsCompressed
                ioScope.launch {
                    val text = try {
                        if (targetCompressed) readGzipTail(targetPath) else readTailBytes(targetPath)
                    } catch (_: Throwable) {
                        ""
                    }
                    runOnGuiThread {
                        if (requestVersion != refreshVersion) return@runOnGuiThread
                        if (activePath != targetPath || activeIsCompressed != targetCompressed) return@runOnGuiThread

                        val sb = logView.verticalScrollBar()
                        val restoreValue = sb?.value() ?: 0
                        val atBottomNow = isAtBottom(sb)
                        isUpdating = true
                        sb?.blockSignals(true)

                        try {
                            logView.plainText = text
                            QTimer.singleShot(0) {
                                val delayedSb = logView.verticalScrollBar()
                                if (force || atBottomNow) {
                                    logView.moveCursor(QTextCursor.MoveOperation.End)
                                    userPaused = false
                                } else if (delayedSb != null) {
                                    delayedSb.value = restoreValue.coerceAtMost(delayedSb.maximum())
                                }
                                delayedSb?.blockSignals(false)
                                isUpdating = false
                            }
                            lastStamp = modified
                            lastSize = size
                        } catch (_: Throwable) {
                            sb?.blockSignals(false)
                            isUpdating = false
                        }
                    }
                }
            } catch (_: Throwable) {
                scrollBar?.blockSignals(false)
                isUpdating = false
            }
        }

        fun setActive(path: VPath, compressed: Boolean) {
            activePath = path
            activeIsCompressed = compressed
            userPaused = false
            updateHeader()
            lastStamp = null
            lastSize = null
            refresh(force = true)
        }

        fun populateCombos() {
            val existing = logsDir.list().filter { it.isFile() }
            val latestOptions = mutableListOf<VPath>()
            val debugOptions = mutableListOf<VPath>()

            val latestLive = logsDir.resolve("latest.log")
            if (latestLive.exists()) latestOptions += latestLive
            val debugLive = logsDir.resolve("debug.log")
            if (debugLive.exists()) debugOptions += debugLive

            existing.forEach { p ->
                val name = p.fileName()
                if (isLatestArchived(name)) latestOptions += p
                if (isDebugArchived(name)) debugOptions += p
            }

            latestOptions.sortWith(compareByDescending<VPath> { latestSortToken(it.fileName()) }
                .thenByDescending { it.fileName() })
            debugOptions.sortWith(compareByDescending<VPath> { debugSortKey(it.fileName()) }
                .thenByDescending { it.fileName() })

            val prevLatest = latestCombo.currentData(Qt.ItemDataRole.UserRole) as? String
            val prevDebug = debugCombo.currentData(Qt.ItemDataRole.UserRole) as? String
            val activeRaw = activePath.toString()

            latestCombo.blockSignals(true)
            debugCombo.blockSignals(true)
            try {
                latestCombo.clear()
                debugCombo.clear()

                latestOptions.forEach { p ->
                    latestCombo.addItem(p.fileName(), p.toString())
                }
                debugOptions.forEach { p ->
                    debugCombo.addItem(p.fileName(), p.toString())
                }

                selectComboByUserData(latestCombo, prevLatest ?: activeRaw)
                selectComboByUserData(debugCombo, prevDebug ?: activeRaw)
                applyContentWidth(latestCombo)
                applyContentWidth(debugCombo)
                latestCombo.adjustSize()
                debugCombo.adjustSize()
            } finally {
                latestCombo.blockSignals(false)
                debugCombo.blockSignals(false)
            }

            val allRawOptions = (latestOptions.asSequence() + debugOptions.asSequence()).map { it.toString() }.toSet()
            if (!allRawOptions.contains(activeRaw)) {
                val fallback = latestOptions.firstOrNull() ?: debugOptions.firstOrNull()
                if (fallback != null) setActive(fallback, fallback.fileName().endsWith(".gz"))
            }
        }

        latestCombo.currentIndexChanged.connect {
            val raw = latestCombo.currentData(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            val p = VPath.parse(raw)
            setActive(p, p.fileName().endsWith(".gz"))
        }
        debugCombo.currentIndexChanged.connect {
            val raw = debugCombo.currentData(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            val p = VPath.parse(raw)
            setActive(p, p.fileName().endsWith(".gz"))
        }

        val scrollBar = logView.verticalScrollBar()
        if (scrollBar != null) {
            scrollBar.valueChanged.connect {
                if (isUpdating) return@connect
                userPaused = !isAtBottom(scrollBar)
            }
        }

        vBoxLayout(root) {
            contentsMargins = 6.m
            widgetSpacing = 6
            val headerRow = qWidget(root)
            hBoxLayout(headerRow) {
                contentsMargins = 0.m
                widgetSpacing = 6
                addWidget(headerLabel)
                addStretch(1)
                addWidget(gameRunningDot)
            }
            addWidget(headerRow)

            val controlsRow = qWidget(root)
            hBoxLayout(controlsRow) {
                contentsMargins = 0.m
                widgetSpacing = 6
                addWidget(latestLabel)
                addWidget(latestCombo)
                addWidget(debugLabel)
                addWidget(debugCombo)
                addStretch(1)
            }
            controlsRow.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            addWidget(controlsRow)
            addWidget(logView)
        }

        root.setThemedStyle {
            selector("QPlainTextEdit") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
            }
            selector("QLabel") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("QComboBox") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(2, 6, 2, 6)
            }
        }

        val timer = QTimer(root).apply {
            interval = LOG_REFRESH_INTERVAL_MS
            timeout.connect { refresh() }
            start()
        }
        val listTimer = QTimer(root).apply {
            interval = LIST_REFRESH_INTERVAL_MS
            timeout.connect { populateCombos() }
            start()
        }
        val unsubscribeGameProcess = GameLauncher.addGameProcessListener {
            runOnGuiThread {
                updateGameRunningIndicator()
            }
        }

        dock.destroyed.connect {
            timer.stop()
            listTimer.stop()
            ioScope.cancel()
            unsubscribeGameProcess()
        }

        updateHeader()
        populateCombos()
        updateGameRunningIndicator()
        if (latestCombo.count > 0) {
            latestCombo.currentIndex = 0
        } else if (debugCombo.count > 0) {
            debugCombo.currentIndex = 0
        }
        refresh(force = true)
        dock.setWidget(root)
        return dock
    }

    private fun configureStatusDot(dot: QLabel) {
        dot.text = ""
        dot.minimumWidth = STATUS_DOT_SIZE
        dot.minimumHeight = STATUS_DOT_SIZE
        dot.maximumWidth = STATUS_DOT_SIZE
        dot.maximumHeight = STATUS_DOT_SIZE
        dot.toolTip = "Game not running"
    }

    private fun updateHeaderLabel(header: QLabel, path: VPath) {
        header.text = path.toString().redactUserPath()
    }

    private fun updateGameRunningIndicator(project: ProjectBase, indicator: QLabel) {
        val ctx = GameLauncher.gameProcessSnapshot(project)
        val running = ctx?.isRunning == true
        val color = if (running) TColors.Green else TColors.Subtext
        val radius = STATUS_DOT_SIZE / 2
        indicator.objectName = "indicator"
        indicator.styleSheet = qtStyle {
            selector("#indicator") {
                backgroundColor(color)
                borderRadius(radius)
                minWidth(STATUS_DOT_SIZE)
                minHeight(STATUS_DOT_SIZE)
                maxWidth(STATUS_DOT_SIZE)
                maxHeight(STATUS_DOT_SIZE)
            }
        }.toStyleSheet()
        val pid = ctx?.pid
        indicator.toolTip = if (running) {
            if (pid != null && pid > 0) "Game running (PID $pid)" else "Game running"
        } else {
            "Game not running"
        }
    }

    private fun readTailBytes(path: VPath, maxBytes: Int = TAIL_MAX_BYTES): String {
        val file = path.toJFile()
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val start = (size - maxBytes).coerceAtLeast(0)
            val len = (size - start).toInt().coerceAtLeast(0)
            if (len <= 0) return ""
            val buf = ByteArray(len)
            raf.seek(start)
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            return if (start > 0) {
                "[log truncated: showing last $maxBytes bytes]\n$text"
            } else {
                text
            }
        }
    }

    /**
     * Read from [fromOffset] (bytes) to end, capped to [maxBytes].
     * If range is larger than [maxBytes], returns the last [maxBytes] bytes with a truncation notice.
     */
    private fun readDeltaFromOffset(path: VPath, fromOffset: Long, maxBytes: Int = TAIL_MAX_BYTES): String {
        val file = path.toJFile()
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size <= 0) return ""
            var start = fromOffset.coerceAtLeast(0L)
            val available = size - start
            if (available <= 0) return ""
            if (available > maxBytes) {
                start = size - maxBytes
            }
            val len = (size - start).toInt().coerceAtLeast(0)
            if (len <= 0) return ""
            val buf = ByteArray(len)
            raf.seek(start)
            raf.readFully(buf)
            val text = String(buf, Charsets.UTF_8)
            return if (start > fromOffset.coerceAtLeast(0L)) {
                "[log truncated: showing last $maxBytes bytes]\n$text"
            } else {
                text
            }
        }
    }

    private fun readGzipTail(path: VPath, maxChars: Int = GZIP_TAIL_MAX_CHARS): String {
        val buffer = StringBuilder(maxChars)
        var truncated = false
        path.inputStream().use { input ->
            GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    buffer.append(line).append('\n')
                    if (buffer.length > maxChars) {
                        truncated = true
                        buffer.delete(0, buffer.length - maxChars)
                    }
                }
            }
        }
        if (buffer.isEmpty()) return ""
        return if (truncated) "[log truncated: showing last $maxChars chars]\n$buffer" else buffer.toString()
    }

    private fun isAtBottom(scrollBar: QScrollBar?): Boolean {
        if (scrollBar == null) return true
        return scrollBar.value() >= (scrollBar.maximum() - SCROLL_BOTTOM_SNAP_PX)
    }

    private fun isLatestArchived(name: String): Boolean = latestArchivedNameRx.matches(name)

    private fun isDebugArchived(name: String): Boolean = debugArchivedNameRx.matches(name)

    private fun latestSortKey(name: String): Pair<String, Int> {
        if (name == "latest.log") return "~~~~" to Int.MAX_VALUE
        val match = latestSortNameRx.matchEntire(name) ?: return "" to -1
        val date = match.groupValues[1]
        val n = match.groupValues[2].toIntOrNull() ?: -1
        return date to n
    }

    private fun latestSortToken(name: String): String {
        val (date, index) = latestSortKey(name)
        return "$date|${index.toString().padStart(6, '0')}"
    }

    private fun debugSortKey(name: String): Int {
        if (name == "debug.log") return Int.MAX_VALUE
        val match = debugSortNameRx.matchEntire(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun selectComboByUserData(combo: QComboBox, target: String?) {
        if (target == null) return
        for (i in 0 until combo.count) {
            if (combo.itemData(i, Qt.ItemDataRole.UserRole) == target) {
                combo.currentIndex = i
                return
            }
        }
    }
}

private class LogHighlighter(doc: QTextDocument) : QSyntaxHighlighter(doc) {
    private val errorFormat = QTextCharFormat().apply {
        setForeground(QBrush(QColor(TColors.Error)))
    }
    private val warnFormat = QTextCharFormat().apply {
        setForeground(QBrush(QColor(TColors.Warning)))
    }
    private val infoFormat = QTextCharFormat().apply {
        setForeground(QBrush(QColor(TColors.Syntax.Information)))
    }
    private val debugFormat = QTextCharFormat().apply {
        setForeground(QBrush(QColor(TColors.Subtext)))
    }
    private val timestampFormat = QTextCharFormat().apply {
        setForeground(QBrush(QColor(TColors.Syntax.Comment)))
    }

    private val errorRx = Regex("\\b(ERROR|FATAL)\\b")
    private val warnRx = Regex("\\b(WARN|WARNING)\\b")
    private val infoRx = Regex("\\b(INFO)\\b")
    private val debugRx = Regex("\\b(DEBUG|TRACE)\\b")
    private val timeRx = Regex("^\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?")

    override fun highlightBlock(text: String) {
        applyRegex(text, errorRx, errorFormat)
        applyRegex(text, warnRx, warnFormat)
        applyRegex(text, infoRx, infoFormat)
        applyRegex(text, debugRx, debugFormat)
        applyRegex(text, timeRx, timestampFormat)
    }

    private fun applyRegex(text: String, rx: Regex, format: QTextCharFormat) {
        rx.findAll(text).forEach { m ->
            setFormat(m.range.first, m.range.last - m.range.first + 1, format)
        }
    }
}
