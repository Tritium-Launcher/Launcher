package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.mod.*
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.source.*
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
import io.github.tritium_launcher.launcher.ui.project.editor.panes.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.TMultiStateCategoryComboBox
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ModBrowserSidePanelProvider : SidePanelProvider {
    override val id: String = "mod_browser"
    override val displayName: String = "Mod Browser"
    override var icon: QIcon? = QIcon(TIcons.Search)
    override val order: Int = 7
    override val closeable: Boolean = true
    override val floatable: Boolean = true
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.LeftDockWidgetArea
    override val allowSplit: Boolean = false

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(ModBrowserSidePanel(project, dock, this))
        return dock
    }

    override fun onDockCreated(project: ProjectBase, editorArea: EditorArea, dock: DockWidget, onStateChanged: () -> Unit) {
        val panel = dock.widget() as? ModBrowserSidePanel
        panel?.onOpenDetailRequested = { modId, title, _ ->
            ModDetailMeta.register(modId, title)
            editorArea.openEditorPane(
                provider = ModDetailPaneProvider,
                title = title,
                paneFactory = { ModDetailPane(it, modId = modId) }
            )
        }
    }
}

class ModBrowserSidePanel(
    private val project: ProjectBase,
    private val dock: DockWidget? = null,
    private val provider: ModBrowserSidePanelProvider? = null
) : QWidget() {
    var onOpenDetailRequested: ((modId: String, title: String, iconUrl: String?) -> Unit)? = null

    companion object {
        private const val PAGE_SIZE = 25
        private val EMPTY_ICON = QIcon(TIcons.Search)
    }

    private val logger = logger()
    private val state = ModBrowserState.forProject(project)
    private val sources = BuiltinRegistries.ModSource
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        defaultRequest {
            header("User-Agent", ClientIdentity.userAgent)
            header("X-Client-Info", ClientIdentity.clientInfoHeader)
        }
    }

    private val availableItemsById = linkedMapOf<String, QListWidgetItem>()
    private val searchResultsById = linkedMapOf<String, ModSearchResult>()
    private val availableRowWidgets = linkedMapOf<String, AvailableRowWidgets_>()
    private val queuedRowWidgets = linkedMapOf<String, QueuedRowWidgets_>()
    private val iconCache get() = state.iconCache
    private val dominantColorCache get() = state.dominantColorCache
    private val iconLoadSemaphore = Semaphore(8)

    private var activeContext: ModBrowserContext? = null
    private var activeSource: ModSource? = null
    private var selectedResultId: String? = null
    private var totalHits: Int = 0
    private var nextOffset: Int = 0
    private var lastQueryText: String = ""
    private var lastIncludedCategories: Set<String> = emptySet()
    private var lastExcludedCategories: Set<String> = emptySet()
    private var isLoadingPage: Boolean = false
    private var hasMoreResults: Boolean = false
    private var lastPageLoadMs: Long = 0L
    private var searchGeneration: Int = 0
    private var searchJob: Job? = null
    private var currentSupportMessage: String? = null

    private val container = qWidget()
    private var suppressSelectionEvents = false
    private val searchField = QLineEdit()
    private val searchButton = pushButton { text = "Search" }
    private val categoryCombo = TMultiStateCategoryComboBox()
    private val availableList = QListWidget().apply {
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground)
    }
    private val queuedList = QListWidget()
    private val downloadQueuedButton = pushButton { text = "Download Queue" }
    private val removeQueuedButton = pushButton { text = "Remove Selected" }
    private val statusLabel = label("Ready") { wordWrap = true }

    init {
        AnimatedScrollController.attach(availableList)
        AnimatedScrollController.attach(queuedList)

        vBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)

            addWidget(container.apply {
                vBoxLayout(this) {
                    setContentsMargins(4, 4, 4, 4)
                    setSpacing(6)

                    addWidget(qWidget().also { row ->
                        hBoxLayout(row) {
                            setContentsMargins(0, 0, 0, 0)
                            setSpacing(6)
                            addWidget(searchField, 1)
                            addWidget(searchButton)
                        }
                    })

                    addWidget(categoryCombo)
                    addWidget(qWidget().also { listPane ->
                        vBoxLayout(listPane) {
                            setContentsMargins(0, 0, 0, 0)
                            setSpacing(6)
                            addWidget(label("Mods to Download"))
                            addWidget(availableList, 1)
                        }
                    }, 1)
                    addWidget(qWidget().also { queuedPane ->
                        vBoxLayout(queuedPane) {
                            setContentsMargins(0, 0, 0, 0)
                            setSpacing(6)
                            addWidget(label("Selected for Download"))
                            addWidget(queuedList, 1)
                            addWidget(qWidget().also { row ->
                                hBoxLayout(row) {
                                    setContentsMargins(0, 0, 0, 0)
                                    setSpacing(6)
                                    addWidget(downloadQueuedButton)
                                    addWidget(removeQueuedButton)
                                }
                            })
                        }
                    })
                }
            }, 1)

            addWidget(statusLabel)
        }

        searchField.placeholderText = "Search Mods"
        listOf(availableList, queuedList).forEach { list ->
            list.iconSize = QSize(40, 40)
            list.uniformItemSizes = false
            list.horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        }
        availableList.apply {
            objectName = "availableModList"
            selectionMode = QAbstractItemView.SelectionMode.NoSelection
            verticalScrollMode = QAbstractItemView.ScrollMode.ScrollPerPixel
        }
        queuedList.selectionMode = QAbstractItemView.SelectionMode.SingleSelection

        searchButton.onClicked { startFreshSearch() }
        searchField.returnPressed.connect { startFreshSearch() }
        categoryCombo.onSelectionChanged = { startFreshSearch() }
        availableList.currentItemChanged.connect { current, _ ->
            if (suppressSelectionEvents) return@connect
            selectedResultId = current?.data(Qt.ItemDataRole.UserRole) as? String
            updateSelectedRowGradient()
            val resultId = selectedResultId ?: return@connect
            val result = searchResultsById[resultId] ?: return@connect
            onOpenDetailRequested?.invoke(resultId, result.title, result.iconUrl)
        }
        availableList.itemClicked.connect { item ->
            val resultId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            val result = searchResultsById[resultId] ?: return@connect
            selectedResultId = resultId
            updateSelectedRowGradient()
            onOpenDetailRequested?.invoke(resultId, result.title, result.iconUrl)
        }
        availableList.itemDoubleClicked.connect { item ->
            val resultId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            if (state.queuedDownloads.containsKey(resultId)) {
                removeQueuedDownload(resultId)
            } else {
                queueResult(resultId)
            }
        }
        queuedList.currentItemChanged.connect { current, _ ->
            val resultId = current?.data(Qt.ItemDataRole.UserRole) as? String
            selectedResultId = resultId
            updateSelectedRowGradient()
            removeQueuedButton.isEnabled = queuedList.currentRow() >= 0
        }
        availableList.verticalScrollBar()?.valueChanged?.connect { value ->
            val now = System.currentTimeMillis()
            if (now - lastPageLoadMs < 350) return@connect
            val bar = availableList.verticalScrollBar()
            if (bar != null && bar.maximum() > 0 && value >= bar.maximum() - 2) {
                lastPageLoadMs = now
                loadNextPage()
            }
        }
        downloadQueuedButton.onClicked { downloadQueue() }
        removeQueuedButton.onClicked { removeQueuedSelection() }

        downloadQueuedButton.isEnabled = false
        removeQueuedButton.isEnabled = false

        projectContext()?.let { ctx ->
            val source = resolveSource(ctx) ?: return@let
            activeContext = ctx
            activeSource = source
            provider?.icon = QIcon(source.icon)
            val support = source.support(ctx)
            currentSupportMessage = support.message
            if (!support.available) {
                statusLabel.text = support.message ?: "Source unavailable"
                return@let
            }
            statusLabel.text = "Ready"
            ioScope.launch {
                val categories = runCatching { source.getCategories(ctx) }.getOrElse {
                    emptyList()
                }
                runOnGuiThread {
                    categoryCombo.setEntries(categories.map { it.id to it.displayName })
                    startFreshSearch()
                }
            }
        } ?: run {
            statusLabel.text = "The Mod Browser requires a typed Modpack project."
        }

        state.queuedDownloads.values.forEach { addQueuedDownloadItem(it) }
        updateQueueButtons()
    }

    private fun projectContext(): ModBrowserContext? {
        val meta = ((project as? Project<*>)?.typedMeta as? ModpackMeta) ?: return null
        return ModBrowserContext(
            project = project,
            minecraftVersion = meta.minecraftVersion,
            modLoaderId = meta.loader
        )
    }

    private fun resolveSource(context: ModBrowserContext): ModSource? {
        val sourceId = ((project as? Project<*>)?.typedMeta as? ModpackMeta)?.source
        return sourceId?.let { id -> sources.all().find { it.id == id } }
    }

    private fun startFreshSearch() {
        val context = activeContext ?: return
        val source = activeSource ?: return
        if (!source.support(context).available) return

        lastQueryText = searchField.text.trim()
        val includedCategories = categoryCombo.includedIds()
        val excludedCategories = categoryCombo.excludedIds()
        lastIncludedCategories = includedCategories
        lastExcludedCategories = excludedCategories
        nextOffset = 0
        totalHits = 0
        hasMoreResults = true
        searchResultsById.clear()
        availableItemsById.clear()
        selectedResultId = null
        searchGeneration++
        suppressSelectionEvents = true
        availableList.clear()
        suppressSelectionEvents = false
        availableRowWidgets.clear()
        loadPage(reset = true)
    }

    private fun loadNextPage() {
        if (!hasMoreResults || isLoadingPage) return
        loadPage(reset = false)
    }

    private fun loadPage(reset: Boolean) {
        val context = activeContext ?: return
        val source = activeSource ?: return
        if (isLoadingPage) return

        isLoadingPage = true
        val offset = nextOffset
        searchJob?.cancel()
        searchJob = ioScope.launch {
            runOnGuiThread {
                statusLabel.text = if (offset == 0) "Searching ${source.displayName}..." else "Loading more results..."
            }
            val page = runCatching {
                source.search(
                    context = context,
                    query = ModSearchQuery(
                        text = lastQueryText,
                        includedCategories = lastIncludedCategories,
                        excludedCategories = lastExcludedCategories,
                        offset = offset,
                        limit = PAGE_SIZE
                    )
                )
            }
            runOnGuiThread {
                isLoadingPage = false
                val resolved = page.getOrNull()
                if (resolved == null) {
                    statusLabel.text = page.exceptionOrNull()?.message ?: "Search failed"
                    if (reset) availableList.clear()
                    return@runOnGuiThread
                }

                totalHits = resolved.total
                nextOffset = offset + resolved.results.size
                hasMoreResults = nextOffset < totalHits && resolved.results.isNotEmpty()
                statusLabel.text = "Showing ${searchResultsById.size + resolved.results.size} of $totalHits"

                suppressSelectionEvents = true
                availableList.updatesEnabled = false
                val newResults = mutableListOf<ModSearchResult>()
                try {
                    resolved.results.forEach { result ->
                        if (searchResultsById.containsKey(result.id)) return@forEach
                        searchResultsById[result.id] = result
                        state.resultsCache[result.id] = result
                        addAvailableResultItem(result)
                        queueIconLoad(result.id, result.iconUrl)
                        newResults.add(result)
                    }
                } finally {
                    availableList.updatesEnabled = true
                    suppressSelectionEvents = false
                }

                val totalCached = state.detailsCache.size
                val remainingBudget = 100 - totalCached
                if (remainingBudget > 0 && newResults.isNotEmpty()) {
                    ioScope.launch {
                        newResults.take(remainingBudget).forEach { result ->
                            launch {
                                runCatching { fetchDetails(context, source, result.id) }
                                runCatching { fetchVersions(context, source, result.id) }
                                preloadIcon(result.iconUrl)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addAvailableResultItem(result: ModSearchResult) {
        val item = QListWidgetItem().apply {
            setData(Qt.ItemDataRole.UserRole, result.id)
        }
        availableItemsById[result.id] = item
        availableList.addItem(item)
        refreshAvailableResultItem(result.id)
    }

    private fun refreshAvailableResultItem(resultId: String) {
        val result = searchResultsById[resultId] ?: return
        val item = availableItemsById[resultId] ?: return
        disposeItemWidget(availableList, item)
        val row = createAvailableRow(result)
        availableRowWidgets[result.id] = row
        availableList.setItemWidget(item, row.root)
        item.setSizeHint(row.root.sizeHint())
    }

    private fun createAvailableRow(result: ModSearchResult): AvailableRowWidgets_ {
        val icon = label {
            setFixedSize(40, 40)
            scaledContents = true
            pixmap = listIconPixmap(result.iconUrl)
        }
        val title = label(result.title) {
            val titleFont = font()
            titleFont.setBold(state.queuedDownloads.containsKey(result.id))
            font = titleFont
        }
        val meta = label(buildAvailableMetaText(result)) {
            styleSheet = "color: ${TColors.Subtext};"
        }
        val root = object : QWidget() {
            override fun sizeHint(): QSize {
                val hint = super.sizeHint()
                return QSize(hint.width(), maxOf(hint.height(), 48))
            }
        }.also { widget ->
            widget.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
            hBoxLayout(widget) {
                setContentsMargins(6, 4, 6, 4)
                setSpacing(8)
                addWidget(icon, 0, Qt.AlignmentFlag.AlignVCenter)
                addWidget(qWidget().also { textCol ->
                    vBoxLayout(textCol) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(2)
                        addWidget(title)
                        if (meta.text().isNotBlank()) addWidget(meta)
                    }
                }, 1)
            }
        }
        return AvailableRowWidgets_(root = root, iconLabel = icon)
    }

    private fun addQueuedDownloadItem(queued: QueuedDownload) {
        val item = QListWidgetItem().apply {
            setData(Qt.ItemDataRole.UserRole, queued.projectId)
        }
        queuedList.addItem(item)
        val row = createQueuedRow(queued)
        queuedRowWidgets[queued.projectId] = row
        queuedList.setItemWidget(item, row.root)
        item.setSizeHint(row.root.sizeHint())
    }

    private fun createQueuedRow(queued: QueuedDownload): QueuedRowWidgets_ {
        val icon = label {
            setFixedSize(40, 40)
            scaledContents = true
            pixmap = listIconPixmap(queued.iconUrl)
        }
        val title = label(queued.title) {
            val titleFont = font()
            titleFont.setBold(true)
            font = titleFont
        }
        val version = label(queued.versionLabel) {
            styleSheet = "color: ${TColors.Subtext};"
        }
        val infoText = buildQueueInfoText(queued)
        val info = label(infoText) {
            styleSheet = "color: ${TColors.Subtext};"
            isVisible = infoText.isNotBlank()
        }
        val errorText = buildQueueErrorText(queued)
        val error = label(errorText) {
            styleSheet = "color: ${TColors.Error};"
            wordWrap = true
            isVisible = errorText.isNotBlank()
        }
        val root = object : QWidget() {
            override fun sizeHint(): QSize {
                val hint = super.sizeHint()
                return QSize(hint.width(), maxOf(hint.height(), 48))
            }
        }.also { widget ->
            widget.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
            hBoxLayout(widget) {
                setContentsMargins(6, 4, 6, 4)
                setSpacing(8)
                addWidget(icon, 0, Qt.AlignmentFlag.AlignTop)
                addWidget(qWidget().also { textCol ->
                    vBoxLayout(textCol) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(2)
                        addWidget(title)
                        addWidget(version)
                        if (info.isVisible) addWidget(info)
                        if (error.isVisible) addWidget(error)
                    }
                }, 1)
            }
        }
        return QueuedRowWidgets_(root = root, iconLabel = icon)
    }

    private fun extractDominantColor(pixmap: QPixmap): Triple<Int, Int, Int>? {
        val small = pixmap.scaled(4, 4, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.SmoothTransformation)
        if (small.isNull) return null
        val image = small.toImage() ?: return null
        if (image.isNull) return null
        for (y in 0 until image.height()) {
            for (x in 0 until image.width()) {
                val argb = image.pixel(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha >= 128) {
                    return Triple(
                        (argb ushr 16) and 0xFF,
                        (argb ushr 8) and 0xFF,
                        argb and 0xFF
                    )
                }
            }
        }
        return null
    }

    private fun updateSelectedRowGradient() {
        for (i in 0 until availableList.count()) {
            availableList.item(i)?.setBackground(QBrush())
        }
        for (i in 0 until queuedList.count()) {
            queuedList.item(i)?.setBackground(QBrush())
        }
        val id = selectedResultId ?: return
        val result = searchResultsById[id] ?: return
        val iconUrl = result.iconUrl ?: return
        val (r, g, b) = dominantColorCache[iconUrl] ?: return
        val gradient = QLinearGradient(0.0, 0.0, 1.0, 0.0).apply {
            setCoordinateMode(QGradient.CoordinateMode.ObjectBoundingMode)
            setColorAt(0.0, QColor(r, g, b, 180))
            setColorAt(1.0, QColor(0, 0, 0, 0))
        }
        availableItemsById[id]?.setBackground(QBrush(gradient))
        for (i in 0 until queuedList.count()) {
            val item = queuedList.item(i) ?: continue
            if (item.data(Qt.ItemDataRole.UserRole) as? String == id) {
                item.setBackground(QBrush(gradient))
                break
            }
        }
    }

    private fun disposeItemWidget(list: QListWidget, item: QListWidgetItem) {
        list.itemWidget(item)?.let { widget ->
            widget.hide()
            list.removeItemWidget(item)
            widget.dispose()
        }
    }

    private fun listIconPixmap(iconUrl: String?, size: Int = 40): QPixmap =
        iconUrl?.let(iconCache::get)?.pixmap(size, size)
            ?: TIcons.Search.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatio)

    private fun buildAvailableMetaText(result: ModSearchResult): String = buildString {
        result.author?.takeIf { it.isNotBlank() }?.let { append("by $it") }
        if (result.categories.isNotEmpty()) {
            if (isNotEmpty()) append("  |  ")
            append(result.categories.joinToString(", "))
        }
    }

    private fun buildQueueInfoText(queued: QueuedDownload): String =
        if (queued.dependencies.isNotEmpty()) {
            "${queued.dependencies.size} dependenc${if (queued.dependencies.size == 1) "y" else "ies"}"
        } else ""

    private fun buildQueueErrorText(queued: QueuedDownload): String = buildString {
        queued.status.missingDependencies.takeIf { it.isNotEmpty() }?.let {
            append("Missing: ")
            append(it.joinToString(", "))
        }
        queued.status.incompatibleWith.takeIf { it.isNotEmpty() }?.let {
            if (isNotEmpty()) append("\n")
            append("Incompatible: ")
            append(it.joinToString(", "))
        }
    }

    private fun queueIconLoad(key: String, url: String?) {
        if (url.isNullOrBlank()) return
        val cached = iconCache[url]
        if (cached != null) {
            availableRowWidgets[key]?.iconLabel?.pixmap = cached.pixmap(40, 40)
            queuedRowWidgets[key]?.iconLabel?.pixmap = cached.pixmap(40, 40)
            return
        }
        ioScope.launch {
            var acquired = false
            try {
                iconLoadSemaphore.acquire()
                acquired = true
                downloadAndCacheIcon(url, key)
            } finally {
                if (acquired) iconLoadSemaphore.release()
            }
        }
    }

    private suspend fun downloadAndCacheIcon(iconUrl: String, listKey: String? = null) {
        val bytes = runCatching { httpClient.get(iconUrl).bodyAsBytes() }.getOrNull() ?: return
        runOnGuiThread {
            val iconObj = runCatching {
                val pixmap = QPixmap()
                if (!pixmap.loadFromData(bytes)) error("Failed to decode icon")
                QIcon(pixmap)
            }.getOrElse { EMPTY_ICON }
            iconCache[iconUrl] = iconObj
            if (iconObj !== EMPTY_ICON && !dominantColorCache.containsKey(iconUrl)) {
                val color = extractDominantColor(iconObj.pixmap(40, 40))
                if (color != null) {
                    dominantColorCache[iconUrl] = color
                    if (listKey == selectedResultId) updateSelectedRowGradient()
                }
            }
            if (listKey != null) {
                availableRowWidgets[listKey]?.iconLabel?.pixmap = iconObj.pixmap(40, 40)
                queuedRowWidgets[listKey]?.iconLabel?.pixmap = iconObj.pixmap(40, 40)
            }
        }
    }

    private fun preloadIcon(iconUrl: String?) {
        if (iconUrl.isNullOrBlank()) return
        if (iconCache.containsKey(iconUrl)) return
        ioScope.launch {
            downloadAndCacheIcon(iconUrl)
        }
    }

    private fun queueResult(resultId: String) {
        ioScope.launch {
            val context = activeContext ?: return@launch
            val source = activeSource ?: return@launch
            val versionId = runCatching {
                fetchVersions(context, source, resultId).firstOrNull()?.id
            }.getOrNull()
            if (versionId == null) {
                runOnGuiThread { statusLabel.text = "No compatible version available." }
                return@launch
            }
            val details = runCatching {
                fetchDetails(context, source, resultId)
            }.getOrNull()
            if (details == null) {
                runOnGuiThread { statusLabel.text = "Failed to load mod details." }
                return@launch
            }
            val version = fetchVersions(context, source, resultId).firstOrNull { it.id == versionId }
            val queued = QueuedDownload(
                projectId = resultId,
                title = details.title,
                versionId = versionId,
                versionLabel = version?.label ?: versionId,
                iconUrl = details.iconUrl,
                dependencies = version?.dependencies ?: emptyList(),
                status = QueueStatus()
            )
            runOnGuiThread {
                state.queuedDownloads[resultId] = queued
                state.manuallyQueuedIds += resultId
                renderQueuedDownloads()
                TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)
                refreshAvailableResultItem(resultId)
                statusLabel.text = "Queued ${details.title}"
            }
        }
    }

    private suspend fun fetchDetails(context: ModBrowserContext, source: ModSource, id: String): ModDetails {
        state.detailsCache[id]?.let { return it }
        val details = source.details(context, id)
        state.detailsCache[id] = details
        return details
    }

    private suspend fun fetchVersions(context: ModBrowserContext, source: ModSource, id: String): List<ModVersionOption> {
        state.versionsCache[id]?.let { return it }
        val versions = source.versions(context, id)
        state.versionsCache[id] = versions
        return versions
    }

    private fun renderQueuedDownloads() {
        queuedList.updatesEnabled = false
        try {
            queuedList.clear()
            queuedRowWidgets.clear()
            state.queuedDownloads.values.forEach { queued ->
                addQueuedDownloadItem(queued)
                queueIconLoad(queued.projectId, queued.iconUrl)
            }
        } finally {
            queuedList.updatesEnabled = true
        }
        updateQueueButtons()
    }

    private fun updateQueueButtons() {
        downloadQueuedButton.isEnabled = state.queuedDownloads.isNotEmpty()
        removeQueuedButton.isEnabled = queuedList.currentRow() >= 0
    }

    private fun removeQueuedSelection() {
        val item = queuedList.currentItem() ?: return
        val projectId = item.data(Qt.ItemDataRole.UserRole) as? String ?: return
        removeQueuedDownload(projectId)
    }

    private fun removeQueuedDownload(projectId: String) {
        state.queuedDownloads.remove(projectId)
        state.manuallyQueuedIds.remove(projectId)
        removeOrphanedQueuedDependencies()
        renderQueuedDownloads()
        TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)
        refreshAvailableResultItem(projectId)
    }

    private fun removeOrphanedQueuedDependencies() {
        val requiredByQueued = state.queuedDownloads.values
            .flatMap { queued -> queued.dependencies.filter { it.required }.map { it.projectId } }
            .toMutableSet()
        var removedAny: Boolean
        do {
            removedAny = false
            val orphanIds = state.queuedDownloads.keys.filter { queuedId ->
                queuedId !in state.manuallyQueuedIds && queuedId !in requiredByQueued
            }
            if (orphanIds.isNotEmpty()) {
                orphanIds.forEach { orphanId ->
                    state.queuedDownloads.remove(orphanId)
                    state.manuallyQueuedIds.remove(orphanId)
                    refreshAvailableResultItem(orphanId)
                }
                requiredByQueued.clear()
                requiredByQueued += state.queuedDownloads.values
                    .flatMap { queued -> queued.dependencies.filter { it.required }.map { it.projectId } }
                removedAny = true
            }
        } while (removedAny)
    }

    @OptIn(ExperimentalTime::class)
    private fun downloadQueue() {
        val context = activeContext ?: return
        val source = activeSource ?: return
        val queued = state.queuedDownloads.values.toList()
        if (queued.isEmpty()) return

        ioScope.launch {
            val taskId = ProjectTaskMngr.start(
                projectPath = project.projectDir,
                title = "Downloading queued mods (Side Panel)",
                detail = "Preparing downloads",
                progressPercent = null
            )
            val modsDir = project.projectDir.resolve("mods")
            modsDir.mkdirs()
            val cacheEnabled = CoreSettingValues.modCacheEnabled
            val downloadSemaphore = Semaphore(4)

            data class PerModData(
                val projectId: String,
                val installedMod: InstalledMod,
                val depIds: List<String>
            )

            val failures = ConcurrentLinkedQueue<Pair<String, String>>()

            val perModResults: List<PerModData?> = coroutineScope {
                queued.map { queuedMod ->
                    async {
                        downloadSemaphore.withPermit {
                            try {
                                val plan = source.resolveInstall(context, queuedMod.projectId, queuedMod.versionId)
                                val jarPath = modsDir.resolve(plan.fileName)
                                val response = httpClient.prepareGet(plan.downloadUrl).execute()
                                val channel = response.bodyAsChannel()
                                val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                                val digest = MessageDigest.getInstance("SHA-1")
                                jarPath.parent().mkdirs()
                                java.io.FileOutputStream(jarPath.toJFile()).use { fos ->
                                    val buffer = ByteArray(8 * 1024)
                                    var downloaded = 0L
                                    while (!channel.isClosedForRead) {
                                        val rc = channel.readAvailable(buffer, 0, buffer.size)
                                        if (rc <= 0) break
                                        fos.write(buffer, 0, rc)
                                        digest.update(buffer, 0, rc)
                                        downloaded += rc
                                        totalBytes.takeIf { it > 0 }?.let { total ->
                                            ProjectTaskMngr.updateProgress(taskId, (downloaded.toDouble() / total) * 100.0)
                                        }
                                    }
                                }
                                val fileHash = digest.digest().joinToString("") { "%02x".format(it) }
                                logger.info("Downloaded queued mod '{}' as '{}'", queuedMod.projectId, plan.fileName)

                                if (cacheEnabled) {
                                    val cacheFile = ModDatabase.cachePathFor(fileHash)
                                    cacheFile.parent().mkdirs()
                                    cacheFile.writeBytesAtomic(jarPath.toJFile().readBytes())
                                }

                                val jarInfo = readModJarInfo(jarPath)
                                val modId = jarInfo?.modId ?: queuedMod.projectId
                                val displayName = jarInfo?.displayName ?: queuedMod.title
                                val side = jarInfo?.side ?: ModSide.BOTH

                                val iconBytes = try {
                                    queuedMod.iconUrl?.let { url -> httpClient.get(url).bodyAsBytes() }
                                } catch (_: Exception) { null }
                                val iconPath: String? = if (iconBytes != null) {
                                    val iconFile = ModDatabase.iconPathFor(queuedMod.projectId)
                                    iconFile.writeBytesAtomic(iconBytes)
                                    iconFile.toAbsolute().toString()
                                } else {
                                    val jarIcon = readModJarIcon(jarPath)
                                    if (jarIcon != null) {
                                        val iconFile = ModDatabase.iconPathFor(queuedMod.projectId)
                                        iconFile.writeBytesAtomic(jarIcon)
                                        iconFile.toAbsolute().toString()
                                    } else null
                                }

                                val depIds = queuedMod.dependencies.filter { it.required }.map { it.projectId }

                                PerModData(
                                    projectId = queuedMod.projectId,
                                    installedMod = InstalledMod(
                                        projectId = queuedMod.projectId,
                                        modId = modId,
                                        fileName = plan.fileName,
                                        displayName = displayName,
                                        side = side,
                                        releaseType = plan.releaseType?.name?.lowercase() ?: "release",
                                        source = source.id,
                                        versionId = plan.versionId,
                                        versionLabel = plan.versionLabel,
                                        iconPath = iconPath,
                                        projectUrl = null,
                                        fileHash = fileHash,
                                        installedAt = Clock.System.now()
                                    ),
                                    depIds = depIds
                                )
                            } catch (e: Exception) {
                                logger.warn("Failed to download mod '{}': {}", queuedMod.title, e.message, e)
                                failures.add(queuedMod.projectId to (e.message ?: "Unknown error"))
                                null
                            }
                        }
                    }
                }.awaitAll()
            }

            val successful = perModResults.filterNotNull()

            if (successful.isNotEmpty()) {
                ModDatabase(project.projectDir).use { db ->
                    successful.forEach { data ->
                        db.install(data.installedMod)
                        db.setDependencies(data.projectId, data.depIds)
                        TritiumEventBus.publish(
                            TritiumEvent.ModInstalled(
                                project, data.projectId,
                                data.installedMod.modId,
                                data.installedMod.displayName,
                                data.installedMod.versionId,
                                data.installedMod.versionLabel
                            )
                        )
                    }
                }
                runOnGuiThread {
                    successful.forEach { state.queuedDownloads.remove(it.projectId) }
                    state.manuallyQueuedIds.removeAll { pid -> successful.none { it.projectId == pid } }
                    renderQueuedDownloads()
                }
                TritiumEventBus.publish(TritiumEvent.ModsInstalled)
                TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)
            }

            ProjectTaskMngr.finish(taskId)
            runOnGuiThread {
                val message = when {
                    failures.isEmpty() -> "Downloaded ${successful.size} mod(s)"
                    successful.isEmpty() -> "All downloads failed"
                    else -> "Downloaded ${successful.size}/${queued.size} mod(s), ${failures.size} failed"
                }
                statusLabel.text = message
            }
        }
    }

    init {
        ioScope.onEvent<TritiumEvent.QueuedDownloadsChanged> { renderQueuedDownloads() }
        destroyed.connect {
            searchJob?.cancel()
            ioScope.cancel()
            httpClient.close()
        }
    }
}

private data class AvailableRowWidgets_(
    val root: QWidget,
    val iconLabel: QLabel
)

private data class QueuedRowWidgets_(
    val root: QWidget,
    val iconLabel: QLabel
)
