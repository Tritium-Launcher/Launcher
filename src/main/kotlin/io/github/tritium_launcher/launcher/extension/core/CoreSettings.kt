package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.font.FontMngr
import io.github.tritium_launcher.launcher.keymap.*
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.settings.RefreshableSettingWidget
import io.github.tritium_launcher.launcher.settings.SettingWidgetContext
import io.github.tritium_launcher.launcher.settings.settingsDefinition
import io.github.tritium_launcher.launcher.ui.widgets.InfoLineEditWidget
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.pushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.Qt
import io.qt.gui.QKeyEvent
import io.qt.gui.QMouseEvent
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val WINDOW_SIZE_REGEX = Regex("^([1-9][0-9]{0,4})x([1-9][0-9]{0,4})$")
private val WINDOW_DIMENSION_REGEX = Regex("^[1-9][0-9]{0,4}$")
private val FONT_VALUE_REGEX = Regex("^(.*)\\|([1-9][0-9]{0,2})$")

private data class WindowSizeParts(
    val width: String,
    val height: String
)

private fun parseWindowSizeParts(raw: String): WindowSizeParts? {
    val match = WINDOW_SIZE_REGEX.matchEntire(raw.trim()) ?: return null
    return WindowSizeParts(
        width = match.groupValues[1],
        height = match.groupValues[2]
    )
}

private fun encodeWindowSize(widthRaw: String, heightRaw: String): String? {
    val width = widthRaw.trim()
    val height = heightRaw.trim()
    if (!WINDOW_DIMENSION_REGEX.matches(width) || !WINDOW_DIMENSION_REGEX.matches(height)) {
        return null
    }
    return "${width}x${height}"
}

private class WindowSizeWidget(
    private val ctx: SettingWidgetContext<String>,
    placeholder: String?
) : QWidget(), RefreshableSettingWidget {
    private val widthInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
        minimumWidth = 72
    }
    private val separator = label("X") {
        setAlignment(Qt.AlignmentFlag.AlignCenter)
        minimumWidth = 12
    }
    private val heightInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
        minimumWidth = 72
    }

    private var isRefreshing = false

    init {
        val layout = hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }
        layout.addWidget(widthInput, 1)
        layout.addWidget(separator, 0)
        layout.addWidget(heightInput, 1)

        val hint = parseWindowSizeParts(placeholder.orEmpty())
        widthInput.placeholderText = hint?.width ?: "Width"
        heightInput.placeholderText = hint?.height ?: "Height"

        widthInput.editingFinished.connect { commit() }
        heightInput.editingFinished.connect { commit() }

        refreshFromSettingValue()
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            val current = ctx.currentValue().trim()
            val currentParts = parseWindowSizeParts(current)
            when {
                currentParts != null -> {
                    widthInput.text = currentParts.width
                    heightInput.text = currentParts.height
                    setInvalid(false)
                }
                current.isNotEmpty() -> {
                    val split = current.split('x', 'X', limit = 2)
                    widthInput.text = split.getOrElse(0) { "" }
                    heightInput.text = split.getOrElse(1) { "" }
                    setInvalid(true)
                }
                else -> {
                    val defaultRaw = ctx.descriptor.defaultValue
                    val defaultParts = parseWindowSizeParts(defaultRaw)
                    widthInput.text = defaultParts?.width.orEmpty()
                    heightInput.text = defaultParts?.height.orEmpty()
                    setInvalid(false)
                }
            }
        } finally {
            isRefreshing = false
        }
    }

    private fun commit() {
        if (isRefreshing) return
        val encoded = encodeWindowSize(widthInput.text, heightInput.text)
        if (encoded == null) {
            setInvalid(true)
            return
        }
        setInvalid(false)
        ctx.updateValue(encoded)
    }

    private fun setInvalid(invalid: Boolean) {
        applyInvalid(widthInput, invalid)
        applyInvalid(heightInput, invalid)
    }

    private fun applyInvalid(input: InfoLineEditWidget, invalid: Boolean) {
        input.setProperty("invalid", invalid)
        input.style()?.let { style ->
            style.unpolish(input)
            style.polish(input)
        }
        input.update()
    }
}

private data class ChoiceSettingOption(
    val value: String,
    val label: String
)

