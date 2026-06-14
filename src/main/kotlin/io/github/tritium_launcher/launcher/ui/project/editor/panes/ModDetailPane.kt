package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.TritiumEventBus
import io.github.tritium_launcher.launcher.core.project.ModpackMeta
import io.github.tritium_launcher.launcher.core.project.Project
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.source.*
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.ClientIdentity
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.CacheManager
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPaneProvider
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.widgets.RemoteImageTextBrowser
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
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
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ModDetailPane(
    project: ProjectBase,
    private val modId: String
) : EditorPane(project) {
    override val allowAutoSave: Boolean = false

    companion object {
        private val EMPTY_ICON = QIcon(TIcons.Search)
    }

    private val logger = logger()
    private val shared = ModBrowserState.forProject(project)
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

    private val markdownExtensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
    )
    private val markdownParser = Parser.builder()
        .extensions(markdownExtensions)
        .build()
    private val markdownRenderer = HtmlRenderer.builder()
        .extensions(markdownExtensions)
        .escapeHtml(false)
        .build()

    private var detailsJob: Job? = null
    private var activeContext: ModBrowserContext? = null
    private var activeSource: ModSource? = null
    private var detailsData: ModDetails? = null
    private val versionsById = linkedMapOf<String, ModVersionOption>()

    private val iconLabel = label {
        setFixedSize(64, 64)
        scaledContents = true
        pixmap = TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
    }
    private val titleLabel = label { wordWrap = true }
    private val summaryLabel = label { wordWrap = true }
    private val metaLabel = label { wordWrap = true }
    private val sourceLabel = label()
    private val contextLabel = label()
    private val versionLabel = label("Version:")
    private val versionCombo = TComboBox {}
    private val queueButton = TPushButton { text = "Add to Queue"; minimumHeight = 30 }
    private val openPageButton = TPushButton { text = "Open Page"; minimumHeight = 30 }
    private val dependencyLabel = label("Dependencies")
    private val dependencyScroll = QScrollArea()
    private val dependencyContent = qWidget()
    private val dependencyLayout = QHBoxLayout(dependencyContent)
    private val imageCacheDir: VPath = fromTR("cache", "mod-browser", "descriptions")

    private suspend fun cachedImageFetch(url: String): ByteArray {
        val sourceId = activeSource?.id ?: "unknown"
        val cacheFile = imageCacheDir.resolve(sourceId).resolve(urlHash(url))
        cacheFile.bytesOrNull()?.let {
            CacheManager.touch(cacheFile)
            return it
        }
        val bytes = httpClient.get(url).bodyAsBytes()
        if (bytes.isNotEmpty()) {
            runCatching {
                val path = cacheFile.toJPath()
                Files.createDirectories(path.parent)
                Files.write(path, bytes)
            }
            CacheManager.evictIfNeeded(imageCacheDir.parent(), "descriptions")
        }
        return bytes
    }

    private fun urlHash(url: String): String =
        MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private val descriptionView = RemoteImageTextBrowser { url ->
        cachedImageFetch(url)
    }
    private val statusLabel = label("Loading...") { wordWrap = true }
    private val container = qWidget()

    init {
        vBoxLayout(container) {
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
                }
            })

            addWidget(sourceLabel)
            addWidget(contextLabel)
            addWidget(qWidget().also { row ->
                hBoxLayout(row) {
                    setContentsMargins(0, 0, 0, 0)
                    setSpacing(6)
                    addWidget(versionLabel)
                    addWidget(versionCombo)
                    addStretch(1)
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

        versionCombo.currentIndexChanged.connect {
            queueButton.isEnabled = versionCombo.currentData != null
            renderDependencyStrip()
        }
        queueButton.onClicked { queueSelection() }
        openPageButton.onClicked {
            detailsData?.website?.takeIf { it.isNotBlank() }?.let { Platform.openBrowser(it) }
        }

        queueButton.isEnabled = false
        openPageButton.isEnabled = false

        ioScope.launch { CacheManager.evict(imageCacheDir.parent(), "descriptions") }
    }

    override fun onOpen() {
        if (detailsJob != null) return
        loadDetails()
    }

    override fun widget(): QWidget = container

    override suspend fun save(): Boolean {
        modified = false
        return true
    }

    override fun onClose() {
        detailsJob?.cancel()
        ioScope.cancel()
        httpClient.close()
    }

    private fun loadDetails() {
        detailsJob?.cancel()
        val context = projectContext() ?: run {
            statusLabel.text = "The Mod Browser requires a typed Modpack project."
            return
        }
        activeContext = context
        val source = resolveSource() ?: run {
            statusLabel.text = "The project's configured mod source is not registered."
            return
        }
        activeSource = source

        sourceLabel.text = "Source: ${source.displayName}"
        contextLabel.text = buildString {
            append("Minecraft: ")
            append(context.minecraftVersion ?: "unknown")
            append("  |  Loader: ")
            append(context.modLoaderId ?: "unknown")
        }

        val cachedDetails = shared.detailsCache[modId]
        val cachedVersions = shared.versionsCache[modId]
        if (cachedDetails != null && cachedVersions != null) {
            detailsData = cachedDetails
            onTitleChanged?.invoke(cachedDetails.title)
            bindVersions(cachedVersions)
            renderDetails(cachedDetails)
            statusLabel.text = "Loaded ${cachedDetails.title}"
            return
        }

        detailsJob = ioScope.launch {
            logger.info("Loading mod detail: modId={} source={}", modId, source.id)
            runOnGuiThread { statusLabel.text = "Loading details..." }

            val detailsDeferred = async { runCatching { fetchDetails(context, source, modId) } }
            val versionsDeferred = async { runCatching { fetchVersions(context, source, modId) } }
            val details = detailsDeferred.await()
            val versions = versionsDeferred.await()

            val detailObj = details.getOrNull()
            val detailError = details.exceptionOrNull()
            if (detailError != null) {
                logger.warn("Failed loading mod details for '{}': {}", modId, detailError.message, detailError)
            }
            val versionError = versions.exceptionOrNull()
            if (versionError != null) {
                logger.warn("Failed loading mod versions for '{}': {}", modId, versionError.message, versionError)
            }

            val descriptionHtml = withContext(Dispatchers.Default) {
                processDescriptionText(detailObj)
            }

            runOnGuiThread {
                detailsData = detailObj
                detailObj?.title?.let { onTitleChanged?.invoke(it) }
                bindVersions(versions.getOrDefault(emptyList()))
                renderDetails(detailObj, detailError?.message, descriptionHtml)
                statusLabel.text = when {
                    detailError != null -> detailError.message ?: "Failed loading details"
                    else -> "Loaded ${detailObj?.title ?: modId}"
                }
            }
        }
    }

    private fun processDescriptionText(details: ModDetails?): String? {
        if (details == null) return null
        val raw = details.description.ifBlank { details.summary }.takeIf { it.isNotBlank() } ?: return null
        if (activeSource?.descriptionFormat == DescriptionFormat.HTML) return raw
        val normalized = normalizeDescription(raw)
        val doc = markdownParser.parse(normalized)
        return markdownRenderer.render(doc)
    }

    private suspend fun fetchDetails(context: ModBrowserContext, source: ModSource, id: String): ModDetails {
        shared.detailsCache[id]?.let { return it }
        val details = source.details(context, id)
        shared.detailsCache[id] = details
        return details
    }

    private suspend fun fetchVersions(context: ModBrowserContext, source: ModSource, id: String): List<ModVersionOption> {
        shared.versionsCache[id]?.let { return it }
        val versions = source.versions(context, id)
        shared.versionsCache[id] = versions
        return versions
    }

    private fun bindVersions(versions: List<ModVersionOption>) {
        versionCombo.clear()
        versionsById.clear()
        versions.forEach { version ->
            versionsById[version.id] = version
            val suffix = version.releaseType?.let { " (${it.name.lowercase().replaceFirstChar(Char::uppercase)})" } ?: ""
            versionCombo.addItem("${version.label}$suffix", version.id)
        }
        versionCombo.isEnabled = versions.isNotEmpty()
        if (versions.isNotEmpty()) {
            versionCombo.currentIndex = 0
            queueButton.isEnabled = true
        }
        val fm = versionCombo.fontMetrics()
        val maxW = versions.maxOfOrNull { v ->
            val s = v.releaseType?.let { " (${it.name.lowercase().replaceFirstChar(Char::uppercase)})" } ?: ""
            fm.horizontalAdvance("${v.label}$s")
        }?.plus(80) ?: 180
        versionCombo.minimumWidth = maxW.coerceAtLeast(180)
    }

    private fun renderDetails(details: ModDetails?, error: String? = null, descriptionHtml: String? = null) {
        if (details == null) {
            titleLabel.text = modId
            summaryLabel.text = ""
            metaLabel.text = error ?: ""
            applyIcon(null)
            if (descriptionHtml != null) {
                descriptionView.setHtmlContent(descriptionHtml)
            } else {
                renderDescription(error ?: "Failed to load mod details.")
            }
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
        queueIconLoad(details.id, details.iconUrl)
        renderDependencyStrip()
        if (descriptionHtml != null) {
            descriptionView.setHtmlContent(descriptionHtml)
        } else {
            renderDescription(details.description.ifBlank { details.summary })
        }
    }

    private fun renderDescription(raw: String) {
        if (raw.isBlank()) {
            descriptionView.plainText = "No description available."
            return
        }
        if (activeSource?.descriptionFormat == DescriptionFormat.HTML) {
            descriptionView.setHtmlContent(raw)
            return
        }
        val normalized = normalizeDescription(raw)
        val doc = markdownParser.parse(normalized)
        descriptionView.setHtmlContent(markdownRenderer.render(doc))
    }

    private fun applyIcon(url: String?) {
        if (url.isNullOrBlank()) {
            iconLabel.pixmap = TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
            onIconChanged?.invoke(null)
        } else {
            shared.iconCache[url]?.let { icon ->
                onIconChanged?.invoke(icon)
                iconLabel.pixmap = icon.pixmap(64, 64)
            } ?: run {
                iconLabel.pixmap = TIcons.Search.scaled(64, 64, Qt.AspectRatioMode.KeepAspectRatio)
                queueIconLoad(shared.detailsCache[modId]?.id ?: modId, url)
            }
        }
    }

    private fun queueIconLoad(key: String, url: String?) {
        if (url.isNullOrBlank()) return
        shared.iconCache[url]?.let { icon ->
            iconLabel.pixmap = icon.pixmap(64, 64)
            onIconChanged?.invoke(icon)
            return
        }

        ioScope.launch {
            val iconBytes = runCatching {
                httpClient.get(url).bodyAsBytes()
            }.getOrNull() ?: return@launch

            runOnGuiThread {
                val iconObj = runCatching {
                    val pixmap = QPixmap()
                    if (!pixmap.loadFromData(iconBytes)) error("Failed to decode icon")
                    QIcon(pixmap)
                }.getOrElse { EMPTY_ICON }

                shared.iconCache[url] = iconObj
                if (iconObj !== EMPTY_ICON) {
                    iconLabel.pixmap = iconObj.pixmap(64, 64)
                    onIconChanged?.invoke(iconObj)
                }
            }
        }
    }

    private fun renderDependencyStrip() {
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
                val depDetails = shared.detailsCache[dependency.projectId]
                dependencyLayout.addWidget(createDependencyButton(dependency, depDetails))
                if (depDetails == null) {
                    queueDependencyDetailLoad(dependency.projectId)
                }
            }
            dependencyLayout.addStretch(1)
        } finally {
            dependencyContent.updatesEnabled = true
        }
    }

    private fun createDependencyButton(dependency: ModDependencyRef, details: ModDetails?): QToolButton =
        QToolButton().apply {
            text = details?.title ?: dependency.projectId
            toolButtonStyle = Qt.ToolButtonStyle.ToolButtonTextUnderIcon
            iconSize = QSize(48, 48)
            setFixedWidth(96)
            minimumHeight = 88
            autoRaise = true
            icon = details?.iconUrl?.let(shared.iconCache::get) ?: EMPTY_ICON
        }

    private fun queueDependencyDetailLoad(projectId: String) {
        ioScope.launch {
            val context = activeContext ?: return@launch
            val source = activeSource ?: return@launch
            runCatching { fetchDetails(context, source, projectId) }
            runOnGuiThread { renderDependencyStrip() }
        }
    }

    private fun queueSelection() {
        val versionId = versionCombo.currentData as? String ?: return
        val details = detailsData ?: return

        val queued = QueuedDownload(
            projectId = details.id,
            title = details.title,
            versionId = versionId,
            versionLabel = versionsById[versionId]?.label ?: versionId,
            iconUrl = details.iconUrl,
            dependencies = versionsById[versionId]?.dependencies ?: emptyList(),
            status = QueueStatus(),
            projectUrl = details.website,
        )
        shared.queuedDownloads[details.id] = queued
        shared.manuallyQueuedIds += details.id
        TritiumEventBus.publish(TritiumEvent.QueuedDownloadsChanged)
        statusLabel.text = "Queued ${details.title}"
    }

    private fun currentLatestCompatibleLabel(details: ModDetails): String? =
        shared.versionsCache[details.id]?.firstOrNull()?.label ?: details.latestVersion

    private fun resolveSource(): ModSource? {
        val sourceId = ((project as? Project<*>)?.typedMeta as? ModpackMeta)?.source
        return sourceId?.let { id -> BuiltinRegistries.ModSource.all().find { s -> s.id == id } }
    }

    private fun projectContext(): ModBrowserContext? {
        val meta = ((project as? Project<*>)?.typedMeta as? ModpackMeta) ?: return null
        return ModBrowserContext(
            project = project,
            minecraftVersion = meta.minecraftVersion,
            modLoaderId = meta.loader
        )
    }

    private fun normalizeDescription(text: String): String {
        var normalized = text
            .replace("\r\n", "\n")
            .replace('\uFFFC', '\n')
            .replace(Regex("""(?<!\|)\s+---\s+(?!\|)"""), "\n\n---\n\n")
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

    private fun looksLikeHtml(text: String): Boolean =
        text.contains("<p", ignoreCase = true) ||
            text.contains("<div", ignoreCase = true) ||
            text.contains("<br", ignoreCase = true) ||
            text.contains("<h1", ignoreCase = true) ||
            text.contains("<ul", ignoreCase = true)
}

object ModDetailMeta {
    private val titles = ConcurrentHashMap<String, String>()

    fun register(modId: String, title: String) {
        titles[modId] = title
    }

    fun get(modId: String): String? = titles[modId]
}

object ModDetailPaneProvider : EditorPaneProvider {
    override val id: String = "mod_detail"
    override val displayName: String = "Mod Details"
    override val order: Int = 5
    override val singletonGroup: String = "mod_detail"

    override fun canOpen(file: VPath, project: ProjectBase): Boolean = false

    override fun tabTitle(file: VPath, project: ProjectBase): String = "Mod Details"

    override fun tabIcon(file: VPath, project: ProjectBase): QIcon? = null

    override fun create(project: ProjectBase, file: VPath): EditorPane {
        return ModDetailPane(project, "unknown")
    }
}
