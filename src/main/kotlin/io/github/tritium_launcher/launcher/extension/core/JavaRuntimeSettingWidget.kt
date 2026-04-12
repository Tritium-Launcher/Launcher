package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.Java
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.settings.RefreshableSettingWidget
import io.github.tritium_launcher.launcher.settings.SettingWidgetContext
import io.github.tritium_launcher.launcher.ui.widgets.InfoLineEditWidget
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.widgets.*

/**
 * Settings widget for choosing a Java executable path.
 */
internal class JavaPathSettingWidget(
    private val ctx: SettingWidgetContext<String>,
    private val targetMajor: Int
) : QWidget(), RefreshableSettingWidget {
    private val pathInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
        placeholderText = "Path to java executable or JAVA_HOME"
    }
    private val detectBtn = TPushButton {
        text = "..."
        minimumWidth = 30
        maximumWidth = 36
        minimumHeight = 25
        toolTip = "Auto-detect installed Java runtimes"
        textVerticalOffset = -4
    }
    private var refreshing = false

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
            addWidget(pathInput, 1)
            addWidget(detectBtn, 0)
        }

        pathInput.editingFinished.connect { commitInput() }
        detectBtn.onClicked { openAutoDetectDialog() }

        refreshFromSettingValue()
    }

    /**
     * Refreshes the input field from the current settings value.
     */
    override fun refreshFromSettingValue() {
        refreshing = true
        try {
            pathInput.text = ctx.currentValue().trim()
        } finally {
            refreshing = false
        }
    }

    /**
     * Applies the edited path to staged settings state.
     */
    private fun commitInput() {
        if (refreshing) return
        ctx.updateValue(pathInput.text.trim())
    }

    /**
     * Opens the runtime auto-detect dialog and applies the selected executable.
     */
    private fun openAutoDetectDialog() {
        val selectedPath = JavaRuntimeAutoDetectDialog(this, targetMajor).choosePath() ?: return
        refreshing = true
        try {
            pathInput.text = selectedPath
        } finally {
            refreshing = false
        }
        ctx.updateValue(selectedPath)
    }
}

/**
 * Modal dialog listing installed Java runtimes.
 */
private class JavaRuntimeAutoDetectDialog(
    parent: QWidget?,
    private val preferredMajor: Int
) : QDialog(parent) {
    private val hintLabel = label("Select a Java runtime for Java $preferredMajor.")
    private val table = QTableWidget()
    private var filling = false
    private var selectedPath: String? = null

    init {
        objectName = "javaRuntimeAutoDetectDialog"
        windowTitle = "Auto-Detect Java Runtime"
        modal = true
        resize(980, 460)
        minimumSize = qs(760, 360)

        hintLabel.wordWrap = true

        table.columnCount = 3
        table.setHorizontalHeaderItem(0, QTableWidgetItem("Java"))
        table.setHorizontalHeaderItem(1, QTableWidgetItem("Version"))
        table.setHorizontalHeaderItem(2, QTableWidgetItem("Executable Path"))
        table.selectionBehavior = QAbstractItemView.SelectionBehavior.SelectRows
        table.selectionMode = QAbstractItemView.SelectionMode.SingleSelection
        table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        table.alternatingRowColors = true
        table.horizontalHeader()?.let { header ->
            header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        }

        table.itemSelectionChanged.connect {
            if (filling) return@connect
            selectCurrentRow()
        }

        vBoxLayout(this) {
            contentsMargins = 10.m
            widgetSpacing = 8
            addWidget(hintLabel)
            addWidget(table, 1)
        }
    }

    /**
     * Opens the dialog and returns the selected executable path.
     */
    fun choosePath(): String? {
        loadRuntimes()
        exec()
        return selectedPath
    }

    /**
     * Fills the table from runtime detection results.
     */
    private fun loadRuntimes() {
        val runtimes = Java.detectInstalledRuntimes()
            .sortedWith(
                compareByDescending<Java.RuntimeCandidate> { it.major == preferredMajor }
                    .thenByDescending { it.major }
                    .thenBy { it.executablePath.lowercase() }
            )

        filling = true
        try {
            table.clearContents()
            if (runtimes.isEmpty()) {
                table.rowCount = 1
                val item = QTableWidgetItem("No Java runtimes were detected on this system.")
                item.setFlags(Qt.ItemFlag.ItemIsEnabled)
                table.setItem(0, 0, item)
                table.setSpan(0, 0, 1, 3)
                return
            }

            table.rowCount = runtimes.size
            runtimes.forEachIndexed { row, runtime ->
                val javaItem = QTableWidgetItem("Java ${runtime.major}")
                javaItem.setData(Qt.ItemDataRole.UserRole, runtime.executablePath)
                val versionItem = QTableWidgetItem(runtime.version)
                val pathItem = QTableWidgetItem(runtime.executablePath)
                if (runtime.major == preferredMajor) {
                    javaItem.setData(Qt.ItemDataRole.ToolTipRole, "Recommended for this setting.")
                }
                table.setItem(row, 0, javaItem)
                table.setItem(row, 1, versionItem)
                table.setItem(row, 2, pathItem)
            }
        } finally {
            filling = false
        }
    }

    /**
     * Applies the selected row and closes the dialog.
     */
    private fun selectCurrentRow() {
        val row = table.currentRow()
        if (row < 0) return
        val path = table.item(row, 0)?.data(Qt.ItemDataRole.UserRole) as? String ?: return
        selectedPath = path
        accept()
    }
}
