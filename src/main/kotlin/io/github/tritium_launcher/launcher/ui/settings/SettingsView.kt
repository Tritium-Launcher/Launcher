package io.github.tritium_launcher.launcher.ui.settings

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.settings.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.InfoLineEditWidget
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.TToggleSwitch
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.widgets.*

/**
 * Reusable Settings UI for browsing categories and editing setting values.
 */
class SettingsView : QWidget() {
    private val logger = logger()

    private val categoryTree = QTreeWidget().apply {
        headerHidden = true
        selectionMode = QAbstractItemView.SelectionMode.SingleSelection
        objectName = "settingsTree"
    }
    private val searchInput = QLineEdit().apply {
        objectName = "settingsSearchInput"
        placeholderText = "Search settings..."
        clearButtonEnabled = true
    }

    private val headerTitle = label {
        objectName = "settingsHeaderTitle"
    }
    private val headerDesc = label {
        objectName = "settingsHeaderDesc"
        wordWrap = true
    }

    private val settingsHost = QWidget()
    private val settingsLayout = vBoxLayout(settingsHost) {
        contentsMargins = 0.m
        widgetSpacing = 8
    }
    private val settingsScroll = QScrollArea().apply {
        objectName = "settingsScroll"
        setWidget(settingsHost)
        widgetResizable = true
        frameShape = QFrame.Shape.NoFrame
    }
    private val cancelBtn = TPushButton {
        text = "Cancel"
        minimumHeight = 30
    }
    private val applyBtn = TPushButton {
        text = "Apply"
        minimumHeight = 30
        isEnabled = false
    }

    private var currentCategory: SettingsRegistry.CategoryNode? = null
    private val categoryItemByNode = LinkedHashMap<SettingsRegistry.CategoryNode, QTreeWidgetItem>()

    private val rowByNode = LinkedHashMap<SettingNode<*>, SettingRow>()
    private val pendingValues = LinkedHashMap<SettingNode<*>, Any?>()
    private var currentRootNodes: List<SettingNode<*>> = emptyList()
    private var activeSearchQuery: String = ""

    private var isRefreshing = false
    private var isSyncingSelection = false

    init {
        objectName = "settingsView"

        val mainLayout = hBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 0
        }

