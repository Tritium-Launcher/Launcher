/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.qs
import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchFilters
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.search.SearchDetailPaneManager
import io.github.tritium_launcher.launcher.search.TritiumSearchService
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.Corner
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.QPoint
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QKeyEvent
import io.qt.gui.QMouseEvent
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*

class SearchEverywhereDialog(parent: QWidget? = null) : QDialog(parent) {
    private val searchInput = QLineEdit()
    private val tabButtons = mutableListOf<QPushButton>()
    private val resultList = QListWidget()
    private val detailContainer = QWidget()
    private val headerLabel = QLabel()
    private val subtextLabel = QLabel()
    private val bodyStack = QStackedWidget()
    private val placeholderPage = QLabel("Select a result to see details")
    private val actionsWidget = QWidget()
    private val actionsLayout = QVBoxLayout()
    private val paneManager = SearchDetailPaneManager(BuiltinRegistries.SearchResultRenderer)
    private val searchScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var searchJob: Job? = null
    private var currentResults = emptyList<SearchResult>()
    private var resultItemIndex = mutableListOf<Int>()
    private var activeTab = "all"
    private var dragPos = QPoint()

    private val tabIds: List<String> by lazy {
        val kinds = BuiltinRegistries.SearchIndexContributor.all()
            .flatMap { it.producedKinds }
            .distinct()
        listOf("all") + kinds
    }

    private val kindOrder: List<String> by lazy {
        BuiltinRegistries.SearchIndexContributor.all()
            .flatMap { it.producedKinds }
            .distinct()
    }

    private val kindDisplayNames: Map<String, String> by lazy {
        BuiltinRegistries.SearchIndexContributor.all()
            .flatMap { c -> c.kindDisplayNames.entries }
            .distinctBy { it.key }
            .associate { it.key to it.value }
    }

    private val debounceTimer = QTimer().apply {
        interval = 300
        isSingleShot = true
        timeout.connect { performSearch() }
    }

    init {
        objectName = "searchEverywhereDialog"
        modal = false
        resize(qs(720, 500))
        minimumSize = qs(480, 320)
        setProperty("keymapFocusGroup", "global")

        setWindowFlags(Qt.WindowType.FramelessWindowHint)
        setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)

        searchInput.objectName = "searchInput"
        searchInput.placeholderText = "Search everywhere\u2026"
        searchInput.textChanged.connect { debounceTimer.start() }

        val tabs = QWidget()
        tabs.objectName = "searchTabs"
        hBoxLayout(tabs) {
            contentsMargins = 0.m
            widgetSpacing = 0
            for (tabId in tabIds) {
                val btn = QPushButton(tabLabel(tabId)).apply {
                    setProperty("tabId", tabId)
                    objectName = "searchTab"
                    checkable = true
                    flat = true
                    clicked.connect {
                        tabButtons.forEach { it.isChecked = false }
                        isChecked = true
                        activeTab = tabId
                        debounceTimer.start()
                    }
                }
                tabButtons.add(btn)
                addWidget(btn)
            }
            tabButtons.firstOrNull()?.isChecked = true
            addStretch(1)
        }

        resultList.objectName = "searchResultList"
        resultList.setSpacing(0)
        resultList.setUniformItemSizes(false)
        resultList.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        resultList.customContextMenuRequested.connect { pos -> showContextMenu(pos) }
        resultList.currentRowChanged.connect { row -> onResultSelected(row) }
        resultList.itemActivated.connect { performDefaultAction() }

        headerLabel.objectName = "detailHeader"
        headerLabel.wordWrap = true
        subtextLabel.objectName = "detailSubtext"
        subtextLabel.wordWrap = true

        bodyStack.objectName = "detailBodyStack"
        placeholderPage.objectName = "detailPlaceholder"
        placeholderPage.setAlignment(Qt.AlignmentFlag.AlignCenter)
        bodyStack.addWidget(placeholderPage)

        val bodyScroll = QScrollArea().apply {
            objectName = "detailScroll"
            setWidget(bodyStack)
            setWidgetResizable(true)
            setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
            setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        }

        actionsWidget.objectName = "detailActions"
        actionsWidget.setVisible(false)
        actionsLayout.setContentsMargins(0.m)
        actionsLayout.setSpacing(6)
        actionsWidget.setLayout(actionsLayout)