private class ChoiceSettingWidget(
    private val ctx: SettingWidgetContext<String>,
    private val options: List<ChoiceSettingOption>
) : QWidget(), RefreshableSettingWidget {
    private val combo = TComboBox()
    private var isRefreshing = false

    init {
        val layout = hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 0
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }
        combo.sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
        combo.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        options.forEach { option ->
            combo.addItem(option.label, option.value)
        }
        applyContentWidth()
        layout.addWidget(combo, 1)

        combo.currentIndexChanged.connect {
            if (isRefreshing) return@connect
            val selected = combo.currentData(Qt.ItemDataRole.UserRole)?.toString()?.trim().orEmpty()
            if (selected.isNotBlank()) {
                ctx.updateValue(selected)
            }
        }

        refreshFromSettingValue()
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            val current = ctx.currentValue().trim()
            val fallback = ctx.descriptor.defaultValue.trim()
            val target = when {
                hasOptionValue(current) -> current
                hasOptionValue(fallback) -> fallback
                else -> options.firstOrNull()?.value.orEmpty()
            }
            if (target.isBlank()) return
            val idx = indexOfValue(target)
            if (idx >= 0) combo.currentIndex = idx
        } finally {
            isRefreshing = false
        }
    }

    private fun hasOptionValue(value: String): Boolean = options.any { it.value.equals(value, ignoreCase = true) }

    private fun indexOfValue(target: String): Int {
        for (i in 0 until combo.count) {
            val value = combo.itemData(i, Qt.ItemDataRole.UserRole)?.toString()?.trim().orEmpty()
            if (value.equals(target, ignoreCase = true)) return i
        }
        return -1
    }

    private fun applyContentWidth() {
        val metrics = combo.fontMetrics()
        val longestLabelWidth = options.maxOfOrNull { option ->
            metrics.horizontalAdvance(option.label)
        } ?: 0
        val extraChrome = 56
        val width = (longestLabelWidth + extraChrome).coerceAtLeast(140)
        combo.minimumWidth = width
        combo.maximumWidth = width
        combo.adjustSize()
    }
}

private class FileSettingWidget(
    private val ctx: SettingWidgetContext<String>,
    private val dialogTitle: String = "Select File",
    private val filter: String = "All Files (*)"
) : QWidget(), RefreshableSettingWidget {
    private val pathInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
    }
    private val browseBtn = TPushButton {
        text = "..."
        minimumWidth = 30
        maximumWidth = 36
        minimumHeight = 25
        textVerticalOffset = -4
    }
    private var isRefreshing = false

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
            addWidget(pathInput, 1)
            addWidget(browseBtn, 0)
        }

        pathInput.editingFinished.connect { commitInput() }
        browseBtn.onClicked {
            val res = QFileDialog.getOpenFileName(this, dialogTitle, pathInput.text, filter)
            if (res != null && res.result.isNotBlank()) {
                pathInput.text = res.result
                commitInput()
            }
        }

        refreshFromSettingValue()
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            pathInput.text = ctx.currentValue().trim()
        } finally {
            isRefreshing = false
        }
    }

    private fun commitInput() {
        if (isRefreshing) return
            ctx.updateValue(pathInput.text.trim())
    }
}

