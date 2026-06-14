package io.github.tritium_launcher.launcher.import.ui

import io.github.tritium_launcher.launcher.accounts.AccountDescriptor
import io.github.tritium_launcher.launcher.accounts.MicrosoftAuth
import io.github.tritium_launcher.launcher.accounts.ModrinthAccount
import io.github.tritium_launcher.launcher.accounts.ModrinthProject
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.mod.*
import io.github.tritium_launcher.launcher.core.project.*
import io.github.tritium_launcher.launcher.core.source.ModBrowserContext
import io.github.tritium_launcher.launcher.core.source.ModSource
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.import.*
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.notifications.NotificationMngr
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TComboBox
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.*
import io.github.tritium_launcher.launcher.userHome
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.sync.Semaphore as CoroutineSemaphore

class ImportProjectDialog(parent: QWidget? = null) : QDialog(parent) {
    private val stacked = QStackedWidget()
    private val pageSelect = QWidget()
    private val pageReview = QWidget()

    // Import Sources (select page)
    private val launcherScroll = QScrollArea()
    private val launcherCards = mutableListOf<ImportOption>()
    private var selectedCard: ImportOption? = null
    private val instanceList = QListWidget()
    private val instanceListStack = QStackedWidget()
    private val instanceListPlaceholder = QLabel("Select a launcher to see instances.")

    // Instance Info and File Tree (review page)
    private val instanceIconLabel = QLabel()
    private val instanceNameLabel = QLabel()
    private val instanceGameVerLabel = QLabel()
    private val instanceLoaderLabel = QLabel()
    private val instanceLoaderVerLabel = QLabel()

    // Destination (review page)
    private val destPathField = QLineEdit()
    private val destBrowseBtn = TPushButton()

    // Mod Source + Search (review page)
    private val modSourceCombo = TComboBox()
    private val searchField = QLineEdit()
    private val refreshBtn = TPushButton {
        text = "↻"
        toolTip = "Re-validate all mods against source"
        maximumWidth = 32
    }

    // Mod List (review page)
    private val modListWidget = QListWidget()
    private val modListStack = QStackedWidget()
    private val modListPlaceholder = QLabel("Scanning mods...")
    private val importableMods = mutableListOf<ImportableMod>()
    private val modListGuard = Any()

    // Tabbed content (review page)
    private val importTabWidget = QTabWidget()
    private val modsTabPage = QWidget()
    private val filesTabPage = QWidget()

    // File Tree (review page)
    private val fileTree = QTreeWidget()
    private val fileTreeStack = QStackedWidget()
    private val fileTreeLoading = QLabel("Scanning files...")

    // Footer
    private val backBtn = TPushButton { text = "Back" }
    private val importBtn = TPushButton { text = "Import" }
    private val statusLabel = QLabel()

    // Modrinth pack account selector
    private val accountCombo = TComboBox()
    private val browseMrpackCard = QWidget()

    // Modrinth pack info page
    private val pageModrinthInfo = QWidget()
    private val modrinthIconLabel = QLabel()
    private val modrinthTitleLabel = QLabel()
    private val modrinthDescLabel = QLabel()
    private val modrinthPackVerLabel = QLabel()
    private val modrinthMetaRow = QWidget()
    private val modrinthExternalBtn = TPushButton()
    private var currentModrinthProject: ModrinthProject? = null

    // State
    private val detectedLaunchers = mutableListOf<KnownLauncher>()
    private var currentLauncher: KnownLauncher? = null
    private var currentInstance: DetectedInstance? = null
    private var instances: List<DetectedInstance> = emptyList()
    private var modrinthPackMode = false
    private val modrinthPackProjects = mutableListOf<ModrinthProject>()
    private val modrinthAccounts = mutableListOf<AccountDescriptor>()
    private var currentValidationJob: Job? = null
    private var currentScanJob: Job? = null
    private var currentFileTreeJob: Job? = null
    private var currentIconJob: Job? = null
    private var currentModrinthFetchJob: Job? = null

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = logger()

    companion object {
        private val expandedState = mutableMapOf<String, Set<String>>()
        private val dummyProject = ProjectBase("dummy", VPath.get("/tmp"), "dummy", "", JsonObject(emptyMap()))
        private val iconSemaphore = Semaphore(4)
        private val depSemaphore = CoroutineSemaphore(2)
        private val iconCache = ConcurrentHashMap<String, QIcon>()
        private val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
        private val json = Json { prettyPrint = true }
    }

    init {
        windowTitle = "Import Project"
        minimumSize = qs(800, 520)
        objectName = "ImportDialog"

        buildPageSelect()
        buildPageReview()
        buildPageModrinthInfo()
        stacked.addWidget(pageSelect)
        stacked.addWidget(pageReview)
        stacked.addWidget(pageModrinthInfo)

        hBoxLayout(this) {
            setContentsMargins(0, 0, 0, 0)
            addWidget(stacked)
        }

        connectSignals()
        populateLaunchers()
        populateModSources()
        applyStyles()
    }

    private fun applyStyles() {
        setThemedStyle {
            selector("#ImportDialog") {
                backgroundColor(TColors.Surface0)
            }
            selector("#instanceList") {
                border()
                background("transparent")
                padding(4)
            }
            selector("QListView::item") {
                border()
                borderRadius(6)
                background("transparent")
                color(TColors.Text)
                padding(0)
            }
            selector("QFrame#launcherCard") {
                border(1, TColors.Surface1)
                borderRadius(8)
                background("transparent")
                padding(0)
            }
            selector("QFrame#launcherCard:hover") {
                backgroundColor(TColors.Surface1)
            }
            selector("QFrame#launcherCard[selected=\"true\"]") {
                backgroundColor(TColors.SelectedUI)
                border(1, TColors.Accent)
            }
            selector("QLabel#launcherCardName") {
                fontSize(13)
                fontWeight(600)
                color(TColors.Text)
            }
            selector("QLabel#launcherCardSub") {
                fontSize(10)
                color(TColors.Subtext)
            }
            selector("#instanceInfoPanel") {
                backgroundColor(TColors.Surface1)
                padding(12)
            }
            selector("QLabel#instanceName") {
                fontSize(14)
                fontWeight(700)
                color(TColors.Text)
            }
            selector("QLabel#instanceMeta") {
                fontSize(11)
                color(TColors.Subtext)
            }
            selector("QTreeWidget#fileTree") {
                border()
                background("transparent")
                color(TColors.Text)
            }
            selector("QTreeWidget#fileTree::item") {
                padding(2, 4)
                color(TColors.Text)
            }
            selector("QTreeWidget#fileTree::item:selected") {
                backgroundColor(TColors.SelectedUI)
                color(TColors.SelectedText)
            }
            selector("QTreeWidget#fileTree::item:hover") {
                backgroundColor(TColors.Surface2)
            }
            selector("#importFooter") {
                backgroundColor(TColors.Surface0)
                padding(8, 12)
            }
            selector("#sidebar") {
                backgroundColor(TColors.Surface1)
            }
            selector("QScrollArea#launcherScroll") {
                background("transparent")
            }
            selector("QScrollArea#launcherScroll > QWidget") {
                backgroundColor("transparent")
            }
            selector("#sectionHeader") {
                color(TColors.Subtext)
                fontSize(12)
                fontWeight(700)
                padding(8, 12)
            }
            selector("#treeHeader") {
                color(TColors.Subtext)
                fontSize(10)
                padding(8, 12)
            }
            selector("#emptyHint") {
                color(TColors.Subtext)
            }

            selector("#destBar") {
                backgroundColor(TColors.Surface1)
                padding(8, 12)
                border(1, TColors.Surface2, "bottom")
            }
            selector("#modListWidget") {
                border()
                background("transparent")
                padding(4)
            }
            selector("QListView#modListWidget::item") {
                padding(4)
            }
            selector("QTabWidget#importTabWidget > QTabBar") {
                background("transparent")
                color(TColors.Subtext)
                padding(0, 4)
            }
            selector("QTabWidget#importTabWidget > QTabBar::tab") {
                padding(8, 20, 8, 20)
                color(TColors.Subtext)
                background("transparent")
                margin(0, 0)
                border()
                border(2, "transparent", "bottom")
            }
            selector("QTabWidget#importTabWidget > QTabBar::tab:selected") {
                color(TColors.Text)
                border(2, TColors.Accent, "bottom")
            }
            selector("QTabWidget#importTabWidget > QTabBar::tab:hover:!selected") {
                color(TColors.Text)
            }
            selector("#statusLabel") {
                color(TColors.Subtext)
                fontSize(10)
                padding(0, 12)
            }

            selector("QFrame#importableModRow") {
                border()
                borderRadius(6)
                background("transparent")
                padding(2)
            }
            selector("QFrame#importableModRow:hover") {
                backgroundColor(TColors.Surface2)
            }
            selector("QLabel#importableModName") {
                fontSize(12)
                fontWeight(600)
                color(TColors.Text)
            }
            selector("QLabel#importableModMeta") {
                fontSize(10)
                color(TColors.Subtext)
            }
            selector("QLabel#badgeAvailable") {
                fontSize(10)
                color(TColors.Green)
                padding(2, 6)
                borderRadius(4)
            }
            selector("QLabel#badgeNameMatch") {
                fontSize(10)
                color(TColors.Warning)
                padding(2, 6)
                borderRadius(4)
            }
            selector("QLabel#badgeUnavailable") {
                fontSize(10)
                color(TColors.Error)
                padding(2, 6)
                borderRadius(4)
            }
            selector("QLabel#badgeChecking") {
                fontSize(10)
                color(TColors.Warning)
                padding(2, 6)
                borderRadius(4)
            }
            selector("QLabel#badgeUnknown") {
                fontSize(10)
                color(TColors.Subtext)
                padding(2, 6)
                borderRadius(4)
            }
        }
    }