        val detailTop = QWidget()
        vBoxLayout(detailTop) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(headerLabel)
            addWidget(subtextLabel)
        }

        vBoxLayout(detailContainer) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(detailTop)
            addWidget(bodyScroll, 1)
            addWidget(actionsWidget)
        }

        val leftPane = QWidget()
        leftPane.objectName = "searchLeftPane"
        vBoxLayout(leftPane) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(searchInput)
            addWidget(tabs)
            addWidget(resultList, 1)
        }

        val root = QWidget()
        root.objectName = "searchRoot"
        val rootLayout = QHBoxLayout(root)
        rootLayout.setContentsMargins(1, 1, 1, 1)
        rootLayout.setSpacing(0)
        rootLayout.addWidget(leftPane)
        rootLayout.addWidget(detailContainer, 1)

        vBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(root, 1)
        }

        applyStyles()
        destroyed.connect {
            searchScope.cancel()
        }
    }

    private fun applyStyles() {
        setThemedStyle {
            selector("#searchRoot") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface2)
                borderRadius(8)
            }
            selector("#searchLeftPane") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2, "right")
                borderRadius(8, Corner.TLeft)
                borderRadius(8, Corner.BLeft)
            }
            selector("#detailContainer") {
                backgroundColor(TColors.Surface0)
                borderRadius(8, Corner.TRight)
                borderRadius(8, Corner.BRight)
            }
            selector("#searchInput") {
                backgroundColor("transparent")
                color(TColors.Text)
                border()
                border(1, TColors.Surface2, "bottom")
                padding(10, 14, 10, 14)
                fontSize(15)
                fontWeight(500)
            }
            selector("#searchInput:focus") {
                border(1, TColors.Accent, "bottom")
            }
            selector("#searchTabs") {
                border(1, TColors.Surface2, "bottom")
            }
            selector("#searchTab") {
                backgroundColor("transparent")
                color(TColors.Subtext)
                border()
                border(2, "transparent", "bottom")
                padding(8, 18, 12, 18)
                fontSize(12)
                fontWeight(500)
            }
            selector("#searchTab:checked") {
                color(TColors.Text)
                border(2, TColors.Accent, "bottom")
            }
            selector("#searchTab:hover:!checked") {
                color(TColors.Text)
            }
            selector("#searchResultList") {
                backgroundColor("transparent")
                color(TColors.Text)
                border()
            }
            selector("#detailHeader") {
                color(TColors.Text)
                fontSize(16)
                fontWeight(700)
                padding(8, 12, 4, 12)
            }
            selector("#detailSubtext") {
                color(TColors.Subtext)
                fontSize(12)
                padding(0, 12, 8, 12)
                border(1, TColors.Surface2, "bottom")
            }
            selector("#detailPlaceholder") {
                color(TColors.Subtext)
                fontSize(13)
            }
            selector("#detailActions") {
                padding(6, 12, 6, 12)
            }
            selector("#detailScroll") {
                backgroundColor("transparent")
                border()
            }
            selector("#detailScroll > QWidget") {
                backgroundColor("transparent")
            }
            selector("#rawJsonContainer") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2)
                borderRadius(4)
            }
            selector("#categoryHeader") {
                color(TColors.Subtext)
                fontSize(10)
                fontWeight(700)
                padding(6, 10, 2, 10)
            }
            selector("#searchResultItem") {
                padding(6, 10)
            }
            selector("#searchResultItem:selected") {
                backgroundColor(TColors.SelectedUI)
            }
            selector("#resultIconBox") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface2)
                borderRadius(6)
            }
            selector("#resultName") {
                color(TColors.Text)
                fontSize(13)
                fontWeight(500)
            }
            selector("#resultDetail") {
                color(TColors.Subtext)
                fontSize(11)
            }

        }
    }

    private fun tabLabel(tabId: String): String = if (tabId == "all") "All"
        else kindDisplayNames[tabId] ?: tabId.replaceFirstChar { it.uppercase() }

    private fun performSearch() {
        val query = searchInput.text.trim()
        if (query.isBlank()) {
            currentResults = emptyList()
            populateList(emptyList())
            clearDetail()
            return
        }
        searchJob?.cancel()
        searchJob = searchScope.launch {
            try {
                val filters = if (activeTab == "all") null else SearchFilters(kind = activeTab)
                val results = TritiumSearchService.search(query, filters)
                withContext(Dispatchers.Main) {
                    currentResults = results
                    populateList(results)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                }
            }
        }
    }

    private fun populateList(results: List<SearchResult>) {
        resultList.clear()
        resultItemIndex.clear()
        clearDetail()

        if (activeTab == "all") {
            val grouped = results.groupBy { it.kind }
            var globalIdx = 0
            for (kind in kindOrder) {
                val items = grouped[kind] ?: continue
                val label = kindDisplayNames[kind] ?: kind.replaceFirstChar { it.uppercase() }
                val headerItem = QListWidgetItem(resultList)
                headerItem.setSizeHint(qs(0, 22))
                headerItem.setFlags(Qt.ItemFlag.NoItemFlags)
                resultItemIndex.add(-1)
                val headerWidget = QLabel(label).apply {
                    objectName = "categoryHeader"
                    wordWrap = false
                }
                resultList.setItemWidget(headerItem, headerWidget)
                for (result in items.take(3)) {
                    val item = QListWidgetItem(resultList)
                    item.setSizeHint(qs(0, 54))
                    resultItemIndex.add(globalIdx)
                    val row = ResultItemRow(result)
                    resultList.setItemWidget(item, row)
                    globalIdx++
                }
            }
        } else {
            for ((idx, result) in results.withIndex()) {
                val item = QListWidgetItem(resultList)
                item.setSizeHint(qs(0, 54))
                resultItemIndex.add(idx)
                val row = ResultItemRow(result)
                resultList.setItemWidget(item, row)
            }
        }

        if (resultList.count() > 0) {
            resultList.setCurrentRow(0)
        }
    }

    private fun onResultSelected(row: Int) {
        if (row < 0 || row >= resultItemIndex.size) {
            clearDetail()
            return
        }
        val resultIdx = resultItemIndex[row]
        if (resultIdx < 0 || resultIdx >= currentResults.size) {
            clearDetail()
            return
        }
        showDetail(currentResults[resultIdx])
    }

    private fun showDetail(result: SearchResult) {
        headerLabel.text = result.name
        subtextLabel.text = result.detail
        detailContainer.setMinimumWidth(paneManager.rendererFor(result)?.detailMinimumWidth ?: 0)

        for (i in 1 until bodyStack.count()) {
            val w = bodyStack.widget(i)
            bodyStack.removeWidget(w)
            w?.disposeLater()
        }

        try {
            val context = SearchDetailContext.Companion.empty()
            val pane = paneManager.buildDetailPane(result, context)
            if (pane != null) {
                bodyStack.addWidget(pane)
                bodyStack.setCurrentIndex(bodyStack.count() - 1)
            } else {
                placeholderPage.text = "No detail view available for ${result.kind}"
                bodyStack.setCurrentIndex(0)
            }
        } catch (e: Exception) {
            placeholderPage.text = "Failed to render detail: ${e.message ?: "unknown error"}"
            bodyStack.setCurrentIndex(0)
        }

        rebuildActions(result)
    }

    private fun clearDetail() {
        headerLabel.text = ""
        subtextLabel.text = ""
        detailContainer.setMinimumWidth(0)
        bodyStack.setCurrentIndex(0)
        actionsWidget.setVisible(false)
    }

    private fun rebuildActions(result: SearchResult) {
        val actions = BuiltinRegistries.SearchResultAction.all().filter { it.canActOn(result) }
        while (actionsLayout.count() > 0) {
            val item = actionsLayout.takeAt(0)
            item?.widget()?.dispose()
        }
        if (actions.isEmpty()) {
            actionsWidget.setVisible(false)
            return
        }
        actionsWidget.setVisible(true)
        for (action in actions) {
            val btn = QPushButton(action.label).apply {
                objectName = "detailActionBtn"
                clicked.connect {
                    searchScope.launch {
                        try {
                            action.execute(result)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            actionsLayout.addWidget(btn)
        }
    }

    private fun showContextMenu(pos: QPoint) {
        val item = resultList.itemAt(pos) ?: return
        val row = resultList.row(item)
        if (row < 0 || row >= resultItemIndex.size) return
        val resultIdx = resultItemIndex[row]
        if (resultIdx < 0 || resultIdx >= currentResults.size) return
        val result = currentResults[resultIdx]
        val actions = BuiltinRegistries.SearchResultAction.all().filter { it.canActOn(result) }
        if (actions.isEmpty()) return
        val menu = QMenu(this)
        for (action in actions) {
            menu.addAction(action.label)?.triggered?.connect {
                searchScope.launch {
                    try {
                        action.execute(result)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        menu.exec(resultList.mapToGlobal(pos))
    }

    private fun performDefaultAction() {
        val row = resultList.currentRow
        if (row >= 0) {
            onResultSelected(row)
        }
    }

    fun openAndFocus() {
        show()
        raise()
        activateWindow()
        searchInput.setFocus()
        searchInput.selectAll()
    }

    override fun mousePressEvent(event: QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            dragPos = event.globalPosition().toPoint() - frameGeometry().topLeft()
            event.accept()
        }
        super.mousePressEvent(event)
    }

    override fun mouseMoveEvent(event: QMouseEvent?) {
        if (event?.buttons()?.testFlag(Qt.MouseButton.LeftButton) == true) {
            move(event.globalPosition().toPoint() - dragPos)
            event.accept()
        }
        super.mouseMoveEvent(event)
    }

    override fun keyPressEvent(event: QKeyEvent?) {
        when (event?.key()) {
            Qt.Key.Key_Escape.value() -> { close(); return }
            Qt.Key.Key_Down.value() -> {
                val next = resultList.currentRow() + 1
                if (next < resultList.count()) resultList.setCurrentRow(next)
                return
            }
            Qt.Key.Key_Up.value() -> {
                val prev = resultList.currentRow() - 1
                if (prev >= 0) resultList.setCurrentRow(prev)
                return
            }
            Qt.Key.Key_Return.value(), Qt.Key.Key_Enter.value() -> {
                val row = resultList.currentRow
                if (row >= 0 && row < resultItemIndex.size && resultItemIndex[row] >= 0) {
                    performDefaultAction()
                }
                return
            }
        }
        super.keyPressEvent(event)
    }

    private class ResultItemRow(result: SearchResult) : QWidget() {
        private val iconLabel: QLabel
        private val iconBox: QWidget

        init {
            objectName = "searchResultItem"
            iconLabel = QLabel().apply {
                objectName = "resultIcon"
                setFixedSize(qs(32, 32))
                setAlignment(Qt.AlignmentFlag.AlignCenter)
            }
            iconBox = QWidget().apply {
                objectName = "resultIconBox"
                setFixedSize(qs(42, 42))
            }
            hBoxLayout(iconBox) {
                contentsMargins = 0.m
                widgetSpacing = 0
                addWidget(iconLabel, 0, Qt.AlignmentFlag.AlignCenter)
            }
            val nameLabel = QLabel(result.name).apply { objectName = "resultName" }
            val detailLabel = QLabel(result.detail).apply { objectName = "resultDetail" }
            val textCol = QWidget()
            vBoxLayout(textCol) {
                contentsMargins = 0.m
                widgetSpacing = 0
                addWidget(nameLabel)
                addWidget(detailLabel)
            }
            hBoxLayout(this) {
                contentsMargins = 8.m
                widgetSpacing = 8
                addWidget(iconBox)
                addWidget(textCol, 1)
            }

            loadIcon(result)
        }

        private fun loadIcon(result: SearchResult) {
            val icon = resultIcon(result)
            if (icon != null && !icon.isNull) {
                iconLabel.setPixmap(icon)
                iconBox.show()
            } else {
                iconBox.hide()
            }
        }

        private fun resultIcon(result: SearchResult): QPixmap? {
            return when (result.kind) {
                "registry_entry" -> {
                    val project = ProjectMngr.activeProject
                    if (project != null) {
                        SearchIconLoader.loadItemIcon(project, result.path, 32)
                    } else TIcons.ItemBrowser.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                }
                "recipe" -> {
                    val project = ProjectMngr.activeProject
                    val outputId = result.outputId
                    val itemIcon = if (project != null && outputId != null) {
                        SearchIconLoader.loadItemIcon(project, outputId, 32)
                    } else null
                    itemIcon ?: TIcons.RecipeBuilder.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                }
                "file", "config" -> {
                    val project = ProjectMngr.activeProject
                    val px = if (project != null) {
                        val vpath = VPath.parse(result.path)
                        FileTypeDescriptor.primary(vpath, project)?.icon?.pixmap(32, 32)
                    } else null
                    px ?: TIcons.File.scaled(32, 32, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
                }
                else -> null
            }
        }
    }

    companion object {
        private var instance: SearchEverywhereDialog? = null

        fun open() {
            val existing = instance
            if (existing != null && existing.isVisible) {
                existing.openAndFocus()
                return
            }
            val dialog = SearchEverywhereDialog()
            dialog.destroyed.connect {
                if (instance === dialog) instance = null
            }
            instance = dialog
            dialog.openAndFocus()
        }
    }
}
