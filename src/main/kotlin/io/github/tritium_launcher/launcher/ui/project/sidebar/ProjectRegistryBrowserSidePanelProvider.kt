package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.extension.kubejs.KubeJSIntelligenceService
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.registrydb.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TTooltip
import io.github.tritium_launcher.launcher.ui.widgets.TTooltipStyle
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*
import kotlin.math.ceil
import kotlin.math.max


/**
 * JEI-like registry browser panel with fixed-page item browsing and bottom search.
 */
class ProjectRegistryBrowserSidePanelProvider : SidePanelProvider, SidePanelTitleBarAccessoryProvider {
    override val id: String = "registry_browser"
    override val displayName: String = "Item Browser"
    override val icon: QIcon = TIcons.SmallGrass.icon
    override val order: Int = 15
    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.BottomDockWidgetArea
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
        return QToolButton().apply {
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

    private class Controller(
        val project: ProjectBase,
        private val dock: DockWidget,
        private val preferredArea: Qt.DockWidgetArea
    ) {
        val root = QWidget()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private val logger = logger()
        private val outerLayout = QVBoxLayout(root)
        private val header = QWidget(root)
        private val headerLayout = QHBoxLayout(header)
        private val prevPageButton = QToolButton(header)
        private val pageLabel = QLabel(header)
        private val nextPageButton = QToolButton(header)

        private val mainContainer = QWidget(root)
        private val mainLayout = QHBoxLayout(mainContainer).apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)
        }
        private val leftContainer = QWidget(mainContainer)
        private val leftLayout = QVBoxLayout(leftContainer).apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)
        }
        private val viewport = GridViewport(this, leftContainer)
        private val viewportLayout = QVBoxLayout(viewport)
        private val statusLabel = QLabel(viewport)
        private val gridWidget = QWidget(viewport)
        private val gridLayout = QGridLayout(gridWidget).apply {
            sizeConstraint = QLayout.SizeConstraint.SetNoConstraint
        }

        internal val detailPanel = DetailPanel(mainContainer, this)

        private val footer = QWidget(leftContainer)
        private val footerLayout = QHBoxLayout(footer)
        private val searchField = QLineEdit(footer)
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
        private val slotButtons = mutableListOf<SlotButton>()
        private var snapshotDir: VPath? = null
        private var restoreAfterLoad: String? = null

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

            searchField.placeholderText = "Search items..."
            searchField.clearButtonEnabled = true
            searchField.minimumHeight = 24

            footerLayout.addWidget(searchField, 1)

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

        fun start() {
            RegistryRefreshService.startWatching(project)
            prevPageButton.clicked.connect { changePage(currentPage - 1) }
            nextPageButton.clicked.connect { changePage(currentPage + 1) }
            searchField.textChanged.connect {
                searchText = searchField.text.trim()
                currentPage = 0
                restoreAfterLoad = null
                searchDebounce.start()
            }
            searchDebounce.timeout.connect { refreshFromDatabase() }

            scope.launch {
                TritiumEventBus.events.collect { event ->
                    if (event is TritiumEvent.RegistryFocusRequest) {
                        searchField.text = event.id
                        searchText = event.id
                        currentPage = 0
                        refreshFromDatabase()
                        dock.show()
                        dock.raise()
                        dock.setFocus()
                    }
                }
            }

            scope.launch {
                RegistryRefreshService.dbUpdated
                    .filter { it.projectDir.toString() == project.projectDir.toString() }
                    .collect {
                        RegistryDatabase.invalidateCachedConnection()
                        KubeJSIntelligenceService.invalidateConnection()
                        refreshFromDatabase()
                    }
            }

            val state = loadState(project)
            if (state.lastSearchText != null) {
                searchText = state.lastSearchText
                searchField.text = state.lastSearchText
            }
            if (state.lastSelectedId != null) {
                restoreAfterLoad = state.lastSelectedId
            }
            if (state.lastPage > 0) {
                currentPage = state.lastPage
            }

            val initialArea = (dock.parent() as? QMainWindow)?.dockWidgetArea(dock) ?: preferredArea
            updateLayoutForArea(initialArea)

            updatePageGeometry()
            refreshFromDatabase()
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

        fun cleanup() {
            scope.cancel()
        }

        private fun changePage(page: Int) {
            if (totalPages() <= 1) return
            val next = ((page % totalPages()) + totalPages()) % totalPages()
            if (next == currentPage) return
            currentPage = next
            val offset = currentPage * itemsPerPage
            scope.launch {
                val items = withContext(Dispatchers.IO) {
                    RegistryDatabase.searchItems(project, searchText, offset, itemsPerPage)
                }
                visibleItems = items
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
                        snapshotDir = status.manifestPath.parent()
                        val (count, items) = withContext(Dispatchers.IO) {
                            clampPage()
                            val offset = currentPage * itemsPerPage
                            val count = RegistryDatabase.countItems(project, searchText)
                            val items = RegistryDatabase.searchItems(project, searchText, offset, itemsPerPage)
                            count to items
                        }
                        totalItems = count
                        visibleItems = items
                        rebuildSlots()
                        updateHeader()

                        val restoreId = restoreAfterLoad
                        if (restoreId != null) {
                            restoreAfterLoad = null
                            if (items.any { it.id == restoreId }) {
                                selectItem(restoreId, addToHistory = false)
                            }
                        }
                    }

                    else -> {
                        snapshotDir = null
                        visibleItems = emptyList()
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

        private fun rebuildSlots() {
            if (visibleItems.isEmpty()) {
                clearGrid()
                showStatus(
                    if (searchText.isBlank()) {
                        "No items found.\nLaunch the game with the Companion mod to generate the item registry."
                    } else {
                        "No items match \"$searchText\"."
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

            val newCount = visibleItems.size
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
                    val item = visibleItems[index]
                    button.setItem(item, selectedItemId == item.id, snapshotDir)
                    button.isVisible = true
                    if (columnsChanged && index < oldCount) {
                        gridLayout.addWidget(button, index / columns, index % columns)
                    }
                } else {
                    button.isVisible = false
                }
            }

            if (slotButtons.size > newCount + 64) {
                val toRemove = slotButtons.subList(newCount, slotButtons.size)
                toRemove.forEach {
                    gridLayout.removeWidget(it)
                    it.disposeLater()
                }
                toRemove.clear()
            }
        }

        private fun updateGridSlots() {
            if (visibleItems.isEmpty()) {
                clearGrid()
                showStatus(
                    if (searchText.isBlank()) {
                        "No items found.\nLaunch the game with the Companion mod to generate the item registry."
                    } else {
                        "No items match \"$searchText\"."
                    }
                )
                return
            }

            val newCount = visibleItems.size
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
                    val item = visibleItems[index]
                    button.setItem(item, selectedItemId == item.id, snapshotDir)
                    button.isVisible = true
                } else {
                    button.isVisible = false
                }
            }

            statusLabel.isVisible = false
            gridWidget.isVisible = true
        }

        fun selectItem(id: String, addToHistory: Boolean = true) {
            if (selectedItemId == id) return
            selectedItemId = id
            updateSelectionState()
            scope.launch {
                val result = withContext(Dispatchers.IO) { RegistryDatabase.itemDetailWithRecipes(project, id) }
                if (result.detail != null) {
                    detailPanel.setItem(result.detail, result.recipeUsage, result.recipeDetails, snapshotDir, scope)
                }
                saveState(
                    project, RegistryBrowserState(
                        lastSelectedId = id,
                        lastSearchText = searchText.takeIf { it.isNotBlank() },
                        lastPage = currentPage
                    )
                )
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
        private val itemIconLabel = QLabel()
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
            itemIconLabel.setAlignment(Qt.AlignmentFlag.AlignCenter)
            itemIconLabel.scaledContents = false

            val textColumn = QWidget()
            val textColumnLayout = QVBoxLayout(textColumn)
            textColumnLayout.setContentsMargins(0, 0, 0, 0)
            textColumnLayout.setSpacing(4)

            itemNameLabel.font = QFont(itemNameLabel.font).apply { setPointSize(12); setBold(true) }
            itemNameLabel.wordWrap = true

            itemNamespaceLabel.styleSheet = "color: ${TColors.Accent}; font-weight: bold;"

            itemIdLabel.styleSheet = "color: ${TColors.Subtext};"
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

        private val skipKeys = setOf("id", "namespace", "path", "displayName", "tags", "texturePath", "rawJson")

        private fun addDynamicField(key: String, value: JsonElement, indent: Int) {
            val indentPx = indent * 16
            val displayKey = key.replaceFirstChar { it.uppercase() }

            when (value) {
                is JsonPrimitive -> {
                    val displayValue = if (value.isString) value.content else value.toString()
                    val label = QLabel("${"  ".repeat(indent)}${displayKey}: $displayValue").apply {
                        styleSheet = "color: ${TColors.Text}; padding-left: ${indentPx}px;"
                        wordWrap = true
                    }
                    dynamicLabels.add(label)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, label)
                }

                is JsonArray -> {
                    val label = QLabel("${"  ".repeat(indent)}${displayKey}:").apply {
                        styleSheet = "color: ${TColors.Text}; padding-left: ${indentPx}px;"
                    }
                    dynamicLabels.add(label)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, label)
                    value.forEachIndexed { index, element ->
                        addDynamicField("[$index]", element, indent + 1)
                    }
                }

                is JsonObject -> {
                    val label = QLabel("${"  ".repeat(indent)}${displayKey}:").apply {
                        styleSheet = "color: ${TColors.Accent}; font-weight: bold; padding-left: ${indentPx}px;"
                    }
                    dynamicLabels.add(label)
                    dynamicPropsLayout.insertWidget(dynamicPropsLayout.count() - 1, label)
                    value.forEach { (subKey, subValue) ->
                        addDynamicField(subKey, subValue, indent + 1)
                    }
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
            showDetailTab()

            itemNameLabel.text = item.displayName ?: item.path
            itemIdLabel.text = item.id
            itemNamespaceLabel.text = item.namespace

            // Create tag chips
            while (tagsLayout.count() > 1) {
                tagsLayout.takeAt(0)?.widget()?.disposeLater()
            }
            item.tags.forEach { tag ->
                val chip = QLabel(tag).apply {
                    styleSheet = """
                        QLabel {
                            background-color: ${TColors.Surface2};
                            color: ${TColors.Text};
                            border-radius: 4px;
                            padding: 2px 6px;
                            font-size: 10px;
                        }
                    """.trimIndent()
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

            jsonObj?.forEach { key, value ->
                if (key in skipKeys) return@forEach
                addDynamicField(key, value, 0)
            }

            val texPath = item.texturePath
            val targetId = item.id
            val displayText = item.displayName?.take(1) ?: "?"
            itemIconLabel.pixmap = QPixmap()
            itemIconLabel.text = displayText
            scope.launch {
                val pixmap = withContext(Dispatchers.IO) { loadIcon(targetId, texPath, snapshotDir, 64) }
                if (isActive) {
                    if (pixmap != null) {
                        itemIconLabel.pixmap = pixmap
                        itemIconLabel.text = ""
                    }
                }
            }

            refreshCatalysts(item.id, scope)
            refreshRecipes(recipeDetails, snapshotDir, scope)
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
                recipeLayout.addWidget(QLabel("No recipes found.").apply {
                    styleSheet = "color: ${TColors.Subtext};"
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
                outputs.firstOrNull()?.let { asJsonObjectOrNull(it) }?.let { primitiveContentOrNull(it.get("id")) }
            } else null

            // Load recipe type icon
            val recipeTypeIcon = recipe.recipeType?.let { rt ->
                scope.launch {
                    val pixmap = withContext(Dispatchers.IO) { loadIcon("recipe_type:$rt", null, snapshotDir, 24) }
                    if (isActive && pixmap != null) {
                        iconLabel.pixmap = pixmap
                    }
                }
            }

            // Load output item icon as fallback
            iconLoadJob = if (targetId != null && recipeTypeIcon == null) {
                val outputId = targetId
                scope.launch {
                    val pixmap = withContext(Dispatchers.IO) { loadIcon(outputId, null, snapshotDir, 24) }
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

        private fun loadIcon() {
            val item = catalystItem ?: return
            iconLoadJob?.cancel()
            val id = item.id
            val texPath = item.texturePath
            val snapDir = snapshotDir
            iconLoadJob = scope.launch {
                val pixmap = withContext(Dispatchers.IO) { loadIcon(id, texPath, snapDir, 64) }
                if (isActive) {
                    if (pixmap != null) {
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
        private var itemTexturePath: String? = null
        private var itemSnapshotDir: VPath? = null
        internal var itemTooltipText: String = ""
        internal var itemTooltipStyle: TTooltipStyle = TTooltipStyle()
        private var pressPos: QPoint? = null
        private var iconLoadJob: Job? = null

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

        private fun startDrag() {
            TTooltip.hide()
            val drag = QDrag(this)
            val mimeData = QMimeData()
            mimeData.setText(itemId!!)
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

        fun setItem(item: RegistryItemSummary, selected: Boolean, snapshotDir: VPath?) {
            itemId = item.id
            itemTexturePath = item.texturePath
            itemSnapshotDir = snapshotDir
            itemTooltipStyle = itemTooltipStyleProvider(item)
            isChecked = selected
            itemTooltipText = buildString {
                append(item.displayName ?: item.id)
                append("\n")
                append(item.id)
                if (item.tags.isNotEmpty()) {
                    append("\n")
                    append(item.tags.joinToString(", "))
                }
            }
            setProperty("tt_style", itemTooltipStyle)
            toolTip = itemTooltipText
            iconLoadJob?.cancel()

            val cacheKey = "${item.id}|${item.texturePath}|64|${currentDpr(null)}|${snapshotDir?.toAbsolute()}"
            val cached = pixmapCache[cacheKey]
            if(cached != null) {
                icon = QIcon(cached)
                iconSize = QSize(32, 32)
                text = ""
                iconLoadJob = null
                return
            }

            iconLoadJob = scope.launch {
                val pixmap = withContext(Dispatchers.IO) { loadIcon(item.id, item.texturePath, snapshotDir, 64) }
                if (isActive) {
                    if (pixmap != null) {
                        icon = QIcon(pixmap)
                        iconSize = QSize(32, 32)
                        text = ""
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

    companion object {
        private const val SLOT_SIZE = 36
        private const val STATE_FILE_NAME = "registry-browser.json"
        private val controllers = WeakHashMap<DockWidget, Controller>()
        private val recipeJson = Json { ignoreUnknownKeys = true }
        private val pixmapCache = object : LinkedHashMap<String, QPixmap>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String?, QPixmap?>?): Boolean = size > 512
        }
        private val stateJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
        var itemTooltipStyleProvider: (RegistryItemSummary) -> TTooltipStyle = { item ->
            minecraftTooltipStyleForRarity(item.rarity)
        }

        @Serializable
        private data class RegistryBrowserState(
            val lastSelectedId: String? = null,
            val lastSearchText: String? = null,
            val lastPage: Int = 0
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

        private fun loadIcon(id: String, texturePath: String?, snapshotDir: VPath?, size: Int): QPixmap? {
            if (snapshotDir == null) return null
            val dpr = currentDpr(QWidget(null))
            val physicalSize = (size * dpr).toInt()

            val cacheKey = "$id|$texturePath|$size|$dpr|${snapshotDir.toAbsolute()}"
            pixmapCache[cacheKey]?.let { return it }

            val candidates = buildList {
                val parts = id.split(':')
                if (parts.size == 2) {
                    val namespace = parts[0]
                    val path = parts[1]
                    // Preferred candidates FIRST (overrides DB path)
                    add("icons/${namespace}/${path}.png")
                    add("icons/${namespace}_${path.replace('/', '_')}.png")
                }

                texturePath?.let { add(it) }

                if (parts.size == 2) {
                    val namespace = parts[0]
                    val path = parts[1]
                    addAll(
                        listOf(
                            "assets/textures/${namespace}/item/${path}.png",
                            "assets/textures/${namespace}/block/${path}.png",
                            "assets/${namespace}/textures/item/${path}.png",
                            "assets/${namespace}/textures/block/${path}.png"
                        )
                    )
                }
            }

            for (relPath in candidates) {
                val iconPath = snapshotDir.resolve(relPath)
                if (iconPath.exists()) {
                    val pixmap = QPixmap()
                    if (pixmap.load(iconPath.toAbsolute().toString())) {
                        val scaled = scaledHighQuality(pixmap, physicalSize, physicalSize)
                        scaled.setDevicePixelRatio(dpr)
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
