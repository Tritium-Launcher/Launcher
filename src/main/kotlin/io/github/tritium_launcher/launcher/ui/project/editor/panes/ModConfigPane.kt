package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.mod_config.*
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPaneProvider
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.widgets.*
import java.math.BigDecimal

internal class ModConfigPane(
    project: ProjectBase,
    file: VPath,
    private var root: ConfigNode,
    private val format: ConfigFormat
) : EditorPane(project, file) {
    private val paneFile: VPath get() = file!!

    private fun applyItemMargins(layout: QBoxLayout, compact: Boolean) {
        if (compact) {
            layout.setContentsMargins(0, 8, 0, 8)
        } else {
            layout.setContentsMargins(0, 12, 0, 12)
        }
    }

    private val container = qWidget {
        objectName = "modConfigPaneContainer"
        autoFillBackground = true
    }
    private val layout = vBoxLayout(container) {
        setAlignment(Qt.AlignmentFlag.AlignTop)
        contentsMargins = 0.m
        widgetSpacing = 0
    }
    private val scrollArea = QScrollArea().apply {
        objectName = "modConfigScrollArea"
        widgetResizable = true
        frameShape = QFrame.Shape.NoFrame
        horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        setWidget(container)
    }

    init {
        AnimatedScrollController.attach(scrollArea)
        scrollArea.viewport()?.objectName = "modConfigScrollViewport"
        scrollArea.setThemedStyle {
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()

            selector("#modConfigScrollArea") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(TColors.Surface1)
                }
                border()
            }
            selector("#modConfigScrollViewport") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(TColors.Surface1)
                }
                border()
            }
            selector("#modConfigPaneContainer") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(TColors.Surface1)
                }
                border()
            }
        }
    }

    override fun widget(): QWidget = scrollArea
    override fun onOpen() {
        val bgImage = CoreSettingValues.uiBackgroundImage
        container.autoFillBackground = bgImage.isNullOrBlank()
        rebuild()
    }

    override suspend fun save(): Boolean = try {
        paneFile.writeBytes(serialize().toByteArray())
        rebuild()
        modified = false
        true
    } catch (_: Throwable) {
        false
    }

    fun rebuild() {
        while(layout.count() > 0) {
            layout.takeAt(0)?.widget()?.disposeLater()
        }

        val compactTopLevel = hasTopLevelOnlySettings()
        layout.setSpacing(if (compactTopLevel) 2 else 0)

        when(root) {
            is ConfigObj -> {
                val pendingComments = mutableListOf<ConfigComment>()
                for((key, child) in (root as ConfigObj).entries) {
                    when {
                        key.startsWith("__comment_") -> pendingComments.add(child as ConfigComment)
                        child is ConfigObj -> {
                            pendingComments.clear()
                            layout.addWidget(buildWidget(child, key, listOf(key), FieldMeta(), compactTopLevel))
                        }
                        else -> {
                            val meta = parseMetaFromComments(pendingComments)
                            pendingComments.clear()
                            layout.addWidget(buildWidget(child, key, listOf(key), meta, compactTopLevel))
                        }
                    }
                }
            }
            else -> layout.addWidget(buildWidget(root, null, emptyList(), FieldMeta(), compactTopLevel))
        }
        layout.addStretch(1)
    }

    fun serialize(): String = format.serialize(root)

    fun onNodeChanged(path: List<String>, newNode: ConfigNode) {
        mutateTree(root, path, newNode)
        modified = true
    }

    private fun mutateTree(node: ConfigNode, path: List<String>, newNode: ConfigNode) {
        if(path.isEmpty()) return
        val parent = node as? ConfigObj ?: return
        if(path.size == 1) {
            parent.entries[path.first()] = newNode
        } else {
            mutateTree(parent.entries[path.first()]!!, path.drop(1), newNode)
        }
    }

    fun buildWidget(node: ConfigNode, key: String?, path: List<String>, meta: FieldMeta, compact: Boolean = false): QWidget = when (node) {
        is ConfigObj -> buildSection(node, key, path)
        is ConfigArray -> buildArray(node, key, path, compact)
        is ConfigString -> buildTextField(node, key, path, meta, compact)
        is ConfigInt -> buildIntSpinner(node, key, path, meta, compact)
        is ConfigDouble -> buildDoubleSpinner(node, key, path, meta, compact)
        is ConfigBool -> buildCheckbox(node, key, path, meta, compact)
        is ConfigComment -> qWidget()
        is ConfigNull -> buildNullBadge(key, path, compact)
    }

    fun parseMetaFromComments(comments: List<ConfigComment>): FieldMeta {
        val descLines = mutableListOf<String>()
        var default: String? = null
        var min: Double?     = null
        var max: Double?     = null

        val defaultRegex = Regex("""default\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
        val rangeRegex = Regex("""range\s*:\s*\[([\d.\-]+)\s*~\s*([\d.\-]+)]""", RegexOption.IGNORE_CASE)

        for (comment in comments) {
            val text = comment.text.trim()
            when {
                defaultRegex.containsMatchIn(text) -> {
                    default = defaultRegex.find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.trim('`')
                        ?.trim()

                    rangeRegex.find(text)?.let {
                        min = it.groupValues[1].toDoubleOrNull()
                        max = it.groupValues[2].toDoubleOrNull()
                    }
                }
                rangeRegex.containsMatchIn(text) -> {
                    rangeRegex.find(text)?.let {
                        min = it.groupValues[1].toDoubleOrNull()
                        max = it.groupValues[2].toDoubleOrNull()
                    }
                }
                text.isNotBlank() -> descLines.add(text)
            }
        }

        return FieldMeta(descLines.joinToString(" "), default, min, max)
    }

    private fun parseDefaultNode(template: ConfigNode, meta: FieldMeta): ConfigNode? {
        val raw = meta.default?.trim()?.trim('`')?.trim() ?: return null
        return when (template) {
            is ConfigString -> ConfigString(raw.trim('"'))
            is ConfigInt -> raw.toIntOrNull()?.let(::ConfigInt)
            is ConfigDouble -> raw.toDoubleOrNull()?.let(::ConfigDouble)
            is ConfigBool -> when (raw.lowercase()) {
                "true" -> ConfigBool(true)
                "false" -> ConfigBool(false)
                else -> null
            }
            is ConfigNull -> when {
                raw.equals("null", ignoreCase = true) -> ConfigNull()
                raw.equals("true", ignoreCase = true) -> ConfigBool(true)
                raw.equals("false", ignoreCase = true) -> ConfigBool(false)
                raw.toIntOrNull() != null -> ConfigInt(raw.toInt())
                raw.toDoubleOrNull() != null -> ConfigDouble(raw.toDouble())
                else -> ConfigString(raw.trim('"'))
            }
            else -> null
        }
    }

    fun buildSection(
        node: ConfigObj,
        label: String?,
        path: List<String>
    ): QWidget {
        val section = qWidget()
        val outerLayout = vBoxLayout(section) {
            contentsMargins = 0.m
            widgetSpacing = 0
        }
        val innerLayoutHost = qWidget()
        val innerLayout = vBoxLayout(innerLayoutHost) {
            setContentsMargins(18, 0, 0, 8)
            widgetSpacing = 0
        }

        if (label != null) {
            var expanded = true

            val header = qWidget {
                objectName = "modConfigSectionHeader"
                minimumHeight = 32
                setCursor(Qt.CursorShape.PointingHandCursor)
            }
            val hl = hBoxLayout(header) {
                setContentsMargins(12, 8, 12, 6)
                widgetSpacing = 10
            }

            val titleLbl = QLabel(displayLabel(label).uppercase()).apply {
                objectName = "modConfigSectionTitle"
            }
            val ruleLine = QFrame().apply {
                objectName = "modConfigSectionRule"
                frameShape = QFrame.Shape.HLine
                frameShadow = QFrame.Shadow.Plain
                lineWidth = 1
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
                maximumHeight = 1
            }
            val arrowLbl = QLabel("▾").apply {
                objectName = "modConfigSectionArrow"
            }

            hl.addWidget(titleLbl, 0)
            hl.addWidget(ruleLine, 1)
            hl.addWidget(arrowLbl, 0)

            header.setThemedStyle {
                val isBgImageSet = !CoreSettingValues.uiBackgroundImage.isNullOrBlank()
                selector("QWidget#modConfigSectionHeader") {
                    backgroundColor(if (isBgImageSet) "transparent" else TColors.Surface1)
                }
                selector("QLabel#modConfigSectionTitle") {
                    color(TColors.Subtext)
                    fontSize(11)
                    padding(0, 0, 0, 2)
                }
                selector("QFrame#modConfigSectionRule") {
                    backgroundColor(TColors.Surface0)
                    border()
                    maxHeight(1)
                }
                selector("QLabel#modConfigSectionArrow") {
                    color(TColors.Subtext)
                    fontSize(11)
                }
            }

            val clickFilter = object : QObject(header) {
                override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                    if (watched === header && event?.type() == QEvent.Type.MouseButtonRelease) {
                        expanded = !expanded
                        innerLayoutHost.isVisible = expanded
                        arrowLbl.text = if (expanded) "▾" else "▸"
                    }
                    return super.eventFilter(watched, event)
                }
            }
            header.installEventFilter(clickFilter)

            outerLayout.addWidget(header)
        }

        val pendingComments = mutableListOf<ConfigComment>()

        for((key, value) in node.entries) {
            when {
                key.startsWith("__comment_") -> {
                    pendingComments.add(value as ConfigComment)
                }
                value is ConfigObj -> {
                    pendingComments.clear()
                    innerLayout.addWidget(buildWidget(value, key, path + key, FieldMeta()))
                }
                else -> {
                    val meta = parseMetaFromComments(pendingComments)
                    pendingComments.clear()
                    innerLayout.addWidget(buildWidget(value, key, path + key, meta))
                }
            }
        }

        outerLayout.addWidget(innerLayoutHost)
        return section
    }

    fun buildArray(
        node: ConfigArray,
        label: String?,
        path: List<String>,
        compact: Boolean
    ): QWidget {
        val container = qWidget()
        val outerLayout = vBoxLayout(container) {
            widgetSpacing = if (compact) 6 else 8
        }
        applyItemMargins(outerLayout, compact)

        val headerLayout = hBoxLayout {
            contentsMargins = 0.m
            widgetSpacing = 8
        }

        val title = label(displayLabel(label ?: "List")) {
            wordWrap = true
            font = QFont(font).apply {
                setBold(true)
            }
        }
        headerLayout.addWidget(title, 0)

        val addBtn = QToolButton().apply {
            text = "+"
            objectName = "modConfigInlineButton"
            setFixedSize(28, 28)
        }

        headerLayout.addWidget(addBtn)
        headerLayout.addStretch()
        outerLayout.addLayout(headerLayout)

        val itemsContainer = qWidget()
        val itemsLayout = vBoxLayout(itemsContainer) {
            setContentsMargins(16, 2, 0, 0)
            widgetSpacing = 8
        }
        outerLayout.addWidget(itemsContainer)

        fun rebuildItems() {
            while(itemsLayout.count() > 0) {
                itemsLayout.takeAt(0)?.widget()?.disposeLater()
            }
            node.items.forEachIndexed { i, child ->
                val row = qWidget()
                val rowLayout = hBoxLayout(row) {
                    contentsMargins = 0.m
                    widgetSpacing = 8
                }

                val childWidget = buildWidget(child, i.toString(), path + i.toString(), FieldMeta())
                rowLayout.addWidget(childWidget, 0)

                val removeBtn = QToolButton().apply {
                    text = "-"
                    objectName = "modConfigInlineButton"
                    setFixedSize(28, 28)
                    clicked.connect {
                        node.items.removeAt(i)
                        rebuildItems()
                        onNodeChanged(path, node)
                    }
                }
                rowLayout.addWidget(removeBtn, 0, Qt.AlignmentFlag.AlignTop)
                rowLayout.addStretch()
                itemsLayout.addWidget(row)
            }
        }

        rebuildItems()

        addBtn.clicked.connect {
            val template = node.items.firstOrNull() ?: ConfigString("")
            node.items.add(cloneEmpty(template))
            rebuildItems()
            onNodeChanged(path, node)
        }

        return container
    }

    fun cloneEmpty(template: ConfigNode): ConfigNode = when(template) {
        is ConfigString -> ConfigString("")
        is ConfigInt    -> ConfigInt(0)
        is ConfigDouble -> ConfigDouble(0.0)
        is ConfigBool -> ConfigBool(false)
        is ConfigObj -> ConfigObj()
        is ConfigArray -> ConfigArray()
        else            -> ConfigString("")
    }

    fun buildLabeledRow(
        key: String?,
        meta: FieldMeta,
        currentNode: ConfigNode,
        widget: QWidget,
        trailing: QWidget? = null,
        compact: Boolean = false
    ): Pair<QWidget, (ConfigNode) -> Unit> {
        val container = qWidget()
        val outerLayout = vBoxLayout(container) {
            widgetSpacing = if (compact) 6 else 8
        }
        applyItemMargins(outerLayout, compact)

        if (key != null) {
            val lbl = label(displayLabel(key)) {
                wordWrap = true
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
                font = QFont(font).apply {
                    setBold(true)
                }
            }
            outerLayout.addWidget(lbl)
        }

        if (meta.description.isNotBlank()) {
            val desc = label(meta.description) {
                wordWrap = true
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            }
            outerLayout.addWidget(desc)
        }

        val row = qWidget()
        val rowLayout = hBoxLayout(row) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }
        rowLayout.addWidget(widget, 0)
        trailing?.let { rowLayout.addWidget(it, 0) }
        rowLayout.addStretch()
        outerLayout.addWidget(row)

        return wrapWithModifiedIndicator(container, currentNode, meta)
    }

    private fun buildResetButton(control: QWidget, node: ConfigNode, meta: FieldMeta, onReset: (ConfigNode) -> Unit): QToolButton? {
        val defaultNode = parseDefaultNode(node, meta) ?: return null
        val size = control.sizeHint().height().coerceAtLeast(control.minimumSizeHint().height()).coerceAtLeast(22)
        return QToolButton().apply {
            icon = style()?.standardIcon(QStyle.StandardPixmap.SP_BrowserReload) ?: QIcon(TIcons.QuestionMark)
            toolTip = "Reset to default (${meta.default})"
            setFixedSize(size, size)
            clicked.connect { onReset(defaultNode) }
        }
    }


    fun buildTextField(node: ConfigString, key: String?, path: List<String>, meta: FieldMeta, compact: Boolean): QWidget {
        val field = QLineEdit(node.value).apply {
            minimumHeight = 30
            minimumWidth = 280
            maximumWidth = 420
        }
        if (meta.default != null) field.toolTip = "Default: ${meta.default}"
        field.textChanged.connect { new ->
            onNodeChanged(path, ConfigString(new))
        }
        val reset = buildResetButton(field, node, meta) { defaultNode ->
            val value = (defaultNode as? ConfigString)?.value ?: return@buildResetButton
            if (field.text != value) field.text = value
            onNodeChanged(path, ConfigString(value))
        }
        return buildLabeledRow(key, meta, node, field, reset, compact).first
    }

    fun buildIntSpinner(node: ConfigInt, key: String?, path: List<String>, meta: FieldMeta, compact: Boolean): QWidget {
        val spinner = QSpinBox().apply {
            minimumHeight = 30
            minimumWidth = 160
            maximumWidth = 220
        }
        spinner.setRange(
            meta.min?.toInt() ?: Int.MIN_VALUE,
            meta.max?.toInt() ?: Int.MAX_VALUE
        )
        spinner.value = node.value
        if (meta.default != null) spinner.toolTip = "Default: ${meta.default}"
        spinner.valueChanged.connect { newVal ->
            onNodeChanged(path, ConfigInt(newVal.toInt()))
        }
        val reset = buildResetButton(spinner, node, meta) { defaultNode ->
            val value = (defaultNode as? ConfigInt)?.value ?: return@buildResetButton
            if (spinner.value != value) spinner.value = value
            onNodeChanged(path, ConfigInt(value))
        }
        return buildLabeledRow(key, meta, node, spinner, reset, compact).first
    }

    fun buildDoubleSpinner(node: ConfigDouble, key: String?, path: List<String>, meta: FieldMeta, compact: Boolean): QWidget {
        val spinner = object : QDoubleSpinBox() {
            override fun textFromValue(value: Double): String {
                val normalized = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
                return if (normalized.contains('.')) normalized else "$normalized.0"
            }
        }.apply {
            minimumHeight = 30
            minimumWidth = 180
            maximumWidth = 240
        }
        spinner.decimals = 12
        spinner.stepType = QAbstractSpinBox.StepType.AdaptiveDecimalStepType
        spinner.setRange(
            meta.min ?: -Double.MAX_VALUE,
            meta.max ?: Double.MAX_VALUE
        )
        spinner.value = node.value
        if (meta.default != null) spinner.toolTip = "Default: ${meta.default}"
        spinner.valueChanged.connect { newVal ->
            onNodeChanged(path, ConfigDouble(newVal))
        }
        val reset = buildResetButton(spinner, node, meta) { defaultNode ->
            val value = (defaultNode as? ConfigDouble)?.value ?: return@buildResetButton
            if (spinner.value != value) spinner.value = value
            onNodeChanged(path, ConfigDouble(value))
        }
        return buildLabeledRow(key, meta, node, spinner, reset, compact).first
    }

    fun buildCheckbox(node: ConfigBool, key: String?, path: List<String>, meta: FieldMeta, compact: Boolean): QWidget {
        val checkbox = QCheckBox()
        checkbox.isChecked = node.value
        if (meta.default != null) checkbox.toolTip = "Default: ${meta.default}"
        checkbox.stateChanged.connect { state ->
            onNodeChanged(path, ConfigBool(state == Qt.CheckState.Checked.value()))
        }
        val checkboxRow = qWidget()
        val checkboxLayout = hBoxLayout(checkboxRow) {
            contentsMargins = 0.m
            widgetSpacing = 10
        }
        checkboxLayout.addWidget(checkbox, 0)
        if (meta.description.isNotBlank()) {
            checkboxLayout.addWidget(label(meta.description) {
                wordWrap = true
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            }, 0)
        }
        val reset = buildResetButton(checkbox, node, meta) { defaultNode ->
            val value = (defaultNode as? ConfigBool)?.value ?: return@buildResetButton
            if (checkbox.isChecked != value) checkbox.isChecked = value
            onNodeChanged(path, ConfigBool(value))
        }
        reset?.let { checkboxLayout.addWidget(it, 0) }
        checkboxLayout.addStretch()
        return buildSettingCard(key, meta, node, checkboxRow, inlineDescription = true, compact = compact).first
    }

    fun buildNullBadge(key: String?, path: List<String>, compact: Boolean): QWidget {
        val badge = label("null")

        val setButton = QPushButton("Set value").apply {
            minimumHeight = 28
        }
        setButton.clicked.connect {
            onNodeChanged(path, ConfigString(""))
        }
        val reset = buildResetButton(setButton, ConfigNull(), FieldMeta(default = null)) { _ -> }
        return buildLabeledRow(key, FieldMeta(), ConfigNull(), badge, reset ?: setButton, compact).first
    }

    private fun buildSettingCard(
        key: String?,
        meta: FieldMeta,
        currentNode: ConfigNode,
        controlRow: QWidget,
        inlineDescription: Boolean = false,
        compact: Boolean = false
    ): Pair<QWidget, (ConfigNode) -> Unit> {
        val container = qWidget()
        val outerLayout = vBoxLayout(container) {
            widgetSpacing = if (compact) 6 else 8
        }
        applyItemMargins(outerLayout, compact)

        if (key != null) {
            outerLayout.addWidget(label(displayLabel(key)) {
                wordWrap = true
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
                font = QFont(font).apply {
                    setBold(true)
                }
            })
        }

        if (!inlineDescription && meta.description.isNotBlank()) {
            outerLayout.addWidget(label(meta.description) {
                wordWrap = true
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            })
        }

        outerLayout.addWidget(controlRow)
        return wrapWithModifiedIndicator(container, currentNode, meta)
    }

    private fun displayLabel(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun hasTopLevelOnlySettings(): Boolean {
        val obj = root as? ConfigObj ?: return true
        return obj.entries.none { (key, value) -> !key.startsWith("__comment_") && value is ConfigObj }
    }

    private fun wrapWithModifiedIndicator(content: QWidget, currentNode: ConfigNode, meta: FieldMeta): Pair<QWidget, (ConfigNode) -> Unit> {
        val wrapper = qWidget()
        val wrapperLayout = hBoxLayout(wrapper) {
            contentsMargins = 0.m
            widgetSpacing = 0
        }

        val indicator = frame {
            frameShape = QFrame.Shape.VLine
            frameShadow = QFrame.Shadow.Plain
            lineWidth = 2
            midLineWidth = 0
            setFixedWidth(2)
            isVisible = isNonDefault(currentNode, meta)
        }

        val gutter = qWidget()
        vBoxLayout(gutter) {
            contentsMargins = 16.m
            widgetSpacing = 0
            addWidget(indicator, 1)
        }

        wrapperLayout.addWidget(gutter, 0)
        wrapperLayout.addWidget(content, 1)
        return wrapper to { updatedNode ->
            indicator.isVisible = isNonDefault(updatedNode, meta)
        }
    }

    private fun isNonDefault(node: ConfigNode, meta: FieldMeta): Boolean {
        val defaultNode = parseDefaultNode(node, meta) ?: return false
        return !configNodesEqual(node, defaultNode)
    }

    private fun configNodesEqual(left: ConfigNode, right: ConfigNode): Boolean = when (left) {
        is ConfigString if right is ConfigString -> left.value == right.value
        is ConfigInt if right is ConfigInt -> left.value == right.value
        is ConfigDouble if right is ConfigDouble -> left.value == right.value
        is ConfigBool if right is ConfigBool -> left.value == right.value
        is ConfigNull if right is ConfigNull -> true
        is ConfigArray if right is ConfigArray ->
            left.items.size == right.items.size && left.items.zip(right.items).all { (a, b) -> configNodesEqual(a, b) }

        is ConfigObj if right is ConfigObj -> {
            val leftEntries = left.entries.filterKeys { !it.startsWith("__comment_") }
            val rightEntries = right.entries.filterKeys { !it.startsWith("__comment_") }
            leftEntries.size == rightEntries.size &&
                    leftEntries.all { (key, value) -> rightEntries[key]?.let { configNodesEqual(value, it) } == true }
        }

        else -> false
    }

    object Provider : EditorPaneProvider {
        override val id: String = "mod_config"
        override val displayName: String = "Mod Config"
        override val order: Int = 1

        override fun canOpen(
            file: VPath,
            project: ProjectBase
        ): Boolean {
            val primary = FileTypeDescriptor.primary(file, project)
            if (primary?.id != BuiltinFileTypes.ModConfig.id) return false
            return resolveFormat(file) != null
        }

        override fun create(
            project: ProjectBase,
            file: VPath
        ): EditorPane {
            val format = resolveFormat(file)
                ?: error("Unsupported config format: ${file.extension().lowercase()}")

            val text = file.readTextOr("")
            return ModConfigPane(project, file, format.parse(text), format)
        }

        private fun resolveFormat(file: VPath): ConfigFormat? {
            val text = file.readTextOr("")
            val ext = file.extension().lowercase()
            return when {
                ext == "cfg" && looksLikeForgeCfg(text) -> ConfigFormat.of("forge_cfg")
                else -> ConfigFormat.of(ext)
            }
        }

        private fun looksLikeForgeCfg(text: String): Boolean {
            return text.lines().any { line ->
                val t = line.trim()
                t.length > 2 && t[1] == ':' && t[2] == '"' && t[0] in "SIBDCM"
            }
        }
    }
}