    private fun buildPageSelect() {
        hBoxLayout(pageSelect) {
            setContentsMargins(0, 0, 0, 0)

            val sidebar = QWidget()
            sidebar.objectName = "sidebar"
            sidebar.minimumWidth = 220
            sidebar.maximumWidth = 260
            vBoxLayout(sidebar) {
                setContentsMargins(0, 0, 0, 0)
                setSpacing(0)
                addWidget(label("Import from...") { objectName = "sectionHeader"; setAlignment(Qt.AlignmentFlag.AlignCenter) })

                launcherScroll.apply {
                    widgetResizable = true
                    frameShape = QFrame.Shape.NoFrame
                    objectName = "launcherScroll"
                    verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                    horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                }

                val scrollContent = QWidget()
                vBoxLayout(scrollContent) {
                    setContentsMargins(8, 8, 8, 8)
                    setSpacing(4)
                }
                launcherScroll.setWidget(scrollContent)
                launcherScroll.viewport()?.autoFillBackground = false
                scrollContent.autoFillBackground = false
                addWidget(launcherScroll, 1)
            }
            addWidget(sidebar, 0)

            val rightSide = qWidget()
            vBoxLayout(rightSide) {
                setSpacing(0)

                val headerRow = qWidget()
                hBoxLayout(headerRow) {
                    setContentsMargins(0, 0, 0, 0)
                    addWidget(label("Instances") { objectName = "sectionHeader" })
                    addStretch()
                    accountCombo.apply {
                        sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
                        visible = false
                    }
                    addWidget(accountCombo)
                }
                addWidget(headerRow)

                instanceListPlaceholder.objectName = "emptyHint"
                instanceListPlaceholder.setAlignment(Qt.AlignmentFlag.AlignCenter)

                instanceList.apply {
                    objectName = "instanceList"
                    selectionMode = QAbstractItemView.SelectionMode.SingleSelection
                    iconSize = qs(32, 32)
                    frameShape = QFrame.Shape.NoFrame
                    spacing = 0
                    uniformItemSizes = true
                    verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
                }

                instanceListStack.apply {
                    addWidget(instanceListPlaceholder)
                    addWidget(instanceList)
                    currentIndex = 0
                }
                addWidget(instanceListStack, 1)

                browseMrpackCard.apply {
                    visible = false
                    hBoxLayout(this) {
                        setContentsMargins(12, 4, 12, 4)
                        setSpacing(8)
                        addStretch()
                        addWidget(label("From File:") {
                            styleSheet = "color: ${TColors.Text}; font-size: 12px;"
                        })
                    val browseBtn = TPushButton {
                        text = "Browse"
                        minimumWidth = 80
                        minimumHeight = 40
                        cursor = QCursor(Qt.CursorShape.PointingHandCursor)
                        clicked.connect { onMrpackBrowse() }
                    }
                        addWidget(browseBtn)
                        addStretch()
                    }
                }
                addWidget(browseMrpackCard)
            }
            addWidget(rightSide, 1)
        }
    }

    private fun buildPageReview() {
        vBoxLayout(pageReview) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)

            // Destination bar
            val destBar = widget { objectName = "destBar" }
            hBoxLayout(destBar) {
                setContentsMargins(12, 8, 12, 8)
                setSpacing(8)
                addWidget(label("Import to:"))
                destPathField.apply {
                    text = "~/tritium/projects/"
                    minimumWidth = 300
                    sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
                }
                addWidget(destPathField, 1)
                destBrowseBtn.apply {
                    icon = QIcon(TIcons.Folder)
                    text = "Browse"
                    minimumWidth = 80
                    sizePolicy = QSizePolicy(QSizePolicy.Policy.Minimum, QSizePolicy.Policy.Fixed)
                }
                addWidget(destBrowseBtn, 0)
            }
            addWidget(destBar)

            // Main content: info panel + mods/files
            val contentArea = QWidget()
            hBoxLayout(contentArea) {
                setContentsMargins(0, 0, 0, 0)
                setSpacing(0)

                // Left: instance info panel
                val infoPanel = widget {
                    objectName = "instanceInfoPanel"
                    minimumWidth = 180
                    maximumWidth = 180
                }
                vBoxLayout(infoPanel) {
                    setContentsMargins(12, 12, 12, 12)
                    setSpacing(8)

                    instanceIconLabel.apply {
                        setFixedSize(48, 48)
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    }
                    addWidget(instanceIconLabel, 0, Qt.AlignmentFlag.AlignCenter)

                    instanceNameLabel.apply { objectName = "instanceName"; wordWrap = true }
                    addWidget(instanceNameLabel, 0, Qt.AlignmentFlag.AlignCenter)

                    fun metaRow(key: String, label: QLabel): QWidget {
                        val row = qWidget()
                        hBoxLayout(row) {
                            setContentsMargins(0, 0, 0, 0)
                            setSpacing(8)
                            addWidget(label("$key:") { styleSheet = "color: ${TColors.Subtext}; font-weight: bold;"; setFixedWidth(80) })
                            addWidget(label.apply { objectName = "instanceMeta"; wordWrap = true }, 1)
                        }
                        return row
                    }

                    addWidget(metaRow("Game", instanceGameVerLabel))
                    addWidget(metaRow("Loader", instanceLoaderLabel))
                    addWidget(metaRow("Version", instanceLoaderVerLabel))
                    addStretch(1)
                }
                addWidget(infoPanel, 0)

                // Right: mod source + search + mod list + files
                val rightPanel = qWidget()
                vBoxLayout(rightPanel) {
                    setContentsMargins(0, 0, 0, 0)
                    setSpacing(0)

                    // Mod source + search bar (with bottom border)
                    val sourceSearchBar = widget {
                        objectName = "modSourceSearchBar"
                        styleSheet = "border-bottom: 1px solid ${TColors.Surface2};"
                    }
                    hBoxLayout(sourceSearchBar) {
                        setContentsMargins(12, 8, 12, 8)
                        setSpacing(8)
                        addWidget(label("Mod Source:"))
                        modSourceCombo.apply {
                            minimumWidth = 140
                            sizeAdjustPolicy = QComboBox.SizeAdjustPolicy.AdjustToContents
                        }
                        addWidget(modSourceCombo, 0)
                        addStretch(1)
                        searchField.apply {
                            placeholderText = "Search mods..."
                            minimumWidth = 200
                        }
                        addWidget(searchField, 1)
                        refreshBtn.apply {
                            objectName = "refreshBtn"
                        }
                        addWidget(refreshBtn, 0)
                    }
                    addWidget(sourceSearchBar)

                    // Tabbed content: Mods / Files
                    importTabWidget.apply {
                        objectName = "importTabWidget"
                        tabBarAutoHide = false
                        documentMode = true

                        // --- Mods tab ---
                        vBoxLayout(modsTabPage) {
                            setContentsMargins(12, 0, 12, 0)
                            setSpacing(0)

                            modListPlaceholder.setAlignment(Qt.AlignmentFlag.AlignCenter)
                            modListPlaceholder.objectName = "emptyHint"

                            modListWidget.apply {
                                objectName = "modListWidget"
                                selectionMode = QAbstractItemView.SelectionMode.NoSelection
                                focusPolicy = Qt.FocusPolicy.NoFocus
                                spacing = 2
                                frameShape = QFrame.Shape.NoFrame
                            }

                            modListStack.apply {
                                addWidget(modListPlaceholder)
                                addWidget(modListWidget)
                                currentIndex = 0
                            }
                            addWidget(modListStack, 1)
                        }
                        addTab(modsTabPage, "Mods")

                        // --- Files tab ---
                        vBoxLayout(filesTabPage) {
                            setContentsMargins(12, 0, 12, 0)
                            setSpacing(0)

                            fileTree.apply {
                                objectName = "fileTree"
                                header()?.isVisible = false
                                rootIsDecorated = true
                                animated = true
                                indentation = 16
                            }
                            fileTreeLoading.setAlignment(Qt.AlignmentFlag.AlignCenter)
                            fileTreeLoading.objectName = "emptyHint"

                            fileTreeStack.apply {
                                addWidget(fileTreeLoading)
                                addWidget(fileTree)
                                currentIndex = 0
                            }
                            addWidget(fileTreeStack, 1)
                        }
                        addTab(filesTabPage, "Files")
                    }
                    addWidget(importTabWidget, 1)
                }
                addWidget(rightPanel, 1)
            }
            addWidget(contentArea, 1)

