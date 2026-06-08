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
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.*
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.CacheManager
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
import io.github.tritium_launcher.launcher.ui.project.editor.panes.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.TMultiStateCategoryComboBox
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.qt.core.QByteArray
import io.qt.core.QRectF
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.svg.QSvgRenderer
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
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
        dock.setWidget(ModBrowserSidePanel(project, this))
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
    private val availableRowWidgets = linkedMapOf<String, AvailableRowWidgets>()
    private val queuedRowWidgets = linkedMapOf<String, QueuedRowWidgets>()
    private val iconCache get() = state.iconCache
    private val dominantColorCache get() = state.dominantColorCache
    private val iconLoadSemaphore = Semaphore(8)
    private val mbCacheDir: VPath = fromTR("mb-cache")

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
    private val prefetchSemaphore = Semaphore(4)
    private var downloadsWatcher: VPathWatcher? = null

    private val container = qWidget()
    private var suppressSelectionEvents = false
    private val searchField = QLineEdit()
    private val searchButton = TPushButton { text = "Search"; minimumHeight = 30 }
    private val categoryCombo = TMultiStateCategoryComboBox()
    private val availableList = QListWidget().apply {
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground)
    }
    private val queuedList = QListWidget()
    private val downloadQueuedButton = TPushButton { text = "Download Queue"; minimumHeight = 30 }
    private val removeQueuedButton = TPushButton { text = "Remove Selected"; minimumHeight = 30 }
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
        queuedList.itemClicked.connect { item ->
            val projectId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            val queued = state.queuedDownloads[projectId] ?: return@connect
            if (queued.requiresManualDownload) {
                val url = queued.projectUrl?.let { "$it/download/${queued.versionId}" }
                if (url != null) Platform.openBrowser(url)
            }
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
            val source = resolveSource() ?: return@let
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
                    categoryCombo.setEntries(categories.map { Triple(it.id, it.displayName, it.iconUrl) })
                    startFreshSearch()
                }
                categories.forEach { cat ->
                    val url = cat.iconUrl ?: return@forEach
                    val cached = iconCache[url]
                    if (cached != null) {
                        runOnGuiThread { categoryCombo.setEntryIcon(cat.id, cached.pixmap(48, 48)) }
                    } else {
                        ioScope.launch {
                            downloadAndCacheIcon(url, subdir = "categories", sourceId = source.id, onCached = { icon ->
                                categoryCombo.setEntryIcon(cat.id, icon.pixmap(48, 48))
                            })
                        }
                    }
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

    private fun resolveSource(): ModSource? {
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
                val newBudget = 500 - totalCached
                if (newBudget > 0 && newResults.isNotEmpty()) {
                    ioScope.launch {
                        newResults.take(newBudget).forEach { result ->
                            launch {
                                prefetchSemaphore.withPermit {
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

    private fun createAvailableRow(result: ModSearchResult): AvailableRowWidgets {
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
        return AvailableRowWidgets(root = root, iconLabel = icon)
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

    private fun createQueuedRow(queued: QueuedDownload): QueuedRowWidgets {
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
        val downloadUrl = if (queued.requiresManualDownload) {
            queued.projectUrl?.let { "$it/download/${queued.versionId}" }
        } else null
        val link = if (downloadUrl != null) {
            label("<a href=\"$downloadUrl\" style=\"color: ${TColors.Accent};\">$downloadUrl</a>") {
                textFormat = Qt.TextFormat.RichText
                wordWrap = true
                isVisible = true
            }
        } else label("") { isVisible = false }
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
                        if (link.isVisible) addWidget(link)
                    }
                }, 1)
            }
        }
        return QueuedRowWidgets(root = root, iconLabel = icon)
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
        if (queued.requiresManualDownload) {
            append("Manual download required (blocked by mod author)")
            return@buildString
        }
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
                downloadAndCacheIcon(url, key, subdir = "items", sourceId = activeSource?.id ?: "")
            } finally {
                if (acquired) iconLoadSemaphore.release()
            }
        }
    }

    private fun urlHash(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cacheFile(subdir: String, sourceId: String, url: String): VPath =
        mbCacheDir.resolve(subdir).resolve(sourceId).resolve(urlHash(url))

    private fun bytesToIcon(bytes: ByteArray): QIcon = runCatching {
        val pixmap = QPixmap()
        if (pixmap.loadFromData(bytes)) return@runCatching QIcon(pixmap)
        val svgText = bytes.toString(Charsets.UTF_8).replace("currentColor", "#000000")
        val renderer = QSvgRenderer(QByteArray(svgText.toByteArray(Charsets.UTF_8)))
        val svgPix = QPixmap(64, 64)
        svgPix.fill(Qt.GlobalColor.transparent)
        val painter = QPainter(svgPix)
        try {
            painter.setRenderHint(QPainter.RenderHint.Antialiasing, true)
            painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, true)
            renderer.render(painter, QRectF(0.0, 0.0, 64.0, 64.0))
        } finally {
            painter.end()
        }
        QIcon(svgPix)
    }.getOrElse { EMPTY_ICON }

    private suspend fun downloadAndCacheIcon(
        iconUrl: String,
        listKey: String? = null,
        subdir: String? = null,
        sourceId: String? = null,
        onCached: ((QIcon) -> Unit)? = null
    ) {
        if (subdir != null && sourceId != null) {
            val cached = cacheFile(subdir, sourceId, iconUrl).bytesOrNull()
            if (cached != null) {
                CacheManager.touch(cacheFile(subdir, sourceId, iconUrl))
                runOnGuiThread {
                    val iconObj = bytesToIcon(cached)
                    if (iconObj !== EMPTY_ICON) {
                        iconCache[iconUrl] = iconObj
                        if (!dominantColorCache.containsKey(iconUrl)) {
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
                        onCached?.invoke(iconObj)
                    }
                }
                return
            }
        }
        val bytes = runCatching {
            if (iconUrl.startsWith("data:image/svg+xml,")) {
                iconUrl.removePrefix("data:image/svg+xml,").toByteArray(Charsets.UTF_8)
            } else {
                httpClient.get(iconUrl).bodyAsBytes()
            }
        }.getOrNull() ?: return
        if (subdir != null && sourceId != null) {
            runCatching {
                val path = cacheFile(subdir, sourceId, iconUrl).toJPath()
                Files.createDirectories(path.parent)
                Files.write(path, bytes)
            }
            CacheManager.evictIfNeeded(mbCacheDir, subdir)
        }
        runOnGuiThread {
            val iconObj = bytesToIcon(bytes)
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
            onCached?.invoke(iconObj)
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
                status = QueueStatus(),
                projectUrl = details.website,
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
        if (state.queuedDownloads.any { it.value.requiresManualDownload }) {
            startDownloadsWatcher()
        } else {
            stopDownloadsWatcher()
        }
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
            val fallbacks = sources.all()
                .filterIsInstance<HashFallbackProvider>()
                .sortedBy { it.priority }

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
                                val resolved = resolveInstallDownload(context, source, queuedMod.projectId, queuedMod.versionId, fallbacks)
                                if (resolved.downloadUrl == null) {
                                    val existing = scanDownloadsForMod(queuedMod, resolved.plan.fileHash)
                                    if (existing != null) {
                                        val (jarPath, hash) = existing
                                        logger.info("Found existing download for '{}': {}", queuedMod.title, jarPath.fileName())
                                        val installedMod = prepareJarInstall(jarPath, queuedMod, hash, source.id)
                                        val depIds = queuedMod.dependencies.filter { it.required }.map { it.projectId }
                                        return@withPermit PerModData(projectId = queuedMod.projectId, installedMod = installedMod, depIds = depIds)
                                    }
                                    runOnGuiThread {
                                        state.queuedDownloads[queuedMod.projectId] = queuedMod.copy(
                                            requiresManualDownload = true,
                                            fileHash = resolved.plan.fileHash,
                                        )
                                        renderQueuedDownloads()
                                        startDownloadsWatcher()
                                    }
                                    val msg = "Manual download required (blocked by mod author)"
                                    failures.add(queuedMod.projectId to msg)
                                    return@withPermit null
                                }
                                val jarPath = modsDir.resolve(resolved.fileName)
                                val response = httpClient.prepareGet(resolved.downloadUrl).execute()
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
                                logger.info("Downloaded queued mod '{}' as '{}'", queuedMod.projectId, resolved.fileName)

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
                                        fileName = resolved.fileName,
                                        displayName = displayName,
                                        side = side,
                                        releaseType = resolved.plan.releaseType?.name?.lowercase() ?: "release",
                                        source = source.id,
                                        versionId = resolved.plan.versionId,
                                        versionLabel = resolved.plan.versionLabel,
                                        iconPath = iconPath,
                                        projectUrl = null,
                                        fileHash = fileHash,
                                        installedAt = Clock.System.now(),
                                        requiresManualDownload = resolved.requiresManualDownload,
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
                val manualCount = state.queuedDownloads.count { it.value.requiresManualDownload }
                val message = when {
                    failures.isEmpty() && manualCount == 0 -> "Downloaded ${successful.size} mod(s)"
                    failures.isEmpty() && manualCount > 0 -> "Downloaded ${successful.size} mod(s), $manualCount require manual download"
                    successful.isEmpty() && manualCount > 0 -> "$manualCount mod(s) require manual download"
                    successful.isEmpty() -> "All downloads failed"
                    else -> "Downloaded ${successful.size}/${queued.size} mod(s), ${failures.size} failed"
                }
                statusLabel.text = message
            }
        }
    }

    private fun startDownloadsWatcher() {
        if (downloadsWatcher != null) return
        if (state.queuedDownloads.none { it.value.requiresManualDownload }) return

        val downloadsDir = VPath.parse(System.getProperty("user.home")).resolve("Downloads")
        if (!downloadsDir.isDir()) {
            logger.warn("Downloads directory not found: {}", downloadsDir.toAbsolute())
            return
        }

        ioScope.launch {
            scanDownloadsFolder(downloadsDir)
            if (state.queuedDownloads.none { it.value.requiresManualDownload }) {
                runOnGuiThread { stopDownloadsWatcher() }
            }
        }

        logger.info("Starting Downloads folder watcher for manual download detection")
        downloadsWatcher = downloadsDir.watch(
            callback = { event ->
                if (event.kind == VWatchEvent.Kind.Create) {
                    val name = event.path.fileName()
                    if (name.endsWith(".jar", ignoreCase = true)) {
                        ioScope.launch {
                            handleDownloadsJar(event.path)
                        }
                    }
                }
            },
            options = VWatchOptions(
                kinds = listOf(java.nio.file.StandardWatchEventKinds.ENTRY_CREATE),
            ),
            ctx = Dispatchers.IO
        )
    }

    private fun stopDownloadsWatcher() {
        downloadsWatcher?.close()
        downloadsWatcher = null
    }

    private suspend fun scanDownloadsFolder(downloadsDir: VPath) {
        val existingJars = downloadsDir.list()
            .filter { it.fileName().endsWith(".jar", ignoreCase = true) }
            .sortedByDescending { it.lastModifiedOrNull() }
        for (jarPath in existingJars) {
            handleDownloadsJar(jarPath)
            if (state.queuedDownloads.none { it.value.requiresManualDownload }) break
        }
    }

    private fun jarMatchesMod(jarPath: VPath, expectedHash: String?, projectId: String): String? {
        val bytes = runCatching { jarPath.bytesOrNothing() }.getOrNull() ?: return null
        val hash = ModDatabase.sha1(bytes)
        if (expectedHash != null && hash == expectedHash) return hash
        val info = runCatching { readModJarInfo(jarPath) }.getOrNull()
        if (info != null && info.modId == projectId) return hash
        return null
    }

    private suspend fun prepareJarInstall(jarPath: VPath, queued: QueuedDownload, hash: String, sourceId: String): InstalledMod {
        val modsDir = project.projectDir.resolve("mods")
        modsDir.mkdirs()
        val targetPath = modsDir.resolve(jarPath.fileName())
        withContext(Dispatchers.IO) {
            Files.copy(
                jarPath.toJPath(),
                targetPath.toJPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        val jarInfo = readModJarInfo(targetPath)
        val projectId = queued.projectId
        val modId = jarInfo?.modId ?: projectId
        val displayName = jarInfo?.displayName ?: queued.title
        val side = jarInfo?.side ?: ModSide.BOTH

        val iconBytes = try {
            queued.iconUrl?.let { url -> httpClient.get(url).bodyAsBytes() }
        } catch (_: Exception) { null } ?: readModJarIcon(targetPath)
        val iconPath: String? = if (iconBytes != null) {
            val iconFile = ModDatabase.iconPathFor(projectId)
            iconFile.writeBytesAtomic(iconBytes)
            iconFile.toAbsolute().toString()
        } else null

        return InstalledMod(
            projectId = projectId,
            modId = modId,
            fileName = targetPath.fileName(),
            displayName = displayName,
            side = side,
            releaseType = "release",
            source = sourceId,
            versionId = queued.versionId,
            versionLabel = queued.versionLabel,
            iconPath = iconPath,
            projectUrl = queued.projectUrl,
            fileHash = hash,
            installedAt = Clock.System.now(),
            requiresManualDownload = true,
        )
    }

    private suspend fun commitManualInstall(installedMod: InstalledMod, depIds: List<String> = emptyList()) {
        val projectId = installedMod.projectId
        withContext(Dispatchers.IO) {
            ModDatabase(project.projectDir).use { db ->
                db.install(installedMod)
                db.setDependencies(projectId, depIds)
            }
        }

        runOnGuiThread {
            state.queuedDownloads.remove(projectId)
            state.manuallyQueuedIds.remove(projectId)
            renderQueuedDownloads()
            if (state.queuedDownloads.none { it.value.requiresManualDownload }) {
                stopDownloadsWatcher()
            }
        }

        TritiumEventBus.publish(
            TritiumEvent.ModInstalled(
                project, projectId, installedMod.modId, installedMod.displayName,
                installedMod.versionId, installedMod.versionLabel
            )
        )
        TritiumEventBus.publish(TritiumEvent.ModsInstalled)
        TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)

        runOnGuiThread { statusLabel.text = "Detected and installed manual download: ${installedMod.displayName}" }
    }

    private fun scanDownloadsForMod(queued: QueuedDownload, fileHash: String?): Pair<VPath, String>? {
        val downloadsDir = VPath.parse(System.getProperty("user.home")).resolve("Downloads")
        if (!downloadsDir.isDir()) return null
        val jars = downloadsDir.list()
            .filter { it.fileName().endsWith(".jar", ignoreCase = true) }
            .sortedByDescending { it.lastModifiedOrNull() }
        for (jarPath in jars) {
            val hash = jarMatchesMod(jarPath, fileHash, queued.projectId) ?: continue
            return jarPath to hash
        }
        return null
    }

    private suspend fun handleDownloadsJar(jarPath: VPath) {
        val pendingManual = state.queuedDownloads.filter { it.value.requiresManualDownload }
        if (pendingManual.isEmpty()) return

        delay(2000.milliseconds)

        for ((_, queued) in pendingManual) {
            val hash = jarMatchesMod(jarPath, queued.fileHash, queued.projectId) ?: continue
            logger.info("Detected manual download for mod '{}': {}", queued.title, jarPath.fileName())
            val installedMod = prepareJarInstall(jarPath, queued, hash, activeSource?.id ?: "unknown")
            val depIds = queued.dependencies.filter { it.required }.map { it.projectId }
            commitManualInstall(installedMod, depIds)
            return
        }
    }

    init {
        ioScope.onEvent<TritiumEvent.QueuedDownloadsChanged> { renderQueuedDownloads() }
        ioScope.onEvent<TritiumEvent.ModsInstalled> {
            val manualIds = state.queuedDownloads.filter { it.value.requiresManualDownload }.keys
            if (manualIds.isEmpty()) return@onEvent
            ioScope.launch {
                val installed = withContext(Dispatchers.IO) {
                    ModDatabase(project.projectDir).use { db ->
                        manualIds.filter { db.exists(it) }
                    }
                }
                if (installed.isNotEmpty()) {
                    runOnGuiThread {
                        installed.forEach { state.queuedDownloads.remove(it) }
                        renderQueuedDownloads()
                        TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)
                    }
                }
            }
        }
        destroyed.connect {
            stopDownloadsWatcher()
            searchJob?.cancel()
            ioScope.cancel()
            httpClient.close()
        }
    }
}

private data class AvailableRowWidgets(
    val root: QWidget,
    val iconLabel: QLabel
)

private data class QueuedRowWidgets(
    val root: QWidget,
    val iconLabel: QLabel
)
