package io.github.tritium_launcher.launcher.ui.logging

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logging.Logs
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.redactUserPath
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QMimeData
import io.qt.gui.QTextCursor
import io.qt.widgets.*

/**
 * Live log viewer dialog for `~/tritium/logs/tritium.log`.
 */
class LogDialog(parent: QWidget? = null) : QDialog(parent) {
    private val logPathLabel = QLabel()
    private val copyFileButton = TPushButton()
    private val logView = QPlainTextEdit()
    private val unsubscribe: () -> Unit

    init {
        objectName = "tritiumLogDialog"
        windowTitle = "Tritium Log"
        modal = false
        resize(qs(1020, 640))
        minimumSize = qs(760, 460)

        logPathLabel.objectName = "tritiumLogPath"
        logPathLabel.text = Logs.currentLogFilePath()

        copyFileButton.apply {
            objectName = "tritiumCopyLogFileButton"
            text = "Copy File"
            toolTip = "Copy the current log file to clipboard"
        }

        logView.objectName = "tritiumLogView"
        logView.isReadOnly = true
        logView.lineWrapMode = QPlainTextEdit.LineWrapMode.NoWrap

        val header = QWidget(this)
        hBoxLayout(header) {
            contentsMargins = 0.m
            widgetSpacing = 6
            addWidget(logPathLabel)
            addStretch(1)
            addWidget(copyFileButton)
        }

        vBoxLayout(this) {
            contentsMargins = 8.m
            widgetSpacing = 6
            addWidget(header)
            addWidget(logView)
        }

        setThemedStyle {
            selector("#tritiumLogDialog") {
                backgroundColor(TColors.Surface0)
            }
            selector("#tritiumLogPath") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#tritiumLogView") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
            }
        }

        reloadFromDisk()
        copyFileButton.clicked.connect {
            copyCurrentLogFileToClipboard()
        }
        unsubscribe = Logs.addEntryListener { entry ->
            runOnGuiThread {
                appendEntry(entry)
            }
        }

        destroyed.connect {
            unsubscribe()
        }
    }

    /**
     * Opens the dialog and refreshes content from the current log file.
     */
    fun openAndFocus() {
        reloadFromDisk()
        show()
        raise()
        activateWindow()
    }

    /**
     * Reloads full log text from disk.
     */
    private fun reloadFromDisk() {
        logPathLabel.text = Logs.currentLogFilePath()
        logView.setPlainText(Logs.readCurrentLog())
        moveCursorToBottom()
    }

    /**
     * Appends a single rendered log entry to the text view.
     */
    private fun appendEntry(entry: String) {
        if (entry.isEmpty()) return
        logView.moveCursor(QTextCursor.MoveOperation.End)
        logView.insertPlainText(entry)
        moveCursorToBottom()
    }

    private fun copyCurrentLogFileToClipboard() {
        val sourceLog = runCatching {
            VPath.parse(Logs.currentLogFilePath()).expandHome().toAbsolute().normalize()
        }.getOrNull() ?: return
        if (!sourceLog.exists() || !sourceLog.isFile()) return

        val redactedContent = sourceLog.readTextOrNull()?.redactUserPath() ?: return
        val tempLog = runCatching {
            val tmpRoot = VPath.parse(System.getProperty("java.io.tmpdir")).expandHome().toAbsolute().normalize()
            tmpRoot.resolve("tritium").resolve("clipboard").resolve("tritium.log")
        }.getOrNull() ?: return
        val tempDir = tempLog.parent()
        if (!tempDir.exists() && !tempDir.mkdirs()) return
        runCatching {
            tempLog.writeBytes(redactedContent.toByteArray(Charsets.UTF_8))
        }.getOrElse { return }

        val clipboard = QApplication.clipboard() ?: return
        val localPath = tempLog.toString()
        val mime = QMimeData()
        mime.setUrls(listOf(tempLog.toQUrl()))
        mime.setText(localPath.redactUserPath())
        clipboard.setMimeData(mime)
    }

    /**
     * Keeps viewport anchored at the newest entry.
     */
    private fun moveCursorToBottom() {
        logView.moveCursor(QTextCursor.MoveOperation.End)
    }
}