            // Status label
            statusLabel.apply {
                objectName = "statusLabel"
                text = ""
            }
            addWidget(statusLabel)

            // Footer
            val footer = widget { objectName = "importFooter" }
            hBoxLayout(footer) {
                addStretch(1)
                addWidget(backBtn.apply { minimumHeight = 36 })
                addWidget(importBtn.apply { minimumHeight = 36 })
            }
            addWidget(footer, 0)
        }
    }

    private fun buildPageModrinthInfo() {
        vBoxLayout(pageModrinthInfo) {
            setContentsMargins(0, 0, 0, 0)
            setSpacing(0)

            val centerArea = qWidget()
            vBoxLayout(centerArea) {
                setContentsMargins(24, 24, 24, 24)
                setSpacing(12)

                addStretch(1)

                modrinthIconLabel.apply {
                    setFixedSize(64, 64)
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                }
                addWidget(modrinthIconLabel, 0, Qt.AlignmentFlag.AlignCenter)

                modrinthTitleLabel.apply {
                    styleSheet = "font-size: 18px; font-weight: 700; color: ${TColors.Text};"
                    wordWrap = true
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                }
                addWidget(modrinthTitleLabel, 0, Qt.AlignmentFlag.AlignCenter)

                modrinthDescLabel.apply {
                    styleSheet = "font-size: 12px; color: ${TColors.Subtext};"
                    wordWrap = true
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                    maximumWidth = 600
                    minimumHeight = 48
                }
                addWidget(modrinthDescLabel, 0, Qt.AlignmentFlag.AlignCenter)

                addSpacing(8)

                hBoxLayout(modrinthMetaRow) {
                    setContentsMargins(0, 0, 0, 0)
                    setSpacing(16)
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                }
                addWidget(modrinthMetaRow, 0, Qt.AlignmentFlag.AlignCenter)

                addSpacing(16)

                val packVerCol = qWidget()
                vBoxLayout(packVerCol) {
                    setContentsMargins(0, 0, 0, 0)
                    setSpacing(4)
                    setAlignment(Qt.AlignmentFlag.AlignCenter)
                    addWidget(label("Pack Version") {
                        styleSheet = "font-size: 10px; font-weight: 700; color: ${TColors.Subtext}; text-transform: uppercase; letter-spacing: 1px;"
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    })
                    modrinthPackVerLabel.apply {
                        styleSheet = "font-size: 13px; color: ${TColors.Text};"
                        setAlignment(Qt.AlignmentFlag.AlignCenter)
                    }
                    addWidget(modrinthPackVerLabel)
                }
                addWidget(packVerCol, 0, Qt.AlignmentFlag.AlignCenter)

                addSpacing(12)

                modrinthExternalBtn.apply {
                    objectName = "modrinthExtBtn"
                    text = "View on Modrinth"
                    minimumWidth = 200
                    minimumHeight = 36
                    styleSheet = qtStyle {
                        selector("modrinthExtBtn") {
                            fontSize(13)
                            fontWeight(600)
                            background("transparent")
                            border(1, TColors.Accent)
                            color(TColors.Accent)
                            borderRadius(6)
                        }
                    }.toStyleSheet()
                    cursor = QCursor(Qt.CursorShape.PointingHandCursor)
                    clicked.connect {
                        val project = currentModrinthProject ?: return@connect
                        val url = if (project.slug != null) "https://modrinth.com/modpack/${project.slug}"
                            else "https://modrinth.com/project/${project.id}"
                        Platform.openBrowser(url)
                    }
                }
                addWidget(modrinthExternalBtn, 0, Qt.AlignmentFlag.AlignCenter)

                addStretch(1)
            }
            addWidget(centerArea, 1)

            // Footer
            val footer = widget { objectName = "importFooter" }
            hBoxLayout(footer) {
                addStretch(1)
                val modrinthBackBtn = TPushButton {
                    text = "Back"
                    minimumHeight = 36
                    clicked.connect {
                        currentModrinthProject = null
                        stacked.currentIndex = 0
                    }
                }
                addWidget(modrinthBackBtn)
                val modrinthImportBtn = TPushButton {
                    text = "Import"
                    minimumHeight = 36
                    clicked.connect {
                        val project = currentModrinthProject ?: return@connect
                        statusLabel.text = "Modpack '${project.title}' import coming soon..."
                    }
                }
                addWidget(modrinthImportBtn)
            }
            addWidget(footer, 0)
        }
    }

    private fun connectSignals() {
        instanceList.itemDoubleClicked.connect {
            if (modrinthPackMode) {
                val idx = instanceList.row(it)
                if (idx >= 0 && idx < modrinthPackProjects.size) {
                    onModrinthPackSelected(modrinthPackProjects[idx])
                }
                return@connect
            }
            val idx = instanceList.row(it)
            if (idx >= 0 && idx < instances.size) onInstanceSelected(instances[idx])
        }

        backBtn.clicked.connect {
            currentFileTreeJob?.cancel()
            currentScanJob?.cancel()
            currentValidationJob?.cancel()
            currentIconJob?.cancel()
            currentModrinthFetchJob?.cancel()
            currentInstance?.let { saveExpandedState(fileTree, it, expandedState) }
            stacked.currentIndex = 0
            currentInstance = null
            importableMods.clear()
            modrinthPackMode = false
            modrinthPackProjects.clear()
            cleanupMrpackTemp(mrpackTempDir)
            mrpackTempDir = null
        }

        importBtn.clicked.connect { onImport() }

        destBrowseBtn.clicked.connect {
            val chosen = QFileDialog.getExistingDirectory(this, "Select Destination Directory", destPathField.text)
            if (!chosen.isNullOrBlank()) {
                destPathField.text = chosen
            }
        }

        searchField.textChanged.connect { filterModsBySearch(it) }

        refreshBtn.clicked.connect {
            val source = modSourceCombo.currentData as? ModSource
            val instance = currentInstance
            if (source != null && instance != null) {
                deleteImportCache(instance, source.id)
                validateModsAgainstSource(source)
            }
        }

        modSourceCombo.currentIndexChanged.connect {
            val source = modSourceCombo.currentData as? ModSource
            if (source != null && currentInstance != null) {
                validateModsAgainstSource(source)
            }
        }

        accountCombo.currentIndexChanged.connect {
            if (!modrinthPackMode) return@connect
            val provider = BuiltinRegistries.AccountProvider.get("modrinth_account") as? ModrinthAccount ?: return@connect
            val accountId = accountCombo.currentData as? String ?: return@connect
            currentModrinthFetchJob?.cancel()
            fetchProjectsForSelectedAccount(provider, accountId)
        }
    }

    // --- Launcher selection ---

    private fun populateLaunchers() {
        detectedLaunchers.clear()
        launcherCards.clear()
        selectedCard = null

        val content = launcherScroll.widget()
        val layout = content?.layout() as? QVBoxLayout ?: return

        fun sectionLabel(text: String) = label(text) { objectName = "sectionHeader" }

        // ---- Launchers ----
        layout.addWidget(sectionLabel("Launchers"))

        detectedLaunchers.addAll(LauncherDetector.detectInstalled().sortedBy { it.displayName })

        for (launcher in detectedLaunchers) {
            val card = createImportOption(launcher, withSubtitle = true)
            card.onClick = { l ->
                selectCard(card)
                onLauncherSelected(l)
            }
            layout.addWidget(card)
            launcherCards.add(card)
        }

        // ---- Modpacks ----
        layout.addWidget(sectionLabel("Modpack"))

        val cursePackCard = ImportOption(KnownLauncher.CURSEFORGE_PACK).apply {
            setIcon(iconForLauncher(KnownLauncher.CURSEFORGE_PACK, 32))
            nameLabel.text = "CurseForge Pack"
            subtitleLabel.text = "Select a modpack archive..."
            onClick = {
                selectCard(this)
                onLauncherSelected(launcher)
            }
        }
        layout.addWidget(cursePackCard)
        launcherCards.add(cursePackCard)

        val modrinthPackCard = ImportOption(KnownLauncher.MODRINTH_PACK).apply {
            setIcon(iconForLauncher(KnownLauncher.MODRINTH_PACK, 32))
            nameLabel.text = "Modrinth Pack"
            subtitleLabel.text = "Select a modpack archive..."
            onClick = {
                selectCard(this)
                onLauncherSelected(launcher)
            }
        }
        layout.addWidget(modrinthPackCard)
        launcherCards.add(modrinthPackCard)

        // ---- Tritium ----
        layout.addWidget(sectionLabel("Tritium"))

        val browseCard = ImportOption(KnownLauncher.BROWSE_FOLDER).apply {
            setIcon(iconForLauncher(KnownLauncher.BROWSE_FOLDER, 32))
            nameLabel.text = "Existing Project"
            subtitleLabel.text = "Select a folder..."
            onClick = { selectCard(this); onLauncherSelected(launcher) }
        }
        layout.addWidget(browseCard)
        launcherCards.add(browseCard)

        layout.addStretch(1)

        if (detectedLaunchers.isNotEmpty() && launcherCards.isNotEmpty()) {
            val first = launcherCards[0]
            selectCard(first)
            onLauncherSelected(first.launcher)
        }
    }

    private fun createImportOption(launcher: KnownLauncher, withSubtitle: Boolean = false): ImportOption {
        val card = ImportOption(launcher)
        card.setIcon(iconForLauncher(launcher, 32))
        card.nameLabel.text = launcher.displayName
        if (withSubtitle) {
            val existingDirs = launcher.instanceDirs.count { it.exists() }
            card.subtitleLabel.text = "$existingDirs location${if (existingDirs != 1) "s" else ""}"
        }
        return card
    }

    private fun populateModSources() {
        modSourceCombo.clear()
        modSourceCombo.addItem("Choose...", null)
        val sources = BuiltinRegistries.ModSource.all().sortedBy { it.order }
        for (source in sources) {
            modSourceCombo.addItem(source.displayName, source)
        }
        modSourceCombo.currentIndex = 0
    }

    private fun selectCard(card: ImportOption) {
        selectedCard?.setSelected(false)
        card.setSelected(true)
        selectedCard = card
    }

    private fun onLauncherSelected(launcher: KnownLauncher) {
        modrinthPackMode = false
        modrinthPackProjects.clear()
        accountCombo.visible = false
        browseMrpackCard.visible = false
        if (launcher.id == "_browse") { openBrowseDialog(); return }
        if (launcher.id == "_cursepack") {
            instanceListPlaceholder.text = "Select a modpack archive to import."
            instanceListStack.currentIndex = 0
            return
        }
        if (launcher.id == "_modrinthpack") {
            currentModrinthFetchJob?.cancel()
            modrinthPackMode = true
            instances = emptyList()
            modrinthPackProjects.clear()
            modrinthAccounts.clear()
            instanceList.clear()
            instanceListPlaceholder.text = ""
            instanceListStack.currentIndex = 0
            accountCombo.visible = false
            browseMrpackCard.visible = false
            currentModrinthFetchJob = ioScope.launch {
                val provider = BuiltinRegistries.AccountProvider.get("modrinth_account") as? ModrinthAccount
                if (provider == null) {
                    withContext(Dispatchers.Main) { onMrpackBrowse() }
                    return@launch
                }
                val accounts = withContext(Dispatchers.IO) { provider.listAccounts() }
                if (accounts.isEmpty()) {
                    withContext(Dispatchers.Main) { onMrpackBrowse() }
                    return@launch
                }
                val accountIcons = mutableMapOf<String, QIcon>()
                for (acc in accounts) {
                    if (acc.avatarUrl != null) {
                        try {
                            val bytes = httpClient.get(acc.avatarUrl).bodyAsBytes()
                            val pix = QPixmap()
                            if (pix.loadFromData(bytes)) {
                                val mode = if (pix.width() <= 64 || pix.height() <= 64)
                                    Qt.TransformationMode.FastTransformation else Qt.TransformationMode.SmoothTransformation
                                accountIcons[acc.id] = QIcon(pix.scaled(16, 16, Qt.AspectRatioMode.KeepAspectRatio, mode))
                            }
                        } catch (_: Exception) {}
                    }
                }
                withContext(Dispatchers.Main) {
                    modrinthAccounts.clear()
                    modrinthAccounts.addAll(accounts)
                    accountCombo.clear()
                    accountCombo.blockSignals(true)
                    accountCombo.visible = true
                    browseMrpackCard.visible = true
                    for (acc in accounts) {
                        val icon = accountIcons[acc.id] ?: QIcon()
                        accountCombo.addItem(icon, acc.label ?: acc.username ?: acc.id, acc.id)
                    }
                    accountCombo.minimumWidth = 200
                    accountCombo.currentIndex = 0
                    accountCombo.blockSignals(false)
                    fetchProjectsForSelectedAccount(provider, accounts[0].id)
                }
            }
            return
        }
        currentLauncher = launcher

        val items = LauncherDetector.scanInstances(launcher).sortedWith(compareBy({ it.name.firstOrNull()?.isLetter() != true }, { it.name.lowercase() }))
        instances = items
        instanceList.clear()

        if (items.isEmpty()) {
            instanceListPlaceholder.text = "No instances found for ${launcher.displayName}."
            instanceListStack.currentIndex = 0
            return
        }

        for (i in items) {
            val widget = InstanceItemWidget(i)
            val item = QListWidgetItem().apply {
                setSizeHint(widget.sizeHint())
            }
            instanceList.addItem(item)
            instanceList.setItemWidget(item, widget)
        }
        instanceListStack.currentIndex = 1
    }

    private fun fetchProjectsForSelectedAccount(provider: ModrinthAccount, accountId: String) {
        currentModrinthFetchJob?.cancel()
        modrinthPackProjects.clear()
        instanceList.clear()
        instanceListPlaceholder.text = "Loading Modrinth projects..."
        instanceListStack.currentIndex = 0
        currentModrinthFetchJob = ioScope.launch {
            val projects = withContext(Dispatchers.IO) {
                provider.fetchModpackProjectsForAccount(accountId)
            }
            withContext(Dispatchers.Main) {
                if (projects.isEmpty()) {
                    onMrpackBrowse()
                    return@withContext
                }
                modrinthPackProjects.clear()
                modrinthPackProjects.addAll(projects)
                instanceList.clear()
                for (project in projects) {
                    val widget = ModrinthPackItemWidget(project)
                    val item = QListWidgetItem().apply {
                        setSizeHint(widget.sizeHint())
                    }
                    instanceList.addItem(item)
                    instanceList.setItemWidget(item, widget)
                    if (project.iconUrl != null) {
                        fetchModrinthPackIcon(widget, project.iconUrl)
                    }
                }
                instanceListStack.currentIndex = 1
            }
        }
    }

    private var mrpackTempDir: VPath? = null

    private fun onMrpackBrowse() {
        val chosen = QFileDialog.getOpenFileName(this, "Select Modrinth Pack", userHome.toString(), "Modrinth Pack (*.mrpack)")
        @Suppress("UNNECESSARY_SAFE_CALL")
        val path = chosen?.result?.trim()
        if (path.isNullOrBlank()) return
        statusLabel.text = "Extracting modpack..."
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                extractAndPrepareMrpack(path, httpClient)
            }
            withContext(Dispatchers.Main) {
                if (result != null) {
                    mrpackTempDir = result.tempDir
                    onMrpackInstanceReady(result.instance)
                } else {
                    statusLabel.text = ""
                    QMessageBox.warning(this@ImportProjectDialog, "Invalid File", "Could not read modrinth.index.json from the selected file.")
                }
            }
        }
    }

    private fun onMrpackInstanceReady(instance: DetectedInstance) {
        currentLauncher = KnownLauncher.BROWSE_FOLDER
        instances = listOf(instance)
        onInstanceSelected(instance)
    }

    private fun openBrowseDialog() {
        val prev = currentLauncher
        val chosen = QFileDialog.getExistingDirectory(this, "Select Instance Directory", userHome.toString())
        if (chosen.isNullOrBlank()) { restoreSelection(prev); return }

        val dir = VPath.get(chosen)
        val instance = LauncherDetector.inspectDirectory(dir)
        if (instance != null) {
            onInstanceSelected(instance)
        } else {
            QMessageBox.warning(this, "Invalid Directory", "Selected directory does not contain a recognizable instance.")
            restoreSelection(prev)
        }
    }

    private fun restoreSelection(launcher: KnownLauncher?) {
        val card = launcherCards.firstOrNull { it.launcher.id == launcher?.id }
        if (card != null) {
            selectCard(card)
            onLauncherSelected(launcher!!)
        } else {
            selectedCard?.setSelected(false)
            selectedCard = null
        }
    }

    // --- Instance selected: scan mods + show review page ---

    private fun onInstanceSelected(instance: DetectedInstance) {
        currentScanJob?.cancel()
        currentValidationJob?.cancel()
        currentIconJob?.cancel()
        currentInstance?.let { saveExpandedState(fileTree, it, expandedState) }
        currentInstance = instance
        instanceNameLabel.text = instance.name
        instanceGameVerLabel.text = instance.gameVersion ?: "Unknown"
        instanceLoaderLabel.text = instance.loader ?: "Unknown"
        instanceLoaderVerLabel.text = instance.loaderVersion ?: "Unknown"

        val cleanName = instance.name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim()
        destPathField.text = "~/tritium/projects/$cleanName"

        val iconPath = LauncherDetector.resolveInstanceIcon(instance)
        val pixmap = if (iconPath != null) QPixmap(iconPath.toAbsolute().toString()) else QPixmap()
        if (!pixmap.isNull) {
            instanceIconLabel.pixmap = pixmap.scaled(qs(48, 48), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
        } else {
            instanceIconLabel.pixmap = QPixmap()
            instanceIconLabel.text = instance.name.take(2).uppercase()
        }

        stacked.currentIndex = 1
        populateFileTreeAsync(instance)
        scanInstanceMods(instance)
    }

    private fun scanInstanceMods(instance: DetectedInstance) {
        importableMods.clear()
        modListWidget.clear()
        modListPlaceholder.text = "Scanning mods..."
        modListStack.currentIndex = 0

        val modsDir = instance.minecraftDir.resolve("mods")
        if (!modsDir.exists() || !modsDir.isDir()) {
            modListPlaceholder.text = "No mods directory found in this instance."
            modListStack.currentIndex = 0
            populateModList()
            return
        }

        currentScanJob?.cancel()
        currentScanJob = ioScope.launch {
            val jars = withContext(Dispatchers.IO) {
                modsDir.listFiles { f -> f.fileName().endsWith(".jar", ignoreCase = true) }
            }

            val source = modSourceCombo.currentData as? ModSource
            val defaultAvailable = source != null
            val defaultStatus = if (source != null) "Not Available" else null

            val scanned = jars.mapNotNull { jarPath ->
                try {
                    val bytes = withContext(Dispatchers.IO) { jarPath.toJFile().readBytes() }
                    val sha1Hash = computeSha1(bytes)
                    val fileFingerprint = source?.computeFileFingerprint(bytes)
                    val info = readModJarInfo(jarPath)
                    if (info == null) {
                        log.info("Could not read mod metadata from '{}', treating as generic jar", jarPath.fileName())
                        ImportableMod(
                            jarPath = jarPath,
                            modId = jarPath.fileName().removeSuffix(".jar"),
                            displayName = jarPath.fileName().removeSuffix(".jar"),
                            fileName = jarPath.fileName(),
                            side = ModSide.BOTH,
                            iconBytes = null,
                            sha1Hash = sha1Hash,
                            fileFingerprint = fileFingerprint,
                            sourceAvailable = if (defaultAvailable) false else null,
                            sourceStatus = defaultStatus,
                            checked = true
                        )
                    } else {
                        val iconBytes = readModJarIcon(jarPath)
                        ImportableMod(
                            jarPath = jarPath,
                            modId = info.modId,
                            displayName = info.displayName,
                            fileName = jarPath.fileName(),
                            side = info.side,
                            iconBytes = iconBytes,
                            sha1Hash = sha1Hash,
                            fileFingerprint = fileFingerprint,
                            sourceAvailable = if (defaultAvailable) false else null,
                            sourceStatus = defaultStatus,
                            checked = true
                        )
                    }
                } catch (t: Throwable) {
                    log.warn("Failed to scan mod jar '{}': {}", jarPath.fileName(), t.message)
                    null
                }
            }

            // Check cache before switching to Main
            val cachedFromCache = if (source != null && scanned.isNotEmpty()) {
                tryLoadImportCache(instance, source.id, scanned)
            } else null

            withContext(Dispatchers.Main) {
                synchronized(modListGuard) {
                    importableMods.clear()
                    if (cachedFromCache != null) {
                        log.warn("Using cached validation for {} mods (source: {})", cachedFromCache.size, source!!.id)
                        importableMods.addAll(cachedFromCache)
                    } else {
                        importableMods.addAll(scanned)
                    }
                }
                populateModList()

                if (source != null && scanned.isNotEmpty() && cachedFromCache == null) {
                    validateModsAgainstSource(source)
                }
            }
        }
    }

    private fun updateModsTabLabel(total: Int, checked: Int) {
        importTabWidget.setTabText(0, "Mods  $checked/$total")
    }

    private fun populateModList(filteredSubset: List<ImportableMod>? = null) {
        val allMods: List<ImportableMod>
        synchronized(modListGuard) {
            allMods = filteredSubset ?: importableMods.toList()
        }

        modListWidget.clear()

        if (allMods.isEmpty()) {
            modListPlaceholder.text = if (filteredSubset != null) "No mods match your search." else "No mods found in this instance."
            modListStack.currentIndex = 0
            return
        }

        modListStack.currentIndex = 1

        val checkCount = allMods.count { it.checked }
        updateModsTabLabel(allMods.size, checkCount)

        allMods.forEachIndexed { index, mod ->
            val item = QListWidgetItem()
            item.setData(Qt.ItemDataRole.UserRole, index)
            val row = ImportableModRow(mod, index, { idx, checked ->
                synchronized(modListGuard) {
                    if (idx in importableMods.indices) {
                        importableMods[idx] = importableMods[idx].copy(checked = checked)
                        // If checked and source is active, fetch dependencies
                        if (checked) {
                            val src = modSourceCombo.currentData as? ModSource
                            val instance = currentInstance
                            if (src != null && instance != null) {
                                resolveDependenciesForMod(importableMods[idx], src, instance)
                            }
                        }
                    }
                }
                // Update count
                val total = synchronized(modListGuard) { importableMods.size }
                val checkedCount = synchronized(modListGuard) { importableMods.count { it.checked } }
                updateModsTabLabel(total, checkedCount)
            }, ::fetchOnlineIcon)
            item.setSizeHint(row.sizeHint())
            modListWidget.addItem(item)
            modListWidget.setItemWidget(item, row)
        }
    }

    private fun filterModsBySearch(text: String) {
        val allMods: List<ImportableMod>
        synchronized(modListGuard) {
            allMods = importableMods.toList()
        }

        if (text.isBlank()) {
            populateModList(allMods)
            return
        }

        val query = text.lowercase()
        val filtered = allMods.filter {
            it.displayName.lowercase().contains(query) ||
            it.modId.lowercase().contains(query) ||
            it.fileName.lowercase().contains(query)
        }
        populateModList(filtered)
    }

    // --- Source validation ---

    private fun validateModsAgainstSource(source: ModSource) {
        currentValidationJob?.cancel()
        val instance = currentInstance ?: return
        val allMods: List<ImportableMod>
        synchronized(modListGuard) {
            allMods = importableMods.toList()
        }
        if (allMods.isEmpty()) return

        val context = ModBrowserContext(
            project = dummyProject,
            minecraftVersion = instance.gameVersion,
            modLoaderId = mapLoaderId(instance.loader)
        )

        for (i in 0 until modListWidget.count()) {
            val item = modListWidget.item(i)
            (modListWidget.itemWidget(item) as? ImportableModRow)?.updateAvailability(null, null, null, null)
        }

        val semaphore = CoroutineSemaphore(4)
        val totalMods = allMods.size
        val progressInterval = maxOf(1, totalMods / 20)
        val completedCount = AtomicInteger(0)

        currentValidationJob = ioScope.launch {
            log.warn("validateModsAgainstSource: starting validation for {} mods", totalMods)

            val fingerprintMatched = mutableSetOf<Int>()
            try {
                val fingerprints = allMods.mapNotNull { it.fileFingerprint }.distinct()
                if (fingerprints.isNotEmpty()) {
                    val fpResults = source.resolveProjectInfosByFingerprints(fingerprints)
                    if (fpResults.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            allMods.forEachIndexed { index, mod ->
                                val fp = mod.fileFingerprint
                                if (fp != null && fp in fpResults) {
                                    val info = fpResults[fp]!!
                                    fingerprintMatched.add(index)
                                    synchronized(modListGuard) {
                                        if (index in importableMods.indices) {
                                            importableMods[index] = importableMods[index].copy(
                                                sourceProjectId = info.projectId,
                                                sourceAvailable = true,
                                                sourceStatus = "Available"
                                            )
                                        }
                                    }
                                    for (i in 0 until modListWidget.count()) {
                                        val item = modListWidget.item(i)
                                        val dataIdx = item?.data(Qt.ItemDataRole.UserRole) as? Int ?: continue
                                        if (dataIdx == index) {
                                            (modListWidget.itemWidget(item) as? ImportableModRow)
                                                ?.updateAvailability(true, info.projectId, null, "Available")
                                            break
                                        }
                                    }
                                    completedCount.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }

            val remainingMods = allMods.filterIndexed { index, _ -> index !in fingerprintMatched }

            coroutineScope {
                remainingMods.mapIndexed { loopIndex, mod ->
                    // Find the original index in allMods
                    val originalIndex = allMods.indexOf(mod)
                    async {
                        semaphore.withPermit {
                            try {
                                ensureActive()
                                val result = findModOnSource(mod, source, context)
                                ensureActive()
                                withContext(Dispatchers.Main) {
                                    if (!isActive) return@withContext
                                    val status = result?.status ?: "Not Available"
                                    synchronized(modListGuard) {
                                        if (originalIndex in importableMods.indices) {
                                            importableMods[originalIndex] = importableMods[originalIndex].copy(
                                                sourceProjectId = result?.projectId,
                                                sourceIconUrl = result?.iconUrl,
                                                sourceAvailable = result != null,
                                                sourceStatus = status
                                            )
                                        }
                                    }
                                    // Update the row widget
                                    for (i in 0 until modListWidget.count()) {
                                        val item = modListWidget.item(i)
                                        val dataIdx = item?.data(Qt.ItemDataRole.UserRole) as? Int ?: continue
                                        if (dataIdx == originalIndex) {
                                            (modListWidget.itemWidget(item) as? ImportableModRow)
                                                ?.updateAvailability(result != null, result?.projectId, result?.iconUrl, status)
                                            break
                                        }
                                    }
                                    // If mod is checked and matching version was found, resolve dependencies
                                    val currentMod = synchronized(modListGuard) { importableMods.getOrNull(originalIndex) }
                                }
                                val done = completedCount.incrementAndGet()
                                if (done % progressInterval == 0) {
                                    log.warn("validateModsAgainstSource: progress {}/{}", done, totalMods)
                                }
                            } catch (t: CancellationException) {
                                throw t
                            } catch (t: Throwable) {
                                if (isActive) {
                                    log.warn("Failed to validate mod '{}' against source '{}': {}", mod.displayName, source.id, t.message)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            val modsCopy = synchronized(modListGuard) { importableMods.toList() }
            saveImportCache(instance, source.id, modsCopy)
            log.warn("validateModsAgainstSource: completed {}/{} mods for {}", completedCount.get(), totalMods, source.id)
        }
    }

    private fun resolveDependenciesForMod(mod: ImportableMod, source: ModSource, instance: DetectedInstance) {
        val projectId = mod.sourceProjectId ?: return
        if (projectId.isBlank()) return

        ioScope.launch {
            // If validation was canceled, skip dependency resolution
            if (currentValidationJob?.isActive == false) return@launch
            depSemaphore.withPermit {
                try {
                    val context = ModBrowserContext(
                        project = dummyProject,
                        minecraftVersion = instance.gameVersion,
                        modLoaderId = mapLoaderId(instance.loader)
                    )
                    val versions = source.versions(context, projectId)
                    if (versions.isEmpty()) return@launch

                    // Pick the best matching version (featured > release > newest)
                    val best = versions
                        .filter { v ->
                            val mcMatch =
                                instance.gameVersion == null || v.gameVersions.any { it == instance.gameVersion }
                            val loaderMatch = instance.loader == null || v.loaders.any {
                                it.equals(instance.loader, ignoreCase = true) ||
                                        mapLoaderId(instance.loader)?.equals(it, ignoreCase = true) == true
                            }
                            mcMatch && loaderMatch
                        }.maxByOrNull { it.featured } ?: versions.firstOrNull { it.featured } ?: versions.firstOrNull()
                    ?: return@launch

                    val depIds = best.dependencies
                        .filter { it.required && !it.incompatible }
                        .map { it.projectId }

                    withContext(Dispatchers.Main) {
                        synchronized(modListGuard) {
                            val idx = importableMods.indexOfFirst { it.jarPath == mod.jarPath }
                            if (idx >= 0) {
                                importableMods[idx] = importableMods[idx].copy(dependencyIds = depIds)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    log.warn("Failed to resolve dependencies for '{}': {}", mod.displayName, t.message)
                }
            }
        }
        }

    // --- Icon loading ---

    private fun fetchOnlineIcon(index: Int, url: String) {
        if (currentIconJob?.isActive != true) currentIconJob = Job()
        ioScope.launch(currentIconJob!!) {
            iconSemaphore.acquire()
            try {
                val cached = iconCache[url]
                if (cached != null) {
                    withContext(Dispatchers.Main) {
                        updateRowIcon(index, cached)
                    }
                    return@launch
                }

                val bytes = httpClient.get(url).bodyAsBytes()
                val pixmap = QPixmap()
                if (pixmap.loadFromData(bytes)) {
                    val scaled = pixmap.scaled(qs(32, 32), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                    val icon = QIcon(scaled)
                    iconCache[url] = icon
                    withContext(Dispatchers.Main) {
                        updateRowIcon(index, icon)
                    }
                }
            } finally {
                iconSemaphore.release()
            }
        }
    }

    private fun fetchModrinthPackIcon(widget: ModrinthPackItemWidget, iconUrl: String) {
        ioScope.launch {
            try {
                val bytes = httpClient.get(iconUrl).bodyAsBytes()
                val pixmap = QPixmap()
                if (pixmap.loadFromData(bytes)) {
                    val mode = if (pixmap.width() <= 64 || pixmap.height() <= 64)
                        Qt.TransformationMode.FastTransformation else Qt.TransformationMode.SmoothTransformation
                    val scaled = pixmap.scaled(qs(32, 32), Qt.AspectRatioMode.KeepAspectRatio, mode)
                    withContext(Dispatchers.Main) {
                        widget.iconLabel.pixmap = scaled
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun onModrinthPackSelected(project: ModrinthProject) {
        currentModrinthProject = project
        modrinthTitleLabel.text = project.title
        modrinthDescLabel.text = project.description ?: ""
        val loaderName = project.latestLoaders.firstOrNull()
        modrinthPackVerLabel.text = project.latestVersionName.ifBlank { project.versions.firstOrNull() ?: "Unknown" }
        modrinthExternalBtn.visible = project.slug != null || project.id.isNotBlank()

        val metaLayout = modrinthMetaRow.layout() as? QHBoxLayout
        if (metaLayout != null) {
            while (metaLayout.count() > 0) {
                metaLayout.takeAt(0)?.widget()?.let { it.hide(); it.setParent(null); it.dispose() }
            }
        }
        val pills = buildMetaTagsWidget(project.latestGameVersion, loaderName)
        modrinthMetaRow.layout()?.addWidget(pills ?: qWidget())

        modrinthIconLabel.pixmap = QPixmap()
        if (project.iconUrl != null) {
            currentIconJob?.cancel()
            currentIconJob = ioScope.launch {
                try {
                    val bytes = httpClient.get(project.iconUrl).bodyAsBytes()
                    val pix = QPixmap()
                    if (pix.loadFromData(bytes)) {
                        val mode = if (pix.width() <= 64 || pix.height() <= 64)
                            Qt.TransformationMode.FastTransformation else Qt.TransformationMode.SmoothTransformation
                        val scaled = pix.scaled(qs(64, 64), Qt.AspectRatioMode.KeepAspectRatio, mode)
                        withContext(Dispatchers.Main) {
                            modrinthIconLabel.pixmap = scaled
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        stacked.currentIndex = 2
    }

    private fun updateRowIcon(index: Int, icon: QIcon) {
        for (i in 0 until modListWidget.count()) {
            val item = modListWidget.item(i)
            val dataIdx = item?.data(Qt.ItemDataRole.UserRole) as? Int ?: continue
            if (dataIdx == index) {
                val row = modListWidget.itemWidget(item) as? ImportableModRow
                row?.setIconFromQIcon(icon)
                break
            }
        }
    }

    private fun populateFileTreeAsync(instance: DetectedInstance) {
        fileTree.clear()
        fileTreeStack.currentIndex = 0

        if (!instance.minecraftDir.exists()) {
            fileTreeLoading.text = "Minecraft directory not found."
            return
        }

        currentFileTreeJob?.cancel()
        currentFileTreeJob = ioScope.launch {
            try {
                val instancePath = instance.minecraftDir.toAbsolute().toString()
                val entries = withContext(Dispatchers.IO) {
                    collectFileTreeEntries(instance.minecraftDir)
                }

                withContext(Dispatchers.Main) {
                    fileTree.blockSignals(true)
                    val root = QTreeWidgetItem(fileTree)
                    root.setText(0, instance.minecraftDir.fileName())
                    root.setIcon(0, QIcon(TIcons.Folder))
                    root.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable, Qt.ItemFlag.ItemIsAutoTristate)
                    root.setCheckState(0, Qt.CheckState.Checked)
                    root.setData(0, Qt.ItemDataRole.UserRole, instancePath)

                    val parentMap = mutableMapOf<VPath, QTreeWidgetItem>()
                    parentMap[instance.minecraftDir] = root

                    for (entry in entries) {
                        val parentItem = entry.parent.let { parentMap[it] } ?: root
                        val item = QTreeWidgetItem(parentItem)
                        item.setText(0, entry.path.fileName())
                        if (entry.isDirectory) {
                            item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable, Qt.ItemFlag.ItemIsAutoTristate)
                            item.setIcon(0, QIcon(TIcons.Folder))
                            item.setChildIndicatorPolicy(QTreeWidgetItem.ChildIndicatorPolicy.ShowIndicator)
                        } else {
                            item.setFlags(Qt.ItemFlag.ItemIsEnabled, Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsUserCheckable)
                            item.setIcon(0, iconForFile(entry.path, dummyProject))
                        }
                        item.setCheckState(0, Qt.CheckState.Checked)
                        item.setData(0, Qt.ItemDataRole.UserRole, entry.path.toString())
                        parentMap[entry.path] = item
                    }

                    if (expandedState.containsKey(instancePath)) {
                        restoreExpandedState(fileTree, instancePath, expandedState)
                    } else {
                        root.isExpanded = true
                    }
                    fileTree.blockSignals(false)
                    fileTreeStack.currentIndex = 1
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    fileTreeLoading.text = "Failed to read directory."
                }
            }
        }
    }

    // --- Import logic ---

    @OptIn(ExperimentalTime::class)
    private fun onImport() {
        val instance = currentInstance ?: run {
            statusLabel.text = "No instance selected."
            return
        }

        val destRaw = destPathField.text.trim()
        if (destRaw.isBlank()) {
            statusLabel.text = "Please specify a destination path."
            return
        }

        val projectRoot = VPath.get(destRaw).expandHome().toAbsolute().normalize()
        if (projectRoot.exists() && projectRoot.list().isNotEmpty()) {
            val answer = QMessageBox.question(
                this, "Directory Not Empty",
                "The destination directory '${projectRoot.fileName()}' already exists and is not empty.\n\n" +
                "Continue importing into this directory? Existing files may be overwritten.",
                QMessageBox.StandardButton.Yes, QMessageBox.StandardButton.No
            )
            if (answer != QMessageBox.StandardButton.Yes.value()) return
        }

        // Get all mods & checked states
        val allMods: List<ImportableMod>
        synchronized(modListGuard) {
            allMods = importableMods.toList()
        }
        val selectedMods = allMods.filter { it.checked }
        val selectedFiles = collectCheckedFiles(fileTree)

        // Disable UI during import
        importBtn.isEnabled = false
        backBtn.isEnabled = false
        statusLabel.text = "Importing project..."

        ioScope.launch {
            try {
                // Step 1: Create directory structure
                withContext(Dispatchers.IO) {
                    projectRoot.mkdirs()
                    listOf("mods", "config", "defaultconfigs", "logs", "saves").forEach { dir ->
                        projectRoot.resolve(dir).mkdirs()
                    }
                }

                // Step 2: Determine loader info
                val loaderId = mapLoaderId(instance.loader)
                val loaderVer = instance.loaderVersion ?: ""
                val source = modSourceCombo.currentData as? ModSource
                val sourceId = source?.id ?: "unknown"

                // Step 3: Write trmodpack.json
                val modpackMeta = ModpackMeta(
                    id = instance.name,
                    minecraftVersion = instance.gameVersion ?: "unknown",
                    loader = loaderId ?: "unknown",
                    loaderVersion = loaderVer,
                    source = sourceId,
                    license = null,
                    icon = null
                )
                val manifest = json.encodeToString(ModpackMeta.serializer(), modpackMeta)
                withContext(Dispatchers.IO) {
                    projectRoot.resolve("trmodpack.json").writeBytesAtomic(manifest.toByteArray())
                }

                // Step 4: Copy icon
                val iconPath = LauncherDetector.resolveInstanceIcon(instance)
                if (iconPath != null) {
                    withContext(Dispatchers.IO) {
                        val iconBytes = iconPath.bytesOrNull()
                        if (iconBytes != null) {
                            projectRoot.resolve("icon.png").writeBytesAtomic(iconBytes)
                        }
                    }
                }

                // Step 5: Write trexportrules.json
                withContext(Dispatchers.IO) {
                    projectRoot.resolve("trexportrules.json").writeBytesAtomic("{}".toByteArray())
                }

                // Step 6: Copy selected mod jars and register in database
                withContext(Dispatchers.IO) {
                    ModDatabase(projectRoot).use { db ->
                        for (mod in selectedMods) {
                            val destJar = projectRoot.resolve("mods/${mod.fileName}")
                            val bytes = mod.jarPath.bytesOrNull()
                            if (bytes != null) {
                                destJar.writeBytesAtomic(bytes)
                                val hash = ModDatabase.sha1(bytes)

                                val iconFile = extractAndCacheModIcon(mod, destJar)

                                val projectId = mod.sourceProjectId ?: mod.modId
                                val installedMod = InstalledMod(
                                    projectId = projectId,
                                    modId = mod.modId,
                                    fileName = mod.fileName,
                                    displayName = mod.displayName,
                                    side = mod.side,
                                    releaseType = "release",
                                    source = sourceId,
                                    versionId = mod.sourceProjectId ?: mod.modId,
                                    versionLabel = "",
                                    iconPath = iconFile?.toAbsolute()?.toString(),
                                    projectUrl = null,
                                    fileHash = hash,
                                    installedAt = Clock.System.now(),
                                    enabled = true,
                                    excludedFromRelease = false,
                                    requiresManualDownload = false,
                                    dependencies = mod.dependencyIds
                                )
                                db.install(installedMod)
                                if (mod.dependencyIds.isNotEmpty()) {
                                    db.setDependencies(projectId, mod.dependencyIds)
                                }
                            }
                        }
                    }
                }

                // Step 7: Copy checked non-mod files
                for (filePath in selectedFiles) {
                    withContext(Dispatchers.IO) {
                        val relativePath = instance.minecraftDir.relativize(filePath)
                        val dest = projectRoot.resolve(relativePath.toString())
                        dest.parent().mkdirs()
                        val bytes = filePath.bytesOrNull()
                        if (bytes != null) {
                            dest.writeBytesAtomic(bytes)
                        }
                    }
                }

                // Step 8: Write trproj.json
                val iconValue = if (iconPath != null) "icon.png" else TIcons.defaultProjectIcon
                val rawMeta = buildJsonObject { put("metaPath", "trmodpack.json") }
                val trMeta = ProjectFiles.buildMeta(
                    type = "source",
                    name = instance.name,
                    icon = iconValue,
                    schemaVersion = ModpackTemplateDescriptor.currentSchema,
                    meta = rawMeta
                )
                withContext(Dispatchers.IO) {
                    ProjectFiles.writeTrProject(projectRoot, trMeta)
                }

                // Step 9: Background bootstrap (MC + loader)
                if (loaderId != null && instance.gameVersion != null && loaderVer.isNotBlank()) {
                    val loader = BuiltinRegistries.ModLoader.all().find { it.id == loaderId }
                    if (loader != null) {
                        withContext(Dispatchers.IO) {
                            val bootstrapTaskId = ProjectTaskMngr.start(
                                projectPath = projectRoot,
                                title = "Bootstrapping imported '${instance.name}'",
                                detail = "Preparing runtime files",
                                progressPercent = 5.0
                            )
                            try {
                                // Setup Minecraft
                                ProjectTaskMngr.update(bootstrapTaskId, detail = "Setting up Minecraft ${instance.gameVersion}")
                                ProjectTaskMngr.updateProgress(bootstrapTaskId, 20.0)
                                val mcOk = MicrosoftAuth.setupMinecraftInstance(instance.gameVersion, projectRoot)
                                ProjectTaskMngr.updateProgress(bootstrapTaskId, if (mcOk) 55.0 else 40.0)

                                if (mcOk) {
                                    ProjectTaskMngr.update(bootstrapTaskId, detail = "Installing $loaderId $loaderVer")
                                    ProjectTaskMngr.updateProgress(bootstrapTaskId, 70.0)
                                    val loaderOk = loader.installClient(loaderVer, instance.gameVersion, projectRoot)
                                    if (loaderOk) {
                                        MicrosoftAuth.writeMergedVersionJson(instance.gameVersion, loaderId, loaderVer, projectRoot)
                                        ProjectTaskMngr.update(bootstrapTaskId, detail = "Bootstrap finished")
                                        ProjectTaskMngr.updateProgress(bootstrapTaskId, 100.0)
                                        NotificationMngr.post(
                                            id = "import_bootstrap_success",
                                            project = ProjectMngr.getProject(projectRoot),
                                            description = "Imported project '${instance.name}' is ready.",
                                            metadata = mapOf("source" to "import.bootstrap", "result" to "success")
                                        )
                                    } else {
                                        log.warn("Loader install failed for imported project {}", instance.name)
                                        NotificationMngr.post(
                                            id = "import_bootstrap_loader_failed",
                                            project = ProjectMngr.getProject(projectRoot),
                                            description = "Imported project '${instance.name}' bootstrap failed: loader install failed.",
                                            metadata = mapOf("source" to "import.bootstrap", "result" to "failed")
                                        )
                                    }
                                }
                                ProjectTaskMngr.finish(bootstrapTaskId)
                            } catch (t: Throwable) {
                                log.warn("Bootstrap failed for imported project {}", instance.name, t)
                                ProjectTaskMngr.finish(bootstrapTaskId)
                                NotificationMngr.post(
                                    id = "import_bootstrap_error",
                                    project = ProjectMngr.getProject(projectRoot),
                                    description = "Imported project '${instance.name}' bootstrap error: ${t.message}",
                                    metadata = mapOf("source" to "import.bootstrap", "result" to "error")
                                )
                            }
                        }
                    }
                }

                // Delete cache after successful import
                val sourceForCache = modSourceCombo.currentData as? ModSource
                if (sourceForCache != null) {
                    deleteImportCache(instance, sourceForCache.id)
                }

                // Step 10: Register and open the project
                val project = withContext(Dispatchers.IO) {
                    ProjectMngr.loadProject(projectRoot)
                }

                if (project != null) {
                    withContext(Dispatchers.Main) {
                        ProjectMngr.notifyCreatedExternal(project)
                        try {
                            ProjectMngr.openProject(project)
                        } catch (t: Throwable) {
                            log.warn("Failed to open imported project window", t)
                        }
                        statusLabel.text = "Project imported successfully!"
                        QTimer.singleShot(1500) { accept() }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusLabel.text = "Project imported, but failed to load."
                        QTimer.singleShot(2000) { accept() }
                    }
                }
            } catch (t: Throwable) {
                log.warn("Import failed", t)
                withContext(Dispatchers.Main) {
                    statusLabel.text = "Import failed: ${t.message}"
                    importBtn.isEnabled = true
                    backBtn.isEnabled = true
                }
            }
        }
    }

    private fun extractAndCacheModIcon(mod: ImportableMod, jarPath: VPath): VPath? {
        if (mod.sourceIconUrl != null && iconCache.containsKey(mod.sourceIconUrl)) {
            return null
        }
        val iconBytes = readModJarIcon(jarPath) ?: return null
        val iconFile = ModDatabase.iconPathFor(mod.sourceProjectId ?: mod.modId)
        iconFile.writeBytesAtomic(iconBytes)
        return iconFile
    }
}
