/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.EditorPane
import io.github.tritium_launcher.api.editor.EditorPaneProvider
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.asAlignment
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.frame
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.qt.core.QRectF
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class OptionsTxtPane(
    project: ProjectBase,
    file: VPath
) : SplitEditorPane(project, file) {

    override val viewModes: List<String> = listOf("Preview", "Split", "Text")
    override var currentViewMode: String? = "Preview"

    override fun viewModeIcon(mode: String): String? = when (mode) {
        "Preview" -> "ui/editor_visual"
        "Split" -> "ui/editor_text_other_right"
        "Text" -> "ui/editor_text"
        else -> null
    }

    override fun onViewModeChanged(mode: String) {
        when (mode) {
            "Preview" -> { syncTextToPreview(); showLeftOnly() }
            "Text" -> { syncPreviewToText(); showRightOnly() }
            "Split" -> { syncPreviewToText(); showBoth() }
        }
    }

    private val paneFile: VPath get() = file!!
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var previewModified = false

    private val scrollArea = QScrollArea()
    private val content = QWidget()
    private val contentLayout = QVBoxLayout(content)
    private var loadingWidget: LoadingWidget? = null

    private val textEditor = TextEditorPane(project, file, null)

    private var schemaLoaded = false
    private var schemaOptions: List<SchemaOption> = emptyList()
    private var currentValues: MutableMap<String, String> = LinkedHashMap()
    private var originalLines: List<String> = emptyList()
    private var missingSchemaKeys: List<String> = emptyList()
    private val searchField = QLineEdit()
    private var searchableSections: List<SearchableSection> = emptyList()
    private val rowHeight: Int by lazy { QComboBox().apply { addItem("X") }.sizeHint().height() }

    init {
        val leftWidget = QWidget()
        val leftLayout = QVBoxLayout(leftWidget)
        leftLayout.setContentsMargins(8, 8, 8, 8)
        leftLayout.setSpacing(4)

        leftWidget.setThemedStyle {
            widget { backgroundColor(TColors.Surface1) }
        }

        val headerLabel = label("Minecraft Options") {
            font = QFont(font).apply { setPointSize(14); setBold(true) }
        }
        leftLayout.addWidget(headerLabel)

        val infoLabel = label("Changes are saved to options.txt. Launch the game to apply them.") {
            wordWrap = true
            setStyle {
                color(TColors.Subtext)
                fontSize(11)
            }
        }
        leftLayout.addWidget(infoLabel)

        searchField.placeholderText = "Search options..."
        searchField.setClearButtonEnabled(true)
        searchField.textChanged.connect { text -> filterOptions(text) }
        leftLayout.addWidget(searchField)

        scrollArea.widgetResizable = true
        scrollArea.frameShape = QFrame.Shape.NoFrame
        scrollArea.setThemedStyle {
            selector("content") { backgroundColor(TColors.Surface1) }
        }
        leftLayout.addWidget(scrollArea, 1)

        content.setThemedStyle {
            widget { backgroundColor(TColors.Surface1) }
        }
        contentLayout.setContentsMargins(0, 0, 0, 0)
        contentLayout.setSpacing(4)
        contentLayout.setAlignment(Qt.AlignmentFlag.AlignTop)

        setLeftContent(leftWidget)
        setRightContent(textEditor.widget(), textEditor)

        textEditor.widget().hide()

        textEditor.onModifiedChanged = { recalcModified() }
    }

    override fun recalcModified() {
        modified = previewModified || (rightPane?.modified == true)
    }

    override fun onClose() {
        scope.cancel()
        super.onClose()
    }

    override fun onOpen() {
        scope.launch {
            showLoading()

            val fileContents = withContext(Dispatchers.IO) { readOptionsTxt() }
            originalLines = fileContents
            currentValues = parseOptionsTxt(fileContents)

            val schemaJson = withContext(Dispatchers.IO) { readSchemaFile() }
            if (schemaJson != null) {
                hideLoading()
                schemaOptions = parseSchemaOptions(schemaJson)
                missingSchemaKeys = currentValues.keys.filter {
                    it !in schemaOptions.map(SchemaOption::key)
                }
                schemaLoaded = true
                rebuildUi()
            } else {
                showError("No options found. Launch the game with the Companion mod to generate the options schema.")
            }
        }
        super.onOpen()
    }

    private data class SchemaOption(
        val key: String,
        val caption: String,
        val tooltip: String?,
        val category: String = "General",
        val value: JsonElement?,
        val schema: JsonObject?
    )

    private fun readSchemaFile(): JsonObject? {
        val f = paneFile.parent().resolve(".tr/options_export.json")
        if (!f.exists()) return null
        return runCatching {
            Json.parseToJsonElement(f.readTextOrNull() ?: return null).jsonObject
        }.getOrNull()
    }

    private fun parseSchemaOptions(root: JsonObject?): List<SchemaOption> {
        val arr = root?.get("options")?.jsonArray ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            SchemaOption(
                key = obj["key"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                caption = obj["caption"]?.jsonPrimitive?.content ?: "",
                tooltip = obj["tooltip"]?.jsonPrimitive?.content,
                category = obj["category"]?.jsonPrimitive?.content ?: "General",
                value = obj["value"],
                schema = obj["schema"]?.jsonObject
            )
        }
    }

    private fun readOptionsTxt(): List<String> {
        if (!paneFile.exists()) return emptyList()
        return paneFile.readTextOrNull()?.lines() ?: emptyList()
    }

    private fun parseOptionsTxt(lines: List<String>): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }
        return map
    }

    private fun rebuildUi() {
        while (contentLayout.count() > 0) {
            contentLayout.takeAt(0)?.widget()?.disposeLater()
        }

        val fm = QFontMetrics(QFont().apply { setPointSize(10) })
        val captionMaxWidth = mutableMapOf<String, Int>()
        for (opt in schemaOptions) {
            val text = opt.caption.ifEmpty { opt.key }
            val w = fm.horizontalAdvance(text) + 8
            captionMaxWidth.merge(opt.category, w, Math::max)
        }

        val sections = mutableListOf<SearchableSection>()
        var currentCategory = ""
        var categoryWidget: QWidget? = null
        var categoryLayout: QVBoxLayout? = null
        var currentRows = mutableListOf<OptionRow>()

        for (opt in schemaOptions) {
            val category = opt.category
            if (category != currentCategory) {
                if (categoryWidget != null) {
                    sections.add(SearchableSection(categoryWidget, currentRows.toList()))
                }
                currentCategory = category
                currentRows = mutableListOf()
                categoryWidget = QWidget()
                categoryLayout = QVBoxLayout(categoryWidget)
                categoryLayout.setContentsMargins(0, 8, 0, 0)
                categoryLayout.setSpacing(2)

                val catLabel = label(category) {
                    font = QFont(font).apply { setPointSize(11); setBold(true) }
                    setStyle { color(TColors.Accent) }
                }
                categoryLayout.addWidget(catLabel)

                val separator = frame {
                    frameShape = QFrame.Shape.HLine
                    frameShadow = QFrame.Shadow.Sunken
                    setStyle { color(TColors.Surface2) }
                }
                categoryLayout.addWidget(separator)

                contentLayout.addWidget(categoryWidget)
            }

            val currentValue = currentValues[opt.key]
            if (currentValue != null) {
                val row = OptionRow(opt, currentValue, rowHeight, captionMaxWidth[opt.category] ?: 0) {
                    currentValues[opt.key] = it
                    previewModified = true
                    recalcModified()
                }
                categoryLayout?.addWidget(row)
                currentRows.add(row)
            }
        }
        if (categoryWidget != null) {
            sections.add(SearchableSection(categoryWidget, currentRows.toList()))
        }
        searchableSections = sections

        if (missingSchemaKeys.isNotEmpty()) {
            val unknownSection = QWidget()
            val unknownLayout = QVBoxLayout(unknownSection)
            unknownLayout.setContentsMargins(0, 8, 0, 0)
            unknownLayout.setSpacing(2)

            val unknownLabel = label("Other Options (not in schema)") {
                font = QFont(font).apply { setPointSize(11); setBold(true) }
                setStyle { color(TColors.Subtext) }
            }
            unknownLayout.addWidget(unknownLabel)

            val separator = frame {
                frameShape = QFrame.Shape.HLine
                frameShadow = QFrame.Shadow.Sunken
                setStyle { color(TColors.Surface2) }
            }
            unknownLayout.addWidget(separator)

            for (key in missingSchemaKeys.sorted()) {
                val value = currentValues[key] ?: continue
                val row = qWidget()
                val rowLayout = hBoxLayout(row) {
                    setContentsMargins(0, 2, 0, 2)
                    setSpacing(8)
                }
                rowLayout.addWidget(label(key) {
                    minimumWidth = 160
                    setStyle {
                        color(TColors.Subtext)
                        fontSize(11)
                    }
                })
                rowLayout.addWidget(label(value) {
                    setStyle { color(TColors.Text) }
                }, 1)
                unknownLayout.addWidget(row)
            }
            contentLayout.addWidget(unknownSection)
        }

        contentLayout.addStretch(1)
    }

    private fun filterOptions(text: String) {
        val q = text.trim().lowercase()
        for (section in searchableSections) {
            var anyVisible = false
            for (row in section.rows) {
                val match = q.isEmpty() ||
                    row.opt.key.lowercase().contains(q) ||
                    row.opt.caption.lowercase().contains(q)
                row.isVisible = match
                if (match) anyVisible = true
            }
            section.widget.isVisible = anyVisible
        }
    }

    private fun showLoading() {
        val w = LoadingWidget()
        loadingWidget = w
        w.start()
        scrollArea.setWidget(w)
    }

    private fun hideLoading() {
        loadingWidget?.stop()
        loadingWidget = null
        scrollArea.setWidget(content)
    }

    private fun showError(msg: String) {
        loadingWidget?.let {
            it.stop()
            it.label.text = msg
        }
    }

    private class LoadingSpinner : QWidget() {
        private var rotation = 0
        private val timer = QTimer(this)

        init {
            setFixedSize(40, 40)
            timer.setInterval(50)
            timer.timeout.connect {
                rotation = (rotation + 30) % 360
                update()
            }
        }

        fun start() { timer.start() }
        fun stop() { timer.stop() }

        override fun paintEvent(event: QPaintEvent?) {
            val p = QPainter(this)
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.translate(20.0, 20.0)
            for (i in 0 until 12) {
                p.save()
                p.rotate(i * 30.0)
                val alpha = (255.0 * (1.0 - i / 12.0)).toInt().coerceIn(0, 255)
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(QColor(150, 150, 150, alpha))
                p.drawRoundedRect(QRectF(14.0, -2.0, 6.0, 4.0), 2.0, 2.0)
                p.restore()
            }
            p.end()
        }
    }

    private class LoadingWidget(message: String = "Loading options...") : QWidget() {
        val spinner = LoadingSpinner()
        val label: QLabel

        init {
            val layout = QVBoxLayout(this)
            layout.setAlignment(Qt.AlignmentFlag.AlignCenter)
            layout.addStretch(1)
            layout.addWidget(spinner, 0, Qt.AlignmentFlag.AlignCenter)
            label = QLabel(message).apply {
                setAlignment(Qt.AlignmentFlag.AlignCenter)
                setStyle { color(TColors.Subtext) }
            }
            layout.addWidget(label, 0, Qt.AlignmentFlag.AlignCenter)
            layout.addStretch(1)
        }

        fun start() { spinner.start() }
        fun stop() { spinner.stop() }
    }

    override suspend fun save(): Boolean = try {
        if (currentViewMode == "Text" || currentViewMode == "Split") {
            if (!textEditor.save()) return false
            val text = paneFile.readTextOrNull() ?: ""
            originalLines = text.lines()
            currentValues = parseOptionsTxt(originalLines)
        } else {
            val modifiedKeys = currentValues.entries
                .filter { (key, value) ->
                    val origIdx = originalLines.indexOfFirst { it.startsWith("$key:") }
                    origIdx < 0 || originalLines[origIdx] != "$key:$value"
                }
                .map { it.key to it.value }.toSet()
            val newLines = originalLines.map { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx)
                    val newValue = currentValues[key]
                    if (newValue != null && modifiedKeys.any { it.first == key }) "$key:$newValue" else line
                } else line
            }
            val result = newLines.toMutableList()
            for ((key, value) in modifiedKeys) {
                if (originalLines.none { it.startsWith("$key:") }) result.add("$key:$value")
            }
            paneFile.writeBytes(result.joinToString("\n").toByteArray())
        }
        previewModified = false
        modified = false
        true
    } catch (t: Throwable) {
        logger.error("Failed to save options.txt", t)
        false
    }

    private fun buildOptionsFileContent(): String {
        val modifiedKeys = currentValues.entries
            .filter { (key, value) ->
                val origIdx = originalLines.indexOfFirst { it.startsWith("$key:") }
                origIdx < 0 || originalLines[origIdx] != "$key:$value"
            }
            .map { it.key to it.value }.toSet()
        val newLines = originalLines.map { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx)
                val newValue = currentValues[key]
                if (newValue != null && modifiedKeys.any { it.first == key }) "$key:$newValue" else line
            } else line
        }
        val result = newLines.toMutableList()
        for ((key, value) in modifiedKeys) {
            if (originalLines.none { it.startsWith("$key:") }) result.add("$key:$value")
        }
        return result.joinToString("\n")
    }

    private fun syncPreviewToText() {
        val text = buildOptionsFileContent()
        paneFile.writeBytes(text.toByteArray())
        textEditor.reload()
    }

    private fun updateRowValues() {
        for (section in searchableSections) {
            for (row in section.rows) {
                val newValue = currentValues[row.opt.key]
                if (newValue != null) row.updateValue(newValue)
            }
        }
    }

    private fun syncTextToPreview() {
        val text = textEditor.textContent
        paneFile.writeBytes(text.toByteArray())
        originalLines = text.lines()
        currentValues = parseOptionsTxt(originalLines)
        if (schemaLoaded) {
            schemaOptions = parseSchemaOptions(readSchemaFile())
            missingSchemaKeys = currentValues.keys.filter { it !in schemaOptions.map(SchemaOption::key) }
            updateRowValues()
        }
    }

    private class SearchableSection(
        val widget: QWidget,
        val rows: List<OptionRow>
    )

    private class OptionRow(
        val opt: SchemaOption,
        currentValue: String,
        rowHeight: Int,
        captionMinWidth: Int,
        private val onChange: (String) -> Unit
    ) : QWidget() {
        private var ignoreChanges = false
        private var valueWidget: QWidget? = null
        private var sliderLabel: QLabel? = null
        private val sliderMin: Double
        private val sliderMax: Double
        private val sliderSpan: Double
        private val sliderValueType: String

        init {
            val schema = opt.schema
            val controlType = schema?.get("control")?.jsonPrimitive?.content ?: "unknown"
            val valueType = schema?.get("type")?.jsonPrimitive?.content ?: "unknown"
            sliderMin = schema?.get("min")?.jsonPrimitive?.doubleOrNull ?: 0.0
            sliderMax = schema?.get("max")?.jsonPrimitive?.doubleOrNull ?: 100.0
            sliderSpan = sliderMax - sliderMin
            sliderValueType = valueType

            setFixedHeight(rowHeight)
            val layout = QHBoxLayout(this)
            layout.setContentsMargins(0, 2, 0, 2)
            layout.setSpacing(8)

            val textLabel = label(opt.caption.ifEmpty { opt.key }) {
                font = QFont(font).apply { setPointSize(10) }
                minimumWidth = captionMinWidth
                wordWrap = true
            }
            layout.addWidget(textLabel)

            val ctrlContainer = QWidget()
            val ctrlLayout = QHBoxLayout(ctrlContainer)
            ctrlLayout.setContentsMargins(0, 0, 0, 0)

            when (controlType) {
                "cycling" -> {
                    val possible = schema?.get("values")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                    if (valueType == "boolean" || possible.map { it.lowercase() } == listOf("true", "false")) {
                        val toggle = QCheckBox().apply {
                            isChecked = currentValue.trim('"').lowercase() == "true"
                            toggled.connect({ checked: Boolean ->
                                if (!ignoreChanges) onChange(if (checked) "true" else "false")
                            })
                        }
                        valueWidget = toggle
                        ctrlLayout.addWidget(toggle, 0, Qt.AlignmentFlag.AlignLeft)
                    } else {
                        val combo = QComboBox().apply {
                            for (v in possible) addItem(v)
                            val cleaned = currentValue.trim('"')
                            val idx = possible.indexOfFirst { it.equals(cleaned, ignoreCase = true) }
                            if (idx >= 0) currentIndex = idx
                            currentTextChanged.connect({ text: String ->
                                if (!ignoreChanges) onChange("\"$text\"")
                            })
                        }
                        valueWidget = combo
                        ctrlLayout.addWidget(combo, 0, Qt.AlignmentFlag.AlignLeft)
                    }
                    ctrlLayout.addStretch(1)
                }

                "slider" -> {
                    val raw = currentValue.trim('"')
                    val rawNum = raw.toDoubleOrNull()
                    val currentNum = if (rawNum != null && (rawNum !in sliderMin..sliderMax) && rawNum in 0.0..1.0) {
                        sliderMin + sliderSpan * rawNum
                    } else {
                        rawNum ?: sliderMin
                    }.coerceIn(sliderMin, sliderMax)

                    if (sliderSpan > 1000) {
                        val spin = if (valueType == "int") {
                            QSpinBox().apply {
                                setRange(sliderMin.toInt(), sliderMax.toInt())
                                value = currentNum.toInt()
                                valueChanged.connect({ v: Int ->
                                    if (!ignoreChanges) onChange(v.toString())
                                })
                            }
                        } else {
                            QDoubleSpinBox().apply {
                                setRange(sliderMin, sliderMax)
                                singleStep = sliderSpan / 100.0
                                value = currentNum
                                valueChanged.connect({ v: Double ->
                                    if (!ignoreChanges) onChange(v.toString())
                                })
                            }
                        }
                        valueWidget = spin
                        ctrlLayout.addWidget(spin, 0, Qt.AlignmentFlag.AlignLeft)
                        ctrlLayout.addStretch(1)
                    } else {
                        val valLabel = label {
                            minimumWidth = 40
                            alignment = Qt.AlignmentFlag.AlignCenter.asAlignment()
                        }
                        sliderLabel = valLabel
                        val s = object : QSlider(Qt.Orientation.Horizontal) {
                            override fun wheelEvent(ev: QWheelEvent?) { }
                        }.apply {
                            setRange(0, 1000)
                            value = ((currentNum - sliderMin) / sliderSpan * 1000).toInt().coerceIn(0, 1000)
                            tickPosition = QSlider.TickPosition.NoTicks
                            valueChanged.connect({ v: Int ->
                                if (!ignoreChanges) {
                                    val real = sliderMin + sliderSpan * v / 1000.0
                                    val display = if (valueType == "int") real.toInt().toString() else String.format("%.2f", real)
                                    valLabel.text = display
                                    onChange(real.toString())
                                }
                            })
                        }
                        valueWidget = s
                        val initDisplay = if (valueType == "int") currentNum.toInt().toString() else String.format("%.2f", currentNum)
                        valLabel.text = initDisplay
                        ctrlLayout.addWidget(s, 1)
                        ctrlLayout.addWidget(valLabel)
                        ctrlLayout.addStretch(3)
                    }
                }

                else -> {
                    val rawKey = QLineEdit(currentValue).apply {
                        textChanged.connect({ text: String ->
                            if (!ignoreChanges) onChange(text)
                        })
                    }
                    valueWidget = rawKey
                    ctrlLayout.addWidget(rawKey, 1)
                }
            }

            layout.addWidget(ctrlContainer, 1)

            layout.addWidget(label(opt.key) {
                minimumWidth = 190
                alignment = Qt.AlignmentFlag.AlignRight.asAlignment()
                setStyle {
                    color(TColors.Subtext)
                    fontSize(10)
                }
            })

            if (opt.tooltip != null) {
                toolTip = opt.tooltip
                textLabel.toolTip = opt.tooltip
            }
        }

        fun updateValue(newValue: String) {
            ignoreChanges = true
            val cleaned = newValue.trim('"')
            when (val w = valueWidget) {
                is QCheckBox -> w.isChecked = cleaned.lowercase() == "true"
                is QComboBox -> {
                    val idx = (0 until w.count()).indexOfFirst {
                        w.itemText(it).equals(cleaned, ignoreCase = true)
                    }
                    if (idx >= 0) w.currentIndex = idx
                }
                is QSpinBox -> {
                    val num = cleaned.toDoubleOrNull() ?: return
                    w.value = num.toInt().coerceIn(w.minimum(), w.maximum())
                }
                is QDoubleSpinBox -> {
                    val num = cleaned.toDoubleOrNull() ?: return
                    w.value = num.coerceIn(w.minimum(), w.maximum())
                }
                is QSlider -> {
                    val num = cleaned.toDoubleOrNull() ?: return
                    w.value = ((num - sliderMin) / sliderSpan * 1000).toInt().coerceIn(0, 1000)
                    sliderLabel?.text = if (sliderValueType == "int") num.toInt().toString() else String.format("%.2f", num)
                }
                is QLineEdit -> w.text = newValue
            }
            ignoreChanges = false
        }
    }

    object Provider : EditorPaneProvider {
        override val id = "options_txt"
        override val displayName = "Options"
        override val order = -50

        override fun canOpen(file: VPath, project: ProjectBase): Boolean =
            BuiltinFileTypes.OptionsTxt.matches(file, project)

        override fun tabIcon(file: VPath, project: ProjectBase): QIcon = TIcons.OptionsTxt.icon

        override fun create(project: ProjectBase, file: VPath): EditorPane =
            OptionsTxtPane(project, file)
    }
}
