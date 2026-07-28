/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.currentDpr
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockPanelTitleBarAccessoryProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.editor.intelligence.EditorIntelligenceProvider
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.state.FlushPolicy
import io.github.tritium_launcher.launcher.mainLogger
import io.github.tritium_launcher.launcher.registrydb.*
import io.github.tritium_launcher.launcher.ui.project.sidebar.AnimatedItemMngr.ItemTexture
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.SuggestionPopup
import io.github.tritium_launcher.launcher.ui.widgets.TTooltip
import io.github.tritium_launcher.launcher.ui.widgets.TTooltipStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*
import kotlin.math.ceil
import kotlin.math.max


/**
 * JEI-like registry browser panel with fixed-page item browsing and bottom search.
 */
class RegistryBrowserDockPanel : DockPanelProvider, DockPanelTitleBarAccessoryProvider {
    override val id: String = "registry_browser"
    override val displayName: String = "Item Browser"
    override var icon: QIcon? = TIcons.ItemBrowser.icon
    override val order: Int = 15
    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.BottomDockWidgetArea
    override val allowSplit: Boolean = false
    override val allowedDockAreas: Set<Qt.DockWidgetArea> = setOf(Qt.DockWidgetArea.BottomDockWidgetArea)

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null).apply {
            minimumWidth = 250
            maximumWidth = 450
        }
        val controller = Controller(project, dock, preferredArea)
        controllers[dock] = controller
        dock.setWidget(controller.root)
        controller.start()
        dock.destroyed.connect {
            controller.cleanup()
            controllers.remove(dock)
        }
        return dock
    }

    override fun createTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget? {
        val controller = controllers[dock] ?: return null
        return toolButton {
            icon = TIcons.Rerun.icon
            iconSize = QSize(16, 16)
            autoRaise = true
            toolTip = "Refresh Item Browser"
            clicked.connect {
                controller.refreshFromDatabase()
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
            }
        }
    }

    override fun createLeftTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget? {
        val controller = controllers[dock] ?: return null
        val container = QWidget()
        val layout = QHBoxLayout(container).apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(4)
        }
        val menuBtn = QToolButton()
        menuBtn.icon = TIcons.SmallMenu.icon
        menuBtn.iconSize = QSize(16, 16)
        menuBtn.autoRaise = true
        menuBtn.toolTip = "Options"
        val menu = QMenu(menuBtn)
        val flipAction = menu.addAction("Flip Split") ?: return null
        flipAction.isCheckable = true
        flipAction.checked = false
        flipAction.triggered.connect {
            controller.toggleSplit()
            onStateChanged()
        }
        menuBtn.setMenu(menu)
        menuBtn.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        layout.addWidget(menuBtn)
        return container
    }

    private class Controller(
        val project: ProjectBase,
        private val dock: DockWidget,
        private val preferredArea: Qt.DockWidgetArea
    ): DockPanelController() {
        val root = QWidget()

        private val logger = logger()
        private val outerLayout = QVBoxLayout(root)
        private val header = QWidget(root)
        private val headerLayout = QHBoxLayout(header)
        private val prevPageButton = QToolButton(header)
        private val pageLabel = QLabel(header)
        private val nextPageButton = QToolButton(header)
        private val typeCombo = QComboBox(header)

        private val mainContainer = QWidget(root)
        private val mainLayout = hBoxLayout(mainContainer) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)
        }
        private val leftContainer = QWidget(mainContainer)
        private val leftLayout = vBoxLayout(leftContainer) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)
        }
        private val viewport = GridViewport(this, leftContainer)
        private val viewportLayout = QVBoxLayout(viewport)
        private val statusLabel = QLabel(viewport)
        private val gridWidget = QWidget(viewport)
        private val gridLayout = gridLayout(gridWidget) {
            sizeConstraint = QLayout.SizeConstraint.SetNoConstraint
        }

        internal val detailPanel = DetailPanel(mainContainer, this)

        private val suggestionPopup = SuggestionPopup().apply { hide() }
        private val footer = QWidget(leftContainer)
        private val footerLayout = QHBoxLayout(footer)
        private val searchField = object : QTextEdit(footer) {
            override fun keyPressEvent(event: QKeyEvent?) {
                if (event != null && suggestionPopup.isVisible && suggestionPopup.handleKeyEvent(event)) return
                super.keyPressEvent(event)
            }
        }.apply {
            setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            setTabChangesFocus(false)
            setAcceptRichText(false)
            setFixedHeight(24)
        }
        private val inventoryToggleBtn = QToolButton(footer)
        private val searchDebounce = QTimer(root)
        private val resizeDebounce = QTimer(root)

        private var lastArea: Qt.DockWidgetArea? = null

        private var searchText: String = ""
        private var currentPage: Int = 0
        private var totalItems: Int = 0
        private var itemsPerPage: Int = 1
        private var columns: Int = 1
        private var lastColumns: Int = 0
        private var rows: Int = 1
        private var selectedItemId: String? = null
        private var visibleItems: List<RegistryItemSummary> = emptyList()
        private var visibleValues: List<RegistryValueSummary> = emptyList()
        private val slotButtons = mutableListOf<SlotButton>()
        private var lastSnapshotDir: String? = null
        private var snapshotDir: VPath? = null
        private var restoreAfterLoad: String? = null
        private var showInventoryOnly: Boolean = false
        private var inventoryItemIds: Set<String> = emptySet()
        private var browseableTypes: List<BrowseableValueType> = emptyList()
        private var selectedTypeIdx: Int = 0
        private var suppressTypeChange: Boolean = false
        private var pendingSearchTextRestore: String? = null
        private var splitFlipped: Boolean = false

        override val persistKey: String = "registry_browser"
        override val flushPolicy: FlushPolicy = FlushPolicy.Immediate

        override fun captureState() = buildJsonObject {
            put("lastSelectedId", selectedItemId)
            put("lastSearchText", searchText.takeIf { it.isNotBlank() })
            put("lastPage", currentPage)
            put("lastTypeIndex", selectedTypeIdx)
        }

        override fun restoreState(state: JsonObject) {
            restoreAfterLoad = state["lastSelectedId"]?.jsonPrimitive?.contentOrNull
            currentPage      = state["lastPage"]?.jsonPrimitive?.intOrNull ?: 0
            selectedTypeIdx  = state["lastTypeIndex"]?.jsonPrimitive?.intOrNull ?: 0
            state["lastSearchText"]?.jsonPrimitive?.contentOrNull?.let { text ->
                searchText = text
                pendingSearchTextRestore = text
            }
        }

        init {
            root.objectName = "registryBrowserPanel"
            header.objectName = "registryBrowserHeader"
            viewport.objectName = "registryBrowserViewport"
            statusLabel.objectName = "registryBrowserStatus"
            gridWidget.objectName = "registryBrowserGrid"
            footer.objectName = "registryBrowserFooter"
            searchField.objectName = "registryBrowserSearch"

            outerLayout.setContentsMargins(4, 4, 4, 4)
            outerLayout.setSpacing(4)

            headerLayout.setContentsMargins(2, 2, 2, 2)
            headerLayout.setSpacing(4)
            footerLayout.setContentsMargins(2, 2, 2, 2)
            footerLayout.setSpacing(4)

            prevPageButton.text = "<"
            prevPageButton.autoRaise = false
            prevPageButton.toolTip = "Previous Page"
            prevPageButton.minimumSize = QSize(24, 22)

            nextPageButton.text = ">"
            nextPageButton.autoRaise = false
            nextPageButton.toolTip = "Next Page"
            nextPageButton.minimumSize = QSize(24, 22)

            pageLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
            pageLabel.font = QFont(pageLabel.font).apply { setPointSize(10); setBold(true) }

            headerLayout.addWidget(prevPageButton)
            headerLayout.addWidget(pageLabel, 1)
            headerLayout.addWidget(nextPageButton)
            headerLayout.addSpacing(8)
            typeCombo.minimumWidth = 100
            headerLayout.addWidget(typeCombo)

            viewportLayout.setContentsMargins(0, 0, 0, 0)
            viewportLayout.setSpacing(0)

            statusLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
            statusLabel.wordWrap = true
            statusLabel.margin = 12

            gridLayout.setContentsMargins(2, 2, 2, 2)
            gridLayout.setHorizontalSpacing(2)
            gridLayout.setVerticalSpacing(2)
            gridLayout.setAlignment(Qt.Alignment(Qt.AlignmentFlag.AlignTop, Qt.AlignmentFlag.AlignLeft))

            viewportLayout.addWidget(statusLabel, 1)
            viewportLayout.addWidget(gridWidget, 1)

            leftLayout.addWidget(header)
            leftLayout.addWidget(viewport, 1)
            leftLayout.addWidget(footer)

            mainLayout.addWidget(leftContainer, 1)
            mainLayout.addWidget(detailPanel, 1)

            SearchHighlighter(searchField.document()!!)

            suggestionPopup.onSelected = { value ->
                val cursor = searchField.textCursor()
                val pos = cursor.position()
                val text = searchField.toPlainText()
                val prefix = findPrefix(text, pos)
                if (prefix != null) {
                    val prefixChar = text[prefix.start]
                    val newText = text.substring(0, prefix.start) + prefixChar + value + " " + text.substring(pos)
                    searchField.setPlainText(newText)
                    val newCursor = searchField.textCursor()
                    newCursor.setPosition(prefix.start + value.length + 2)
                    searchField.setTextCursor(newCursor)
                }
            }

            inventoryToggleBtn.apply {
                text = "Inv"
                isCheckable = true
                minimumSize = QSize(24, 22)
                toolTip = "Show only items in player inventory"
                clicked.connect {
                    showInventoryOnly = isChecked
                    if (showInventoryOnly) {
                        inventoryItemIds = loadInventoryIds()
                        if (inventoryItemIds.isEmpty()) {
                            isChecked = false
                            showInventoryOnly = false
                        }
                    }
                    currentPage = 0
                    refreshFromDatabase()
                }
                setThemedStyle {
                    selector("QToolButton") {
                        backgroundColor(TColors.Surface1)
                        color(TColors.Text)
                        border(1, TColors.Surface1)
                        borderRadius(3)
                    }
                    selector("QToolButton:hover") {
                        backgroundColor(TColors.Surface2)
                    }
                    selector("QToolButton:checked") {
                        backgroundColor(TColors.Accent)
                        color(TColors.Text)
                        border(1, TColors.Accent)
                    }
                }
            }

            footerLayout.addWidget(searchField, 1)
            footerLayout.addWidget(inventoryToggleBtn)

            outerLayout.addWidget(mainContainer, 1)

            dock.dockLocationChanged.connect { area ->
                updateLayoutForArea(area)
            }

            searchDebounce.isSingleShot = true
            searchDebounce.interval = 120

            resizeDebounce.isSingleShot = true
            resizeDebounce.interval = 50
            resizeDebounce.timeout.connect {
                if (updatePageGeometry()) {
                    currentPage = 0
                    refreshFromDatabase()
                }
            }

            root.setThemedStyle {
                selector("#registryBrowserPanel") {
                    backgroundColor(TColors.Surface0)
                }
                selector("#registryBrowserHeader") {
                    backgroundColor(TColors.Surface0)
                    any("border-bottom", "1px solid ${TColors.Surface1}")
                }
                selector("#registryBrowserFooter") {
                    backgroundColor(TColors.Surface0)
                    any("border-top", "1px solid ${TColors.Surface1}")
                }
                selector("#registryBrowserViewport") {
                    backgroundColor(TColors.Surface0)
                }
                selector("#registryBrowserStatus") {
                    color(TColors.Subtext)
                    fontSize(11)
                }
                selector("#registryBrowserSearch") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface1)
                    borderRadius(4)
                    padding(2, 6, 2, 6)
                }
                selector("#registryBrowserHeader QToolButton") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface1)
                    borderRadius(3)
                }
                selector("#registryBrowserHeader QToolButton:hover") {
                    backgroundColor(TColors.Surface2)
                }
                selector("#registryBrowserHeader QToolButton:disabled") {
                    color(TColors.Surface2)
                }
                selector("#registryBrowserHeader QComboBox") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface1)
                    borderRadius(3)
                    padding(2, 4)
                }
                selector("#registryBrowserHeader QComboBox:hover") {
                    border(1, TColors.Surface2)
                }
                selector("#registryBrowserHeader QComboBox::drop-down") {
                    border()
                }
                selector("#registryBrowserHeader QComboBox QAbstractItemView") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface2)
                }
                selector("QToolButton#registryBrowserSlot") {
                    background("transparent")
                    border()
                    padding(0)
                }
                selector("QToolButton#registryBrowserSlot:hover") {
                    background("transparent")
                }
                selector("QToolButton#registryBrowserSlot:checked") {
                    background("transparent")
                    border(1, TColors.Accent)
                }
            }
        }

        override fun start() {
            super.start()
            RegistryRefreshService.startWatching(project)
            prevPageButton.clicked.connect { changePage(currentPage - 1) }
            nextPageButton.clicked.connect { changePage(currentPage + 1) }
            searchField.textChanged.connect {
                logger.info("textChanged: text='{}' cursorPos={}", searchField.toPlainText(), searchField.textCursor().position())
                searchText = searchField.toPlainText().trim()
                currentPage = 0
                restoreAfterLoad = null
                searchDebounce.start()
                updateSuggestions()
            }
            searchField.cursorPositionChanged.connect {
                logger.info("cursorPositionChanged: pos={} text='{}'", searchField.textCursor().position(), searchField.toPlainText())
                updateSuggestions()
            }
            searchDebounce.timeout.connect { refreshFromDatabase() }

            typeCombo.currentIndexChanged.connect { idx ->
                if (suppressTypeChange) return@connect
                selectedTypeIdx = idx
                currentPage = 0
                selectedItemId = null
                restoreAfterLoad = null
                refreshFromDatabase()
            }

            scope.onEvent<TritiumEvent.RegistryFocusRequest> { event ->
                searchField.setPlainText(event.id)
                searchText = event.id
                currentPage = 0
                refreshFromDatabase()
                dock.show()
                dock.raise()
            }

            scope.launch {
                RegistryRefreshService.dbUpdated
                    .filter { it.projectDir.toString() == project.projectDir.toString() }
                    .collect {
                        RegistryDatabase.invalidateCachedConnection()
                        EditorIntelligenceProvider.instance?.invalidateConnection()
                        refreshFromDatabase()
                    }
            }

            scope.launch {
                val types = withContext(Dispatchers.IO) {
                    runCatching { RegistryDatabase.browseableValueTypes(project) }.getOrDefault(emptyList())
                }
                browseableTypes = types
                suppressTypeChange = true
                types.forEach { vt ->
                    typeCombo.addItem(vt.displayName ?: vt.id)
                }
                val desiredIdx = if (typeCombo.count() > 0) selectedTypeIdx.coerceIn(0, typeCombo.count() - 1) else 0
                typeCombo.currentIndex = desiredIdx
                selectedTypeIdx = desiredIdx
                suppressTypeChange = false

                pendingSearchTextRestore?.let { text ->
                    pendingSearchTextRestore = null
                    searchText = text
                    searchField.plainText = text
                }

                val initialArea = (dock.parent() as? QMainWindow)?.dockWidgetArea(dock) ?: preferredArea
                updateLayoutForArea(initialArea)
                updatePageGeometry()
                refreshFromDatabase()
            }
            AnimatedItemMngr.ensureStarted()
            val listener = { triggerAnimUpdate() }
            animTickListener = listener
            AnimatedItemMngr.registerTickListener(listener)
        }

        private fun updateLayoutForArea(area: Qt.DockWidgetArea) {
            if (area == lastArea) return
            lastArea = area

            if (area == Qt.DockWidgetArea.BottomDockWidgetArea) {
                dock.maximumWidth = 10000
                dock.minimumHeight = 150
                dock.maximumHeight = 800
            } else {
                dock.maximumWidth = 450
                dock.minimumHeight = 0
                dock.maximumHeight = 10000
            }
        }

        private var animTickListener: (() -> Unit)? = null

        override fun cleanup() {
            animTickListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
            animTickListener = null
            suggestionPopup.disposeLater()
            super.cleanup()
        }

        fun triggerAnimUpdate() {
            slotButtons.forEach { btn ->
                if (btn.isVisible && btn.isAnimated) btn.update()
            }
            detailPanel.triggerAnimUpdate()
        }

        fun toggleSplit() {
            splitFlipped = !splitFlipped
            mainLayout.removeWidget(leftContainer)
            mainLayout.removeWidget(detailPanel)
            if (splitFlipped) {
                mainLayout.addWidget(detailPanel, 1)
                mainLayout.addWidget(leftContainer, 1)
            } else {
                mainLayout.addWidget(leftContainer, 1)
                mainLayout.addWidget(detailPanel, 1)
            }
        }

        private fun loadInventoryIds(): Set<String> {
            val file = project.projectDir.resolve("registryObjs/player_inventory.json")
            if (!file.exists()) return emptySet()
            return runCatching {
                val text = file.readTextOrNull() ?: return emptySet()
                when (val element = recipeJson.parseToJsonElement(text)) {
                    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                    is JsonObject -> (element["items"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
                    else -> emptySet()
                }
            }.getOrDefault(emptySet())
        }

        private data class PrefixInfo(val start: Int, val text: String)

        private fun findPrefix(text: String, cursorPos: Int): PrefixInfo? {
            if (cursorPos <= 0) return null
            val before = text.substring(0, cursorPos.coerceAtMost(text.length))
            var i = before.length - 1
            while (i >= 0) {
                val c = before[i]
                if (c == '@' || c == '#' || c == '$') {
                    if (i == 0 || before[i - 1] == ' ') {
                        val partial = before.substring(i + 1)
                        return PrefixInfo(i, partial)
                    }
                }
                i--
            }
            return null
        }

        private fun updateSuggestions() {
            val pos = searchField.textCursor().position()
            val text = searchField.toPlainText()
            val prefix = findPrefix(text, pos)
            logger.info("updateSuggestions: text='{}' pos={} prefix={}", text, pos, prefix)
            if (prefix == null || prefix.text.contains(' ')) {
                suggestionPopup.hide()
                return
            }
            scope.launch {
                val suggestions = withContext(Dispatchers.IO) {
                    try {
                        val filter = prefix.text
                        val result = when (text[prefix.start]) {
                            '@' -> RegistryDatabase.suggestNamespaces(project, filter)
                            '$' -> RegistryDatabase.suggestTags(project, filter)
                            else -> emptyList()
                        }
                        logger.info("suggestNamespaces: filter='{}' result={}", filter, result)
                        result
                    } catch (_: Exception) {
                        logger.info("suggestNamespaces caught exception")
                        null
                    }
                }
                if (isActive && suggestions != null) {
                    logger.info("calling showSuggestions with {} items", suggestions.size)
                    suggestionPopup.showSuggestions(suggestions, searchField, offsetX = 0)
                }
            }
        }

        private fun changePage(page: Int) {
            if (totalPages() <= 1) return
            val next = ((page % totalPages()) + totalPages()) % totalPages()
            if (next == currentPage) return
            currentPage = next
            val offset = currentPage * itemsPerPage
            scope.launch {
                if (selectedTypeIdx == 0) {
                    val items = withContext(Dispatchers.IO) {
                        RegistryDatabase.searchItems(project, searchText, offset, itemsPerPage, inventoryItemIds.takeIf { showInventoryOnly })
                    }
                    visibleItems = items
                } else {
                    val typeId = browseableTypes.getOrNull(selectedTypeIdx)?.id ?: ""
                    val values = withContext(Dispatchers.IO) {
                        RegistryDatabase.searchCustomValues(project, typeId, searchText, offset, itemsPerPage)
                    }
                    visibleValues = values
                }
                updateGridSlots()
                updateHeader()
            }
        }

        private fun updateHeader() {
            pageLabel.text = if (totalItems <= 0) "0 / 0" else "${currentPage + 1} / ${totalPages()}"
        }

        fun refreshFromDatabase() {
            scope.launch {
                val status = withContext(Dispatchers.IO) { RegistryDatabase.status(project) }
                when (status) {
                    is RegistryDbStatus.Ready -> {
                        val newDir = status.manifestPath.parent().toAbsolute().toString()
                        if (lastSnapshotDir != null && lastSnapshotDir != newDir) {
                            pixmapCache.clear()
                        }
                        lastSnapshotDir = newDir
                        snapshotDir = status.manifestPath.parent()

                        if (selectedTypeIdx == 0) {
                            // Items
                            val invIds = inventoryItemIds.takeIf { showInventoryOnly }
                            val (count, items) = withContext(Dispatchers.IO) {
                                clampPage()
                                val offset = currentPage * itemsPerPage
                                val count = RegistryDatabase.countItems(project, searchText, invIds)
                                val items = RegistryDatabase.searchItems(project, searchText, offset, itemsPerPage, invIds)
                                count to items
                            }
                            totalItems = count
                            visibleItems = items
                            visibleValues = emptyList()
                        } else {
                            // Custom value type
                            val typeId = browseableTypes.getOrNull(selectedTypeIdx)?.id ?: ""
                            val (count, values) = withContext(Dispatchers.IO) {
                                clampPage()
                                val offset = currentPage * itemsPerPage
                                val count = RegistryDatabase.countCustomValues(project, typeId, searchText)
                                val items = RegistryDatabase.searchCustomValues(project, typeId, searchText, offset, itemsPerPage)
                                count to items
                            }
                            totalItems = count
                            visibleValues = values
                            visibleItems = emptyList()
                        }
                        rebuildSlots()
                        updateHeader()
                        updateSuggestions()

                        val restoreId = restoreAfterLoad
                        if (restoreId != null) {
                            restoreAfterLoad = null
                            if (visibleItems.any { it.id == restoreId } || visibleValues.any { it.id == restoreId }) {
                                selectItem(restoreId, addToHistory = false)
                            }
                        }
                    }

                    else -> {
                        snapshotDir = null
                        visibleItems = emptyList()
                        visibleValues = emptyList()
                        totalItems = 0
                        selectedItemId = null
                        showStatus(messageFor(status))
                        updateHeader()
                    }
                }
            }
        }

        fun onViewportResized() {
            resizeDebounce.start()
        }

        private fun updatePageGeometry(): Boolean {
            val slotSize = SLOT_SIZE
            val spacing = gridLayout.horizontalSpacing().coerceAtLeast(0)
            val availableWidth = max(viewport.width() - 4, slotSize)
            val availableHeight = max(1, viewport.height() - 4)

            val nextColumns = max(1, (availableWidth + spacing) / (slotSize + spacing))
            val nextRows = max(1, (availableHeight + spacing) / (slotSize + spacing))
            val nextItemsPerPage = max(1, nextColumns * nextRows)

            val changed = nextColumns != columns || nextRows != rows || nextItemsPerPage != itemsPerPage
            columns = nextColumns
            rows = nextRows
            itemsPerPage = nextItemsPerPage
            return changed
        }

        private fun clampPage() {
            val maxPage = max(0, totalPages() - 1)
            currentPage = currentPage.coerceIn(0, maxPage)
        }

        private fun totalPages(): Int =
            if (totalItems <= 0) 1 else ceil(totalItems / itemsPerPage.toDouble()).toInt()

        private fun currentSlots(): List<SlotItemInfo> =
            if (selectedTypeIdx == 0) {
                visibleItems.map { SlotItemInfo(it.id, it.displayName, it.path, it.texturePath, it.animationJson, it.rarity, it.tags, registryType = "item") }
            } else {
                visibleValues.map { SlotItemInfo(it.id, it.displayName, it.path, it.texturePath, null, null, emptyList(), it.tintColor, registryType = it.typeId) }
            }

        private val hasSlots: Boolean get() =
            if (selectedTypeIdx == 0) visibleItems.isNotEmpty() else visibleValues.isNotEmpty()

        private fun rebuildSlots() {
            val slots = currentSlots()
            if (!hasSlots) {
                clearGrid()
                showStatus(
                    if (searchText.isBlank()) {
                        if (selectedTypeIdx == 0) {
                            "No items found.\nLaunch the game with the Companion mod to generate the item registry."
                        } else {
                            "No entries found.\nLaunch the game with the Companion mod to generate the registry."
                        }
                    } else {
                        "No entries match \"$searchText\"."
                    }
                )
                return
            }

            val columnsChanged = columns != lastColumns
            if (columnsChanged) {
                detachGridButtons()
                lastColumns = columns
            }

            statusLabel.isVisible = false
            gridWidget.isVisible = true

            val newCount = slots.size
            val oldCount = slotButtons.size

            if (newCount > oldCount) {
                repeat(newCount - oldCount) { i ->
                    SlotButton(gridWidget, scope, this::selectItem).also { btn ->
                        btn.objectName = "registryBrowserSlot"
                        btn.minimumSize = QSize(SLOT_SIZE, SLOT_SIZE)
                        btn.maximumSize = QSize(SLOT_SIZE, SLOT_SIZE)
                        btn.isCheckable = true
                        slotButtons.add(btn)
                        val slotIndex = oldCount + i
                        gridLayout.addWidget(btn, slotIndex / columns, slotIndex % columns)
                    }
                }
            }

            slotButtons.forEachIndexed { index, button ->
                if (index < newCount) {
                    val slot = slots[index]
                    button.setItem(slot, selectedItemId == slot.id, snapshotDir)
                    button.isVisible = true
                    if (columnsChanged && index < oldCount) {
                        gridLayout.addWidget(button, index / columns, index % columns)
                    }
                } else {
                    button.clearIcon()
                    button.isVisible = false
                }
            }

            if (slotButtons.size > newCount + 8) {
                val toRemove = slotButtons.subList(newCount, slotButtons.size)
                toRemove.forEach {
                    gridLayout.removeWidget(it)
                    it.disposeLater()
                }
                toRemove.clear()
            }
        }

        private fun trimSlotPool(newCount: Int) {
            if (slotButtons.size > newCount + 8) {
                val toRemove = slotButtons.subList(newCount, slotButtons.size)
                toRemove.forEach {
                    gridLayout.removeWidget(it)
                    it.disposeLater()
                }
                toRemove.clear()
            }
        }

        private fun updateGridSlots() {
            val slots = currentSlots()
            if (!hasSlots) {
                clearGrid()
                showStatus(
                    if (searchText.isBlank()) {
                        if (selectedTypeIdx == 0) {
                            "No items found.\nLaunch the game with the Companion mod to generate the item registry."
                        } else {
                            "No entries found.\nLaunch the game with the Companion mod to generate the registry."
                        }
                    } else {
                        "No entries match \"$searchText\"."
                    }
                )
                return
            }

            val newCount = slots.size
            val oldCount = slotButtons.size

            if (newCount > oldCount) {
                repeat(newCount - oldCount) { i ->
                    SlotButton(gridWidget, scope, this::selectItem).also { btn ->
                        btn.objectName = "registryBrowserSlot"
                        btn.minimumSize = QSize(SLOT_SIZE, SLOT_SIZE)
                        btn.maximumSize = QSize(SLOT_SIZE, SLOT_SIZE)
                        btn.isCheckable = true
                        slotButtons.add(btn)
                        val slotIndex = oldCount + i
                        gridLayout.addWidget(btn, slotIndex / columns, slotIndex % columns)
                    }
                }
            }

            slotButtons.forEachIndexed { index, button ->
                if (index < newCount) {
                    val slot = slots[index]
                    button.setItem(slot, selectedItemId == slot.id, snapshotDir)
                    button.isVisible = true
                } else {
                    button.clearIcon()
                    button.isVisible = false
                }
            }

            trimSlotPool(newCount)

            statusLabel.isVisible = false
            gridWidget.isVisible = true
        }

        fun selectItem(id: String, addToHistory: Boolean = true) {
            if (selectedItemId == id) return
            selectedItemId = id
            updateSelectionState()
            scope.launch {
                if (selectedTypeIdx == 0) {
                    val result = withContext(Dispatchers.IO) { RegistryDatabase.itemDetailWithRecipes(project, id) }
                    if (result.detail != null) {
                        detailPanel.setItem(result.detail, result.recipeUsage, result.recipeDetails, snapshotDir, scope)
                    }
                } else {
                    val typeId = browseableTypes.getOrNull(selectedTypeIdx)?.id
                    if (typeId != null) {
                        val detail = withContext(Dispatchers.IO) { RegistryDatabase.customValueDetail(project, typeId, id) }
                        if (detail != null) {
                            detailPanel.setCustomValue(detail, snapshotDir, scope)
                        }
                    }
                }
                markDirty()
            }
        }

        private fun updateSelectionState() {
            slotButtons.forEach { slot ->
                if (slot.isVisible) {
                    slot.isChecked = slot.itemId == selectedItemId
                }
            }
        }

        private fun detachGridButtons() {
            slotButtons.forEach { gridLayout.removeWidget(it) }
        }

        private fun clearGrid() {
            detachGridButtons()
            slotButtons.forEach { it.disposeLater() }
            slotButtons.clear()
        }

        private fun showGrid() {
            statusLabel.isVisible = false
            gridWidget.isVisible = true
        }

        private fun showStatus(message: String) {
            statusLabel.text = message
            statusLabel.isVisible = true
            gridWidget.isVisible = false
        }

        private fun messageFor(status: RegistryDbStatus): String = when (status) {
            is RegistryDbStatus.MissingRoot ->
                "This project hasn't been exported to the item registry yet.\nLaunch the game with the Companion mod to create it."

            is RegistryDbStatus.MissingLatestPointer ->
                "No registry snapshot has been selected yet.\nLaunch the game and run the registry export, then refresh."

            is RegistryDbStatus.MissingDatabase ->
                "The registry database hasn't been built yet.\nLaunch the game and run the registry export to generate it."

            is RegistryDbStatus.MissingManifest ->
                "A registry snapshot was found but its manifest file is missing.\nRe-run the registry export to fix this."

            is RegistryDbStatus.InvalidLatestPointer ->
                "The registry snapshot pointer file is corrupted.\nRe-run the registry export to fix this."

            is RegistryDbStatus.InvalidManifest ->
                "The registry manifest file is corrupted.\nRe-run the registry export to fix this."

            is RegistryDbStatus.IncompleteDump ->
                "The registry snapshot is incomplete.\nRe-run the registry export to generate a full snapshot."

            is RegistryDbStatus.SchemaMismatch ->
                "The registry database is from a different version of Tritium.\nRun the registry export again to rebuild it with the latest format."

            is RegistryDbStatus.StaleDatabase ->
                "The registry database is outdated.\nA new registry snapshot is available — refresh to update."

            is RegistryDbStatus.InvalidDatabase ->
                "The registry database is corrupted or unreadable.\nRe-run the registry export to rebuild it."

            is RegistryDbStatus.Ready ->
                ""
        }
    }

    private class GridViewport(
        private val controller: Controller,
        parent: QWidget?
    ) : QWidget(parent) {
        override fun minimumSizeHint(): QSize = QSize(1, 1)

        override fun event(event: QEvent?): Boolean {
            if (event?.type() == QEvent.Type.Resize) {
                controller.onViewportResized()
            }
            return super.event(event)
        }
    }

    private class DetailPanel(
        parent: QWidget?,
        private val controller: Controller
    ) : QWidget(parent) {
        private val outerLayout = QVBoxLayout(this)
        private val tabBar = QWidget()
        private val tabBarLayout = QHBoxLayout(tabBar)
        private val detailTabButton = QToolButton()
        private val recipesTabButton = QToolButton()

        // Detail view
        private val detailScrollArea = QScrollArea()
        private val detailContent = QWidget()
        private val detailLayout = QVBoxLayout(detailContent)
        private val itemIconLabel = DetailIconWidget()
        private val itemNameLabel = QLabel()
        private val itemNamespaceLabel = QLabel()
        private val itemIdLabel = QLabel()
        private val tagsContainer = QWidget()
        private val tagsLayout = QHBoxLayout(tagsContainer)
        private val dynamicPropsContainer = QWidget()
        private val dynamicPropsLayout = QVBoxLayout(dynamicPropsContainer)
        private val dynamicLabels = mutableListOf<QLabel>()

        // Recipes view
        private val recipeContainer = QWidget()
        private val recipeContainerLayout = QVBoxLayout(recipeContainer)
        private val recipeScrollArea = QScrollArea()
        private val recipeContent = QWidget()
        private val recipeLayout = QVBoxLayout(recipeContent)
        private val catalystRow = QWidget()
        private val catalystRowLayout = QHBoxLayout(catalystRow)
        private val catalystButtonGroup = QButtonGroup(catalystRow)
        private val recipeWidgetCache = mutableMapOf<String, RecipeRow>()
        private val catalystWidgetCache = mutableMapOf<String, CatalystButton>()

        private var currentSnapshotDir: VPath? = null
        private var allRecipes: List<RegistryRecipeSummary> = emptyList()
        private var selectedRecipeTypeId: String? = null
        private var currentScope: CoroutineScope? = null
        private var iconLoadJob: Job? = null

        init {
            outerLayout.setContentsMargins(0, 0, 0, 0)
            outerLayout.setSpacing(0)

            // Tab bar
            tabBarLayout.setContentsMargins(4, 4, 4, 0)
            tabBarLayout.setSpacing(2)
            detailTabButton.text = "Detail"
            detailTabButton.isCheckable = true
            detailTabButton.isChecked = true
            recipesTabButton.text = "Recipes"
            recipesTabButton.isCheckable = true
            detailTabButton.clicked.connect { showDetailTab() }
            recipesTabButton.clicked.connect { showRecipeTab() }
            tabBarLayout.addWidget(detailTabButton)
            tabBarLayout.addWidget(recipesTabButton)
            tabBarLayout.addStretch(1)
            outerLayout.addWidget(tabBar)

            // Detail tab
            detailScrollArea.widgetResizable = true
            detailScrollArea.setWidget(detailContent)
            detailScrollArea.frameShape = QFrame.Shape.NoFrame
            detailLayout.setContentsMargins(8, 8, 8, 8)
            detailLayout.setSpacing(12)

            val infoSection = QWidget()
            val infoLayout = QVBoxLayout(infoSection)
            infoLayout.setContentsMargins(0, 0, 0, 0)
            infoLayout.setSpacing(6)

            val headerRow = QWidget()
            val headerRowLayout = QHBoxLayout(headerRow)
            headerRowLayout.setContentsMargins(0, 0, 0, 0)
            headerRowLayout.setSpacing(8)

            itemIconLabel.setFixedSize(64, 64)

            val textColumn = QWidget()
            val textColumnLayout = QVBoxLayout(textColumn)
            textColumnLayout.setContentsMargins(0, 0, 0, 0)
            textColumnLayout.setSpacing(4)

            itemNameLabel.font = QFont(itemNameLabel.font).apply { setPointSize(12); setBold(true) }
            itemNameLabel.wordWrap = true

            itemNamespaceLabel.setStyle {
                color(TColors.Accent)
                fontWeight(700)
            }

            itemIdLabel.setStyle {
                color(TColors.Subtext)
            }
            itemIdLabel.wordWrap = true
            itemIdLabel.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)

            textColumnLayout.addWidget(itemNameLabel)
            textColumnLayout.addWidget(itemNamespaceLabel)
            textColumnLayout.addWidget(itemIdLabel)
            textColumnLayout.addStretch(1)

            headerRowLayout.addWidget(itemIconLabel)
            headerRowLayout.addWidget(textColumn, 1)

            infoLayout.addWidget(headerRow)

            // Dynamic properties section
            dynamicPropsLayout.setContentsMargins(0, 0, 0, 0)
            dynamicPropsLayout.setSpacing(4)
            dynamicPropsLayout.addStretch(1)
            infoLayout.addWidget(dynamicPropsContainer)

            // Tags section (at bottom)
            tagsLayout.setContentsMargins(0, 8, 0, 0)
            tagsLayout.setSpacing(4)
            tagsLayout.addStretch(1)
            infoLayout.addWidget(tagsContainer)

            detailLayout.addWidget(infoSection)
            detailLayout.addStretch(1)

            outerLayout.addWidget(detailScrollArea, 1)

            // Recipe tab
            recipeContainerLayout.setContentsMargins(0, 0, 0, 0)
            recipeContainerLayout.setSpacing(0)

            recipeScrollArea.widgetResizable = true
            recipeScrollArea.setWidget(recipeContent)
            recipeScrollArea.frameShape = QFrame.Shape.NoFrame
            recipeLayout.setContentsMargins(4, 4, 4, 4)
            recipeLayout.setSpacing(2)
            recipeLayout.addStretch(1)

            catalystRowLayout.setContentsMargins(4, 2, 4, 4)
            catalystRowLayout.setSpacing(2)
            catalystRow.sizePolicy = QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)

            recipeContainerLayout.addWidget(recipeScrollArea, 1)
            recipeContainerLayout.addWidget(catalystRow)
            outerLayout.addWidget(recipeContainer, 1)

            showDetailTab()

            setThemedStyle {
                selector("QTextEdit") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface2)
                    borderRadius(4)
                }
                selector("QToolButton") {
                    backgroundColor(TColors.Surface1)
                    color(TColors.Text)
                    border(1, TColors.Surface2)
                    borderRadius(3)
                }
                selector("QToolButton:hover") {
                    backgroundColor(TColors.Surface2)
                }
                selector("QToolButton:checked") {
                    backgroundColor(TColors.Accent)
                    color(TColors.Text)
                    border(1, TColors.Accent)
                }
            }
        }

        private fun showDetailTab() {
            detailTabButton.isChecked = true
            recipesTabButton.isChecked = false
            detailScrollArea.isVisible = true
            recipeContainer.isVisible = false
        }

        private fun showRecipeTab() {
            detailTabButton.isChecked = false
            recipesTabButton.isChecked = true
            detailScrollArea.isVisible = false
            recipeScrollArea.isVisible = true
            recipeContainer.isVisible = true
        }

        fun selectRecipeType(recipeTypeId: String) {
            if (selectedRecipeTypeId == recipeTypeId) return
            selectedRecipeTypeId = recipeTypeId
            catalystWidgetCache[recipeTypeId]?.isChecked = true
            currentScope?.let { refreshRecipes(emptyList(), currentSnapshotDir, it) }
        }

        private val skipKeys = setOf("id", "namespace", "path", "displayName", "tags", "texturePath", "rawJson", "typeId")

        private fun addDynamicField(key: String, value: JsonElement, indent: Int) {
            val indentPx = indent * 16
            val displayKey = key.replaceFirstChar { it.uppercase() }

            when (value) {
                is JsonPrimitive -> {
                    val displayValue = if (value.isString) value.content else value.toString()
                    val lbl = label("${"  ".repeat(indent)}${displayKey}: $displayValue") {
                        wordWrap = true
                        setStyle {
                            color(TColors.Text)
                            padding(0, 0, 0, indentPx)
                        }
                    }
                    dynamicLabels.add(lbl)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, lbl)
                }

                is JsonArray -> {
                    val lbl = label("${"  ".repeat(indent)}${displayKey}:") {
                        setStyle {
                            color(TColors.Text)
                            padding(0, 0, 0, indentPx)
                        }
                    }
                    dynamicLabels.add(lbl)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, lbl)
                    value.forEachIndexed { index, element ->
                        addDynamicField("[$index]", element, indent + 1)
                    }
                }

                is JsonObject -> {
                    val lbl = label("${"  ".repeat(indent)}${displayKey}:") {
                        setStyle {
                            color(TColors.Accent)
                            fontWeight(700)
                            padding(0, 0, 0, indentPx)
                        }
                    }
                    dynamicLabels.add(lbl)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, lbl)
                    value.forEach { (subKey, subValue) ->
                        addDynamicField(subKey, subValue, indent + 1)
                    }
                }
            }
        }

        private class DetailIconWidget(parent: QWidget? = null) : QWidget(parent) {
            private var itemTexture: AnimatedItemMngr.ItemTexture? = null
            private var staticPixmap: QPixmap? = null
            private var fallbackText: String = ""
            private var animListener: (() -> Unit)? = null

            val isAnimated: Boolean get() = itemTexture is AnimatedItemMngr.ItemTexture.Animated

            fun setItemTexture(tex: AnimatedItemMngr.ItemTexture) {
                itemTexture = tex
                staticPixmap = null
                fallbackText = ""
                animListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
                when (tex) {
                    is AnimatedItemMngr.ItemTexture.Animated -> {
                        val listener = { update() }
                        animListener = listener
                        AnimatedItemMngr.registerTickListener(listener)
                    }
                    is AnimatedItemMngr.ItemTexture.Static -> {
                        staticPixmap = tex.pixmap
                    }
                }
                update()
            }

            fun setStaticPixmap(pix: QPixmap) {
                itemTexture = null
                staticPixmap = pix
                fallbackText = ""
                animListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
                animListener = null
                update()
            }

            fun showFallback(text: String) {
                itemTexture = null
                staticPixmap = null
                fallbackText = text
                animListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
                animListener = null
                update()
            }

            fun clear() {
                itemTexture = null
                staticPixmap = null
                fallbackText = ""
                animListener?.let { AnimatedItemMngr.unregisterTickListener(it) }
                animListener = null
            }

            override fun paintEvent(event: QPaintEvent?) {
                val tex = itemTexture
                if (tex is AnimatedItemMngr.ItemTexture.Animated) {
                    val pix = AnimatedItemMngr.currentAnimatedPixmap("detail", tex, width())
                    if (!pix.isNull) {
                        val p = QPainter(this)
                        val x = (width() - pix.width() / pix.devicePixelRatio().toInt()) / 2
                        val y = (height() - pix.height() / pix.devicePixelRatio().toInt()) / 2
                        p.drawPixmap(x, y, pix)
                        p.end()
                        return
                    }
                }
                val static = staticPixmap
                if (static != null && !static.isNull) {
                    val p = QPainter(this)
                    val x = (width() - static.width() / static.devicePixelRatio().toInt()) / 2
                    val y = (height() - static.height() / static.devicePixelRatio().toInt()) / 2
                    p.drawPixmap(x, y, static)
                    p.end()
                    return
                }
                if (fallbackText.isNotEmpty()) {
                    val p = QPainter(this)
                    p.drawText(rect(), Qt.AlignmentFlag.AlignCenter.value(), fallbackText)
                    p.end()
                }
            }
        }

        fun setItem(
            item: RegistryItemDetail,
            recipeUsage: RegistryItemRecipeUsage,
            recipeDetails: List<RegistryRecipeDetail>,
            snapshotDir: VPath?,
            scope: CoroutineScope
        ) {
            currentSnapshotDir = snapshotDir
            currentScope = scope
            selectedRecipeTypeId = null
            allRecipes = recipeUsage.producedBy + recipeUsage.usedIn
            recipesTabButton.isVisible = true
            catalystRow.isVisible = true
            showDetailTab()

            itemNameLabel.text = item.displayName ?: item.path
            itemIdLabel.text = item.id
            itemNamespaceLabel.text = item.namespace

            // Create tag chips
            while (tagsLayout.count() > 1) {
                tagsLayout.takeAt(0)?.widget()?.disposeLater()
            }
            item.tags.forEach { tag ->
                val chip = label(tag) {
                    setStyle {
                        backgroundColor(TColors.Surface2)
                        color(TColors.Text)
                        borderRadius(4)
                        padding(2, 6)
                        fontSize(10)
                    }
                }
                tagsLayout.insertWidget(tagsLayout.count() - 1, chip)
            }

            // Parse raw JSON and display dynamic fields
            val jsonObj = runCatching { parseJsonObject(item.rawJson) }.getOrNull()
            dynamicLabels.forEach { it.disposeLater() }
            dynamicLabels.clear()
            while (dynamicPropsLayout.count() > 1) {
                dynamicPropsLayout.takeAt(0)?.widget()?.disposeLater()
            }

            jsonObj?.forEach { (key, value) ->
                if (key in skipKeys) return@forEach
                addDynamicField(key, value, 0)
            }

            iconLoadJob?.cancel()
            val texPath = item.texturePath
            val targetId = item.id
            val animJson = item.animationJson
            itemIconLabel.clear()
            iconLoadJob = scope.launch {
                val tex = loadItemTextureThrottled(targetId, texPath, animJson, snapshotDir, 64)
                if (isActive && tex != null) {
                    itemIconLabel.setItemTexture(tex)
                } else {
                    val pixmap = loadIconThrottled(targetId, texPath, snapshotDir, 64)
                    if (isActive) {
                        if (pixmap != null) {
                            itemIconLabel.setStaticPixmap(pixmap)
                        } else {
                            itemIconLabel.showFallback(item.displayName?.take(1) ?: "?")
                        }
                    }
                }
            }

            refreshCatalysts(item.id, scope)
            refreshRecipes(recipeDetails, snapshotDir, scope)
        }

        fun setCustomValue(
            value: RegistryValueDetail,
            snapshotDir: VPath?,
            scope: CoroutineScope
        ) {
            currentSnapshotDir = snapshotDir
            currentScope = scope
            selectedRecipeTypeId = null
            allRecipes = emptyList()
            showDetailTab()
            catalystRow.isVisible = false
            recipeContainer.isVisible = false
            detailScrollArea.isVisible = true
            recipesTabButton.isVisible = false

            itemNameLabel.text = value.displayName ?: value.path
            itemIdLabel.text = value.id
            itemNamespaceLabel.text = value.typeDisplayName ?: value.typeId

            while (tagsLayout.count() > 1) {
                tagsLayout.takeAt(0)?.widget()?.disposeLater()
            }
            value.tags.forEach { tag ->
                val chip = label(tag) {
                    setStyle {
                        backgroundColor(TColors.Surface2)
                        color(TColors.Text)
                        borderRadius(4)
                        padding(2, 6)
                        fontSize(10)
                    }
                }
                tagsLayout.insertWidget(tagsLayout.count() - 1, chip)
            }

            val jsonObj = runCatching { parseJsonObject(value.rawJson) }.getOrNull()
            dynamicLabels.forEach { it.disposeLater() }
            dynamicLabels.clear()
            while (dynamicPropsLayout.count() > 1) {
                dynamicPropsLayout.takeAt(0)?.widget()?.disposeLater()
            }
            jsonObj?.forEach { (key, v) ->
                if (key in skipKeys) return@forEach
                addDynamicField(key, v, 0)
            }

            iconLoadJob?.cancel()
            val texPath = value.texturePath
            val targetId = value.id
            val tintColor = runCatching {
                val obj = parseJsonObject(value.rawJson)
                obj?.get("tintColor")?.jsonPrimitive?.longOrNull
                    ?: obj?.get("rawData")?.jsonObject?.get("tintColor")?.jsonPrimitive?.longOrNull
            }.getOrNull()
            itemIconLabel.clear()
            iconLoadJob = scope.launch {
                val tex = loadItemTextureThrottled(targetId, texPath, null, snapshotDir, 64, tintColor)
                if (isActive && tex != null) {
                    itemIconLabel.setItemTexture(tex)
                } else {
                    val pixmap = loadIconThrottled(targetId, texPath, snapshotDir, 64, tintColor)
                    if (isActive) {
                        if (pixmap != null) {
                            itemIconLabel.setStaticPixmap(pixmap)
                        } else {
                            itemIconLabel.showFallback(value.displayName?.take(1) ?: "?")
                        }
                    }
                }
            }
        }

        private fun refreshCatalysts(itemId: String, scope: CoroutineScope) {
            catalystWidgetCache.forEach { (_, btn) -> btn.disposeLater() }
            catalystWidgetCache.clear()
            while (catalystRowLayout.count() > 0) {
                catalystRowLayout.takeAt(0)?.widget()?.disposeLater()
            }

            scope.launch {
                val recipeTypes = withContext(Dispatchers.IO) {
                    RegistryDatabase.recipeTypesForItem(controller.project, itemId)
                }
                val allCatalystIds = recipeTypes.flatMap { it.catalystIds }.distinct()
                val catalystItems = if (allCatalystIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        RegistryDatabase.itemSummariesByIds(controller.project, allCatalystIds)
                    }.associateBy { it.id }
                } else emptyMap()

                if (isActive) {
                    buildCatalystRow(recipeTypes, catalystItems, scope)
                }
            }
        }

        private fun buildCatalystRow(
            recipeTypes: List<RecipeTypeCatalyst>,
            catalystItems: Map<String, RegistryItemSummary>,
            scope: CoroutineScope
        ) {
            catalystWidgetCache.forEach { (_, btn) -> btn.disposeLater() }
            catalystWidgetCache.clear()
            while (catalystRowLayout.count() > 0) {
                catalystRowLayout.takeAt(0)?.widget()?.disposeLater()
            }
            catalystButtonGroup.buttons().filterNotNull().forEach { catalystButtonGroup.removeButton(it) }

            catalystRow.isVisible = true

            if (recipeTypes.isEmpty()) {
                catalystRow.isVisible = false
                return
            }

            recipeTypes.forEach { rt ->
                val catalystItem = rt.catalystIds.firstNotNullOfOrNull { catalystItems[it] }
                val btn = CatalystButton(rt, catalystItem, currentSnapshotDir, scope, controller)
                catalystWidgetCache[rt.recipeTypeId] = btn
                catalystButtonGroup.addButton(btn)
                catalystRowLayout.addWidget(btn)
            }
            catalystRowLayout.addStretch(1)

            catalystButtonGroup.exclusive = true

            if (selectedRecipeTypeId != null) {
                catalystWidgetCache[selectedRecipeTypeId]?.isChecked = true
            } else if (recipeTypes.isNotEmpty()) {
                selectedRecipeTypeId = recipeTypes.first().recipeTypeId
                catalystWidgetCache[selectedRecipeTypeId]?.isChecked = true
            }

            catalystButtonGroup.buttonClicked.connect { btn ->
                selectedRecipeTypeId = catalystWidgetCache.entries.find { it.value == btn }?.key
                currentScope?.let { refreshRecipes(emptyList(), currentSnapshotDir, it) }
            }
        }

        private fun refreshRecipes(
            recipeDetails: List<RegistryRecipeDetail>,
            snapshotDir: VPath?,
            scope: CoroutineScope
        ) {
            val detailsById = recipeDetails.associateBy { it.id }
            val filteredRecipes = if (selectedRecipeTypeId != null) {
                allRecipes.filter { it.recipeType == selectedRecipeTypeId }
            } else {
                allRecipes
            }
            val neededIds = filteredRecipes.map { it.id }.toSet()

            // Cancel icon loads for widgets that will be removed
            recipeWidgetCache.forEach { (id, widget) ->
                if (id !in neededIds) {
                    widget.cancelIconLoad()
                }
            }

            // Build set of widgets currently in layout
            val widgetsInLayout = mutableSetOf<QWidget>()
            for (i in 0 until recipeLayout.count()) {
                recipeLayout.itemAt(i)?.widget()?.let { widgetsInLayout.add(it) }
            }

            // Remove widgets no longer needed
            val toRemove = recipeWidgetCache.keys.filter { it !in neededIds }.toList()
            toRemove.forEach { id ->
                recipeWidgetCache.remove(id)?.let { widget ->
                    recipeLayout.removeWidget(widget)
                    widget.disposeLater()
                }
            }

            // Remove any stale widgets (not in cache but still in layout)
            widgetsInLayout.filter { it !in recipeWidgetCache.values }.forEach { w ->
                recipeLayout.removeWidget(w)
                w.disposeLater()
            }

            // Hide unused cached widgets
            recipeWidgetCache.forEach { (id, widget) ->
                widget.isVisible = id in neededIds
            }

            if (filteredRecipes.isEmpty()) {
                recipeLayout.addWidget(label("No recipes found.") {
                    setStyle { color(TColors.Subtext) }
                })
            } else {
                filteredRecipes.forEach { recipe ->
                    val detail = detailsById[recipe.id]
                    val widget = recipeWidgetCache.getOrPut(recipe.id) {
                        RecipeRow(recipe, detail, snapshotDir, controller, scope)
                    }
                    widget.isVisible = true
                    if (widget.parentWidget() != recipeContent) {
                        recipeLayout.addWidget(widget)
                    }
                }
            }
        }

        fun triggerAnimUpdate() {
            if (itemIconLabel.isAnimated) itemIconLabel.update()
            catalystWidgetCache.values.forEach { btn ->
                if (btn.isAnimated) btn.update()
            }
        }
    }

    private class RecipeRow(
        private val recipe: RegistryRecipeSummary,
        private val detail: RegistryRecipeDetail?,
        snapshotDir: VPath?,
        private val controller: Controller,
        scope: CoroutineScope
    ) : QFrame() {
        private val iconLoadJob: Job?

        init {
            val layout = QHBoxLayout(this)
            layout.setContentsMargins(4, 4, 4, 4)
            layout.setSpacing(8)

            val iconLabel = QLabel().apply {
                setFixedSize(28, 28)
                alignment = Qt.Alignment(Qt.AlignmentFlag.AlignCenter)
            }

            val recipeTypeLabel = QLabel(recipe.recipeType ?: "Recipe").apply {
                font = QFont(font).apply { setBold(true) }
            }

            layout.addWidget(iconLabel)
            layout.addWidget(recipeTypeLabel)
            layout.addStretch(1)

            val targetId = if (detail != null) {
                val recipeRoot = parseJsonObject(detail.rawJson)
                val outputs = asJsonArrayOrEmpty(recipeRoot?.get("outputs"))
                outputs.firstOrNull()?.let { asJsonObjectOrNull(it) }?.let { primitiveContentOrNull(it["id"]) }
            } else null

            // Load recipe type icon
            val recipeTypeIcon = recipe.recipeType?.let { rt ->
                scope.launch {
                    val pixmap = loadIconThrottled("recipe_type:$rt", null, snapshotDir, 24)
                    if (isActive && pixmap != null) {
                        iconLabel.pixmap = pixmap
                    }
                }
            }

            // Load output item icon as fallback
            iconLoadJob = if (targetId != null && recipeTypeIcon == null) {
                val outputId = targetId
                scope.launch {
                    val pixmap = loadIconThrottled(outputId, null, snapshotDir, 24)
                    if (isActive) {
                        if (pixmap != null) {
                            iconLabel.pixmap = pixmap
                        } else {
                            iconLabel.text = abbreviation(recipe.recipeType ?: "Recipe")
                        }
                    }
                }
            } else {
                iconLabel.text = abbreviation(recipe.recipeType ?: "Recipe")
                null
            }

            frameShape = Shape.StyledPanel
            setThemedStyle {
                selector("QFrame") {
                    backgroundColor(TColors.Surface1)
                    border(1, TColors.Surface2)
                    borderRadius(3)
                }
                selector("QFrame:hover") {
                    backgroundColor(TColors.Surface2)
                }
            }

            setCursor(Qt.CursorShape.PointingHandCursor)
        }

        private fun abbreviation(name: String): String {
            val words = name.split(Regex("\\s+|[_-]")).filter { it.isNotBlank() }
            return when {
                words.size >= 2 -> (words[0].first().toString() + words[1].first().toString()).uppercase()
                name.length >= 2 -> name.take(2).uppercase()
                else -> name.uppercase()
            }
        }

        override fun mousePressEvent(event: QMouseEvent?) {
            super.mousePressEvent(event)
            recipe.recipeType?.let { recipeType ->
                controller.detailPanel.selectRecipeType(recipeType)
            }
        }

        fun cancelIconLoad() {
            iconLoadJob?.cancel()
        }
    }

    private class CatalystButton(
        recipeType: RecipeTypeCatalyst,
        private val catalystItem: RegistryItemSummary?,
        private val snapshotDir: VPath?,
        private val scope: CoroutineScope,
        private val controller: Controller
    ) : QToolButton() {
        private var iconLoadJob: Job? = null
        private var itemTexture: ItemTexture? = null
        internal val isAnimated: Boolean get() = itemTexture is ItemTexture.Animated

        init {
            setFixedSize(SLOT_SIZE, SLOT_SIZE)
            isCheckable = true
            autoRaise = true
            objectName = "registryBrowserSlot"
            toolTip = catalystItem?.let { "${it.displayName ?: it.id}\n${it.id}" } ?: (recipeType.displayName
                ?: recipeType.recipeTypeId)

            loadIcon()

            clicked.connect {
                catalystItem?.id?.let { controller.selectItem(it) }
            }

            setThemedStyle {
                selector("QToolButton#registryBrowserSlot") {
                    background("transparent")
                    border()
                    padding(0)
                }
                selector("QToolButton#registryBrowserSlot:hover") {
                    background("transparent")
                }
                selector("QToolButton#registryBrowserSlot:checked") {
                    background("transparent")
                    border(1, TColors.Accent)
                }
            }
        }

        override fun paintEvent(event: QPaintEvent?) {
            val tex = itemTexture
            if (tex is AnimatedItemMngr.ItemTexture.Animated) {
                val painter = QPainter(this)
                val pix = AnimatedItemMngr.currentAnimatedPixmap(catalystItem?.id ?: "", tex, width() - 4)
                val x = (width() - pix.width() / pix.devicePixelRatio().toInt()) / 2
                val y = (height() - pix.height() / pix.devicePixelRatio().toInt()) / 2
                painter.drawPixmap(x, y, pix)
                if (isChecked) {
                    val pen = QPen(TColors.Accent.toQC(), 2.0)
                    painter.setPen(pen)
                    painter.drawRect(rect().adjusted(1, 1, -1, -1))
                }
                painter.end()
            } else {
                super.paintEvent(event)
            }
        }

        private fun loadIcon() {
            val item = catalystItem ?: return
            iconLoadJob?.cancel()
            val id = item.id
            val texPath = item.texturePath
            val animJson = item.animationJson
            val snapDir = snapshotDir
            iconLoadJob = scope.launch {
                if (animJson != null) {
                    val tex = loadItemTextureThrottled(id, texPath, animJson, snapDir, 64)
                    if (isActive && tex != null) {
                        itemTexture = tex
                        when (tex) {
                            is AnimatedItemMngr.ItemTexture.Static -> {
                                icon = QIcon(tex.pixmap)
                                iconSize = QSize(SLOT_SIZE - 4, SLOT_SIZE - 4)
                                text = ""
                            }
                            is AnimatedItemMngr.ItemTexture.Animated -> {
                                icon = QIcon()
                                text = ""
                                update()
                            }
                        }
                    }
                } else {
                    val pixmap = loadIconThrottled(id, texPath, snapDir, 64)
                    if (isActive) {
                        if (pixmap != null) {
                            itemTexture = AnimatedItemMngr.ItemTexture.Static(pixmap)
                            icon = QIcon(pixmap)
                            iconSize = QSize(SLOT_SIZE - 4, SLOT_SIZE - 4)
                            text = ""
                        } else {
                            icon = QIcon()
                            text = abbreviation(item)
                        }
                    }
                }
            }
        }

        private fun abbreviation(item: RegistryItemSummary): String {
            val base = item.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: item.path
            val words = base.split(Regex("\\s+|[_-]")).filter { it.isNotBlank() }
            return when {
                words.size >= 2 -> (words[0].first().toString() + words[1].first().toString()).uppercase()
                base.length >= 2 -> base.take(2).uppercase()
                else -> base.uppercase()
            }
        }
    }

    private class RecipeValueButton(
        values: List<RenderedValue>,
        component: RecipeComponentLayout?,
        snapshotDir: VPath?,
        project: ProjectBase,
        private val controller: Controller
    ) : QToolButton() {
        private val entries = values.ifEmpty {
            listOf(
                RenderedValue(
                    "value",
                    "placeholder",
                    component?.label ?: "empty",
                    0,
                    component?.label
                )
            )
        }
        private val previews: List<RecipeValuePreview> = entries.flatMap { value ->
            when (value.valueType) {
                "item" if value.refType == "item" -> listOf(
                    RecipeValuePreview(
                        id = value.id,
                        label = value.displayName ?: value.id,
                        texturePath = runCatching { RegistryDatabase.itemTexturePath(project, value.id) }.getOrNull(),
                        clickableItemId = value.id
                    )
                )

                "item" if value.refType == "tag" -> runCatching {
                    RegistryDatabase.itemPreviewsForTag(project, value.id).map {
                        RecipeValuePreview(
                            id = it.id,
                            label = "#${value.id}",
                            texturePath = it.texturePath,
                            clickableItemId = it.id
                        )
                    }
                }.getOrDefault(
                    listOf(RecipeValuePreview("#${value.id}", "#${value.id}", null, null))
                )

                else -> {
                    val preview = runCatching {
                        RegistryDatabase.customValuePreview(
                            project,
                            value.valueType,
                            value.id
                        )
                    }.getOrNull()
                    listOf(
                        RecipeValuePreview(
                            id = value.id,
                            label = value.displayName ?: preview?.displayName ?: value.id,
                            texturePath = preview?.texturePath,
                            clickableItemId = null
                        )
                    )
                }
            }
        }
        private var currentIndex = 0

        init {
            val targetWidth = component?.width ?: 28
            val targetHeight = component?.height ?: 28
            setFixedSize(targetWidth, targetHeight)
            autoRaise = true
            render(snapshotDir)
            if (previews.size > 1) {
                val timer = QTimer(this).apply {
                    interval = 1000
                    timeout.connect {
                        currentIndex = (currentIndex + 1) % previews.size
                        render(snapshotDir)
                    }
                    start()
                }
                destroyed.connect { timer.stop() }
            }
            toolTip = previews.joinToString("\n") { it.label }
            clicked.connect {
                currentPreview()?.clickableItemId?.let(controller::selectItem)
            }
            setThemedStyle {
                selector("QToolButton") {
                    backgroundColor(TColors.Surface1)
                    border(1, TColors.Surface2)
                    borderRadius(3)
                    padding(0)
                }
                selector("QToolButton:hover") {
                    backgroundColor(TColors.Surface2)
                }
            }
        }

        private fun render(snapshotDir: VPath?) {
            val preview = currentPreview()
            val pixmap = preview?.let { loadIcon(it.id, it.texturePath, snapshotDir, minOf(width(), height()) - 4) }
            if (pixmap != null) {
                icon = QIcon(pixmap)
                iconSize = QSize(minOf(width(), height()) - 4, minOf(width(), height()) - 4)
                text = ""
            } else {
                icon = QIcon()
                text = when {
                    preview != null -> abbreviation(preview.label)
                    else -> "?"
                }
            }
        }

        private fun currentPreview(): RecipeValuePreview? =
            previews.getOrNull(currentIndex)

        private fun abbreviation(text: String): String {
            val words = text.split(Regex("\\s+|[_:-]")).filter { it.isNotBlank() }
            return when {
                words.size >= 2 -> (words[0].first().toString() + words[1].first().toString()).uppercase()
                text.isNotBlank() -> text.take(2).uppercase()
                else -> "?"
            }
        }
    }

    internal class SlotButton(
        parent: QWidget?,
        private val scope: CoroutineScope,
        private val onItemSelected: (String) -> Unit
    ) : QToolButton(parent) {
        var itemId: String? = null
            private set
        private var itemRegistryType: String? = null
        private var itemTexturePath: String? = null
        private var itemSnapshotDir: VPath? = null
        private var itemAnimationJson: String? = null
        internal var itemTooltipText: String = ""
        internal var itemTooltipStyle: TTooltipStyle = TTooltipStyle()
        private var pressPos: QPoint? = null
        private var iconLoadJob: Job? = null
        internal var itemTexture: ItemTexture? = null
            private set
        internal val isAnimated: Boolean get() = itemTexture is ItemTexture.Animated

        fun clearIcon() {
            iconLoadJob?.cancel()
            iconLoadJob = null
            itemTexture = null
            icon = QIcon()
            text = ""
        }

        init {
            autoRaise = true
            mouseTracking = false
            clicked.connect {
                val id = itemId
                if (id != null) onItemSelected(id)
            }
        }

        override fun mousePressEvent(event: QMouseEvent?) {
            if (event?.button() == Qt.MouseButton.LeftButton && itemId != null) {
                pressPos = event.pos()
            }
            super.mousePressEvent(event)
        }

        override fun mouseMoveEvent(event: QMouseEvent?) {
            if (pressPos != null && event != null && itemId != null) {
                val dx = event.pos().x() - pressPos!!.x()
                val dy = event.pos().y() - pressPos!!.y()
                if (dx * dx + dy * dy >= 16) {
                    pressPos = null
                    startDrag()
                    return
                }
            }
            super.mouseMoveEvent(event)
        }

        override fun mouseReleaseEvent(event: QMouseEvent?) {
            pressPos = null
            super.mouseReleaseEvent(event)
        }

        override fun paintEvent(event: QPaintEvent?) {
            val tex = itemTexture
            if (tex is AnimatedItemMngr.ItemTexture.Animated) {
                val painter = QPainter(this)
                val pix = AnimatedItemMngr.currentAnimatedPixmap(itemId ?: "", tex, width())
                val x = (width() - pix.width() / pix.devicePixelRatio().toInt()) / 2
                val y = (height() - pix.height() / pix.devicePixelRatio().toInt()) / 2
                painter.drawPixmap(x, y, pix)
                if (isChecked) {
                    val pen = QPen(TColors.Accent.toQC(), 2.0)
                    painter.setPen(pen)
                    painter.drawRect(rect().adjusted(1, 1, -1, -1))
                }
                painter.end()
            } else {
                super.paintEvent(event)
            }
        }

        private fun startDrag() {
            TTooltip.hide()
            val drag = QDrag(this)
            val mimeData = QMimeData()
            val dragData = buildJsonObject {
                put("id", itemId!!)
                itemRegistryType?.let { put("type", it) }
            }.toString()
            mainLogger.info("startDrag: state={} registryType={}", dragData, itemRegistryType)
            mimeData.setText(itemId!!)
            mimeData.setData("application/x-tritium-item", QByteArray(dragData.toByteArray()))
            drag.setMimeData(mimeData)

            val dragPixmap = createDragPixmap(itemId!!, itemTexturePath, itemSnapshotDir, itemTooltipStyle)

            val shiftX = 24
            val shiftY = 24

            val padded = QPixmap(dragPixmap.width() + shiftX, dragPixmap.height() + shiftY)
            padded.fill("transparent")

            val painter = QPainter(padded)
            painter.drawPixmap(shiftX, shiftY, dragPixmap)
            painter.end()

            drag.setPixmap(padded)
            drag.setHotSpot(QPoint(0, 0))

            drag.exec(Qt.DropAction.CopyAction)
        }

        private fun createDragPixmap(
            id: String,
            texturePath: String?,
            snapshotDir: VPath?,
            tooltipStyle: TTooltipStyle
        ): QPixmap {
            val iconSize = 18
            val iconPixmap = loadIcon(id, texturePath, snapshotDir, iconSize)
            return TTooltip.renderPixmap(id, tooltipStyle, iconPixmap, iconSize)
        }

        fun setItem(slot: SlotItemInfo, selected: Boolean, snapshotDir: VPath?) {
            itemId = slot.id
            itemRegistryType = slot.registryType
            itemTexturePath = slot.texturePath
            itemSnapshotDir = snapshotDir
            itemAnimationJson = slot.animationJson
            itemTooltipStyle = itemTooltipStyleProvider(slot.rarity)
            isChecked = selected
            itemTooltipText = buildString {
                append(slot.displayName ?: slot.id)
                append("\n")
                append(slot.id)
                if (slot.tags.isNotEmpty()) {
                    append("\n")
                    append(slot.tags.joinToString(", "))
                }
            }
            setProperty("tt_style", itemTooltipStyle)
            toolTip = itemTooltipText
            iconLoadJob?.cancel()

            val tc = slot.tintColor

            val cacheKey = "${slot.id}|${slot.texturePath}|$tc|64|${currentDpr(null)}|${snapshotDir?.toAbsolute()}"
            val cached = pixmapCache[cacheKey]
            if (cached != null) {
                itemTexture = ItemTexture.Static(cached)
                icon = QIcon(cached)
                iconSize = QSize(32, 32)
                text = ""
                iconLoadJob = null
                return
            }

            iconLoadJob = scope.launch {

                val tex = loadItemTextureThrottled(slot.id, slot.texturePath, slot.animationJson, snapshotDir, 64, slot.tintColor)
                if (isActive && tex != null) {
                    itemTexture = tex
                    when (tex) {
                        is ItemTexture.Static -> {
                            icon = QIcon(tex.pixmap)
                            iconSize = QSize(32, 32)
                            text = ""
                        }
                        is ItemTexture.Animated -> {
                            icon = QIcon()
                            text = ""
                            update()
                        }
                    }
                } else {
                    val pixmap = loadIconThrottled(slot.id, slot.texturePath, snapshotDir, 64, slot.tintColor)
                    if (isActive) {
                        if (pixmap != null) {
                            itemTexture = ItemTexture.Static(pixmap)
                            icon = QIcon(pixmap)
                            iconSize = QSize(32, 32)
                            text = ""
                        } else {
                            icon = QIcon()
                            text = abbreviation(slot)
                        }
                    }
                }
            }
        }

        private fun abbreviation(slot: SlotItemInfo): String {
            val base = slot.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: slot.path
            val words = base.split(Regex("\\s+|[_-]")).filter { it.isNotBlank() }
            return when {
                words.size >= 2 -> (words[0].first().toString() + words[1].first().toString()).uppercase()
                base.length >= 2 -> base.take(2).uppercase()
                else -> base.uppercase()
            }
        }
    }

    private class SearchHighlighter(doc: QTextDocument) : QSyntaxHighlighter(doc) {
        private val modFormat = QTextCharFormat().apply {
            setForeground(TColors.Syntax.Namespace.toQB())
        }
        private val tooltipFormat = QTextCharFormat().apply {
            setForeground(TColors.Syntax.String.toQB())
        }
        private val tagFormat = QTextCharFormat().apply {
            setForeground(TColors.Syntax.Tag.toQB())
        }
        private val negateFormat = QTextCharFormat().apply {
            setForeground(TColors.Syntax.Constant.toQB())
        }

        private val tokenRx = Regex("""[@#$%-]\S+""")

        override fun highlightBlock(text: String) {
            tokenRx.findAll(text).forEach { m ->
                val token = m.value
                when {
                    token.startsWith('@') -> setFormat(m.range.first, m.range.last - m.range.first + 1, modFormat)
                    token.startsWith('#') -> setFormat(m.range.first, m.range.last - m.range.first + 1, tooltipFormat)
                    token.startsWith('$') -> setFormat(m.range.first, m.range.last - m.range.first + 1, tagFormat)
                    token.startsWith('-') -> setFormat(m.range.first, m.range.last - m.range.first + 1, negateFormat)
                }
            }
        }
    }

    companion object {
        private const val SLOT_SIZE = 36
        private const val STATE_FILE_NAME = "registry-browser.json"
        private val controllers = WeakHashMap<DockWidget, Controller>()
        private val recipeJson = Json { ignoreUnknownKeys = true }
        private val iconLoadSemaphore = Semaphore(8)
        private val pixmapCache = object : LinkedHashMap<String, QPixmap>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String?, QPixmap?>?): Boolean = size > 1024
        }
        private val stateJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
        var itemTooltipStyleProvider: (rarity: String?) -> TTooltipStyle = { rarity ->
            minecraftTooltipStyleForRarity(rarity)
        }

        fun loadItemTexture(
            id: String,
            texturePath: String?,
            animationJson: String?,
            snapshotDir: VPath?,
            size: Int,
            tintColor: Long? = null
        ): ItemTexture? {
            if (snapshotDir == null) return null

            if (animationJson == null) {
                val cacheKey = "$id|$texturePath|$tintColor|$size|${AnimatedItemMngr.cachedDpr}|${snapshotDir.toAbsolute()}"
                pixmapCache[cacheKey]?.let { return ItemTexture.Static(it) }
            }

            val candidates = buildList {
                val parts = id.split(':')
                val ns = parts.getOrNull(0)
                val pth = parts.getOrNull(1)

                if (animationJson != null) {
                    texturePath?.let { add(it) }
                    if (ns != null && pth != null) {
                        addAll(listOf(
                            "assets/textures/${ns}/item/${pth}.png",
                            "assets/textures/${ns}/block/${pth}.png",
                            "assets/textures/${ns}/block/${pth}_still.png",
                            "assets/${ns}/textures/item/${pth}.png",
                            "assets/${ns}/textures/block/${pth}.png",
                            "assets/${ns}/textures/block/${pth}_still.png"
                        ))
                    }
                }

                if (tintColor == null && ns != null && pth != null) {
                    add("icons/${ns}/${pth}.png")
                    add("icons/${ns}_${pth.replace('/', '_')}.png")
                    add("icons/${ns}/fluid_${pth}.png")
                    add("icons/${ns}_fluid_${pth.replace('/', '_')}.png")
                }

                if (animationJson == null) {
                    texturePath?.let { add(it) }
                    if (ns != null && pth != null) {
                        addAll(listOf(
                            "assets/textures/${ns}/item/${pth}.png",
                            "assets/textures/${ns}/block/${pth}.png",
                            "assets/textures/${ns}/block/${pth}_still.png",
                            "assets/${ns}/textures/item/${pth}.png",
                            "assets/${ns}/textures/block/${pth}.png",
                            "assets/${ns}/textures/block/${pth}_still.png"
                        ))
                    }
                }
            }

            for (relPath in candidates) {
                val iconPath = snapshotDir.resolve(relPath)
                if (!iconPath.exists()) continue

                val img = QImage(iconPath.toAbsolute().toString())
                if (img.isNull) continue

                if (animationJson != null) {
                    val meta = AnimatedItemMngr.parseAnimationMeta(animationJson, img.width(), img.height())
                    if (meta != null) {
                        val tintedImg = if (tintColor != null) {
                            AnimatedItemMngr.applyTint(QPixmap.fromImage(img), tintColor).toImage()
                        } else {
                            img
                        }
                        return ItemTexture.Animated(tintedImg, meta)
                    }
                }

                val mcmetaFile = snapshotDir.resolve("$relPath.mcmeta")
                if (mcmetaFile.exists()) {
                    val mcmetaText = mcmetaFile.readTextOrNull()
                    if (mcmetaText != null) {
                        val meta = AnimatedItemMngr.parseAnimationMeta(mcmetaText, img.width(), img.height())
                        if (meta != null) {
                            val tintedImg = if (tintColor != null) {
                                AnimatedItemMngr.applyTint(QPixmap.fromImage(img), tintColor).toImage()
                            } else {
                                img
                            }
                            return ItemTexture.Animated(tintedImg, meta)
                        }
                    }
                }

                val physicalSize = (size * AnimatedItemMngr.cachedDpr).toInt()
                val cacheKey = "$id|$texturePath|$tintColor|$size|${AnimatedItemMngr.cachedDpr}|${snapshotDir.toAbsolute()}"
                val pixmap = QPixmap()
                if (pixmap.load(iconPath.toAbsolute().toString())) {
                    var display = pixmap
                    if (tintColor != null) {
                        display = AnimatedItemMngr.applyTint(pixmap, tintColor)
                    }
                    val mode = if (physicalSize <= 64) Qt.TransformationMode.FastTransformation
                    else Qt.TransformationMode.SmoothTransformation
                    val scaled = display.scaled(physicalSize, physicalSize,
                        Qt.AspectRatioMode.KeepAspectRatio, mode)
                    scaled.setDevicePixelRatio(AnimatedItemMngr.cachedDpr)
                    pixmapCache[cacheKey] = scaled
                    return ItemTexture.Static(scaled)
                }
            }
            return null
        }

        @Serializable
        private data class RegistryBrowserState(
            val lastSelectedId: String? = null,
            val lastSearchText: String? = null,
            val lastPage: Int = 0,
            val lastTypeIndex: Int = 0
        )

        private fun stateFileFor(project: ProjectBase): VPath =
            project.projectDir.resolve(".tr").resolve(STATE_FILE_NAME)

        private fun loadState(project: ProjectBase): RegistryBrowserState =
            runCatching {
                val file = stateFileFor(project)
                if (file.exists()) {
                    val text = file.readTextOrNull()
                    if (!text.isNullOrBlank()) {
                        return stateJson.decodeFromString<RegistryBrowserState>(text)
                    }
                }
                RegistryBrowserState()
            }.getOrDefault(RegistryBrowserState())

        private fun saveState(project: ProjectBase, state: RegistryBrowserState) =
            runCatching {
                val file = stateFileFor(project)
                val dir = file.parent()
                if (!dir.exists()) dir.mkdirs()
                file.writeTextAtomic(stateJson.encodeToString(state))
            }

        private fun parseJsonObject(raw: String): JsonObject? =
            runCatching { asJsonObjectOrNull(recipeJson.parseToJsonElement(raw)) }.getOrNull()

        private fun loadIcon(id: String, texturePath: String?, snapshotDir: VPath?, size: Int, tintColor: Long? = null): QPixmap? {
            if (snapshotDir == null) return null
            val physicalSize = (size * AnimatedItemMngr.cachedDpr).toInt()

            val cacheKey = "$id|$texturePath|$tintColor|$size|${AnimatedItemMngr.cachedDpr}|${snapshotDir.toAbsolute()}"
            pixmapCache[cacheKey]?.let { return it }

            val candidates = buildList {
                val parts = id.split(':')
                if (parts.size == 2) {
                    val namespace = parts[0]
                    val path = parts[1]
                    if (tintColor == null) {
                        add("icons/${namespace}/${path}.png")
                        add("icons/${namespace}_${path.replace('/', '_')}.png")
                        add("icons/${namespace}/fluid_${path}.png")
                        add("icons/${namespace}_fluid_${path.replace('/', '_')}.png")
                    }
                }

                texturePath?.let { add(it) }

                if (parts.size == 2) {
                    val namespace = parts[0]
                    val path = parts[1]
                    addAll(listOf(
                        "assets/textures/${namespace}/item/${path}.png",
                        "assets/textures/${namespace}/block/${path}.png",
                        "assets/textures/${namespace}/block/${path}_still.png",
                        "assets/${namespace}/textures/item/${path}.png",
                        "assets/${namespace}/textures/block/${path}.png",
                        "assets/${namespace}/textures/block/${path}_still.png"
                    ))
                }
            }

            for (relPath in candidates) {
                val iconPath = snapshotDir.resolve(relPath)
                if (iconPath.exists()) {
                    val pixmap = QPixmap()
                    if (pixmap.load(iconPath.toAbsolute().toString())) {
                        var display = pixmap
                        if (tintColor != null) {
                            display = AnimatedItemMngr.applyTint(pixmap, tintColor)
                        }
                        val mode = if (physicalSize <= 64) Qt.TransformationMode.FastTransformation
                                   else Qt.TransformationMode.SmoothTransformation
                        val scaled = display.scaled(physicalSize, physicalSize,
                            Qt.AspectRatioMode.KeepAspectRatio, mode)
                        scaled.setDevicePixelRatio(AnimatedItemMngr.cachedDpr)
                        pixmapCache[cacheKey] = scaled
                        return scaled
                    }
                }
            }
            return null
        }

        private fun scaledHighQuality(src: QPixmap, targetW: Int, targetH: Int): QPixmap {
            if (src.width() <= 32 && src.height() <= 32) {
                return src.scaled(targetW, targetH,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.FastTransformation)
            }

            var img = src.toImage()
            while (img.width() / 2 >= targetW && img.height() / 2 >= targetH) {
                img = img.scaled(
                    img.width() / 2,
                    img.height() / 2,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation
                )
            }

            val finalImg = img.scaled(targetW, targetH,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation)

            return QPixmap.fromImage(finalImg)
        }

        private suspend fun loadIconThrottled(id: String, texturePath: String?, snapshotDir: VPath?, size: Int, tintColor: Long? = null): QPixmap? =
            iconLoadSemaphore.withPermit {
                withContext(Dispatchers.IO) { loadIcon(id, texturePath, snapshotDir, size, tintColor) }
            }

        private suspend fun loadItemTextureThrottled(
            id: String,
            texturePath: String?,
            animationJson: String?,
            snapshotDir: VPath?,
            size: Int,
            tintColor: Long? = null
        ): ItemTexture? =
            iconLoadSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    loadItemTexture(id, texturePath, animationJson, snapshotDir, size, tintColor)
                }
            }

        private fun loadTexture(textureRef: String?, snapshotDir: VPath?, width: Int, height: Int): QPixmap? {
            if (textureRef.isNullOrBlank() || snapshotDir == null) return null
            val cacheKey = "texture|$textureRef|$width|$height|${snapshotDir.toAbsolute()}"
            pixmapCache[cacheKey]?.let { return it }

            val parts = textureRef.split(':', limit = 2)
            if (parts.size != 2) return null
            val namespace = parts[0]
            val path = parts[1].removePrefix("textures/")
            val texturePath = snapshotDir.resolve("assets/textures/$namespace/$path")
            if (!texturePath.exists()) return null
            val pixmap = QPixmap()
            return if (pixmap.load(texturePath.toAbsolute().toString())) {
                val scaled = QPixmap(
                    pixmap.scaled(
                        width,
                        height,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.FastTransformation
                    )
                )
                pixmapCache[cacheKey] = scaled
                scaled
            } else {
                null
            }
        }

        private fun isNamespacedId(value: String): Boolean =
            value.contains(':') && value.none(Char::isWhitespace)

        private fun asJsonObjectOrNull(element: JsonElement?): JsonObject? = element as? JsonObject

        private fun asJsonArrayOrEmpty(element: JsonElement?): JsonArray =
            element as? JsonArray ?: JsonArray(emptyList())

        private fun primitiveContentOrNull(element: JsonElement?): String? =
            runCatching { (element as? JsonPrimitive)?.content }.getOrNull()

        private fun primitiveIntOrNull(element: JsonElement?): Int? =
            primitiveContentOrNull(element)?.toIntOrNull()

        private fun primitiveLongOrNull(element: JsonElement?): Long? =
            primitiveContentOrNull(element)?.toLongOrNull()

        private fun minecraftTooltipStyleForRarity(rarity: String?): TTooltipStyle {
            fun color(hex: String, alpha: Int = 190): QColor =
                QColor(hex).apply { setAlpha(alpha) }

            return when (rarity?.lowercase(Locale.ROOT)) {
                "uncommon" -> TTooltipStyle(
                    borderTop = color("#ffff55"),
                    borderBottom = color("#bfa53f")
                )
                "rare" -> TTooltipStyle(
                    borderTop = color("#55ffff"),
                    borderBottom = color("#2aa8c8")
                )
                "epic" -> TTooltipStyle(
                    borderTop = color("#ff55ff"),
                    borderBottom = color("#9f3fd0")
                )
                else -> TTooltipStyle()
            }
        }
    }

    data class SlotItemInfo(
        val id: String,
        val displayName: String?,
        val path: String,
        val texturePath: String?,
        val animationJson: String? = null,
        val rarity: String? = null,
        val tags: List<String> = emptyList(),
        val tintColor: Long? = null,
        val registryType: String? = null
    )

    private data class RecipeBinding(
        val componentId: String,
        val entries: List<RenderedValue>
    )

    private data class RecipeComponentLayout(
        val id: String,
        val category: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val label: String?
    )

    private data class RenderedValue(
        val refType: String,
        val valueType: String,
        val id: String,
        val amount: Long,
        val displayName: String?
    )

    private data class RecipeValuePreview(
        val id: String,
        val label: String,
        val texturePath: String?,
        val clickableItemId: String?
    )
}
