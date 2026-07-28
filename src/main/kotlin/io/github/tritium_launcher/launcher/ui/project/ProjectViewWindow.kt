/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project

import io.github.tritium_launcher.api.*
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.TritiumEventBus
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.keymap.KeymapMngr
import io.github.tritium_launcher.launcher.ui.dashboard.SettingsDialog
import io.github.tritium_launcher.launcher.ui.notifications.Toaster
import io.github.tritium_launcher.launcher.ui.project.editor.DefaultEditorArea
import io.github.tritium_launcher.launcher.ui.project.menu.ProjectMenuBar
import io.github.tritium_launcher.launcher.ui.project.sidebar.DockPanelMngr
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectFilesDockPanelProvider
import io.github.tritium_launcher.launcher.ui.settings.SettingsLink
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.util.SeasonalEvents.isPrideMonth
import io.qt.Nullable
import io.qt.core.*
import io.qt.core.Qt.DockWidgetArea
import io.qt.gui.*
import io.qt.widgets.QApplication
import io.qt.widgets.QMainWindow
import io.qt.widgets.QMessageBox
import io.qt.widgets.QWidget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

/**
 * The main window for active Projects.
 */
class ProjectViewWindow internal constructor(
    private val project: ProjectBase,
    initialUIState: ProjectUIState? = null,
    initialOpenFiles: List<String>? = null
): QMainWindow() {

    private val logger = logger()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; }
    private val tDir: VPath = project.projectDir.resolve(".tr")
    private val stateFile: VPath = tDir.resolve("tritium-ui.json")
    private val defaultWindowSize: Pair<Int, Int> = CoreSettingValues.projectWindowDefaultSize()

    private val menuBarBuilder = ProjectMenuBar()
    private var backgroundLayer: ProjectBackgroundWidget
    internal val editorArea: DefaultEditorArea = DefaultEditorArea(project)
    internal val dockPanelMngr: DockPanelMngr
    private var notificationOverlay = Toaster(this.project, this)
    private val settingsDialog = SettingsDialog(this)
    private val statePersistTimer = QTimer(this).apply {
        isSingleShot = true
        interval = 3_000
        timeout.connect { persistState() }
    }
    private val rebuildMenusTimer = QTimer(this).apply {
        isSingleShot = true
        interval = 50
        timeout.connect { rebuildMenus() }
    }

    private var uiState: ProjectUIState = ProjectUIState()
    private var lastPersistedState: ProjectUIState? = null
    private var suppressStatePersistence: Boolean = false
    private var gameEventScope: CoroutineScope? = null
    private var unsubscribeTaskListener: Job? = null
    private var unsubscribeKeymapListener: Job? = null

    private val menuItemsRegistry = BuiltinRegistries.MenuItem
    private var pendingOpenFiles: List<String>? = initialOpenFiles
    private var uiStateRestored = false
    private var preMaximizeGeometry: QRect? = null

    init {
        uiState = initialUIState ?: run {
            loadState()
        }
        lastPersistedState = uiState
        applyGeometryBeforeShow()
        windowTitle = "Tritium Launcher | " + project.name
        windowIcon = if (isPrideMonth()) {
            TIcons.TritiumGrayscale.applyRainbowOverlay(opacity = 0.5f).icon
        } else {
            QIcon(TIcons.Tritium.scaled(qs(256, 256), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation))
        }

        menuBarBuilder.attach(this)
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)

        backgroundLayer = ProjectBackgroundWidget(this)
        backgroundLayer.lower()

        val projectFilesTreeState = ProjectFilesDockPanelProvider.TreeState(
            expandedPaths = if (uiState.projectFilesViewStates.isNotEmpty())
                uiState.projectFilesViewStates.first().expandedPaths.toSet()
            else uiState.projectFilesExpandedPaths.toSet(),
            selectedPath = if (uiState.projectFilesViewStates.isNotEmpty())
                uiState.projectFilesViewStates.first().selectedPath
            else uiState.projectFilesSelectedPath
        )
        val restoredViewStates = if(uiState.projectFilesViewStates.isNotEmpty()) {
            uiState.projectFilesViewStates.map { vs ->
                ProjectFilesDockPanelProvider.ViewState(
                    viewId = vs.viewId,
                    treeState = ProjectFilesDockPanelProvider.TreeState(
                        expandedPaths = vs.expandedPaths.toSet(),
                        selectedPath = vs.selectedPath
                    )
                )
            }
        } else {
            listOf(
                ProjectFilesDockPanelProvider.ViewState(
                    viewId = "project_files",
                    treeState = ProjectFilesDockPanelProvider.TreeState(
                        expandedPaths = uiState.projectFilesExpandedPaths.toSet(),
                        selectedPath = uiState.projectFilesSelectedPath
                    )
                )
            )
        }
        ProjectFilesDockPanelProvider.setPendingInitialDockState(
            ProjectFilesDockPanelProvider.DockState(
                activeViewId = uiState.projectFilesActiveViewId,
                viewStates = restoredViewStates
            )
        )

        dockPanelMngr = DockPanelMngr(
            project = project,
            parent = this,
            editorArea = editorArea,
            onStateChanged = { scheduleStatePersist() },
            onAllProvidersBuilt = {}
        )

        setCentralWidget(editorArea.widget().apply {
            setProperty("keymapFocusGroup", "editor")
        })
        editorArea.onOpenFilesChanged = { scheduleStatePersist() }

        notificationOverlay = Toaster(project, this)

        DeferredRegistryBuilder(menuItemsRegistry) {
            runOnGuiThread {
                rebuildMenusTimer.start()
            }
        }

        gameEventScope = CoroutineScope(Dispatchers.Main + CoroutineName("GameProcessMngr")).apply {
            onEvent<TritiumEvent.GameAttached> { handleGameEvent() }
            onEvent<TritiumEvent.GameDetached> { handleGameEvent() }
            onEvent<TritiumEvent.GameExited> { handleGameEvent() }
        }
        unsubscribeTaskListener = ProjectTaskMngr.taskChanges.onEach {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenusTimer.start()
            }
        }.launchIn(CoroutineScope(Dispatchers.Main + CoroutineName("ProjectTaskMngr")))

        unsubscribeKeymapListener = KeymapMngr.activeKeymapFlow.onEach {
            runOnGuiThread {
                if (!isVisible) return@runOnGuiThread
                rebuildMenusTimer.start()
            }
        }.launchIn(CoroutineScope(Dispatchers.Main + CoroutineName("KeymapMngr")))

        destroyed.connect {
            gameEventScope?.cancel()
            gameEventScope = null
            unsubscribeTaskListener?.cancel()
            unsubscribeTaskListener = null
            unsubscribeKeymapListener?.cancel()
            unsubscribeKeymapListener = null
        }
    }

    private fun handleGameEvent() {
        runOnGuiThread {
            if (!isVisible) return@runOnGuiThread
            rebuildMenusTimer.start()
        }
    }

    /**
     * Ensures the Tritium files directory exists; creates otherwise
     */
    private fun ensureTDir() {
        if(!tDir.exists()) tDir.mkdirs()
    }

    /**
     * Restores previous window state after the window is shown.
     */
    private fun restoreUIState() {
        if (uiStateRestored) return
        uiStateRestored = true
        try {
            suppressStatePersistence = true

            uiState.mainWindowState?.let {
                try {
                    restoreState(QByteArray(it))
                } catch (t: Throwable) {
                    logger.warn("Failed to restore window state for '{}'", project.name, t)
                }
            }

            dockPanelMngr.restoreState(
                uiState.sidePanels.mapNotNull { panel ->
                    val area = parseDockArea(panel.area) ?: return@mapNotNull null
                    DockPanelMngr.PersistedDockState(
                        id = panel.id,
                        area = area,
                        visible = panel.visible
                    )
                }
            )

            editorArea.restoreOpenFiles(pendingOpenFiles ?: uiState.openFiles)
            pendingOpenFiles = null
        } catch (t: Throwable) {
            logger.warn("Failed to apply UI state for '{}'", project.name, t)
            resize(defaultWindowSize.first, defaultWindowSize.second)
        } finally {
            suppressStatePersistence = false
        }
    }

    /**
     * Loads previous window state
     */
    private fun loadState(): ProjectUIState {
        return try {
            ensureTDir()
            if (!stateFile.exists()) return ProjectUIState()
            val txt = stateFile.readTextOrNull() ?: return ProjectUIState()
            return ProjectUIState.parseOrNull(txt) ?: ProjectUIState()
        } catch (t: Throwable) {
            logger.warn("Failed to load UI state for {}", project.name, t)
            ProjectUIState()
        }
    }

    /**
     * Saves window state
     */
    private fun persistState() {
        if (suppressStatePersistence) return
        try {
            ensureTDir()
            val openFiles = editorArea.openFiles()
            val screen = windowHandle()?.screen()
            val geomRect = if(isMaximized) {
                preMaximizeGeometry ?: geometry
            } else {
                geometry
            }
            val stateQBA = saveState()
            val state = ByteUtils.toByteArray(stateQBA.data())

            val sidePanels = dockPanelMngr.captureState().map { dock ->
                ProjectUIState.SidePanelState(
                    id = dock.id,
                    area = dockAreaName(dock.area),
                    visible = dock.visible
                )
            }
            val projectFilesDock = dockPanelMngr.dockWidgets()["project_files"]
            val projectFilesTree = ProjectFilesDockPanelProvider.captureDockTreeState(projectFilesDock)
            val s = ProjectUIState(
                openFiles = openFiles,
                sidePanels = sidePanels,
                projectFilesActiveViewId = projectFilesTree.activeViewId,
                projectFilesViewStates = projectFilesTree.viewStates.map { viewState ->
                    ProjectUIState.ProjectFilesViewState(
                        viewId = viewState.viewId,
                        expandedPaths = viewState.treeState.expandedPaths.toList(),
                        selectedPath = viewState.treeState.selectedPath
                    )
                },
                projectFilesExpandedPaths = projectFilesTree.viewStates
                    .firstOrNull { it.viewId == projectFilesTree.activeViewId }
                    ?.treeState?.expandedPaths?.toList()
                    ?: emptyList(),
                projectFilesSelectedPath = projectFilesTree.viewStates
                    .firstOrNull { it.viewId == projectFilesTree.activeViewId }
                    ?.treeState?.selectedPath,
                mainWindowState = state,
                windowX = geomRect.x(),
                windowY = geomRect.y(),
                windowWidth = geomRect.width(),
                windowHeight = geomRect.height(),
                windowMaximized = isMaximized,
                windowScreenName = screen?.name(),
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

    private fun applyGeometryBeforeShow() {
        val w = uiState.windowWidth ?: defaultWindowSize.first
        val h = uiState.windowHeight ?: defaultWindowSize.second
        resize(w, h)

        val targetScreen = uiState.windowScreenName
            ?.let { name -> QApplication.screens().find { it?.name() == name } }
            ?: QApplication.primaryScreen()

        if (uiState.windowX != null && uiState.windowY != null) {
            val available = targetScreen?.availableGeometry()
            val x = uiState.windowX!!.coerceIn(
                available?.x() ?: 0,
                ((available?.x() ?: 0) + (available?.width() ?: w) - w).coerceAtLeast(available?.x() ?: 0)
            )
            val y = uiState.windowY!!.coerceIn(
                available?.y() ?: 0,
                ((available?.y() ?: 0) + (available?.height() ?: h) - h).coerceAtLeast(available?.y() ?: 0)
            )
            move(x, y)
        }

        if (uiState.windowMaximized) {
            preMaximizeGeometry = QRect(
                uiState.windowX ?: 0,
                uiState.windowY ?: 0,
                w,
                h
            )
            setWindowState(Qt.WindowState.WindowMaximized)
        }
    }

    /**
     * Schedule timer for persisting window state
     */
    private fun scheduleStatePersist() {
        if (suppressStatePersistence) return
        statePersistTimer.start()
    }


    override fun changeEvent(event: @Nullable QEvent?) {
        if (event?.type() == QEvent.Type.WindowStateChange) {
            val wasMaximized = (event as? QWindowStateChangeEvent)?.oldState()
                ?.testFlag(Qt.WindowState.WindowMaximized) == true
            val isNowMaximized = windowState().testFlag(Qt.WindowState.WindowMaximized)

            if (!wasMaximized && isNowMaximized) {
                preMaximizeGeometry = geometry()
            }

        }
        super.changeEvent(event)
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
            notificationOverlay.reposition()
        if (!uiStateRestored) {
            QTimer.singleShot(0) { restoreUIState() }
        }
    }

    override fun resizeEvent(event: @Nullable QResizeEvent?) {
        super.resizeEvent(event)
        backgroundLayer.setGeometry(0, 0, width(), height())
        notificationOverlay.reposition()
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
        TritiumEventBus.publish(TritiumEvent.ProjectClosing(project))
        super.closeEvent(event)
    }

    /**
     * Returns the Dock Area name from [DockWidgetArea] value
     */
    private fun dockAreaName(area: DockWidgetArea): String = when (area) {
        DockWidgetArea.LeftDockWidgetArea   -> "left"
        DockWidgetArea.RightDockWidgetArea  -> "right"
        DockWidgetArea.BottomDockWidgetArea -> "bottom"
        else -> "left"
    }

    /**
     * Returns the [DockWidgetArea] value from name
     */
    private fun parseDockArea(area: String): DockWidgetArea? = when (area.trim().lowercase()) {
        "left"   -> DockWidgetArea.LeftDockWidgetArea
        "right"  -> DockWidgetArea.RightDockWidgetArea
        "bottom" -> DockWidgetArea.BottomDockWidgetArea
        else -> null
    }

    /**
     * Rebuilds the Menu Bar
     */
    fun rebuildMenus() {
        menuBarBuilder.rebuildFor(this, project, null)
    }

    /**
     * Adjusts font size for the active editor content.
     *
     * @return `true` when an editor text widget was updated.
     */
    fun adjustEditorFontSize(delta: Int): Boolean = editorArea.adjustActiveEditorFont(delta)

    /**
     * Saves the currently active editor if it has unsaved changes.
     */
    fun saveActiveEditor() = editorArea.saveActive()

    /**
     * Saves all editors that have unsaved changes.
     */
    fun saveAllEditors() = editorArea.saveAll()

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

    /**
     * Asks whether the user wants to close the project when exiting Tritium, depending on [CoreSettingValues.closeProjectConfirmationPolicy]
     */
    private fun confirmCloseProjectIfNeeded(): Boolean {
        val policy = CoreSettingValues.closeProjectConfirmationPolicy
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
}

private class ProjectBackgroundWidget(parent: QWidget) : QWidget(parent) {
    private var backgroundPixmap: QPixmap? = null
    private var scaledPixmap: QPixmap? = null
    private var lastBgImagePath: String? = null
    private var lastSize: QSize? = null

    init {
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
    }

    override fun paintEvent(event: @Nullable QPaintEvent?) {
        val bgPath = CoreSettingValues.uiBackgroundImage
        val currentSize = size()

        if (!bgPath.isNullOrBlank()) {
            val pathChanged = bgPath != lastBgImagePath
            val sizeChanged = currentSize != lastSize

            if (pathChanged) {
                backgroundPixmap = QPixmap(bgPath)
                lastBgImagePath = bgPath
            }

            if (pathChanged || sizeChanged) {
                backgroundPixmap?.let { pix ->
                    if (!pix.isNull) {
                        scaledPixmap = pix.scaled(
                            currentSize,
                            Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                            Qt.TransformationMode.SmoothTransformation
                        )
                    }
                }
                lastSize = currentSize
            }

            scaledPixmap?.let { scaled ->
                if (!scaled.isNull) {
                    val painter = QPainter(this)
                    painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
                    val x = (width() - scaled.width()) / 2
                    val y = (height() - scaled.height()) / 2
                    painter.drawPixmap(x, y, scaled)
                    painter.end()
                    return
                }
            }
        } else {
            lastBgImagePath = null
            backgroundPixmap = null
            scaledPixmap = null
            lastSize = null
        }

        // Default fallback if no image
        val painter = QPainter(this)
        painter.fillRect(rect(), TColors.Surface0.toQC())
        painter.end()
    }
}
