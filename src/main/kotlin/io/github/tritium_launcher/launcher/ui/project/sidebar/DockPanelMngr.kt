/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockPanelTitleBarAccessoryProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.api.editor.EditorArea
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.api.runOnGuiThread
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

/**
 * Manages side panel docks and toolbars for a project window.
 *
 * Panels are discovered from the registry.
 */
class DockPanelMngr(
    private val project: ProjectBase,
    private val parent: QMainWindow,
    private val editorArea: EditorArea,
    private val onStateChanged: () -> Unit = {},
    private val onAllProvidersBuilt: () -> Unit = {},
) {
    data class PersistedDockState(
        val id: String,
        val area: Qt.DockWidgetArea,
        val visible: Boolean
    )

    private val dockDragMimeType = "application/x-dock-id"
    private val dragInstalledProperty = "dockDragInstalled"

    private val docks = LinkedHashMap<String, DockWidget>()
    private val providersById = LinkedHashMap<String, DockPanelProvider>()
    private val dockStyleDisposers = mutableMapOf<DockWidget, () -> Unit>()
    private data class ClassicButton(val button: IntelliJDockButton, val toolbarAction: QAction)
    private val classicDockButtons = mutableMapOf<String, ClassicButton>()

    fun getDock(id: String): DockWidget? = docks[id]

    private val leftBar   = createSidebar(Qt.ToolBarArea.LeftToolBarArea)
    private val rightBar  = createSidebar(Qt.ToolBarArea.RightToolBarArea)
    private val bottomBar = createSidebar(Qt.ToolBarArea.BottomToolBarArea)

    private val dockActions = mutableMapOf<String, QAction>()
    private val pendingDockStates = mutableMapOf<String, PersistedDockState>()
    private var leftSpacerAction: QAction? = null
    private var rightSpacerAction: QAction? = null
    private var bottomTaskSpacerAction: QAction? = null
    private var bottomTaskUnsubscribe: Job? = null
    private var bottomTaskWidgetAction: QAction? = null
    private lateinit var bottomTaskWidget: QWidget
    private lateinit var bottomTaskLabel: QLabel
    private lateinit var bottomTaskProgress: QProgressBar

    private val logger = logger()

    init {
        parent.setProperty("sidebar.separatorColor", TColors.Surface0)
        parent.setThemedStyle {
            val dockSurface = TColors.Surface0
            val dockBorder = TColors.Surface1
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()

            selector("QMainWindow::separator") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(dockSurface)
                }
                any("image", "none")
                any("border", "1px solid $dockBorder")
                minWidth(1)
                minHeight(1)
            }
            selector("#dockTitleBar") {
                if (isBgImageSet) {
                    backgroundColor("rgba(0, 0, 0, 40)")
                } else {
                    backgroundColor(dockSurface)
                }
                border()
            }
            selector("#dockTitleLabel") {
                color(TColors.Text)
            }
            selector("#bottomTaskWidget") {
                backgroundColor("transparent")
                border()
            }
            selector("#bottomTaskLabel") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#bottomTaskProgress") {
                backgroundColor(TColors.Surface2)
                border(1, TColors.Surface1)
                borderRadius(3)
            }
            selector("#bottomTaskProgress::chunk") {
                backgroundColor(TColors.Accent)
                borderRadius(3)
            }
        }
        installBottomTaskIndicator()
        bottomTaskUnsubscribe = ProjectTaskMngr.taskChanges.onEach {
            runOnGuiThread { refreshBottomTaskIndicator() }
        }.launchIn(CoroutineScope(Dispatchers.Main + CoroutineName("ProjectTaskMngr")))
        parent.destroyed.connect {
            bottomTaskUnsubscribe?.cancel()
            bottomTaskUnsubscribe = null
        }

        DeferredRegistryBuilder(BuiltinRegistries.SidePanel) { providers ->
            runOnGuiThread {
                buildProviders(providers.sortedBy { it.order })
                onAllProvidersBuilt()
            }
        }
    }

    /**
     * Creates a [QToolBar] from [Qt.ToolBarArea]
     */
    private fun createSidebar(area: Qt.ToolBarArea): QToolBar = QToolBar().apply {
        val areaId = when (area) {
            Qt.ToolBarArea.LeftToolBarArea   -> "leftDockBar"
            Qt.ToolBarArea.RightToolBarArea  -> "rightDockBar"
            Qt.ToolBarArea.BottomToolBarArea -> "bottomDockBar"
            else -> "leftDockBar"
        }
        objectName = areaId
        isMovable = false
        isFloatable = false
        val vertical = area != Qt.ToolBarArea.BottomToolBarArea
        orientation = if(vertical) Qt.Orientation.Vertical else Qt.Orientation.Horizontal
        installSidebarDropTarget(this, toolbarAreaToDockArea(area))
        if(!vertical) {
            minimumHeight = 28
            iconSize = QSize(20, 20)
        } else {
            minimumWidth = 30
            iconSize = QSize(20, 20)
        }
        setThemedStyle {
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()
            val dockSurface = TColors.Surface0
            val dockBorder = TColors.Surface1

            selector("QToolBar") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(dockSurface)
                }
                border()
                when (areaId) {
                    "leftDockBar" -> any("border-right", "1px solid $dockBorder")
                    "rightDockBar" -> any("border-left", "1px solid $dockBorder")
                    "bottomDockBar" -> any("border-top", "1px solid $dockBorder")
                }
            }
            selector("QToolButton") {
                backgroundColor("transparent")
                border()
                borderRadius(3)
                margin(4, 4, 4, 4)
            }
            selector("QToolButton:hover") {
                backgroundColor(TColors.Surface1)
            }
            selector("QToolButton:checked") {
                backgroundColor(TColors.Surface2)
                border(1, TColors.Surface2.brightness(0.20f))
                borderRadius(3)
            }
            selector("QToolButton:pressed") {
                backgroundColor(TColors.Surface2)
            }
            selector("QToolBar::handle") {
                any("image", "none")
                any("width", "0px")
                any("height", "0px")
                border()
                margin(0)
                padding(0)
            }
        }
        parent.addToolBar(area, this)
        if(vertical) {
            val spacer = widget(this) {
                minimumWidth = 30
                maximumWidth = 30
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Expanding)
            }
            val action = addWidget(spacer)
            when (area) {
                Qt.ToolBarArea.LeftToolBarArea -> leftSpacerAction = action
                Qt.ToolBarArea.RightToolBarArea -> rightSpacerAction = action
                else -> {}
            }
        }
    }

    /**
     * Build [QDockWidget]s from registered [DockPanelProvider]'s
     */
    private fun buildProviders(providers: List<DockPanelProvider>) {
        for(p in providers) {
            try {
                val dock = p.create(project)
                docks[p.id] = dock
                providersById[p.id] = p
                dock.features = QDockWidget.DockWidgetFeatures(QDockWidget.DockWidgetFeature.NoDockWidgetFeatures)

                val persisted = pendingDockStates[p.id]
                val initialArea = persisted?.area ?: run {
                    val pref = p.preferredArea
                    if (p.allowedDockAreas.contains(pref)) pref else p.allowedDockAreas.firstOrNull() ?: normalizeDockArea(pref)
                }

                val action = QAction(p.icon ?: QIcon(), "").apply {
                    toolTip = p.displayName
                    isCheckable = true
                    isChecked = persisted?.visible ?: dock.isVisible
                    triggered.connect { checked ->
                        if(checked) {
                            val area = parent.dockWidgetArea(dock)
                            docks.values.forEach { otherDock ->
                                if (otherDock != dock && parent.dockWidgetArea(otherDock) == area && otherDock.isVisible) {
                                    val otherProvider = providersById[otherDock.objectName]
                                    if (otherProvider?.allowSplit == false || !p.allowSplit) otherDock.hide()
                                }
                            }
                            dock.show()
                            dock.raise()
                        } else {
                            dock.hide()
                        }
                    }
                }
                dock.visibilityChanged.connect { visible ->
                    if(action.isChecked != visible) action.isChecked = visible
                    onStateChanged()
                }
                dockActions[p.id] = action

                parent.addDockWidget(initialArea, dock)
                addDockActionToToolbar(initialArea, action, p.id)
                setDockVisibility(dock, action, persisted?.visible ?: p.defaultVisible)

                dock.objectName = p.id
                applyDockAreaChrome(dock)
                setupTitleBar(dock, p, initialArea)

                val titleBarIcon = dock.findChild(QLabel::class.java, "dockTitleBarIcon")
                dock.iconUpdater = { newIcon ->
                    action.icon = newIcon
                    titleBarIcon?.let {
                        val px = newIcon.pixmap(16, 16)
                        if (px != null && !px.isNull) it.pixmap = px
                    }
                    dock.windowIcon = newIcon
                }
                p.icon?.let { dock.applyIcon(it) }
                dock.destroyed.connect {
                    dockStyleDisposers.remove(dock)?.invoke()
                }
                p.onDockCreated(project, editorArea, dock, onStateChanged)
                onStateChanged()
            } catch (t: Throwable) {
                logger.warn("Failed to create side panel {}", p.id, t)
            }
        }
    }

    /**
     * Create title bar for specified [DockPanelProvider]
     */
    private fun setupTitleBar(dock: DockWidget, provider: DockPanelProvider, currentArea: Qt.DockWidgetArea) {
        val titleBar = widget().apply { objectName = "dockTitleBar" }
        val layout = hBoxLayout(titleBar) {
            setContentsMargins(5,2,5,2)
            widgetSpacing = 5
        }

        layout.addWidget(label { objectName = "dockTitleBarIcon"; pixmap = provider.icon?.pixmap(16, 16) ?: QPixmap() })
        layout.addWidget(label(provider.displayName) { objectName = "dockTitleLabel" })

        if (provider is DockPanelTitleBarAccessoryProvider) {
            provider.createLeftTitleBarAccessory(project, dock, onStateChanged)?.let { accessory ->
                layout.addWidget(accessory)
            }
        }

        layout.addStretch()

        if (provider is DockPanelTitleBarAccessoryProvider) {
            provider.createTitleBarAccessory(project, dock, onStateChanged)?.let { accessory ->
                layout.addWidget(accessory)
            }
        }

        val toolBtn = toolButton {
            iconSize = QSize(20, 20)
            autoRaise = true
            popupMode = QToolButton.ToolButtonPopupMode.InstantPopup
            setThemedStyle {
                selector("QToolButton") {
                    background("transparent")
                    border()
                    padding(0)
                    margin(0)
                }
                selector("QToolButton::menu-indicator") {
                    any("image", "none")
                    any("width", "0px")
                    any("height", "0px")
                }
            }
        }

        val menu = QMenu(toolBtn)
        val areas = listOf(
            "Move to Left" to Qt.DockWidgetArea.LeftDockWidgetArea,
            "Move to Right" to Qt.DockWidgetArea.RightDockWidgetArea,
            "Move to Bottom" to Qt.DockWidgetArea.BottomDockWidgetArea,
        )

        for((label, area) in areas) {
            if(area == currentArea) continue
            // Only show moves that the provider allows
            if (!provider.allowedDockAreas.contains(area)) continue
            menu.addAction(label)?.triggered?.connect { moveDock(dock, provider, area) }
        }

        toolBtn.setMenu(menu)
        layout.addWidget(toolBtn)
        dock.setTitleBarWidget(titleBar)
    }

    /**
     * Moves a provided [DockWidget] to a different [Qt.DockWidgetArea]
     */
    private fun moveDock(dock: DockWidget, provider: DockPanelProvider, newArea: Qt.DockWidgetArea) {
        val area = normalizeDockArea(newArea)
        // Respect provider's allowed dock areas
        if (!provider.allowedDockAreas.contains(area)) return
        parent.addDockWidget(area, dock)

        val action = dockActions[provider.id] ?: return
        if (CoreSettingValues.dockButtonStyle == CoreSettingValues.DockButtonStyle.IntelliJClassic) {
            classicDockButtons.remove(provider.id)?.let { cb ->
                leftBar.removeAction(cb.toolbarAction)
                rightBar.removeAction(cb.toolbarAction)
                bottomBar.removeAction(cb.toolbarAction)
                cb.button.setParent(null)
                cb.button.close()
            }
        } else {
            leftBar.removeAction(action)
            rightBar.removeAction(action)
            bottomBar.removeAction(action)
        }

        addDockActionToToolbar(area, action, provider.id)
        applyDockAreaChrome(dock)

        setupTitleBar(dock, provider, area)
        refreshBottomTaskIndicator()
        onStateChanged()
    }

    /**
     * Adds an action to [DockPanelProvider] toolbar
     */
    private fun addDockActionToToolbar(area: Qt.DockWidgetArea, action: QAction, providerId: String) {
        val toolbar = toolbarForDockArea(area)
        if (CoreSettingValues.dockButtonStyle == CoreSettingValues.DockButtonStyle.IntelliJClassic) {
            addClassicDockButton(toolbar, area, action, providerId)
            return
        }
        if (area == Qt.DockWidgetArea.BottomDockWidgetArea) {
            val spacerAction = bottomTaskSpacerAction
            if (spacerAction != null) {
                toolbar.insertAction(spacerAction, action)
            } else {
                toolbar.addAction(action)
            }
        } else {
            val spacerAction = when (area) {
                Qt.DockWidgetArea.LeftDockWidgetArea -> leftSpacerAction
                Qt.DockWidgetArea.RightDockWidgetArea -> rightSpacerAction
                else -> null
            }
            if(spacerAction != null) {
                toolbar.insertAction(spacerAction, action)
            } else {
                toolbar.addAction(action)
            }
        }
        toolbar.show()
        bindDockActionWidget(toolbar, action, providerId)
    }

    private fun addClassicDockButton(toolbar: QToolBar, area: Qt.DockWidgetArea, action: QAction, providerId: String) {
        val provider = providersById[providerId] ?: return
        val btn = IntelliJDockButton(area).apply {
            setCustomIcon(provider.icon ?: QIcon())
            setCustomText(provider.displayName)
            isChecked = action.isChecked
            toggled.connect { checked ->
                action.isChecked = checked
                action.trigger()
            }
        }
        val vertical = toolbar.orientation() == Qt.Orientation.Vertical
        if (vertical) {
            btn.minimumWidth = toolbar.minimumWidth
            btn.maximumWidth = toolbar.maximumWidth
        } else {
            btn.minimumHeight = toolbar.minimumHeight
            btn.maximumHeight = toolbar.maximumHeight
        }
        val toolbarAction = if (area == Qt.DockWidgetArea.BottomDockWidgetArea) {
            val spacerAction = bottomTaskSpacerAction
            if (spacerAction != null) {
                toolbar.insertWidget(spacerAction, btn)
            } else {
                toolbar.addWidget(btn)
            }
        } else {
            val spacerAction = when (area) {
                Qt.DockWidgetArea.LeftDockWidgetArea -> leftSpacerAction
                Qt.DockWidgetArea.RightDockWidgetArea -> rightSpacerAction
                else -> null
            }
            if (spacerAction != null) {
                toolbar.insertWidget(spacerAction, btn)
            } else {
                toolbar.addWidget(btn)
            }
        }
        classicDockButtons[providerId] = ClassicButton(btn, toolbarAction)
        toolbar.show()
    }

    private fun normalizeDockArea(area: Qt.DockWidgetArea): Qt.DockWidgetArea = when (area) {
        Qt.DockWidgetArea.LeftDockWidgetArea,
        Qt.DockWidgetArea.RightDockWidgetArea,
        Qt.DockWidgetArea.BottomDockWidgetArea -> area
        else -> Qt.DockWidgetArea.LeftDockWidgetArea
    }

    private fun toolbarForDockArea(area: Qt.DockWidgetArea): QToolBar = when (area) {
        Qt.DockWidgetArea.LeftDockWidgetArea -> leftBar
        Qt.DockWidgetArea.RightDockWidgetArea -> rightBar
        Qt.DockWidgetArea.BottomDockWidgetArea -> bottomBar
        else -> leftBar
    }

    private fun toolbarAreaToDockArea(area: Qt.ToolBarArea): Qt.DockWidgetArea = when (area) {
        Qt.ToolBarArea.LeftToolBarArea -> Qt.DockWidgetArea.LeftDockWidgetArea
        Qt.ToolBarArea.RightToolBarArea -> Qt.DockWidgetArea.RightDockWidgetArea
        Qt.ToolBarArea.BottomToolBarArea -> Qt.DockWidgetArea.BottomDockWidgetArea
        else -> Qt.DockWidgetArea.LeftDockWidgetArea
    }

    /**
     * Style a provided [DockWidget]
     */
    private fun applyDockAreaChrome(dock: DockWidget) {
        dockStyleDisposers.remove(dock)?.invoke()
        dockStyleDisposers[dock] = dock.setThemedStyle {
            val dockSurface = TColors.Surface0
            val dockBorder = TColors.Surface2
            val bgImage = CoreSettingValues.uiBackgroundImage
            val isBgImageSet = !bgImage.isNullOrBlank()

            selector("QDockWidget") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(dockSurface)
                }
                border()
            }
            selector("#dockTitleBar") {
                if (isBgImageSet) {
                    backgroundColor("rgba(0, 0, 0, 40)")
                } else {
                    backgroundColor(dockSurface)
                }
                border()
            }
            selector("#dockTitleLabel") {
                color(TColors.Text)
            }
            selector("QDockWidget > QWidget") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                } else {
                    backgroundColor(dockSurface)
                }
                border()
            }
            selector("QDockWidget QTreeView, QDockWidget QTreeWidget, QDockWidget QListView, QDockWidget QListWidget") {
                if (isBgImageSet) {
                    backgroundColor("transparent")
                }
            }
        }
    }

    /**
     * Bind actions to a [DockPanelProvider] using its ID
     */
    private fun bindDockActionWidget(toolbar: QToolBar, action: QAction, providerId: String) {
        fun install() {
            val button = toolbar.widgetForAction(action) as? QToolButton ?: return
            if((button.property(dragInstalledProperty) as? Boolean) == true) return
            button.setProperty(dragInstalledProperty, true)
            installDockButtonDrag(button, providerId)
            if (toolbar.orientation() == Qt.Orientation.Vertical) {
                button.minimumWidth = 30
                button.maximumWidth = 30
                button.minimumHeight = 30
                button.maximumHeight = 30
            } else {
                button.minimumHeight = 28
                button.maximumHeight = 28
                button.minimumHeight = 28
                button.maximumHeight = 28
            }
        }

        install()
        QTimer.singleShot(0) { install() }
    }

    /**
     * Installs a Dragging event filter to enable moving [DockPanelProvider] to another area
     */
    private fun installDockButtonDrag(button: QToolButton, providerId: String) {
        button.installEventFilter(object : QObject(button) {
            private var pressPos: QPoint? = null

            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                if(watched !== button || event == null) return super.eventFilter(watched, event)
                when(event.type()) {
                    QEvent.Type.MouseButtonPress -> {
                        val mouse = event as QMouseEvent
                        if(mouse.button() == Qt.MouseButton.LeftButton) {
                            pressPos = mouse.pos()
                        }
                    }
                    QEvent.Type.MouseMove -> {
                        val start = pressPos ?: return super.eventFilter(watched, event)
                        val mouse = event as QMouseEvent
                        val dx = abs(mouse.pos().x() - start.x())
                        val dy = abs(mouse.pos().y() - start.y())
                        if(dx + dy >= QApplication.startDragDistance()) {
                            pressPos = null
                            startDockButtonDrag(button, providerId)
                            return true
                        }
                    }
                    QEvent.Type.MouseButtonRelease,
                    QEvent.Type.Hide -> {
                        pressPos = null
                    }
                    else -> {}
                }
                return super.eventFilter(watched, event)
            }
        })
    }

    /**
     * Drag helper for [installDockButtonDrag]
     */
    private fun startDockButtonDrag(button: QToolButton, providerId: String) {
        val mime = QMimeData().apply { setData(dockDragMimeType, QByteArray(providerId.toByteArray())) }
        val drag = QDrag(button)
        drag.setMimeData(mime)
        drag.setPixmap(button.grab())
        drag.setHotSpot(QPoint(button.width() / 2, button.height() / 2))
        drag.exec()
    }

    /**
     * Drag helper for [installDockButtonDrag]
     */
    private fun installSidebarDropTarget(toolbar: QToolBar, area: Qt.DockWidgetArea) {
        toolbar.acceptDrops = true
        toolbar.installEventFilter(object : QObject(toolbar) {
            override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
                if(watched !== toolbar || event == null) return super.eventFilter(watched, event)
                when(event.type()) {
                    QEvent.Type.DragEnter -> {
                        val dragEvent = event as QDragEnterEvent
                        if(extractDockId(dragEvent.mimeData()) != null) {
                            dragEvent.acceptProposedAction()
                            return true
                        }
                    }
                    QEvent.Type.DragMove -> {
                        val dragEvent = event as QDragMoveEvent
                        if(extractDockId(dragEvent.mimeData()) != null) {
                            dragEvent.acceptProposedAction()
                            return true
                        }
                    }
                    QEvent.Type.Drop -> {
                        val dropEvent = event as QDropEvent
                        val dockId = extractDockId(dropEvent.mimeData()) ?: return super.eventFilter(watched, event)
                        moveDockById(dockId, area)
                        dropEvent.acceptProposedAction()
                        return true
                    }
                    else -> {}
                }
                return super.eventFilter(watched, event)
            }
        })
    }

    /**
     * Gets a dock ID from [QMimeData]
     */
    private fun extractDockId(mimeData: QMimeData?): String? {
        val md = mimeData ?: return null
        if(!md.hasFormat(dockDragMimeType)) return null
        val raw = md.data(dockDragMimeType) ?: return null
        val buffer = raw.data() ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val id = String(bytes, Charsets.UTF_8)
        return id.takeIf { it.isNotBlank() }
    }

    /**
     * Moves a dock using its ID
     */
    private fun moveDockById(dockId: String, area: Qt.DockWidgetArea) {
        val dock = docks[dockId] ?: return
        val provider = providersById[dockId] ?: return
        moveDock(dock, provider, area)
    }

    /**
     * Toggles a dock widget by its provider ID
     */
    fun toggleDock(id: String) {
        val action = dockActions[id] ?: return
        action.trigger()
    }

    /**
     * Shows or hides specified [DockWidget]
     */
    private fun setDockVisibility(dock: DockWidget, action: QAction, visible: Boolean) {
        if (visible) {
            dock.show()
            dock.raise()
        } else {
            dock.hide()
        }
        action.isChecked = visible
    }

    /**
     * Installs an Active Task indicator to the bottom toolbar
     */
    private fun installBottomTaskIndicator() {
        val spacer = widget(bottomBar) {
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Preferred)
            minimumWidth = 10
            minimumHeight = 18
        }
        bottomTaskSpacerAction = bottomBar.addWidget(spacer)

        bottomTaskWidget = widget(parent = bottomBar) {
            objectName = "bottomTaskWidget"
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        }
        hBoxLayout(bottomTaskWidget) {
            setContentsMargins(6, 0, 4, 0)
            widgetSpacing = 8
            addWidget(label(parent = bottomTaskWidget) {
                objectName = "bottomTaskLabel"
                text = "Background Tasks"
                minimumWidth = 170
                maximumWidth = 370
                sizePolicy = QSizePolicy(QSizePolicy.Policy.Preferred, QSizePolicy.Policy.Fixed)
            }.also { bottomTaskLabel = it })
            addWidget(QProgressBar(bottomTaskWidget).apply {
                objectName = "bottomTaskProgress"
                minimumWidth = 120
                maximumWidth = 140
                minimumHeight = 10
                maximumHeight = 10
                textVisible = false
                setRange(0, 0)
            }.also { bottomTaskProgress = it })
        }
        bottomTaskWidgetAction = bottomBar.addWidget(bottomTaskWidget)
        refreshBottomTaskIndicator()
    }

    /**
     * Refresh the active task indicator
     */
    private fun refreshBottomTaskIndicator() {
        if (!::bottomTaskWidget.isInitialized) return

        val tasks = ProjectTaskMngr.activeForProject(project)
        if (tasks.isEmpty()) {
            bottomTaskWidgetAction?.isVisible = false
            bottomTaskLabel.toolTip = ""
            bottomTaskProgress.toolTip = ""
            bottomTaskWidget.hide()
            bottomBar.update()
            bottomBar.updateGeometry()
            parent.update()
            parent.updateGeometry()
            return
        }

        val primary = tasks.first()
        val extraCount = (tasks.size - 1).coerceAtLeast(0)
        val extraText = if (extraCount > 0) " (+$extraCount)" else ""
        bottomTaskLabel.text = buildString {
            append(primary.title)
            if (primary.detail.isNotBlank()) {
                append(": ")
                append(primary.detail)
            }
            append(extraText)
        }

        val tooltip = tasks.joinToString("\n") { task ->
            val detail = task.detail.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            val progress = task.progressPercent?.let { "$it%" } ?: "working..."
            "${task.title}$detail ($progress)"
        }
        bottomTaskLabel.toolTip = tooltip
        bottomTaskProgress.toolTip = tooltip

        val progress = primary.progressPercent
        if (progress == null) {
            bottomTaskProgress.setRange(0, 0)
        } else {
            bottomTaskProgress.setRange(0, 100)
            bottomTaskProgress.value = progress
        }

        bottomTaskWidgetAction?.isVisible = true
        bottomTaskWidget.show()
        bottomBar.update()
        bottomBar.updateGeometry()
        parent.update()
        parent.updateGeometry()
    }

    /**
     * Returns active dock widgets
     */
    fun dockWidgets(): Map<String, QDockWidget> = HashMap(docks)

    /**
     * Persist docks' states
     */
    fun captureState(): List<PersistedDockState> {
        return docks.entries.map { (id, dock) ->
            PersistedDockState(
                id = id,
                area = normalizeDockArea(parent.dockWidgetArea(dock)),
                visible = dock.isVisible
            )
        }
    }

    /**
     * Restores dock states
     */
    fun restoreState(states: List<PersistedDockState>) {
        pendingDockStates.clear()
        states.forEach { state ->
            pendingDockStates[state.id] = state
        }

        states.forEach { state ->
            val dock = docks[state.id] ?: return@forEach
            val provider = providersById[state.id] ?: return@forEach
            val area = normalizeDockArea(state.area)
            moveDock(dock, provider, area)
            val action = dockActions[state.id] ?: return@forEach
            setDockVisibility(dock, action, state.visible)
        }
    }
}