private class FontSettingWidget(
    private val ctx: SettingWidgetContext<String>,
    private val defaultFamily: String
) : QWidget(), RefreshableSettingWidget {
    private val fontCombo = TComboBox()
    private val sizeSpinner = QSpinBox().apply {
        minimum = 8
        maximum = 32
    }
    private var isRefreshing = false

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
            addWidget(fontCombo, 1)
            addWidget(label("Size:"))
            addWidget(sizeSpinner)
        }

        loadFonts()

        fontCombo.currentTextChanged.connect { commit() }
        sizeSpinner.valueChanged.connect { commit() }

        refreshFromSettingValue()
    }

    private fun loadFonts() {
        val current = fontCombo.currentText
        fontCombo.clear()
        FontMngr.availableFontFamilies().forEach { fontCombo.addItem(it) }
        if (current.isNotBlank()) {
            val idx = (0 until fontCombo.count).indexOfFirst { fontCombo.itemText(it) == current }
            if (idx >= 0) fontCombo.currentIndex = idx
        }
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            val raw = ctx.currentValue().trim()
            val match = FONT_VALUE_REGEX.matchEntire(raw)
            if (match != null) {
                val family = match.groupValues[1]
                val size = match.groupValues[2].toInt()
                if (family.isNotBlank()) {
                    val idx = (0 until fontCombo.count).indexOfFirst { fontCombo.itemText(it) == family }
                    if (idx >= 0) fontCombo.currentIndex = idx
                    else fontCombo.currentText = family
                } else {
                    val idx = (0 until fontCombo.count).indexOfFirst { fontCombo.itemText(it) == defaultFamily }
                    if (idx >= 0) fontCombo.currentIndex = idx
                }
                sizeSpinner.value = size.coerceIn(sizeSpinner.minimum, sizeSpinner.maximum)
            } else {
                val idx = (0 until fontCombo.count).indexOfFirst { fontCombo.itemText(it) == defaultFamily }
                if (idx >= 0) fontCombo.currentIndex = idx
                sizeSpinner.value = 10
            }
        } finally {
            isRefreshing = false
        }
    }

    private fun commit() {
        if (isRefreshing) return
        val family = fontCombo.currentText.takeIf { it.isNotBlank() } ?: return
        ctx.updateValue("$family|${sizeSpinner.value}")
    }
}

