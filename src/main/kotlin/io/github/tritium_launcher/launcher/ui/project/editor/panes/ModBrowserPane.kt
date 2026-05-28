package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.mod.*
import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.source.*
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPaneProvider
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.RemoteImageTextBrowser
import io.github.tritium_launcher.launcher.ui.widgets.TMultiStateCategoryComboBox
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.*
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class ModBrowserPane(project: ProjectBase, file: VPath) : EditorPane(project, file) {
    override val allowAutoSave: Boolean = false

    companion object {
        private const val PAGE_SIZE = 25
        private const val DETAIL_PREFETCH_CONCURRENCY = 4
        private const val ICON_LOAD_CONCURRENCY = 8
        const val FILE_NAME = "mod-browser.trtab"
        private val EMPTY_ICON = QIcon(TIcons.Search)

        fun tabPath(project: ProjectBase): VPath = project.projectDir.resolve(".tr").resolve(FILE_NAME)
    }

    private val logger = logger()
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

    private val resultItemsById = linkedMapOf<String, QListWidgetItem>()
    private val resultsById = linkedMapOf<String, ModSearchResult>()
    private val versionsById = linkedMapOf<String, ModVersionOption>()
    private val availableRowWidgets = linkedMapOf<String, AvailableRowWidgets>()
    private val queuedRowWidgets = linkedMapOf<String, QueuedRowWidgets>()
    private val detailsCache = ConcurrentHashMap<String, ModDetails>()
    private val versionsCache = ConcurrentHashMap<String, List<ModVersionOption>>()
    private val queuedDownloads = linkedMapOf<String, QueuedDownload>()
    private val manuallyQueuedIds = linkedSetOf<String>()
    private val iconCache = ConcurrentHashMap<String, QIcon>()
    private val dominantColorCache = ConcurrentHashMap<String, Triple<Int, Int, Int>>()
    private val queuedDetailIds = ConcurrentHashMap.newKeySet<String>()
    private val prefetchSemaphore = Semaphore(DETAIL_PREFETCH_CONCURRENCY)
    private val iconLoadSemaphore = Semaphore(ICON_LOAD_CONCURRENCY)
    private val navigationBackStack = ArrayDeque<String>()
    private val navigationForwardStack = ArrayDeque<String>()
    private val detailRequestsInFlight = ConcurrentHashMap<String, Deferred<ModDetails>>()
    private val versionRequestsInFlight = ConcurrentHashMap<String, Deferred<List<ModVersionOption>>>()
    private val markdownParser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()
    private val markdownRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .escapeHtml(false)
        .build()

    private var activeContext: ModBrowserContext? = null
    private var activeSource: ModSource? = null
    private var selectedResultId: String? = null
    private var selectedDetails: ModDetails? = null
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
    private var detailsJob: Job? = null
    private var currentSupportMessage: String? = null
    private var suppressSelectionEvents: Boolean = false
    private var dependencyStripSignature: String = ""

    private val container = qWidget()
    private val splitter = QSplitter(Qt.Orientation.Horizontal, container)
    private val searchField = QLineEdit()
    private val searchButton = pushButton { text = "Search" }
    private val categoryCombo = TMultiStateCategoryComboBox()
    private val availableList = QListWidget().apply {
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground)
    }
    private val queuedList = QListWidget()
    private val iconLabel = label {
        setFixedSize(64, 64)
        scaledContents = true
        pixmap = TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
    }
    private val titleLabel = label("Select a mod") { wordWrap = true }
    private val summaryLabel = label { wordWrap = true }
    private val metaLabel = label { wordWrap = true }
    private val sourceLabel = label()
    private val contextLabel = label()
    private val versionLabel = label("Version:")
    private val versionCombo = QComboBox()
    private val queueButton = pushButton { text = "Add to Queue" }
    private val downloadQueuedButton = pushButton { text = "Download Queue" }
    private val removeQueuedButton = pushButton { text = "Remove Selected" }
    private val openPageButton = pushButton { text = "Open Page" }
    private val backButton = pushButton { text = "<" }
    private val forwardButton = pushButton { text = ">" }
    private val dependencyLabel = label("Dependencies")
    private val dependencyScroll = QScrollArea()
    private val dependencyContent = qWidget()
    private val dependencyLayout = QHBoxLayout(dependencyContent)
    private val descriptionView = RemoteImageTextBrowser { url ->
        httpClient.get(url).bodyAsBytes()
    }
    private val statusLabel = label("Ready") { wordWrap = true }

    init {
        AnimatedScrollController.attach(availableList)
        AnimatedScrollController.attach(queuedList)
        AnimatedScrollController.attach(dependencyScroll)
        AnimatedScrollController.attach(descriptionView)
        vBoxLayout(container) {
            setContentsMargins(0, 0, 0, 0)
            addWidget(splitter)
        }

        val leftPane = qWidget().also { pane ->
            vBoxLayout(pane) {
                setContentsMargins(10, 10, 10, 10)
                setSpacing(8)

                addWidget(qWidget().also { row ->
                    hBoxLayout(row) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(6)
                        addWidget(searchField, 1)
                        addWidget(searchButton)
                    }
                })

                addWidget(categoryCombo)
                addWidget(QSplitter(Qt.Orientation.Horizontal).also { listSplit ->
                    listSplit.addWidget(qWidget().also { availablePane ->
                        vBoxLayout(availablePane) {
                            setContentsMargins(0, 0, 0, 0)
                            setSpacing(6)
                            addWidget(label("Mods to Download"))
                            addWidget(availableList, 1)
                        }
                    })
                    listSplit.addWidget(qWidget().also { queuedPane ->
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
                    listSplit.setStretchFactor(0, 3)
                    listSplit.setStretchFactor(1, 2)
                }, 1)
            }
        }

        val rightPane = qWidget().also { pane ->
            vBoxLayout(pane) {
                setContentsMargins(10, 10, 10, 10)
                setSpacing(8)

                addWidget(qWidget().also { header ->
                    hBoxLayout(header) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(10)
                        addWidget(iconLabel)
                        addWidget(qWidget().also { textCol ->
                            vBoxLayout(textCol) {
                                setContentsMargins(0, 0, 0, 0)
                                setSpacing(4)
                                addWidget(titleLabel)
                                addWidget(summaryLabel)
                                addWidget(metaLabel)
                            }
                        }, 1)
                        addWidget(qWidget().also { navButtons ->
                            hBoxLayout(navButtons) {
                                setContentsMargins(0, 0, 0, 0)
                                setSpacing(4)
                                addWidget(backButton)
                                addWidget(forwardButton)
                            }
                        }, 0, Qt.AlignmentFlag.AlignTop)
                    }
                })

                addWidget(sourceLabel)
                addWidget(contextLabel)
                addWidget(qWidget().also { row ->
                    hBoxLayout(row) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(6)
                        addWidget(versionLabel)
                        addWidget(versionCombo, 1)
                    }
                })
                addWidget(qWidget().also { row ->
                    hBoxLayout(row) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(6)
                        addWidget(queueButton)
                        addWidget(openPageButton)
                        addStretch(1)
                    }
                })
                addWidget(qWidget().also { dependencySection ->
                    vBoxLayout(dependencySection) {
                        setContentsMargins(0, 0, 0, 0)
                        setSpacing(6)
                        addWidget(qWidget().also { row ->
                            hBoxLayout(row) {
                                setContentsMargins(0, 0, 0, 0)
                                setSpacing(0)
                                addWidget(dependencyLabel)
                                addStretch(1)
                            }
                        })
                        addWidget(dependencyScroll)
                    }
                })
                addWidget(descriptionView, 1)
                addWidget(statusLabel)
            }
        }

        splitter.addWidget(leftPane)
        splitter.addWidget(rightPane)
        splitter.setStretchFactor(0, 2)
        splitter.setStretchFactor(1, 3)

        searchField.placeholderText = "Search mods"
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
        versionCombo.isEnabled = false
        descriptionView.apply {
            openExternalLinks = false
            openLinks = false
            lineWrapMode = QTextEdit.LineWrapMode.WidgetWidth
        }
        descriptionView.anchorClicked.connect { url ->
            Platform.openBrowser(url.toString())
        }
        dependencyLayout.apply {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(10)
            addStretch(1)
        }
        dependencyScroll.apply {
            widgetResizable = true
            frameShape = QFrame.Shape.NoFrame
            setWidget(dependencyContent)
            minimumHeight = 116
            maximumHeight = 116
            horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAsNeeded
            verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        }
        listOf(backButton, forwardButton).forEach { button ->
            button.setFixedWidth(28)
        }

        container.setThemedStyle {
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()

            selector("QListWidget") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                }
            }
            selector("QListWidget::item") {
                if (isBgImageSet) {
                    backgroundColor("rgba(0, 0, 0, 40)")
                }
            }
            selector("QListWidget::item:selected") {
                if (isBgImageSet) {
                    backgroundColor("rgba(255, 255, 255, 30)")
                }
            }
        }

        searchButton.onClicked { startFreshSearch() }
        searchField.returnPressed.connect { startFreshSearch() }
        categoryCombo.onSelectionChanged = { startFreshSearch() }
        versionCombo.currentIndexChanged.connect {
            val versionId = versionCombo.currentData as? String
            queueButton.isEnabled = versionId != null && selectedResultId != null
            renderDependencyStrip()
        }
        availableList.currentItemChanged.connect { current, _ ->
            if (suppressSelectionEvents) return@connect
            val resultId = current?.data(Qt.ItemDataRole.UserRole) as? String
            selectResult(resultId)
        }
        availableList.itemDoubleClicked.connect { item ->
            val resultId = item?.data(Qt.ItemDataRole.UserRole) as? String ?: return@connect
            if (queuedDownloads.containsKey(resultId)) {
                removeQueuedDownload(resultId)
            } else {
                queueResult(resultId, if (resultId == selectedResultId) versionCombo.currentData as? String else null)
            }
        }
        queuedList.currentItemChanged.connect { current, _ ->
            if (suppressSelectionEvents) return@connect
            val resultId = current?.data(Qt.ItemDataRole.UserRole) as? String
            if (resultId != null) selectResult(resultId)
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
        queueButton.onClicked { queueSelection() }
        downloadQueuedButton.onClicked { downloadQueue() }
        removeQueuedButton.onClicked { removeQueuedSelection() }
        openPageButton.onClicked {
            selectedDetails?.website?.takeIf { it.isNotBlank() }?.let { Platform.openBrowser(it) }
        }
        backButton.onClicked { navigateBack() }
        forwardButton.onClicked { navigateForward() }

        queueButton.isEnabled = false
        downloadQueuedButton.isEnabled = false
        removeQueuedButton.isEnabled = false
        openPageButton.isEnabled = false
        backButton.isEnabled = false
        forwardButton.isEnabled = false
    }

    override fun widget(): QWidget = container

    override fun onOpen() {
        val context = projectContext()
        activeContext = context
        activeSource = context?.projectSourceId()?.let { sourceId -> sources.all().find { it.id == sourceId } }

        if (context == null) {
            currentSupportMessage = "The Mod Browser requires a typed Modpack project."
            logger.warn("Mod Browser opened without typed modpack metadata for '{}'", project.name)
            renderContext()
            renderEmptyState()
            return
        }

        val source = activeSource
        if (source == null) {
            currentSupportMessage = "The project's configured mod source is not registered."
            logger.warn("Mod Browser source missing for project '{}' (source={})", project.name, context.projectSourceId())
            renderContext()
            renderEmptyState()
            return
        }

        val support = source.support(context)
        currentSupportMessage = support.message
        renderContext()
        if (!support.available) {
            logger.info("Mod Browser unavailable for project '{}' via source '{}': {}", project.name, source.id, support.message)
            renderEmptyState()
            return
        }

        logger.info(
            "Opening Mod Browser for '{}' (source={} mc={} loader={})",
            project.name,
            source.id,
            context.minecraftVersion,
            context.modLoaderId
        )

        ioScope.launch {
            val categories = runCatching { source.getCategories(context) }.getOrElse {
                logger.warn("Failed loading mod categories from {}", source.id, it)
                emptyList()
            }
            runOnGuiThread {
                categoryCombo.setEntries(categories.map { it.id to it.displayName })
                startFreshSearch()
            }
        }
    }

    override fun onClose() {
        searchJob?.cancel()
        detailsJob?.cancel()
        ioScope.cancel()
        httpClient.close()
    }

    override suspend fun save(): Boolean {
        modified = false
        return true
    }

    private fun renderContext() {
        val context = activeContext
        sourceLabel.text = "Source: ${activeSource?.displayName ?: context?.projectSourceId().orEmpty().ifBlank { "unknown" }}"
        contextLabel.text = buildString {
            append("Minecraft: ")
            append(context?.minecraftVersion ?: "unknown")
            append("  |  Loader: ")
            append(context?.modLoaderId ?: "unknown")
        }
        statusLabel.text = currentSupportMessage ?: "Ready"
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
        resultsById.clear()
        resultItemsById.clear()
        selectedResultId = null
        selectedDetails = null
        versionsById.clear()
        queuedDetailIds.clear()
        searchGeneration++
        navigationBackStack.clear()
        navigationForwardStack.clear()
        availableList.clear()
        availableRowWidgets.clear()
        versionCombo.clear()
        versionCombo.isEnabled = false
        queueButton.isEnabled = false
        openPageButton.isEnabled = false
        updateNavigationButtons()
        renderDetails(null)
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
            logger.info(
                "Loading mod page: project='{}' source={} offset={} text='{}' categories={}",
                project.name,
                source.id,
                offset,
                lastQueryText,
                "include=$lastIncludedCategories exclude=$lastExcludedCategories"
            )
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
                    val message = page.exceptionOrNull()?.message ?: "Search failed"
                    logger.warn("Mod search failed for project '{}': {}", project.name, message, page.exceptionOrNull())
                    statusLabel.text = message
                    if (reset) renderEmptyState()
                    return@runOnGuiThread
                }

                totalHits = resolved.total
                nextOffset = offset + resolved.results.size
                hasMoreResults = nextOffset < totalHits && resolved.results.isNotEmpty()
                statusLabel.text = "Showing ${resultsById.size + resolved.results.size} of $totalHits"

                availableList.updatesEnabled = false
                try {
                    resolved.results.forEach { result ->
                        if (resultsById.containsKey(result.id)) return@forEach
                        resultsById[result.id] = result
                        addAvailableResultItem(result)
                        queueIconLoad(result.id, result.iconUrl)
                        queueDetailPrefetch(result.id)
                    }
                } finally {
                    availableList.updatesEnabled = true
                }

                if (availableList.count() > 0 && availableList.currentRow() < 0) {
                    availableList.currentRow = 0
                }
                if (availableList.count() == 0) {
                    renderDetails(null)
                }
            }
        }
    }

    private fun selectResult(resultId: String?, syncLists: Boolean = true) {
        selectedResultId = resultId
        updateSelectedRowGradient()
        versionsById.clear()
        versionCombo.clear()
        versionCombo.isEnabled = false
        queueButton.isEnabled = false
        if (syncLists) {
            syncSelectedItems(resultId)
        }

        val result = resultId?.let(resultsById::get)
        if (result == null) {
            selectedDetails = null
            renderDetails(null)
            return
        }

        val cachedDetails = detailsCache[result.id]
        val cachedVersions = versionsCache[result.id]
        if(cachedDetails != null && cachedVersions != null) {
            selectedDetails = cachedDetails
            bindVersions(cachedVersions)
            renderDetails(cachedDetails)
            statusLabel.text = "Loaded ${result.title}"
            return
        }

        detailsJob?.cancel()
        detailsJob = ioScope.launch {
            val context = activeContext ?: return@launch
            val source = activeSource ?: return@launch
            logger.info("Loading mod details: project='{}' source={} modId={}", project.name, source.id, result.id)

            runOnGuiThread {
                titleLabel.text = result.title
                summaryLabel.text = result.summary
                metaLabel.text = "Loading details..."
                statusLabel.text = "Loading details..."
                applyIcon(result.iconUrl, iconLabel)
                renderDescription(result.summary)
            }

            val detailsDeferred = async { runCatching { cachedOrFetchDetails(context, source, result.id) } }
            val versionsDeferred = async { runCatching { cachedOrFetchVersions(context, source, result.id) } }
            val details = detailsDeferred.await()
            val versions = versionsDeferred.await()

            runOnGuiThread {
                if (selectedResultId != result.id) return@runOnGuiThread
                selectedDetails = details.getOrNull()
                val detailError = details.exceptionOrNull()
                val versionError = versions.exceptionOrNull()
                if (detailError != null) {
                    logger.warn("Failed loading mod details for '{}': {}", result.id, detailError.message, detailError)
                }
                if (versionError != null) {
                    logger.warn("Failed loading mod versions for '{}': {}", result.id, versionError.message, versionError)
                }

                bindVersions(versions.getOrDefault(emptyList()))
                renderDetails(details.getOrNull() ?: fallbackDetails(result), detailError?.message)
                statusLabel.text = when {
                    detailError != null -> detailError.message ?: "Failed loading details"
                    else -> "Loaded ${result.title}"
                }
            }
        }
    }

    private fun bindVersions(versions: List<ModVersionOption>) {
        versionCombo.clear()
        versionsById.clear()
        versions.forEach { version ->
            versionsById[version.id] = version
            versionCombo.addItem(version.label, version.id)
        }
        versionCombo.isEnabled = versions.isNotEmpty()
        if (versions.isNotEmpty()) {
            versionCombo.currentIndex = 0
            queueButton.isEnabled = selectedResultId != null
        }
    }

    private fun renderDetails(details: ModDetails?, error: String? = null) {
        if (details == null) {
            titleLabel.text = selectedResultId?.let(resultsById::get)?.title ?: "Select a mod"
            summaryLabel.text = selectedResultId?.let(resultsById::get)?.summary ?: ""
            metaLabel.text = error ?: ""
            applyIcon(null, iconLabel)
            renderDependencyStrip()
            renderDescription(error ?: "Choose a mod from the list to inspect it.")
            openPageButton.isEnabled = false
            return
        }

        titleLabel.text = details.title
        summaryLabel.text = details.summary
        metaLabel.text = buildString {
            details.author?.takeIf { it.isNotBlank() }?.let { append("Author: $it") }
            details.downloads?.let {
                if (isNotEmpty()) append("  |  ")
                append("Downloads: $it")
            }
            if (details.categories.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(details.categories.joinToString(", "))
            }
            currentLatestCompatibleLabel(details)?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n")
                append("Latest compatible: $it")
            }
        }
        openPageButton.isEnabled = !details.website.isNullOrBlank()
        queueIconLoad(details.id, details.iconUrl, iconLabel)
        renderDependencyStrip()
        renderDescription(details.description.ifBlank { details.summary })
    }

    private fun renderDescription(raw: String) {
        if (raw.isBlank()) {
            descriptionView.plainText = "No description available."
            return
        }

        val normalized = normalizeDescription(raw)
        val doc = markdownParser.parse(normalized)
        val html = buildString {
            append("<style>img { max-width: 100%; height: auto; }</style>")
            append(markdownRenderer.render(doc))
        }
        descriptionView.setHtmlContent(html)
    }

    private fun queueDetailPrefetch(modId: String) {
        if (queuedDetailIds.contains(modId)) return
        if (detailsCache.containsKey(modId) && versionsCache.containsKey(modId)) return
        queuedDetailIds += modId
        val generation = searchGeneration
        ioScope.launch {
            var acquired = false
            try {
                prefetchSemaphore.acquire()
                acquired = true
                if (generation != searchGeneration) return@launch
                queuedDetailIds.remove(modId)
                val context = activeContext ?: return@launch
                val source = activeSource ?: return@launch
                runCatching {
                    if (!detailsCache.containsKey(modId)) {
                        cachedOrFetchDetails(context, source, modId)
                    }
                    if (!versionsCache.containsKey(modId)) {
                        cachedOrFetchVersions(context, source, modId)
                    }
                }.onFailure {
                    logger.debug("Mod detail prefetch skipped for '{}': {}", modId, it.message)
                }
            } finally {
                if (acquired) prefetchSemaphore.release()
            }
        }
    }

    private suspend fun cachedOrFetchDetails(context: ModBrowserContext, source: ModSource, modId: String): ModDetails {
        detailsCache[modId]?.let { return it }
        detailRequestsInFlight[modId]?.let { return it.await() }
        val newRequest = ioScope.async(start = CoroutineStart.LAZY) {
            logger.debug("Fetching mod details from {} for {}", source.id, modId)
            source.details(context, modId).also { details ->
                detailsCache[modId] = details
            }
        }
        val request = detailRequestsInFlight.putIfAbsent(modId, newRequest) ?: newRequest
        if (request === newRequest) newRequest.start()
        return try {
            request.await()
        } finally {
            detailRequestsInFlight.remove(modId, request)
        }
    }

    private suspend fun cachedOrFetchVersions(context: ModBrowserContext, source: ModSource, modId: String): List<ModVersionOption> {
        versionsCache[modId]?.let { return it }
        versionRequestsInFlight[modId]?.let { return it.await() }
        val newRequest = ioScope.async(start = CoroutineStart.LAZY) {
            source.versions(context, modId).also { versionsCache[modId] = it }
        }
        val request = versionRequestsInFlight.putIfAbsent(modId, newRequest) ?: newRequest
        if (request === newRequest) newRequest.start()
        return try {
            request.await()
        } finally {
            versionRequestsInFlight.remove(modId, request)
        }
    }

    private fun queueSelection() {
        val resultId = selectedResultId ?: return
        val versionId = versionCombo.currentData as? String ?: return
        queueResult(resultId, versionId)
    }

    private fun queueResult(resultId: String, preferredVersionId: String?) {
        val existingQueuedIds = queuedDownloads.keys.toSet()
        ioScope.launch {
            val context = activeContext ?: return@launch
            val source = activeSource ?: return@launch
            val chosenVersionId = preferredVersionId ?: runCatching {
                cachedOrFetchVersions(context, source, resultId).firstOrNull()?.id
            }.getOrElse {
                logger.warn("Failed resolving versions before queueing '{}': {}", resultId, it.message, it)
                null
            }
            if (chosenVersionId == null) {
                runOnGuiThread { statusLabel.text = "No compatible version available." }
                return@launch
            }
            val resolved = resolveQueuedDownloads(
                context = context,
                source = source,
                rootId = resultId,
                rootVersionId = chosenVersionId,
                existingQueuedIds = existingQueuedIds
            )
            runOnGuiThread {
                if (resolved.isEmpty()) {
                    if (queuedDownloads.containsKey(resultId) && !isRequiredByQueuedMod(resultId)) {
                        manuallyQueuedIds += resultId
                        refreshAvailableResultItem(resultId)
                    }
                    statusLabel.text = "Already queued"
                    return@runOnGuiThread
                }
                resolved.forEach { queuedDownloads[it.projectId] = it }
                if (!isRequiredByQueuedMod(resultId)) {
                    manuallyQueuedIds += resultId
                }
                modified = true
                renderQueuedDownloads()
                resolved.map { it.projectId }.forEach(::refreshAvailableResultItem)
                val addedDependencies = (resolved.size - 1).coerceAtLeast(0)
                statusLabel.text = when {
                    addedDependencies == 0 -> "Queued ${resolved.first().title}"
                    else -> "Queued ${resolved.first().title} and $addedDependencies dependenc${if (addedDependencies == 1) "y" else "ies"}"
                }
            }
        }
    }

    private suspend fun resolveQueuedDownloads(
        context: ModBrowserContext,
        source: ModSource,
        rootId: String,
        rootVersionId: String,
        existingQueuedIds: Set<String>
    ): List<QueuedDownload> {
        val pending = ArrayDeque<Pair<String, String>>()
        val planned = linkedMapOf<String, QueuedDownload>()
        pending += rootId to rootVersionId

        while (pending.isNotEmpty()) {
            val (resultId, versionId) = pending.removeFirst()
            if (resultId in existingQueuedIds || planned.containsKey(resultId)) continue

            val (detailsResult, versionsResult) = coroutineScope {
                val detailsDeferred = async { runCatching { cachedOrFetchDetails(context, source, resultId) } }
                val versionsDeferred = async { runCatching { cachedOrFetchVersions(context, source, resultId) } }
                detailsDeferred.await() to versionsDeferred.await()
            }
            val versions = versionsResult.getOrNull()
            if (versions == null) {
                val error = versionsResult.exceptionOrNull()
                logger.warn("Failed resolving versions for queued mod '{}': {}", resultId, error?.message, error)
                continue
            }
            val selectedVersion = versions.firstOrNull { it.id == versionId } ?: versions.firstOrNull()
            if (selectedVersion == null) {
                logger.warn("No compatible version available while queueing '{}'", resultId)
                continue
            }
            val details = detailsResult.getOrNull()
            if (details == null) {
                val error = detailsResult.exceptionOrNull()
                logger.warn("Failed resolving details for queued mod '{}': {}", resultId, error?.message, error)
                continue
            }

            planned[resultId] = QueuedDownload(
                projectId = resultId,
                title = details.title,
                versionId = selectedVersion.id,
                versionLabel = selectedVersion.label,
                iconUrl = details.iconUrl,
                dependencies = selectedVersion.dependencies,
                status = QueueStatus()
            )

            val dependencyVersions = coroutineScope {
                selectedVersion.dependencies
                    .filter { it.required }
                    .filterNot { it.projectId in existingQueuedIds || planned.containsKey(it.projectId) }
                    .map { dependency ->
                        async {
                            dependency.projectId to runCatching {
                                cachedOrFetchVersions(context, source, dependency.projectId).firstOrNull()?.id
                            }.getOrElse {
                                logger.warn("Failed resolving dependency versions for '{}': {}", dependency.projectId, it.message, it)
                                null
                            }
                        }
                    }
                    .awaitAll()
            }
            dependencyVersions.forEach { (dependencyId, dependencyVersionId) ->
                dependencyVersionId?.let { pending += dependencyId to it }
            }
        }

        return planned.values.toList()
    }

    @OptIn(ExperimentalTime::class)
    private fun downloadQueue() {
        val context = activeContext ?: return
        val source = activeSource ?: return
        val queued = queuedDownloads.values.toList()
        if (queued.isEmpty()) return

        ioScope.launch {
            val taskId = ProjectTaskMngr.start(
                projectPath = project.projectDir,
                title = "Downloading queued mods",
                detail = "Preparing downloads",
                progressPercent = null
            )
            logger.info(
                "Downloading queued mods: project='{}' source={} count={}",
                project.name,
                source.id,
                queued.size
            )

            val outcome = runCatching {
                val modsDir = project.projectDir.resolve("mods")
                modsDir.mkdirs()
                val cacheEnabled = CoreSettingValues.modCacheEnabled
                val dependencyRelations = mutableListOf<Pair<String, List<String>>>()
                val registryEntries = mutableListOf<ModRegistryEntry>()
                ModDatabase(project.projectDir).use { db ->
                    queued.forEachIndexed { idx, queuedMod ->
                        ProjectTaskMngr.update(taskId, detail = "Downloading ${queuedMod.title}")
                        ProjectTaskMngr.updateProgress(taskId, ((idx.toDouble() / queued.size) * 100.0))
                        val plan = source.resolveInstall(context, queuedMod.projectId, queuedMod.versionId)
                        val bytes = httpClient.get(plan.downloadUrl).bodyAsBytes()
                        val jarPath = modsDir.resolve(plan.fileName)
                        jarPath.writeBytesAtomic(bytes)
                        logger.info("Downloaded queued mod '{}' as '{}'", queuedMod.projectId, plan.fileName)

                        val jarInfo = readModJarInfo(jarPath)
                        val modId = jarInfo?.modId ?: queuedMod.projectId
                        val displayName = jarInfo?.displayName ?: queuedMod.title
                        val side = jarInfo?.side ?: "BOTH"

                        val fileHash = ModDatabase.sha1(bytes)

                        val iconBytes = runCatching {
                            queuedMod.iconUrl?.let { url ->
                                httpClient.get(url).bodyAsBytes()
                            }
                        }.getOrNull()
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

                        if (cacheEnabled) {
                            val cacheFile = ModDatabase.cachePathFor(fileHash)
                            cacheFile.parent().mkdirs()
                            cacheFile.writeBytesAtomic(bytes)
                        }

                        val depIds = queuedMod.dependencies.filter { it.required }.map { it.projectId }

                        db.install(
                            InstalledMod(
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
                            )
                        )
                        dependencyRelations.add(queuedMod.projectId to depIds)
                        registryEntries.add(
                            ModRegistryEntry(
                                projectId = queuedMod.projectId,
                                modId = modId,
                                displayName = displayName,
                                fileName = plan.fileName,
                                source = source.id,
                                versionId = plan.versionId,
                                versionLabel = plan.versionLabel,
                                iconPath = iconPath,
                                fileHash = fileHash,
                                installedAt = Clock.System.now().toEpochMilliseconds(),
                                side = side,
                                releaseType = plan.releaseType?.name?.lowercase() ?: "release",
                                dependencies = depIds,
                            )
                        )
                    }
                    dependencyRelations.forEach { (projectId, depIds) ->
                        db.setDependencies(projectId, depIds)
                    }
                }
                ModRegistryStore(project.projectDir).let { store ->
                    registryEntries.forEach { store.updateEntry(it) }
                }
                "Downloaded ${queued.size} mod(s)"
            }

            if (outcome.isSuccess) {
                queuedDownloads.clear()
                manuallyQueuedIds.clear()
                TritiumEventBus.publish(TritiumEvent.ModsInstalled)
                runOnGuiThread {
                    modified = false
                    renderQueuedDownloads()
                }
            }

            ProjectTaskMngr.finish(taskId)
            runOnGuiThread {
                val message = outcome.getOrElse {
                    logger.warn("Queued mod download failed: {}", it.message, it)
                    it.message ?: "Download failed"
                }
                statusLabel.text = message
            }
        }
    }

    private fun renderQueuedDownloads() {
        refreshQueueStatuses()
        queuedList.updatesEnabled = false
        try {
            queuedList.clear()
            queuedRowWidgets.clear()
            queuedDownloads.values.forEach { queued ->
                addQueuedDownloadItem(queued)
                queueIconLoad(queued.projectId, queued.iconUrl)
            }
        } finally {
            queuedList.updatesEnabled = true
        }
        downloadQueuedButton.isEnabled = queuedDownloads.isNotEmpty()
        removeQueuedButton.isEnabled = queuedList.currentRow() >= 0
    }

    private fun removeQueuedSelection() {
        val item = queuedList.currentItem() ?: return
        val projectId = item.data(Qt.ItemDataRole.UserRole) as? String ?: return
        removeQueuedDownload(projectId)
    }

    private fun removeQueuedDownload(projectId: String) {
        queuedDownloads.remove(projectId)
        manuallyQueuedIds.remove(projectId)
        removeOrphanedQueuedDependencies()
        modified = queuedDownloads.isNotEmpty()
        renderQueuedDownloads()
        refreshAvailableResultItem(projectId)
        if (selectedResultId == projectId) {
            statusLabel.text = "Removed from queue"
        }
    }

    private fun queueIconLoad(key: String, url: String?, targetLabel: QLabel? = null) {
        if (url.isNullOrBlank()) {
            if (targetLabel != null) applyIcon(null, targetLabel)
            return
        }

        iconCache[url]?.let { icon ->
            availableRowWidgets[key]?.iconLabel?.pixmap = icon.pixmap(40, 40)
            queuedRowWidgets[key]?.iconLabel?.pixmap = icon.pixmap(40, 40)
            if (targetLabel != null) targetLabel.pixmap = icon.pixmap(64, 64)
            return
        }

        ioScope.launch {
            var acquired = false
            try {
                iconLoadSemaphore.acquire()
                acquired = true
                val icon = runCatching {
                    val bytes = httpClient.get(url).bodyAsBytes()
                    bytes
                }.getOrElse {
                    logger.debug("Mod icon load failed from {}: {}", url, it.message)
                    null
                }
                runOnGuiThread {
                    val iconObj = if (icon != null) {
                        runCatching {
                            val pixmap = QPixmap()
                            if (!pixmap.loadFromData(icon)) error("Failed to decode icon from $url")
                            QIcon(pixmap)
                        }.getOrElse {
                            logger.debug("Mod icon decode failed from {}: {}", url, it.message)
                            EMPTY_ICON
                        }
                    } else {
                        EMPTY_ICON
                    }
                    iconCache[url] = iconObj
                    if (iconObj !== EMPTY_ICON && !dominantColorCache.containsKey(url)) {
                        val color = extractDominantColor(iconObj.pixmap(40, 40))
                        if (color != null) {
                            dominantColorCache[url] = color
                            if (key == selectedResultId) updateSelectedRowGradient()
                        }
                    }
                    availableRowWidgets[key]?.iconLabel?.pixmap = iconObj.pixmap(40, 40)
                    queuedRowWidgets[key]?.iconLabel?.pixmap = iconObj.pixmap(40, 40)
                    if (targetLabel != null) targetLabel.pixmap = iconObj.pixmap(64, 64)
                    if (currentDependencyIds().contains(key)) {
                        renderDependencyStrip()
                    }
                }
            } finally {
                if (acquired) iconLoadSemaphore.release()
            }
        }
    }

    private fun applyIcon(url: String?, target: QLabel) {
        target.pixmap = if (url.isNullOrBlank()) {
            TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
        } else {
            iconCache[url]?.pixmap(64, 64) ?: TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
        }
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
        val id = selectedResultId ?: return
        val result = resultsById[id] ?: return
        val iconUrl = result.iconUrl ?: return
        val (r, g, b) = dominantColorCache[iconUrl] ?: return
        val item = resultItemsById[id] ?: return
        val gradient = QLinearGradient(0.0, 0.0, 1.0, 0.0).apply {
            setCoordinateMode(QGradient.CoordinateMode.ObjectBoundingMode)
            setColorAt(0.0, QColor(r, g, b, 180))
            setColorAt(1.0, QColor(0, 0, 0, 0))
        }
        item.setBackground(QBrush(gradient))
    }

    private fun renderEmptyState() {
        availableList.clear()
        queuedList.clear()
        resultItemsById.clear()
        resultsById.clear()
        versionsById.clear()
        availableRowWidgets.clear()
        queuedRowWidgets.clear()
        queuedDownloads.clear()
        manuallyQueuedIds.clear()
        navigationBackStack.clear()
        navigationForwardStack.clear()
        dependencyStripSignature = ""
        versionCombo.clear()
        versionCombo.isEnabled = false
        queueButton.isEnabled = false
        downloadQueuedButton.isEnabled = false
        removeQueuedButton.isEnabled = false
        openPageButton.isEnabled = false
        updateNavigationButtons()
        renderDetails(null)
    }

    private fun addAvailableResultItem(result: ModSearchResult) {
        val item = QListWidgetItem().apply {
            setData(Qt.ItemDataRole.UserRole, result.id)
        }
        resultItemsById[result.id] = item
        availableList.addItem(item)
        refreshAvailableResultItem(result.id)
    }

    private fun refreshAvailableResultItem(resultId: String) {
        val result = resultsById[resultId] ?: return
        val item = resultItemsById[resultId] ?: return
        disposeItemWidget(availableList, item)
        val row = createAvailableRow(result)
        availableRowWidgets[result.id] = row
        availableList.setItemWidget(item, row.root)
        item.setSizeHint(row.root.sizeHint())
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

    private fun createAvailableRow(result: ModSearchResult): AvailableRowWidgets {
        val icon = label {
            setFixedSize(40, 40)
            scaledContents = true
            pixmap = listIconPixmap(result.iconUrl)
        }
        val title = label(result.title) {
            val titleFont = font()
            titleFont.setBold(queuedDownloads.containsKey(result.id))
            font = titleFont
        }
        val meta = label(buildAvailableMetaText(result)) {
            wordWrap = true
            styleSheet = "color: ${TColors.Subtext};"
        }
        val root = qWidget().also { widget ->
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
        val root = qWidget().also { widget ->
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
        return QueuedRowWidgets(root = root, iconLabel = icon)
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

    private fun buildResultLabel(result: ModSearchResult): String = buildString {
        append(result.title)
        result.author?.takeIf { it.isNotBlank() }?.let { append("\nby $it") }
        if (result.categories.isNotEmpty()) append("\n${result.categories.joinToString(", ")}")
    }

    private fun buildQueueLabel(queued: QueuedDownload): String = buildString {
        append(queued.title)
        append("\n")
        append(queued.versionLabel)
        if (queued.dependencies.isNotEmpty()) {
            append("\n+ ")
            append(queued.dependencies.size)
            append(" dependencies")
        }
        queued.status.missingDependencies.takeIf { it.isNotEmpty() }?.let {
            append("\nMissing: ")
            append(it.joinToString(", "))
        }
        queued.status.incompatibleWith.takeIf { it.isNotEmpty() }?.let {
            append("\nIncompatible: ")
            append(it.joinToString(", "))
        }
    }

    private fun buildQueueInfoText(queued: QueuedDownload): String =
        if (queued.dependencies.isNotEmpty()) {
            "${queued.dependencies.size} dependenc${if (queued.dependencies.size == 1) "y" else "ies"}"
        } else {
            ""
        }

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

    private fun refreshQueueStatuses() {
        val queuedIds = queuedDownloads.keys.toSet()
        queuedDownloads.replaceAll { _, queued ->
            val missing = queued.dependencies
                .filter { it.required }
                .map { it.projectId }
                .filterNot { it in queuedIds }
                .map { id -> detailsCache[id]?.title ?: resultsById[id]?.title ?: id }
            val incompatible = queued.dependencies
                .filter { it.incompatible }
                .map { it.projectId }
                .filter { it in queuedIds }
                .map { id -> detailsCache[id]?.title ?: resultsById[id]?.title ?: id }
            queued.copy(status = QueueStatus(missingDependencies = missing, incompatibleWith = incompatible))
        }
    }

    private fun projectContext(): ModBrowserContext? {
        val meta = ((project as? Project<*>)?.typedMeta as? ModpackMeta) ?: return null
        return ModBrowserContext(
            project = project,
            minecraftVersion = meta.minecraftVersion,
            modLoaderId = meta.loader
        )
    }

    private fun ModBrowserContext.projectSourceId(): String? =
        ((project as? Project<*>)?.typedMeta as? ModpackMeta)?.source

    private fun fallbackDetails(result: ModSearchResult): ModDetails = ModDetails(
        id = result.id,
        title = result.title,
        summary = result.summary,
        description = result.summary,
        author = result.author,
        downloads = result.downloads,
        categories = result.categories,
        iconUrl = result.iconUrl
    )

    private fun renderDependencyStrip() {
        val signature = buildDependencyStripSignature()
        if (signature == dependencyStripSignature) {
            return
        }
        dependencyStripSignature = signature

        dependencyContent.updatesEnabled = false
        try {
            while (dependencyLayout.count() > 0) {
                val item = dependencyLayout.takeAt(0)
                item?.widget()?.let { widget ->
                    widget.hide()
                    widget.setParent(null)
                    widget.dispose()
                }
            }

            val version = versionCombo.currentData?.let { it as? String }?.let(versionsById::get)
            val dependencies = version?.dependencies
                ?.filterNot { it.incompatible }
                ?.distinctBy { it.projectId }
                .orEmpty()

            dependencyLabel.isVisible = dependencies.isNotEmpty()
            dependencyScroll.isVisible = dependencies.isNotEmpty()

            dependencyLabel.text = if (dependencies.isEmpty()) "Dependencies" else "Dependencies (${dependencies.size})"
            if (dependencies.isEmpty()) {
                dependencyLayout.addStretch(1)
                return
            }

            dependencies.forEach { dependency ->
                val details = detailsCache[dependency.projectId]
                dependencyLayout.addWidget(createDependencyButton(dependency, details))
                when {
                    details == null -> queueDependencyDetailLoad(dependency.projectId)
                    !details.iconUrl.isNullOrBlank() && !iconCache.containsKey(details.iconUrl) ->
                        queueIconLoad(dependency.projectId, details.iconUrl)
                }
            }
            dependencyLayout.addStretch(1)
        } finally {
            dependencyContent.updatesEnabled = true
        }
    }

    private fun currentDependencyIds(): Set<String> =
        versionCombo.currentData?.let { it as? String }
            ?.let(versionsById::get)
            ?.dependencies
            ?.filterNot { it.incompatible }
            ?.map { it.projectId }
            ?.toSet()
            .orEmpty()

    private fun createDependencyButton(dependency: ModDependencyRef, details: ModDetails?): QToolButton =
        QToolButton().apply {
            text = details?.title ?: resultsById[dependency.projectId]?.title ?: dependency.projectId
            toolButtonStyle = Qt.ToolButtonStyle.ToolButtonTextUnderIcon
            iconSize = QSize(48, 48)
            setFixedWidth(96)
            minimumHeight = 88
            autoRaise = true
            icon = details?.iconUrl?.let(iconCache::get) ?: EMPTY_ICON
            clicked.connect { _ -> navigateToResult(dependency.projectId) }
        }

    private fun queueDependencyDetailLoad(projectId: String) {
        ioScope.launch {
            val context = activeContext ?: return@launch
            val source = activeSource ?: return@launch
            runCatching { cachedOrFetchDetails(context, source, projectId) }
                .onFailure { logger.debug("Dependency detail load failed for '{}': {}", projectId, it.message) }
            runOnGuiThread {
                if (selectedResultId != null) renderDependencyStrip()
            }
        }
    }

    private fun navigateToResult(resultId: String) {
        val current = selectedResultId
        if (current != null && current != resultId) {
            navigationBackStack.addLast(current)
            navigationForwardStack.clear()
            updateNavigationButtons()
        }
        selectResult(resultId, syncLists = false)
    }

    private fun navigateBack() {
        val previous = navigationBackStack.removeLastOrNull() ?: return
        selectedResultId?.let(navigationForwardStack::addLast)
        updateNavigationButtons()
        selectResult(previous, syncLists = false)
    }

    private fun navigateForward() {
        val next = navigationForwardStack.removeLastOrNull() ?: return
        selectedResultId?.let(navigationBackStack::addLast)
        updateNavigationButtons()
        selectResult(next, syncLists = false)
    }

    private fun updateNavigationButtons() {
        backButton.isEnabled = navigationBackStack.isNotEmpty()
        forwardButton.isEnabled = navigationForwardStack.isNotEmpty()
    }

    private fun syncSelectedItems(resultId: String?) {
        suppressSelectionEvents = true
        try {
            syncListSelection(availableList, resultId)
            syncListSelection(queuedList, resultId)
        } finally {
            suppressSelectionEvents = false
        }
    }

    private fun syncListSelection(list: QListWidget, resultId: String?) {
        var matchedRow = -1
        for (index in 0 until list.count()) {
            val item = list.item(index) ?: continue
            if ((item.data(Qt.ItemDataRole.UserRole) as? String) == resultId) {
                matchedRow = index
                break
            }
        }
        if (matchedRow >= 0) {
            list.currentRow = matchedRow
        } else {
            list.clearSelection()
            list.currentRow = -1
        }
    }

    private fun removeOrphanedQueuedDependencies() {
        val requiredByQueued = queuedDownloads.values
            .flatMap { queued -> queued.dependencies.filter { it.required }.map { it.projectId } }
            .toMutableSet()

        var removedAny: Boolean
        do {
            removedAny = false
            val orphanIds = queuedDownloads.keys.filter { queuedId ->
                queuedId !in manuallyQueuedIds && queuedId !in requiredByQueued
            }
            if (orphanIds.isNotEmpty()) {
                orphanIds.forEach { orphanId ->
                    queuedDownloads.remove(orphanId)
                    manuallyQueuedIds.remove(orphanId)
                    refreshAvailableResultItem(orphanId)
                }
                requiredByQueued.clear()
                requiredByQueued += queuedDownloads.values
                    .flatMap { queued -> queued.dependencies.filter { it.required }.map { it.projectId } }
                removedAny = true
            }
        } while (removedAny)
    }

    private fun isRequiredByQueuedMod(projectId: String): Boolean =
        queuedDownloads.values.any { queued ->
            queued.projectId != projectId && queued.dependencies.any { it.required && it.projectId == projectId }
        }

    private fun currentLatestCompatibleLabel(details: ModDetails): String? =
        versionsCache[details.id]?.firstOrNull()?.label ?: details.latestVersion

    private fun buildDependencyStripSignature(): String {
        val dependencyIds = currentDependencyIds().sorted()
        return buildString {
            append(selectedResultId ?: "")
            append('|')
            append(versionCombo.currentData as? String ?: "")
            append('|')
            append(dependencyIds.joinToString(","))
            dependencyIds.forEach { dependencyId ->
                append('|')
                append(detailsCache[dependencyId]?.title ?: resultsById[dependencyId]?.title ?: dependencyId)
                append('|')
                append(detailsCache[dependencyId]?.iconUrl ?: "")
                append('|')
                append(detailsCache[dependencyId]?.iconUrl?.let(iconCache::containsKey) == true)
            }
        }
    }

    private fun looksLikeHtml(text: String): Boolean =
        text.contains("<p", ignoreCase = true) ||
            text.contains("<div", ignoreCase = true) ||
            text.contains("<br", ignoreCase = true) ||
            text.contains("<h1", ignoreCase = true) ||
            text.contains("<ul", ignoreCase = true)

    private fun normalizeDescription(text: String): String {
        var normalized = text
            .replace("\r\n", "\n")
            .replace('\uFFFC', '\n')
            .replace(Regex("""\s+---\s+"""), "\n\n---\n\n")
            .replace(Regex("""\s+(#{1,6}\s)"""), "\n\n$1")
            .replace(Regex("""\)\s+(!?\[)"""), ")\n\n$1")
            .replace(Regex("""([.!?])\s+(#{1,6}\s)"""), "$1\n\n$2")
            .replace(Regex("""([^\n])\s+(\d+\.\s+)"""), "$1\n$2")
            .replace(Regex("""([^\n])\s+(✅|📥|⚠️|ℹ️)"""), "$1\n\n$2")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        if (!looksLikeHtml(normalized)) {
            normalized = normalized
                .replace(Regex("""(\*\*[^*]+\*\*)\s+([A-Z][a-z]+)"""), "$1\n\n$2")
        }
        return normalized
    }
}

private data class QueuedDownload(
    val projectId: String,
    val title: String,
    val versionId: String,
    val versionLabel: String,
    val iconUrl: String?,
    val dependencies: List<ModDependencyRef>,
    val status: QueueStatus
)

private data class QueueStatus(
    val missingDependencies: List<String> = emptyList(),
    val incompatibleWith: List<String> = emptyList()
)

private data class AvailableRowWidgets(
    val root: QWidget,
    val iconLabel: QLabel
)

private data class QueuedRowWidgets(
    val root: QWidget,
    val iconLabel: QLabel
)

class ModBrowserPaneProvider : EditorPaneProvider {
    override val id: String = "mod_browser"
    override val displayName: String = "Mod Browser"
    override val order: Int = 4

    override fun canOpen(file: VPath, project: ProjectBase): Boolean =
        file.toAbsolute() == ModBrowserPane.tabPath(project).toAbsolute()

    override fun tabTitle(file: VPath, project: ProjectBase): String = displayName

    override fun tabIcon(file: VPath, project: ProjectBase): QIcon? {
        val meta = ((project as? Project<*>)?.typedMeta as? ModpackMeta) ?: return null
        val source = BuiltinRegistries.ModSource.all().find { it.id == meta.source } ?: return null
        return QIcon(source.icon)
    }

    override fun create(project: ProjectBase, file: VPath): EditorPane = ModBrowserPane(project, file)
}
