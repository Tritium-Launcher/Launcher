package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.ProjectTaskMngr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.*
import kotlin.math.abs

/**
 * Manages side panel docks and toolbars for a project window.
 *
 * Panels are discovered from the registry.
 */
class SidePanelMngr(
    private val project: ProjectBase,
    private val parent: QMainWindow,
    private val onStateChanged: () -> Unit = {},
    private val onDockCreated: (String, DockWidget) -> Unit = { _, _ -> },
) {
    data class PersistedDockState(
        val id: String,
        val area: Qt.DockWidgetArea,
        val visible: Boolean
    )

    private val dockDragMimeType = "application/x-dock-id"
    private val dragInstalledProperty = "dockDragInstalled"

    private val docks = LinkedHashMap<String, DockWidget>()
    private val providersById = LinkedHashMap<String, SidePanelProvider>()
    private val dockStyleDisposers = mutableMapOf<DockWidget, () -> Unit>()

    private val leftBar   = createSidebar(Qt.ToolBarArea.LeftToolBarArea)
    private val rightBar  = createSidebar(Qt.ToolBarArea.RightToolBarArea)
    private val bottomBar = createSidebar(Qt.ToolBarArea.BottomToolBarArea)

    private val dockActions = mutableMapOf<String, QAction>()
    private val pendingDockStates = mutableMapOf<String, PersistedDockState>()
    private var leftSpacerAction: QAction? = null
    private var rightSpacerAction: QAction? = null
    private var bottomTaskSpacerAction: QAction? = null
    private var bottomTaskUnsubscribe: (() -> Unit)? = null
    private var bottomTaskWidgetAction: QAction? = null
    private lateinit var bottomTaskWidget: QWidget
    private lateinit var bottomTaskLabel: QLabel
    private lateinit var bottomTaskProgress: QProgressBar

    private val logger = logger()

    init {
        parent.setProperty("sidebar.separatorColor", TColors.Surface0)
        parent.setThemedStyle {
            val dockSurface = TColors.Surface0
            val dockBorder = TColors.Surface2

            selector("QToolBar") {
                backgroundColor(dockSurface)
                border()
            }
            selector("#leftDockBar") {
                backgroundColor(dockSurface)
                border()
                border(1, dockBorder, "right", "solid")
            }
            selector("#rightDockBar") {
                backgroundColor(dockSurface)
                border()
                border(1, dockBorder, "left", "solid")
            }
            selector("#bottomDockBar") {
                backgroundColor(dockSurface)
                border()
                border(1, dockBorder, "top", "solid")
            }
            selector("QMainWindow::separator") {
                backgroundColor(dockSurface)
                any("image", "none")
                border()
                minWidth(1)
                minHeight(1)
            }
            selector("#dockTitleBar") {
                backgroundColor(dockSurface)
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
                border(1, TColors.Surface2)
                borderRadius(3)
            }
            selector("#bottomTaskProgress::chunk") {
                backgroundColor(TColors.Accent)
                borderRadius(3)
            }
        }
        installBottomTaskIndicator()
        bottomTaskUnsubscribe = ProjectTaskMngr.addListener {
            runOnGuiThread { refreshBottomTaskIndicator() }
        }
        parent.destroyed.connect {
            bottomTaskUnsubscribe?.invoke()
            bottomTaskUnsubscribe = null
        }

        DeferredRegistryBuilder(BuiltinRegistries.SidePanel) { providers ->
            runOnGuiThread {
                buildProviders(providers.sortedBy { it.order })
            }
        }
    }

    private fun createSidebar(area: Qt.ToolBarArea): QToolBar = QToolBar().apply {
        objectName = when (area) {
            Qt.ToolBarArea.LeftToolBarArea -> "leftDockBar"
            Qt.ToolBarArea.RightToolBarArea -> "rightDockBar"
            Qt.ToolBarArea.BottomToolBarArea -> "bottomDockBar"
            else -> "leftDockBar"
        }
        isMovable = false
        isFloatable = false
        val vertical = area != Qt.ToolBarArea.BottomToolBarArea
        orientation = if(vertical) Qt.Orientation.Vertical else Qt.Orientation.Horizontal
        installSidebarDropTarget(this, toolbarAreaToDockArea(area))
        if(!vertical) {
            minimumHeight = 22
            iconSize = QSize(16, 16)
        } else {
            minimumWidth = 24
            iconSize = QSize(16, 16)
        }
        setThemedStyle {
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
            val spacer = QWidget(this).apply {
                minimumWidth = 22
                maximumWidth = 22
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

    private fun buildProviders(providers: List<SidePanelProvider>) {
        for(p in providers) {
            try {
                val dock = p.create(project)
                docks[p.id] = dock
                providersById[p.id] = p
                dock.features = QDockWidget.DockWidgetFeatures(QDockWidget.DockWidgetFeature.NoDockWidgetFeatures)

                val persisted = pendingDockStates[p.id]
                val initialArea = persisted?.area ?: normalizeDockArea(p.preferredArea)

                val action = QAction(p.icon, "").apply {
                    toolTip = p.displayName
                    isCheckable = true
                    isChecked = persisted?.visible ?: dock.isVisible
                    triggered.connect { checked ->
                        if(checked) {
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
                setDockVisibility(dock, action, persisted?.visible ?: true)

                dock.objectName = p.id
                applyDockAreaChrome(dock)
                dock.applyIcon(p.icon)

                setupTitleBar(dock, p, initialArea)
                dock.destroyed.connect {
                    dockStyleDisposers.remove(dock)?.invoke()
                }
                onDockCreated(p.id, dock)
                onStateChanged()
            } catch (t: Throwable) {
                logger.warn("Failed to create side panel {}", p.id, t)
            }
        }
    }

    private fun setupTitleBar(dock: DockWidget, provider: SidePanelProvider, currentArea: Qt.DockWidgetArea) {
        val titleBar = widget().apply { objectName = "dockTitleBar" }
        val layout = hBoxLayout(titleBar) {
            setContentsMargins(5,2,5,2)
            widgetSpacing = 5
        }

        layout.addWidget(label { pixmap = provider.icon.pixmap(16,16) ?: QPixmap() })
        layout.addWidget(label(provider.displayName) { objectName = "dockTitleLabel" })
        layout.addStretch()

        val toolBtn = toolButton {
            icon = TIcons.SmallMenu.icon
            iconSize = QSize(16, 16)
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
        val areas = mapOf(
            "Move to Left" to Qt.DockWidgetArea.LeftDockWidgetArea,
            "Move to Right" to Qt.DockWidgetArea.RightDockWidgetArea,
            "Move to Bottom" to Qt.DockWidgetArea.BottomDockWidgetArea,
        )

        for((label, area) in areas) {
            if(area == currentArea) continue
            menu.addAction(label)?.triggered?.connect { moveDock(dock, provider, area) }
        }

        toolBtn.setMenu(menu)
        layout.addWidget(toolBtn)
        dock.setTitleBarWidget(titleBar)
    }

    private fun moveDock(dock: DockWidget, provider: SidePanelProvider, newArea: Qt.DockWidgetArea) {
        val area = normalizeDockArea(newArea)
        parent.addDockWidget(area, dock)

        val action = dockActions[provider.id] ?: return
        leftBar.removeAction(action)
        rightBar.removeAction(action)
        bottomBar.removeAction(action)

        addDockActionToToolbar(area, action, provider.id)
        applyDockAreaChrome(dock)

        setupTitleBar(dock, provider, area)
        refreshBottomTaskIndicator()
        onStateChanged()
    }

    private fun addDockActionToToolbar(area: Qt.DockWidgetArea, action: QAction, providerId: String) {
        val toolbar = toolbarForDockArea(area)
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

    private fun applyDockAreaChrome(dock: DockWidget) {
        dockStyleDisposers.remove(dock)?.invoke()
        dockStyleDisposers[dock] = dock.setThemedStyle {
            val dockSurface = TColors.Surface0
            selector("QDockWidget") {
                backgroundColor(dockSurface)
                border()
            }
            selector("#dockTitleBar") {
                backgroundColor(dockSurface)
                border()
            }
            selector("#dockTitleLabel") {
                color(TColors.Text)
            }
            selector("QDockWidget > QWidget") {
                backgroundColor(dockSurface)
                border()
            }
        }
    }

    private fun bindDockActionWidget(toolbar: QToolBar, action: QAction, providerId: String) {
        fun install() {
            val button = toolbar.widgetForAction(action) as? QToolButton ?: return
            if((button.property(dragInstalledProperty) as? Boolean) == true) return
            button.setProperty(dragInstalledProperty, true)
            installDockButtonDrag(button, providerId)
        }

        install()
        QTimer.singleShot(0) { install() }
    }

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

    private fun startDockButtonDrag(button: QToolButton, providerId: String) {
        val mime = QMimeData().apply { setData(dockDragMimeType, QByteArray(providerId.toByteArray())) }
        val drag = QDrag(button)
        drag.setMimeData(mime)
        drag.setPixmap(button.grab())
        drag.setHotSpot(QPoint(button.width() / 2, button.height() / 2))
        drag.exec()
    }

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

    private fun moveDockById(dockId: String, area: Qt.DockWidgetArea) {
        val dock = docks[dockId] ?: return
        val provider = providersById[dockId] ?: return
        moveDock(dock, provider, area)
    }

    private fun setDockVisibility(dock: DockWidget, action: QAction, visible: Boolean) {
        if (visible) {
            dock.show()
            dock.raise()
        } else {
            dock.hide()
        }
        action.isChecked = visible
    }

    private fun installBottomTaskIndicator() {
        val spacer = QWidget(bottomBar).apply {
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

    fun dockWidgets(): Map<String, QDockWidget> = HashMap(docks)

    fun captureState(): List<PersistedDockState> {
        return docks.entries.map { (id, dock) ->
            PersistedDockState(
                id = id,
                area = normalizeDockArea(parent.dockWidgetArea(dock)),
                visible = dock.isVisible
            )
        }
    }

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