private class KeymapActionsWidget(
    private val ctx: SettingWidgetContext<String>
) : QWidget(), RefreshableSettingWidget {
    private val actionRole = Qt.ItemDataRole.UserRole

    private val tree = QTreeWidget().apply {
        columnCount = 2
        setHeaderLabels(listOf("Action", "Shortcuts"))
        rootIsDecorated = true
        alternatingRowColors = true
        sortingEnabled = true
        header()?.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        header()?.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        contextMenuPolicy = Qt.ContextMenuPolicy.CustomContextMenu
    }

    init {
        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 0
            addWidget(tree, 1)
        }
        tree.customContextMenuRequested.connect { pt ->
            showContextMenu(pt.x(), pt.y())
        }
        CoroutineScope(Dispatchers.Main).onEvent<TritiumEvent.SettingChanged> { event ->
            val key = "${event.namespace}:${event.nodeKey}"
            if (key == CoreSettingKeys.KeymapActionsOverview.toString()) {
                val raw = (event.newValue as? String)?.trim().orEmpty()
                val overrides = if (raw.isBlank()) {
                    emptyMap()
                } else {
                    runCatching {
                        Json.decodeFromString(
                            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                            raw
                        )
                    }.getOrDefault(emptyMap())
                }
                KeymapMngr.applyOverridesFromStrings(overrides)
                refreshFromSettingValue()
            }
        }
        refreshFromSettingValue()
    }

    override fun refreshFromSettingValue() {
        tree.clear()
        val ids = (KeymapMngr.declaredActionIds() + ActionRegistry.actionIds()).toSortedSet()
        if (ids.isEmpty()) return
        val draftOverrides = decodeDraftOverrides()

        val groups = linkedMapOf<String, MutableList<String>>()
        ids.forEach { id ->
            val category = id.substringBefore('.', missingDelimiterValue = "other")
            groups.getOrPut(category) { mutableListOf() }.add(id)
        }

        groups.forEach { (category, actionIds) ->
            val groupItem = QTreeWidgetItem(tree).apply {
                setText(0, category.replaceFirstChar { it.uppercase() })
            }

            actionIds.sorted().forEach { actionId ->
                val effectiveBindings = draftOverrides[actionId]
                    ?.mapNotNull { KeymapMngr.parseBindingString(it) }
                    ?: KeymapMngr.bindingsFor(actionId)
                val bindingText = effectiveBindings
                    .joinToString(", ") { it.displayString() }
                    .ifBlank { "-" }
                QTreeWidgetItem(groupItem).apply {
                    setText(0, ActionRegistry.actionLabel(actionId))
                    setToolTip(0, actionId)
                    setText(1, bindingText)
                    setData(0, actionRole, actionId)
                }
            }
        }
        tree.expandAll()
        tree.sortItems(0, Qt.SortOrder.AscendingOrder)
    }

    private fun showContextMenu(x: Int, y: Int) {
        val item = tree.itemAt(x, y) ?: return
        val actionId = item.data(0, actionRole)?.toString()?.trim().orEmpty()
        if (actionId.isBlank()) return

        val menu = QMenu(this)
        val addKeyboard = menu.addAction("Add Keyboard Shortcut...")
        val addMouse = menu.addAction("Add Mouse Shortcut...")
        menu.addSeparator()

        val existing = KeymapMngr.bindingsFor(actionId)
        existing.forEach { binding ->
            val remove = menu.addAction("Remove ${binding.displayString()}")
            remove?.triggered?.connect {
                val updated = existing.filter { it != binding }
                stageActionBindings(actionId, updated)
            }
        }

        addKeyboard?.triggered?.connect {
            addShortcut(actionId, ShortcutKind.Keyboard)
        }
        addMouse?.triggered?.connect {
            addShortcut(actionId, ShortcutKind.Mouse)
        }
        val globalPoint = tree.viewport()?.mapToGlobal(io.qt.core.QPoint(x, y)) ?: return
        menu.exec(globalPoint)
    }

    private fun addShortcut(actionId: String, kind: ShortcutKind) {
        if (!ActionRegistry.allows(actionId, kind)) {
            QMessageBox.warning(this, "Shortcut Not Allowed", "This action does not allow ${kind.name.lowercase()} shortcuts.")
            return
        }

        val parsed = captureShortcut(kind) ?: return

        val existing = currentBindingsForAction(actionId)
        if (parsed in existing) return

        val updated = existing + parsed
        val conflicts = KeymapMngr.findConflicts(actionId, updated)
        if (conflicts.isNotEmpty()) {
            val lines = conflicts.keys.sorted().joinToString("\n")
            QMessageBox.warning(
                this,
                "Shortcut Conflict",
                "Shortcut conflicts with:\n$lines"
            )
            return
        }
        stageActionBindings(actionId, updated)
    }

    private fun captureShortcut(kind: ShortcutKind): KeyBinding? {
        val dialog = QDialog(this).apply {
            windowTitle = when (kind) {
                ShortcutKind.Keyboard -> "Capture Keyboard Shortcut"
                ShortcutKind.Mouse -> "Capture Mouse Shortcut"
            }
            modal = true
            minimumWidth = 420
        }

        var captured: KeyBinding? = null
        val instruction = label(
            when (kind) {
                ShortcutKind.Keyboard -> "Press the keyboard shortcut now. Press Esc to cancel."
                ShortcutKind.Mouse -> "Click the mouse shortcut now. Press Esc to cancel."
            },
            dialog
        )
        val cancel = pushButton("Cancel", dialog) {
            clicked.connect { dialog.reject() }
        }
        vBoxLayout(dialog) {
            addWidget(instruction)
            addWidget(cancel)
        }

        val filter = object : QObject(dialog) {
            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                event ?: return false
                if (event.type() == QEvent.Type.KeyPress) {
                    val keyEvent = event as? QKeyEvent ?: return false
                    if (keyEvent.key() == Qt.Key.Key_Escape.value()) {
                        dialog.reject()
                        return true
                    }
                    if (kind == ShortcutKind.Keyboard) {
                        if (keyEvent.key() in setOf(
                                Qt.Key.Key_Control.value(),
                                Qt.Key.Key_Shift.value(),
                                Qt.Key.Key_Alt.value(),
                                Qt.Key.Key_Meta.value()
                            )
                        ) return true
                        captured = KeyBinding.Single(
                            Keystroke(
                                key = keyEvent.key(),
                                modifiers = keyEvent.modifiers().value()
                            )
                        )
                        dialog.accept()
                        return true
                    }
                }
                if (event.type() == QEvent.Type.MouseButtonPress && kind == ShortcutKind.Mouse) {
                    val mouseEvent = event as? QMouseEvent ?: return false
                    captured = KeyBinding.Mouse(
                        MouseStroke(
                            button = mouseEvent.button().value(),
                            modifiers = mouseEvent.modifiers().value()
                        )
                    )
                    dialog.accept()
                    return true
                }
                return false
            }
        }

        dialog.installEventFilter(filter)
        QApplication.instance()?.installEventFilter(filter)
        try {
            dialog.exec()
        } finally {
            QApplication.instance()?.removeEventFilter(filter)
            dialog.removeEventFilter(filter)
        }
        return captured
    }

    private fun stageActionBindings(actionId: String, updated: List<KeyBinding>) {
        val draft = decodeDraftOverrides().toMutableMap()
        draft[actionId] = updated.map { it.displayString() }
        ctx.updateValue(encodeDraftOverrides(draft))
        refreshFromSettingValue()
    }

    private fun currentBindingsForAction(actionId: String): List<KeyBinding> {
        val draft = decodeDraftOverrides()
        return draft[actionId]
            ?.mapNotNull { KeymapMngr.parseBindingString(it) }
            ?: KeymapMngr.bindingsFor(actionId)
    }

    private fun decodeDraftOverrides(): Map<String, List<String>> {
        val raw = ctx.currentValue().trim()
        if (raw.isBlank()) return KeymapMngr.activeLocalOverridesAsStrings()
        return runCatching {
            Json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                raw
            )
        }.getOrElse { KeymapMngr.activeLocalOverridesAsStrings() }
    }

    private fun encodeDraftOverrides(overrides: Map<String, List<String>>): String {
        return Json.encodeToString(
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
            overrides
        )
    }
}