        val splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(buildNav())
        splitter.addWidget(buildContent())
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)

        mainLayout.addWidget(splitter, 1)

        connectSignals()
        reload()

        setThemedStyle {
            selector("#settingsView") { backgroundColor(TColors.Surface0) }
            selector("#settingsNav") { backgroundColor(TColors.Surface1) }
            selector("#settingsTree") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2)
            }
            selector("#settingsHeaderTitle") { fontSize(16); fontWeight(600) }
            selector("#settingsHeaderDesc") { fontSize(11); color(TColors.Subtext) }
            selector("#settingTitle") { fontSize(13); fontWeight(600) }
            selector("#settingDesc") { fontSize(11); color(TColors.Subtext) }
            selector("#settingComment") { fontSize(12); color(TColors.Subtext) }
            selector("#settingsEmpty") { fontSize(12); color(TColors.Subtext) }
            selector("QLineEdit#settingsSearchInput") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(4, 6, 4, 6)
            }
            selector("QLineEdit#settingsInput") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(4, 6, 4, 6)
            }
            selector("QLineEdit#settingsInput[invalid=\"true\"]") {
                border(1, TColors.Error)
            }
        }
    }

    /**
     * Rebuilds category navigation and selects the first available category.
     *
     * @see rebuildCategories
     * @see renderCategory
     */
    fun reload() {
        pendingValues.clear()
        updateActionButtons()
        activeSearchQuery = ""
        searchInput.clear()
        rebuildCategories()
        if (categoryTree.topLevelItemCount() > 0) {
            categoryTree.setCurrentItem(categoryTree.topLevelItem(0))
        } else {
            renderCategory(null)
        }
    }

    /**
     * Builds the left navigation panel containing search and category tree.
     *
     * @return Navigation widget.
     * @see buildContent
     */
    private fun buildNav(): QWidget {
        val nav = QWidget().apply { objectName = "settingsNav" }
        val layout = vBoxLayout(nav) {
            contentsMargins = 8.m
            widgetSpacing = 8
        }

        layout.addWidget(searchInput)
        layout.addWidget(categoryTree, 1)
        return nav
    }

    /**
     * Builds the right content panel containing headers and setting rows.
     *
     * @return Content widget.
     * @see buildNav
     */
    private fun buildContent(): QWidget {
        val content = QWidget()
        val layout = vBoxLayout(content) {
            contentsMargins = 12.m
            widgetSpacing = 10
        }

        layout.addWidget(headerTitle)
        layout.addWidget(headerDesc)
        layout.addWidget(settingsScroll, 1)
        layout.addWidget(buildActions())
        return content
    }

    /**
     * Builds the action row containing cancel/apply controls for staged changes.
     *
     * @return Actions widget.
     */
    private fun buildActions(): QWidget {
        val actions = QWidget()
        val layout = hBoxLayout(actions) {
            contentsMargins = 0.m
            widgetSpacing = 8
        }
        layout.addStretch(1)
        layout.addWidget(cancelBtn, 0)
        layout.addWidget(applyBtn, 0)
        return actions
    }

    /**
     * Connects category selection and action signals.
     */
    private fun connectSignals() {
        categoryTree.itemSelectionChanged.connect {
            if (isSyncingSelection) return@connect
            if (activeSearchQuery.isNotBlank()) return@connect
            val item = categoryTree.currentItem() ?: return@connect
            val node = item.data(0, UserRole) as? SettingsRegistry.CategoryNode ?: return@connect
            renderCategory(node)
        }

        searchInput.textChanged.connect {
            renderSearch(it ?: "")
        }

        cancelBtn.onClicked { cancelStagedChanges() }
        applyBtn.onClicked { applyStagedChanges() }
    }

    /**
     * Rebuilds category tree from the registry root categories.
     *
     * @see SettingsMngr.roots
     */
    private fun rebuildCategories() {
        categoryTree.clear()
        categoryItemByNode.clear()

        val roots = SettingsMngr.roots()
        roots.forEach { addCategoryNode(it, null) }
        categoryTree.expandAll()
    }

    /**
     * Adds [node] to the category tree.
     *
     * @param node Category node to add.
     * @param parent Optional parent tree item; `null` for root categories.
     * @see formatCategoryLabel
     */
    private fun addCategoryNode(node: SettingsRegistry.CategoryNode, parent: QTreeWidgetItem?) {
        val item = if (parent == null) QTreeWidgetItem(categoryTree) else QTreeWidgetItem(parent)
        item.setText(0, formatCategoryLabel(node))
        item.setData(0, UserRole, node)
        if (!node.descriptor.description.isNullOrBlank()) {
            item.setToolTip(0, node.descriptor.description)
        }
        categoryItemByNode[node] = item

        val children = SettingsMngr.childrenOf(node)
        children.forEach { addCategoryNode(it, item) }
    }

    /**
     * Formats the category label for navigation display.
     *
     * Non-core namespaces are prefixed so users can identify extension ownership.
     *
     * @param node Category node to format.
     * @return Display label.
     */
    private fun formatCategoryLabel(node: SettingsRegistry.CategoryNode): String {
        val base = node.descriptor.title
        return if (node.ownerNamespace == "tritium") base else "[${node.ownerNamespace}] $base"
    }

    /**
     * Synchronizes tree selection with [node].
     *
     * @param node Category node that should be selected in the tree.
     */
    private fun syncTreeToCategory(node: SettingsRegistry.CategoryNode) {
        val item = categoryItemByNode[node] ?: return
        isSyncingSelection = true
        try {
            categoryTree.setCurrentItem(item)
        } finally {
            isSyncingSelection = false
        }
    }

    /**
     * Applies search state from [rawQuery].
     *
     * @param rawQuery Search text from the input box.
     * @see SettingsMngr.search
     */
    private fun renderSearch(rawQuery: String) {
        val query = rawQuery.trim()
        activeSearchQuery = query
        if (query.isEmpty()) {
            val selected = categoryTree.currentItem()?.data(0, UserRole) as? SettingsRegistry.CategoryNode
            renderCategory(selected ?: currentCategory)
            return
        }

        val hits = SettingsMngr.search(query)
        val uniqueNodes = LinkedHashSet<SettingNode<*>>()
        hits.forEach { uniqueNodes += it.node }

        headerTitle.text = "Search"
        headerDesc.text = if (hits.isEmpty()) {
            "No settings match \"$query\"."
        } else {
            "${hits.size} result(s) for \"$query\"."
        }
        headerDesc.isVisible = true
        currentCategory = null

        if (uniqueNodes.isEmpty()) {
            rebuildSettings(emptyList(), "No settings match \"$query\".")
            return
        }
        rebuildSettings(uniqueNodes.toList())
    }

    /**
     * Opens and focuses [link] inside this settings view.
     *
     * @param link Target settings link.
     * @return `true` when the setting was found and focused.
     */
    fun openLink(link: SettingsLink): Boolean {
        val setting = SettingsMngr.findSetting(link.key) ?: return false
        val category = SettingsMngr.findCategory(setting.categoryPath) ?: return false

        if (activeSearchQuery.isNotBlank()) {
            searchInput.clear()
        }
        syncTreeToCategory(category)
        renderCategory(category)

        val row = rowByNode[setting] ?: return true
        settingsScroll.ensureWidgetVisible(row.container)
        row.container.setFocus()
        return true
    }

    /**
     * Renders header and settings rows for a category.
     *
     * @param node Category to render, or `null` to show an empty settings state.
     * @see rebuildSettings
     */
    private fun renderCategory(node: SettingsRegistry.CategoryNode?) {
        currentCategory = node
        if (node == null) {
            headerTitle.text = "Settings"
            headerDesc.text = ""
            headerDesc.isVisible = false
            rebuildSettings(emptyList())
            return
        }

        headerTitle.text = node.descriptor.title
        val desc = node.descriptor.description.orEmpty()
        headerDesc.text = desc
        headerDesc.isVisible = desc.isNotBlank()

        val settings = SettingsMngr.settingsOf(node)
        rebuildSettings(settings)
    }

    /**
     * Rebuilds row widgets for [settings].
     *
     * @param settings Settings to render for the current category.
     * @param emptyMessage Message shown when [settings] is empty.
     * @see buildSettingRecursive
     */
    private fun rebuildSettings(settings: List<SettingNode<*>>, emptyMessage: String = "No settings registered in this category.") {
        clearLayout(settingsLayout)
        rowByNode.clear()
        currentRootNodes = emptyList()

        if (settings.isEmpty()) {
            val emptyLabel = label(emptyMessage) {
                objectName = "settingsEmpty"
                wordWrap = true
            }
            settingsLayout.addWidget(emptyLabel)
            settingsLayout.addStretch(1)
            return
        }

        val childSet = settings.flatMap { it.children.map { link -> link.node } }.toSet()
        val roots = settings.filter { it !in childSet }
        currentRootNodes = roots

        val visited = HashSet<SettingNode<*>>()
        roots.forEach { buildSettingRecursive(it, 0, visited) }
        settingsLayout.addStretch(1)

        refreshAll()
    }

    /**
     * Recursively builds a setting row and all child rows while preventing cycles.
     *
     * @param node Setting node to render.
     * @param indent Depth-based indentation level.
     * @param visited Set used to prevent duplicate/cyclic traversal.
     */
    private fun buildSettingRecursive(node: SettingNode<*>, indent: Int, visited: MutableSet<SettingNode<*>>) {
        if (!visited.add(node)) return
        val row = buildSettingRow(node, indent)
        rowByNode[node] = row
        settingsLayout.addWidget(row.container)
        node.children.forEach { child ->
            buildSettingRecursive(child.node, indent + 1, visited)
        }
    }

    /**
     * Refreshes all rows from current model values and recomputes enable states.
     *
     * @see refreshEnableStates
     */
    private fun refreshAll() {
        isRefreshing = true
        try {
            rowByNode.values.forEach { it.refresh() }
            refreshEnableStates()
        } finally {
            isRefreshing = false
        }
    }

    /**
     * Re-applies hierarchical enable state rules from root settings downward.
     */
    private fun refreshEnableStates() {
        currentRootNodes.forEach { applyEnabled(it, true) }
    }

    /**
     * Recursively applies enabled/disabled state to [node] and descendants.
     *
     * @param node Setting node to update.
     * @param parentEnabled Whether the parent branch is currently enabled.
     */
    private fun applyEnabled(node: SettingNode<*>, parentEnabled: Boolean) {
        val row = rowByNode[node] ?: return
        row.setEnabled(parentEnabled)
        val value = effectiveAnyValue(node)
        node.children.forEach { child ->
            val enabled = parentEnabled && child.enableWhen(value)
            applyEnabled(child.node, enabled)
        }
    }

    /**
     * Returns the effective value for a type-erased [node], preferring staged edits.
     *
     * @param node Setting node to query.
     * @return Effective setting value as `Any?`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun effectiveAnyValue(node: SettingNode<*>): Any? {
        val typed = node as SettingNode<Any?>
        return effectiveValue(typed)
    }

    /**
     * Returns the effective value for [node], using staged edits when present.
     *
     * @param node Setting node to query.
     * @return Effective value.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> effectiveValue(node: SettingNode<T>): T {
        if (pendingValues.containsKey(node)) {
            return pendingValues[node] as T
        }
        return SettingsMngr.currentValue(node).value
    }

    /**
     * Stages a new candidate [value] for [node] after descriptor validation.
     *
     * @param node Setting node being edited.
     * @param value Candidate value.
     * @return Validation result from [SettingDescriptor.validate].
     */
    private fun <T> stageValue(node: SettingNode<T>, value: T): SettingValidation {
        val validation = node.descriptor.validate(value)
        if (validation is SettingValidation.Invalid) return validation

        val persisted = SettingsMngr.currentValue(node).value
        if (persisted == value) {
            pendingValues.remove(node)
        } else {
            pendingValues[node] = value
        }
        updateActionButtons()
        return SettingValidation.Valid
    }

    /**
     * Applies all staged values to [SettingsMngr].
     *
     * Values that fail validation are kept staged and logged.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyStagedChanges() {
        if (pendingValues.isEmpty()) return
        val staged = pendingValues.entries.toList()
        staged.forEach { (nodeAny, valueAny) ->
            val node = nodeAny as SettingNode<Any?>
            val validation = SettingsMngr.updateValue(node, valueAny)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Invalid staged value for {}: {}", node.key, validation.reason)
            } else {
                pendingValues.remove(nodeAny)
            }
        }
        updateActionButtons()
        refreshAll()
    }

    /**
     * Discards all staged values and refreshes controls from persisted settings.
     */
    private fun cancelStagedChanges() {
        if (pendingValues.isEmpty()) {
            closeHostingDialogIfPresent()
            return
        }
        pendingValues.clear()
        updateActionButtons()
        refreshAll()
    }

    /**
     * Updates enabled state of cancel/apply buttons according to staged changes.
     */
    private fun updateActionButtons() {
        val hasPending = pendingValues.isNotEmpty()
        applyBtn.isEnabled = hasPending
    }

    /**
     * Closes the nearest parent [QDialog] if this view is hosted inside one.
     */
    private fun closeHostingDialogIfPresent() {
        var current: QWidget? = this
        while (current != null) {
            if (current is QDialog) {
                current.close()
                return
            }
            current = current.parentWidget()
        }
    }

    /**
     * Refreshes a changed [node] and then recomputes child enable states.
     *
     * @param node Setting node that changed.
     * @see refreshEnableStates
     */
    private fun updateAfterChange(node: SettingNode<*>) {
        isRefreshing = true
        try {
            rowByNode[node]?.refresh?.invoke()
        } finally {
            isRefreshing = false
        }
        refreshEnableStates()
    }

    /**
     * Dispatches row construction based on descriptor type.
     *
     * @param node Setting node to render.
     * @param indent Depth-based indentation level.
     * @return Constructed [SettingRow].
     */
    private fun buildSettingRow(node: SettingNode<*>, indent: Int): SettingRow {
        return when (val descriptor = node.descriptor) {
            is CommentSettingDescriptor -> buildCommentRow(node, descriptor, indent)
            is ToggleSettingDescriptor -> buildToggleRow(node, descriptor, indent)
            is TextSettingDescriptor -> buildTextRow(node, descriptor, indent)
            is WidgetSettingDescriptor<*> -> buildWidgetRow(node, descriptor, indent)
        }
    }

    /**
     * Builds a comment-only row used for informational text blocks.
     *
     * @param node Setting node.
     * @param descriptor Comment descriptor metadata.
     * @param indent Depth-based indentation level.
     * @return Row model used for enabling semantics.
     */
    private fun buildCommentRow(
        node: SettingNode<*>,
        descriptor: CommentSettingDescriptor,
        indent: Int
    ): SettingRow {
        val container = QWidget()
        val layout = vBoxLayout(container) {
            setContentsMargins(indent * 16, 4, 4, 4)
            widgetSpacing = 4
        }
        val label = label(descriptor.title) {
            objectName = "settingComment"
            wordWrap = true
        }
        layout.addWidget(label)

        return SettingRow(
            node = node,
            container = container,
            refresh = {},
            setEnabled = { enabled -> container.isEnabled = enabled }
        )
    }

    /**
     * Builds a toggle setting row.
     *
     * @param node Setting node.
     * @param descriptor Toggle descriptor metadata.
     * @param indent Depth-based indentation level.
     * @return Row model used for refresh/enabling.
     */
    private fun buildToggleRow(
        node: SettingNode<*>,
        descriptor: ToggleSettingDescriptor,
        indent: Int
    ): SettingRow {
        @Suppress("UNCHECKED_CAST")
        val typedNode = node as SettingNode<Boolean>
        val container = QWidget()
        val layout = vBoxLayout(container) {
            setContentsMargins(indent * 16, 4, 4, 4)
            widgetSpacing = 4
        }

        val topRow = QWidget()
        val topLayout = hBoxLayout(topRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        val title = label(descriptor.title) {
            objectName = "settingTitle"
            toolTip = descriptor.description.orEmpty()
        }
        val toggle = TToggleSwitch()
        val resetBtn = TPushButton {
            text = "Reset"
            minimumHeight = 25
        }

        topLayout.addWidget(title, 1)
        topLayout.addWidget(toggle, 0)
        topLayout.addWidget(resetBtn, 0)
        layout.addWidget(topRow)

        val descLabel = descriptor.description?.takeIf { it.isNotBlank() }?.let {
            label(it) {
                objectName = "settingDesc"
                wordWrap = true
            }
        }
        if (descLabel != null) layout.addWidget(descLabel)

        toggle.toggled.connect { checked ->
            if (isRefreshing) return@connect
            val validation = stageValue(typedNode, checked)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Invalid value for {}: {}", typedNode.key, validation.reason)
                refreshAll()
                return@connect
            }
            updateAfterChange(typedNode)
        }

        resetBtn.onClicked {
            if (isRefreshing) return@onClicked
            val validation = stageValue(typedNode, descriptor.defaultValue)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Invalid value for {}: {}", typedNode.key, validation.reason)
                refreshAll()
            } else {
                updateAfterChange(typedNode)
            }
        }

        return SettingRow(
            node = node,
            container = container,
            refresh = {
                val current = effectiveValue(typedNode)
                toggle.setChecked(current)
                resetBtn.isEnabled = current != descriptor.defaultValue
            },
            setEnabled = { enabled -> container.isEnabled = enabled }
        )
    }

    /**
     * Builds a text setting row with inline validation visuals.
     *
     * @param node Setting node.
     * @param descriptor Text descriptor metadata.
     * @param indent Depth-based indentation level.
     * @return Row model used for refresh/enabling.
     */
    private fun buildTextRow(
        node: SettingNode<*>,
        descriptor: TextSettingDescriptor,
        indent: Int
    ): SettingRow {
        @Suppress("UNCHECKED_CAST")
        val typedNode = node as SettingNode<String>
        val container = QWidget()
        val layout = vBoxLayout(container) {
            setContentsMargins(indent * 16, 4, 4, 4)
            widgetSpacing = 4
        }

        val topRow = QWidget()
        val topLayout = hBoxLayout(topRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        val title = label(descriptor.title) {
            objectName = "settingTitle"
            toolTip = descriptor.description.orEmpty()
        }
        val input = InfoLineEditWidget(descriptor.description.orEmpty()).apply {
            objectName = "settingsInput"
            placeholderText = descriptor.placeholder.orEmpty()
        }
        val resetBtn = TPushButton {
            text = "Reset"
            minimumHeight = 25
        }

        topLayout.addWidget(title, 1)
        topLayout.addWidget(input, 2)
        topLayout.addWidget(resetBtn, 0)
        layout.addWidget(topRow)

        val descLabel = descriptor.description?.takeIf { it.isNotBlank() }?.let {
            label(it) {
                objectName = "settingDesc"
                wordWrap = true
            }
        }
        if (descLabel != null) layout.addWidget(descLabel)

        var lastValue = effectiveValue(typedNode)

        input.editingFinished.connect {
            if (isRefreshing) return@connect
            val newValue = input.text
            val validation = stageValue(typedNode, newValue)
            if (validation is SettingValidation.Invalid) {
                input.setProperty("invalid", true)
                input.toolTip = validation.reason
                input.style()?.let { style ->
                    style.unpolish(input)
                    style.polish(input)
                }
                input.update()
                input.text = lastValue
                return@connect
            }
            input.setProperty("invalid", false)
            input.toolTip = descriptor.description.orEmpty()
            input.style()?.let { style ->
                style.unpolish(input)
                style.polish(input)
            }
            input.update()
            lastValue = effectiveValue(typedNode)
            updateAfterChange(typedNode)
        }

        resetBtn.onClicked {
            if (isRefreshing) return@onClicked
            val validation = stageValue(typedNode, descriptor.defaultValue)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Invalid value for {}: {}", typedNode.key, validation.reason)
                refreshAll()
            } else {
                lastValue = descriptor.defaultValue
                updateAfterChange(typedNode)
            }
        }

        return SettingRow(
            node = node,
            container = container,
            refresh = {
                val current = effectiveValue(typedNode)
                input.text = current
                input.setProperty("invalid", false)
                input.style()?.let { style ->
                    style.unpolish(input)
                    style.polish(input)
                }
                input.update()
                resetBtn.isEnabled = current != descriptor.defaultValue
                lastValue = current
            },
            setEnabled = { enabled -> container.isEnabled = enabled }
        )
    }

    /**
     * Builds a custom widget-backed setting row.
     *
     * @param node Setting node.
     * @param descriptor Widget descriptor metadata.
     * @param indent Depth-based indentation level.
     * @return Row model used for refresh/enabling.
     */
    private fun buildWidgetRow(
        node: SettingNode<*>,
        descriptor: WidgetSettingDescriptor<*>,
        indent: Int
    ): SettingRow {
        @Suppress("UNCHECKED_CAST")
        val typedNode = node as SettingNode<Any?>
        val container = QWidget()
        val layout = vBoxLayout(container) {
            setContentsMargins(indent * 16, 4, 4, 4)
            widgetSpacing = 4
        }

        val topRow = QWidget()
        val topLayout = hBoxLayout(topRow) {
            contentsMargins = 0.m
            widgetSpacing = 8
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        val title = label(descriptor.title) {
            objectName = "settingTitle"
            toolTip = descriptor.description.orEmpty()
        }
        val resetBtn = TPushButton {
            text = "Reset"
            minimumHeight = 25
        }

        val widget = createWidget(descriptor, node)

        topLayout.addWidget(title, 1)
        topLayout.addStretch(1)
        topLayout.addWidget(widget, 0)
        topLayout.setAlignment(widget, Qt.AlignmentFlag.AlignRight)
        topLayout.addWidget(resetBtn, 0)
        layout.addWidget(topRow)

        val descLabel = descriptor.description?.takeIf { it.isNotBlank() }?.let {
            label(it) {
                objectName = "settingDesc"
                wordWrap = true
            }
        }
        if (descLabel != null) layout.addWidget(descLabel)

        resetBtn.onClicked {
            if (isRefreshing) return@onClicked
            val validation = stageValue(typedNode, descriptor.defaultValue)
            if (validation is SettingValidation.Invalid) {
                logger.warn("Invalid value for {}: {}", typedNode.key, validation.reason)
                refreshAll()
            } else {
                updateAfterChange(typedNode)
            }
        }

        return SettingRow(
            node = node,
            container = container,
            refresh = {
                val current = effectiveValue(typedNode)
                (widget as? RefreshableSettingWidget)?.refreshFromSettingValue()
                resetBtn.isEnabled = current != descriptor.defaultValue
            },
            setEnabled = { enabled -> container.isEnabled = enabled }
        )
    }

    /**
     * Creates the custom editor widget for [descriptor] and wires update callbacks.
     *
     * @param descriptor Widget descriptor containing the factory.
     * @param node Setting node being edited.
     * @return Instantiated editor widget.
     */
    @Suppress("UNCHECKED_CAST")
    private fun createWidget(
        descriptor: WidgetSettingDescriptor<*>,
        node: SettingNode<*>
    ): QWidget {
        val typedDescriptor = descriptor as WidgetSettingDescriptor<Any?>
        val typedNode = node as SettingNode<Any?>
        val ctx = SettingWidgetContext(
            id = typedNode.key,
            descriptor = typedDescriptor,
            currentValue = { effectiveValue(typedNode) },
            updateValue = { value ->
                val validation = stageValue(typedNode, value)
                if (validation is SettingValidation.Invalid) {
                    logger.warn("Invalid value for {}: {}", typedNode.key, validation.reason)
                } else {
                    updateAfterChange(typedNode)
                }
            }
        )
        return typedDescriptor.widgetFactory(ctx)
    }

    /**
     * Removes and disposes every item from [layout] recursively.
     *
     * @param layout Layout to clear.
     */
    private fun clearLayout(layout: QLayout) {
        while (layout.count() > 0) {
            val item = layout.takeAt(0) ?: continue
            val widget = item.widget()
            if (widget != null) {
                widget.hide()
                widget.setParent(null)
                widget.disposeLater()
            } else {
                item.layout()?.let { clearLayout(it) }
            }
        }
    }

    /**
     * Cached row handles used to refresh values and enabled states without rebuilding widgets.
     *
     * @property node Backing setting node.
     * @property container Root widget for this row.
     * @property refresh Callback that reads current value and updates UI controls.
     * @property setEnabled Callback used to apply effective enabled state.
     */
    private data class SettingRow(
        val node: SettingNode<*>,
        val container: QWidget,
        val refresh: () -> Unit,
        val setEnabled: (Boolean) -> Unit
    )
}
