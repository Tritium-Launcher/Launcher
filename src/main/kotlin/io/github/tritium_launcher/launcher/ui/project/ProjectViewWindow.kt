package io.github.tritium_launcher.launcher.ui.project

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.GameLauncher
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.dashboard.SettingsDialog
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.notifications.NotificationLink
import io.github.tritium_launcher.launcher.ui.notifications.NotificationMngr
import io.github.tritium_launcher.launcher.ui.notifications.NotificationRenderContext
import io.github.tritium_launcher.launcher.ui.notifications.Toaster
import io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
import io.github.tritium_launcher.launcher.ui.project.menu.ProjectMenuBar
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectFilesSidePanelProvider
import io.github.tritium_launcher.launcher.ui.project.sidebar.SidePanelMngr
import io.github.tritium_launcher.launcher.ui.settings.SettingsLink
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.github.tritium_launcher.launcher.util.ByteUtils
import io.qt.Nullable
import io.qt.core.QByteArray
import io.qt.core.QTimer
import io.qt.core.Qt.DockWidgetArea
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.core.Qt.WidgetAttribute.WA_TransparentForMouseEvents
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * The main window for active Projects.
 */
class ProjectViewWindow internal constructor(
    private val project: ProjectBase
): QMainWindow() {

    private val logger = logger()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; }
    private val tDir: VPath = project.projectDir.resolve(".tr")
    private val stateFile: VPath = tDir.resolve("tritium-ui.json")
    private val defaultWindowSize: Pair<Int, Int> = CoreSettingValues.projectWindowDefaultSize()

    private val menuBarBuilder = ProjectMenuBar()
    private val menuBottomDivider = widget(this) {
        objectName = "projectMenuBottomDivider"
        setAttribute(WA_TransparentForMouseEvents, true)
        setThemedStyle {
            selector("#projectMenuBottomDivider") {
                backgroundColor(TColors.Surface2)
                border()
            }
        }
        hide()
    }
    private val editorArea = EditorArea(project)
    private lateinit var sidePanelMngr: SidePanelMngr
    private lateinit var notificationOverlay: Toaster
    private val settingsDialog = SettingsDialog(this)
    private val statePersistTimer = QTimer(this).apply {
        isSingleShot = true
        interval = 3_000
        timeout.connect { persistState() }
    }

    private var uiState: ProjectUIState = ProjectUIState()
    private var lastPersistedState: ProjectUIState? = null
    private var suppressStatePersistence: Boolean = false
    private var unsubscribeGameProcessListener: (() -> Unit)? = null
    private var unsubscribeRuntimePreparationListener: (() -> Unit)? = null
    private var unsubscribeTaskListener: (() -> Unit)? = null

    private val menuItemsRegistry = BuiltinRegistries.MenuItem

    init {
        uiState = loadState()
        lastPersistedState = uiState
        windowTitle = "Tritium | " + project.name
        menuBarBuilder.attach(this)

        sidePanelMngr = SidePanelMngr(
            project = project,
            parent = this,
            onStateChanged = { scheduleStatePersist() }
        ) { id, dock ->
            if(id == "project_files") {
                val tree = (dock.widget() as? QTreeWidget) ?: dock.widget()?.findChild(QTreeWidget::class.java)

                tree?.itemDoubleClicked?.connect { item, _ ->
                    val path = item?.data(0, UserRole) as? VPath
                    if(path != null && !path.isDir()) {
                        editorArea.openFile(path)
                    }
                }
                tree?.itemExpanded?.connect { scheduleStatePersist() }
                tree?.itemCollapsed?.connect { scheduleStatePersist() }
                tree?.currentItemChanged?.connect { _, _ -> scheduleStatePersist() }

                ProjectFilesSidePanelProvider.restoreDockTreeState(
                    dock,
                    ProjectFilesSidePanelProvider.TreeState(
                        expandedPaths = uiState.projectFilesExpandedPaths.toSet(),
                        selectedPath = uiState.projectFilesSelectedPath
                    )
                )
            }
        }

        setCentralWidget(editorArea.widget())
        editorArea.onOpenFilesChanged = { scheduleStatePersist() }
        notificationOverlay = Toaster(project, this)
        QTimer.singleShot(0) { updateMenuBottomDivider() }
        applyState(uiState)
        installNotificationTestShortcut()

        DeferredRegistryBuilder(menuItemsRegistry) {
            runOnGuiThread {
                menuBarBuilder.rebuildFor(this, project, null)
            }
        }

        unsubscribeGameProcessListener = GameLauncher.addGameProcessListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }
        unsubscribeRuntimePreparationListener = GameLauncher.addRuntimePreparationListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }
        unsubscribeTaskListener = ProjectTaskMngr.addListener {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenus()
            }
        }

        destroyed.connect {
            unsubscribeGameProcessListener?.invoke()
            unsubscribeGameProcessListener = null
            unsubscribeRuntimePreparationListener?.invoke()
            unsubscribeRuntimePreparationListener = null
            unsubscribeTaskListener?.invoke()
            unsubscribeTaskListener = null
        }
    }

    private fun ensureTDir() {
        if(!tDir.exists()) tDir.mkdirs()
    }

    private fun applyState(state: ProjectUIState) {
        try {
            suppressStatePersistence = true
            var restored = false
            state.mainWindowGeometry?.let {
                if(restoreGeometry(QByteArray(state.mainWindowGeometry))) {
                    restored = true
                }
            }
            state.mainWindowState?.let { restoreState(QByteArray(state.mainWindowState)) }

            if(!restored) {
                resize(defaultWindowSize.first, defaultWindowSize.second)
            }

            sidePanelMngr.restoreState(
                state.sidePanels.mapNotNull { panel ->
                    val area = parseDockArea(panel.area) ?: return@mapNotNull null
                    SidePanelMngr.PersistedDockState(
                        id = panel.id,
                        area = area,
                        visible = panel.visible
                    )
                }
            )

            editorArea.restoreOpenFiles(state.openFiles)
        } catch (t: Throwable) {
            logger.warn("Failed to apply UI state for '{}'", project.name, t)
            resize(defaultWindowSize.first, defaultWindowSize.second)
        } finally {
            suppressStatePersistence = false
        }
    }

    private fun loadState(): ProjectUIState {
        return try {
            ensureTDir()
            if (!stateFile.exists()) return ProjectUIState()
            val txt = stateFile.readTextOrNull() ?: return ProjectUIState()
            return json.decodeFromString<ProjectUIState>(txt)
        } catch (t: Throwable) {
            logger.warn("Failed to load UI state for {}", project.name, t)
            ProjectUIState()
        }
    }

    private fun persistState() {
        if (suppressStatePersistence) return
        try {
            ensureTDir()
            val openFiles = editorArea.openFiles()
            val geom = ByteUtils.toByteArray(saveGeometry().data())
            val state = ByteUtils.toByteArray(saveState().data())
            val sidePanels = sidePanelMngr.captureState().map { dock ->
                ProjectUIState.SidePanelState(
                    id = dock.id,
                    area = dockAreaName(dock.area),
                    visible = dock.visible
                )
            }
            val projectFilesDock = sidePanelMngr.dockWidgets()["project_files"]
            val projectFilesTree = ProjectFilesSidePanelProvider.captureDockTreeState(projectFilesDock)
            val s = ProjectUIState(
                openFiles = openFiles,
                sidePanels = sidePanels,
                projectFilesExpandedPaths = projectFilesTree.expandedPaths.toList(),
                projectFilesSelectedPath = projectFilesTree.selectedPath,
                mainWindowState = state,
                mainWindowGeometry = geom
            )
            if (s == lastPersistedState) {
                return
            }
            val txt = json.encodeToString(s)
            stateFile.writeBytesAtomic(txt.toByteArray())
            lastPersistedState = s
        } catch (t: Throwable) {
            logger.warn("Failed to persist UI state for '{}'", project.name, t)
        }
    }

    private fun scheduleStatePersist() {
        if (suppressStatePersistence) return
        statePersistTimer.start()
    }

    private fun installNotificationTestShortcut() {
        val action = QAction(this).apply {
            setShortcut("Ctrl+Alt+Shift+N") //TODO: Keymap
            toolTip = "Emit a random notification test payload"
        }
        action.triggered.connect {
            emitRandomTestNotification()
        }
        addAction(action)
    }

    private fun emitRandomTestNotification() {
        val seed = Random.nextInt(1000, 9999)
        val header = listOf(
            "Test Notification #$seed"
        ).random()
        val description = listOf(
            "Description."
        ).random()

        val icon = listOf(
            TIcons.QuestionMark.icon,
            TIcons.Build.icon,
            TIcons.Run.icon,
            TIcons.Tritium.icon
        ).random()

        val links: List<NotificationLink>? = if (Random.nextInt(100) < 70) {
            listOf(
                listOf(
                    NotificationLink("HTTP Link", "https://github.com/")
                ).random()
            )
        } else {
            null
        }

        val customWidgetFactory: ((NotificationRenderContext) -> QWidget)? = if (Random.nextInt(100) < 55) {
            { _: NotificationRenderContext ->
                QWidget().apply {
                    objectName = "notificationTestCustomWidget"
                    val progressValue = Random.nextInt(5, 100)
                    val layout = vBoxLayout(this) {
                        setContentsMargins(0, 4, 0, 0)
                        setSpacing(3)
                    }
                    layout.addWidget(label("Custom widget payload: $progressValue%"))
                    layout.addWidget(QProgressBar().apply {
                        setRange(0, 100)
                        value = progressValue
                        textVisible = false
                        maximumHeight = 8
                    })
                }
            }
        } else {
            null
        }

        NotificationMngr.post(
            id = "generic",
            project = project,
            header = header,
            description = description,
            icon = icon,
            links = links,
            customWidgetFactory = customWidgetFactory,
            metadata = mapOf(
                "source" to "project_hotkey",
                "seed" to seed.toString()
            )
        )
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        updateMenuBottomDivider()
        if(::notificationOverlay.isInitialized) notificationOverlay.reposition()
    }

    override fun resizeEvent(event: @Nullable QResizeEvent?) {
        super.resizeEvent(event)
        updateMenuBottomDivider()
        if(::notificationOverlay.isInitialized) notificationOverlay.reposition()
        scheduleStatePersist()
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        scheduleStatePersist()
    }

    override fun closeEvent(event: @Nullable QCloseEvent?) {
        if (!confirmCloseProjectIfNeeded()) {
            event?.ignore()
            return
        }
        statePersistTimer.stop()
        persistState()
        super.closeEvent(event)
    }

    private fun dockAreaName(area: DockWidgetArea): String = when (area) {
        DockWidgetArea.LeftDockWidgetArea -> "left"
        DockWidgetArea.RightDockWidgetArea -> "right"
        DockWidgetArea.BottomDockWidgetArea -> "bottom"
        else -> "left"
    }

    private fun parseDockArea(area: String): DockWidgetArea? = when (area.trim().lowercase()) {
        "left" -> DockWidgetArea.LeftDockWidgetArea
        "right" -> DockWidgetArea.RightDockWidgetArea
        "bottom" -> DockWidgetArea.BottomDockWidgetArea
        else -> null
    }

    fun rebuildMenus() {
        menuBarBuilder.rebuildFor(this, project, null)
        QTimer.singleShot(0) { updateMenuBottomDivider() }
    }

    /**
     * Adjusts font size for the active editor content.
     *
     * @return `true` when an editor text widget was updated.
     */
    fun adjustEditorFontSize(delta: Int): Boolean = editorArea.adjustActiveEditorFont(delta)

    /**
     * Canonical project identifier used by project-window routing logic.
     */
    fun projectCanonicalPath(): String = project.path.toString().trim()

    /**
     * Opens global settings and optionally focuses [link].
     */
    fun openSettings(link: SettingsLink? = null) {
        settingsDialog.open(link)
    }

    private fun updateMenuBottomDivider() {
        val menu = menuWidget() ?: run {
            menuBottomDivider.hide()
            return
        }
        val menuRect = menu.geometry
        if(menuRect.height() <= 0 || width() <= 0) {
            menuBottomDivider.hide()
            return
        }
        val y = menuRect.y() + menuRect.height() - 1
        menuBottomDivider.setGeometry(0, y, width(), 1)
        menuBottomDivider.show()
        menuBottomDivider.raise()
    }

    private fun confirmCloseProjectIfNeeded(): Boolean {
        val policy = CoreSettingValues.closeProjectConfirmationPolicy()
        if (policy != CoreSettingValues.CloseProjectConfirmationPolicy.Ask) return true

        val box = QMessageBox(this)
        box.icon = QMessageBox.Icon.Question
        box.windowTitle = "Close Project"
        box.text = "Close project '${project.name}'?"
        box.informativeText = "This only closes this project window."
        val closeButton = box.addButton("Close Project", QMessageBox.ButtonRole.AcceptRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.exec()
        return box.clickedButton() == closeButton
    }

    companion object {
        private val logger = logger(ProjectViewWindow::class)

        fun dashboardList(
            list: QListWidget,
            openWindow: (ProjectBase) -> Unit = { p -> ProjectViewWindow(p).apply { show() } }
        ) {
            list.itemDoubleClicked.connect { item ->
                val name = item?.text() ?: return@connect
                val proj = ProjectMngr.getProject(name)
                if(proj != null) {
                    openWindow(proj)
                } else {
                    logger.warn("Dashboard requested open for unknown project '{}'", name)
                }
            }
        }
    }
}