/**
 * Core settings schema declarations.
 *
 * Define settings once here, register from extension bootstrap with a namespace.
 */
internal object CoreSettings {
    val registration = settingsDefinition {

        val versionControl = category("version_control") {
            title = "Version Control"
            allowForeignSettings = true
            allowForeignSubcategories = true
        }

        val ui = category("ui") {
            title = "Appearance & UI"
            allowForeignSettings = true
        }

        val keymap = category("keymap") {
            title = "Keymap"
            description = "View all declared actions and their active keyboard/mouse shortcuts."
            allowForeignSettings = true
        }

        widget(keymap.path, "keymap.actions_overview") {
            title = "Declared Actions"
            description = "Shows action ids grouped by category and their current shortcuts."
            defaultValue = ""
            serializer = null
            fullWidth = true
            fullHeight = true
            widgetFactory = { ctx ->
                KeymapActionsWidget(ctx)
            }
        }

        toggle(ui.path, "ui.tooltip_style") {
            title = "Use Game Tooltip Style (Experimental)"
            description = "Applies a Tooltip style similar to Minecraft (Requires Restart)."
            defaultValue = false
        }

        toggle(ui.path, "ui.animate_scrolling") {
            title = "Animate Scrolling (Experimental)"
            description = "Smoothly animate wheel scrolling across scrollable views."
            defaultValue = false
        }

        toggle(ui.path, "ui.seasonal_events") {
            title = "Seasonal Events"
            description = "Show seasonal visual effects and features."
            defaultValue = true
        }

        widget(ui.path, "ui.global_font") {
            title = "Global Font"
            description = "Font family and size used across the UI."
            defaultValue = "|10"
            serializer = String.serializer()
            comments = listOf(
                "The Themes panel applies font changes live.",
                "The font must be installed on your system or bundled with Tritium."
            )
            widgetFactory = { ctx -> FontSettingWidget(ctx, FontMngr.defaultFontFamily) }
        }

        widget(ui.path, "ui.editor_font") {
            title = "Editor Font"
            description = "Font family and size used in the code editor."
            defaultValue = "|10"
            serializer = String.serializer()
            comments = listOf(
                "The Themes panel applies font changes live.",
                "The font must be installed on your system or bundled with Tritium."
            )
            widgetFactory = { ctx -> FontSettingWidget(ctx, FontMngr.monoFontFamily) }
        }

        widget(ui.path, "ui.background_image") {
            title = "Background Image"
            description = "Applies a custom background image globally to the main windows."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Absolute path to an image file (PNG, JPG, etc.)."
            )
            widgetFactory = { ctx ->
                FileSettingWidget(
                    ctx,
                    dialogTitle = "Choose Background Image",
                    filter = "Images (*.png *.jpg *.jpeg *.bmp *.webp);;All Files (*)"
                )
            }
        }

        val projects = category("projects") {
            title = "Projects"
            allowForeignSettings = true
        }

        val projectFiles = category("project_files") {
            title = "Project Files"
            parent = projects
            allowForeignSettings = true
        }

        val minecraft = category("minecraft") {
            title = "Minecraft"
            parent = projects
            allowForeignSettings = true
        }

        toggle(minecraft.path, "game.smart_rerun") {
            title = "Smart Rerun"
            description = "When enabled, clicking the Play button while the game is running sends a server reload request to the Companion mod instead of restarting the game. Hold Shift while clicking to force a full restart. Falls back to normal restart if Companion mod is unavailable."
            defaultValue = true
        }

        val companionBridge = category("companion_bridge") {
            title = "Companion Bridge"
            parent = projects
            allowForeignSettings = true
        }

        val javaRuntime = category("java_runtime") {
            title = "Java Runtime"
            parent = projects
            allowForeignSettings = true
        }

        val editor = category("editor") {
            title = "Editor"
            parent = projects
            allowForeignSettings = true
        }

        val autoSave = toggle(editor.path, "editor.auto_save") {
            title = "Auto Save"
            description = "Automatically save modified files after an interval."
            defaultValue = false
        }

        val autoSaveInterval = text(editor.path, "editor.auto_save_interval") {
            title = "Auto Save Interval (seconds)"
            description = "Interval in seconds to wait before auto-saving modified files."
            defaultValue = "60"
            disallow("^0$")
        }
        autoSave.addChild(autoSaveInterval) { it }

        val indicatorIntensity = widget(editor.path, "editor.unsaved_indicator_intensity") {
            title = "Unsaved Indicator Intensity"
            description = "How intense the unsaved changes indicator should be."
            defaultValue = "low"
            serializer = String.serializer()
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("low", "Low"),
                        ChoiceSettingOption("high", "High")
                    )
                )
            }
        }
        autoSave.addChild(indicatorIntensity) { !it }

        toggle(editor.path, "editor.rainbow_brackets") {
            title = "Rainbow Brackets (Experimental)"
            description = "Color code brackets, parentheses, and curly braces based on nesting depth."
            defaultValue = false
        }

        toggle(projects.path, "projects.close_dashboard_on_open") {
            title = "Close Dashboard When Opening Project"
            description = "Automatically close the dashboard after opening a project window."
            defaultValue = true
            comments = listOf(
                "When true, opening a project window closes the dashboard window."
            )
        }

        widget(projects.path, "app.close_game_on_exit") {
            title = "Close Game On Tritium Exit"
            description = "Controls whether running game processes are closed when Tritium exits."
            defaultValue = "never"
            serializer = String.serializer()
            comments = listOf(
                "Allowed values: never, ask, always."
            )
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("never", "Never"),
                        ChoiceSettingOption("ask", "Ask"),
                        ChoiceSettingOption("always", "Always")
                    )
                )
            }
        }

        val projectOpenPrompt = widget(projects.path, "projects.open_window_prompt") {
            title = "Ask Where To Open Project"
            description = "Prompt to open projects in the current window or a new window when another project window already exists."
            defaultValue = "always"
            serializer = String.serializer()
            comments = listOf(
                "Allowed values: always, never."
            )
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("always", "Always"),
                        ChoiceSettingOption("never", "Never")
                    )
                )
            }
        }

        val projectOpenDefault = widget(projects.path, "projects.open_window_default") {
            title = "Default Project Window Target"
            defaultValue = "current"
            serializer = String.serializer()
            comments = listOf(
                "Allowed values: current, new."
            )
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("current", "Current Window"),
                        ChoiceSettingOption("new", "New Window")
                    )
                )
            }
        }
        projectOpenPrompt.addChild(projectOpenDefault) { mode -> mode.equals("never", ignoreCase = true) }

        widget(projects.path, "projects.close_confirmation") {
            title = "Confirm Before Closing Project"
            description = "Prompts before closing a project window."
            defaultValue = "never"
            serializer = String.serializer()
            comments = listOf(
                "Allowed values: never, ask."
            )
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("never", "Never"),
                        ChoiceSettingOption("ask", "Ask")
                    )
                )
            }
        }

        widget(projectFiles.path, "projects.files.config_sort") {
            title = "Config Directory Sort Mode"
            description = "Controls how files are sorted inside the project's /config directory."
            defaultValue = "alphabetical"
            serializer = String.serializer()
            comments = listOf(
                "Allowed values: alphabetical, file_type."
            )
            widgetFactory = { ctx ->
                ChoiceSettingWidget(
                    ctx,
                    options = listOf(
                        ChoiceSettingOption("alphabetical", "Alphabetical"),
                        ChoiceSettingOption("file_type", "File Type")
                    )
                )
            }
        }

        text(minecraft.path, "source.mc_args") {
            title = "Modpack JVM Args"
            description = "Additional JVM arguments to append for source launches."
            defaultValue = ""
            placeholder = "-Dexample=true"
            comments = listOf(
                "Space-separated extra JVM arguments for source launch."
            )
        }

        text(minecraft.path, "source.mc_memory_mb") {
            title = "Modpack Memory (MB)"
            description = "Default memory allocation in MB for source launches."
            defaultValue = "6144"
            placeholder = "6144"
            comments = listOf(
                "Memory allocation for source launches in megabytes."
            )
        }

        toggle(minecraft.path, "minecraft.include_prerelease_versions") {
            title = "Include Pre-release MC Versions"
            description = "Include snapshot, pre-release, and release-candidate Minecraft versions in selectors."
            defaultValue = false
            comments = listOf(
                "When enabled, Minecraft version lists include pre-release versions."
            )
        }

        toggle(minecraft.path, "mods.cache_enabled") {
            title = "Mod Cache"
            description = "Cache downloaded mod jars in a shared directory (~/.tritium/mod-cache/) for reuse across projects."
            defaultValue = false
        }

        widget(ui.path, "ui.dashboard.window_size") {
            title = "Dashboard Window Size"
            description = "Fixed dashboard window size represented as WIDTH x HEIGHT."
            defaultValue = "650x400"
            serializer = String.serializer()
            comments = listOf(
                "Dashboard size in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "650x400") }
        }

        widget(projects.path, "ui.project_window.default_size") {
            title = "Project Window Default Size"
            description = "Default project window size used when saved values are broken."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default project window size in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }

        val gameMaximized = toggle(projects.path, "game.maximized") {
            title = "Game Launch Maximized"
            description = "Launches the game window maximized."
            defaultValue = false
        }

        val gameResolution = widget(projects.path, "game.default_resolution") {
            title = "Game Launch Resolution"
            description = "Game resolution represented as WIDTH x HEIGHT when launched."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default Minecraft launch resolution in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }
        gameMaximized.addChild(gameResolution) { maximized -> !maximized }

        text(companionBridge.path, "companion.ws.host") {
            title = "Companion Websocket Host"
            description = "Host Tritium connects to for Companion websocket commands."
            defaultValue = "127.0.0.1"
            placeholder = "127.0.0.1"
            comments = listOf(
                "Use 127.0.0.1 for local game sessions."
            )
        }

        text(companionBridge.path, "companion.ws.port") {
            title = "Companion Websocket Port"
            description = "Port used for websocket commands between Tritium and the Companion mod."
            defaultValue = "38765"
            placeholder = "38765"
            comments = listOf(
                "Must match the port used by the Companion mod."
            )
        }

        widget(javaRuntime.path, "java.path.8") {
            title = "Java 8 Path"
            description = "Java runtime for Minecraft 1.16.5 and below."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.16.5 and below."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 8) }
        }

        widget(javaRuntime.path, "java.path.17") {
            title = "Java 17 Path"
            description = "Java runtime for Minecraft 1.17 to 1.20."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.17 through 1.20."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 17) }
        }

        widget(javaRuntime.path, "java.path.21") {
            title = "Java 21 Path"
            description = "Java runtime for Minecraft 1.21 to 1.21.11."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.21 through 1.21.11."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 21) }
        }

        widget(javaRuntime.path, "java.path.25") {
            title = "Java 25 Path"
            description = "Java runtime for Minecraft 26.1."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 26.*."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 25) }
        }

        text(versionControl.path, "git.path") {
            title = "Git Executable Path"
            description = "Optional absolute path to the git executable. Leave blank to use auto-detection."
            defaultValue = ""
            placeholder = "Auto-detected from PATH"
            comments = listOf(
                "Optional absolute path to the git executable.",
                "Leave blank to allow Tritium to auto-detect git from PATH."
            )
        }

        category("extensions") {
            title = "Extensions"
            description = "Extension-provided settings."
            allowForeignSubcategories = true
            allowForeignSettings = false
        }
    }
}
