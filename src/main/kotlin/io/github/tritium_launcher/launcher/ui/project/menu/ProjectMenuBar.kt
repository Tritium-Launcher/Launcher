/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.menu

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.menu.MenuActionContext
import io.github.tritium_launcher.api.menu.MenuItem
import io.github.tritium_launcher.api.menu.MenuItemKind
import io.github.tritium_launcher.api.project.ProjectType
import io.github.tritium_launcher.launcher.keymap.ActionRegistry
import io.github.tritium_launcher.launcher.keymap.KeymapMngr
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.registrydb.RegistryRefreshService
import io.github.tritium_launcher.launcher.ui.project.ProjectViewWindow
import io.github.tritium_launcher.launcher.ui.project.menu.builtin.BuiltinMenuItems
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.LongPressButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.pushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.qt.core.QSize
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QGuiApplication
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Command bar that replaces the native menu bar and supports:
 * - Action buttons
 * - Drop-down menus
 * - Separators
 * - Arbitrary embedded widgets via [io.github.tritium_launcher.api.menu.MenuItem.widgetFactory]
 *
 * Extensions contribute items via the `ui.menu` registry.
 * Visible items are filtered by the active project's [ProjectType.menuScope].
 * Mnemonics are supported via '&' in titles (Qt standard).
 */
class ProjectMenuBar : QWidget() {
    private val logger = logger()
    private var attachedWindow: QMainWindow? = null
    private var lastProject: ProjectBase? = null
    private var lastSelection: Any? = null

    private val layout = hBoxLayout(this) {
        widgetSpacing = 0
        contentsMargins = 0.m
    }

    // Center section widgets
    private var centerSection: QWidget? = null
    private var projectIconLabel: QLabel? = null
    private var projectNameLabel: QLabel? = null
    private var playBtn: QPushButton? = null
    private var stopBtn: QPushButton? = null
    private var buildBtn: QPushButton? = null
    private var settingsBtn: QPushButton? = null

    /** Container for left-side menu items, overlaid to avoid shifting the center. */
    private var leftOverlay: QWidget? = null

    init {
        objectName = "projectMenuBar"
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
        val keymapJob = CoroutineScope(Dispatchers.Main).launch {
            KeymapMngr.activeKeymapFlow.collect {
                val window = attachedWindow ?: return@collect
                if (!window.isVisible) return@collect
                QTimer.singleShot(0) {
                    rebuildFor(window, lastProject, lastSelection)
                }
            }
        }
        destroyed.connect {
            keymapJob.cancel()
        }
        setThemedStyle {
            selector("#projectMenuBar") {
                backgroundColor(TColors.Surface0)
                border(1, TColors.Surface1, "bottom")
                minHeight(32)
            }

            selector("#projectMenuBar QPushButton, #projectMenuBar QToolButton") {
                backgroundColor("transparent")
                color(TColors.Text)
                border()
                minHeight(24)
                borderRadius(4)
                padding(4, 6, 4, 6)
            }

            selector("#projectMenuBar QPushButton:hover, #projectMenuBar QToolButton:hover") {
                backgroundColor(TColors.Surface1)
                borderRadius(4)
            }

            selector("#projectMenuBar QPushButton:pressed, #projectMenuBar QToolButton:pressed") {
                backgroundColor(TColors.Surface2)
                borderRadius(4)
            }

            selector("#projectMenuBar QPushButton:disabled, #projectMenuBar QToolButton:disabled") {
                color(TColors.Subtext)
                backgroundColor("transparent")
            }

            selector("#projectMenuBar QPushButton[menuIconOnly=\"true\"]") {
                minWidth(28)
                maxWidth(28)
            }

            selector("#menuBarCenterSection") {
                backgroundColor("transparent")
            }

            selector("#menuBarProjectIcon") {
                minWidth(18)
                minHeight(18)
                maxWidth(18)
                maxHeight(18)
            }

            selector("#menuBarProjectName") {
                color(TColors.Text)
                fontSize(13)
                fontWeight(600)
            }

            selector("#menuBarSettingsBtn") {
                minWidth(28)
                maxWidth(28)
                borderRadius(4)
            }

            selector("#menuBarSettingsBtn:hover") {
                backgroundColor(TColors.Surface1)
            }

            // Hide the default drop-down indicator on top-level menu buttons.
            selector("#projectMenuBar QToolButton::menu-indicator") {
                any("image", "none")
                any("width", "0px")
                any("height", "0px")
            }
        }
    }

    fun attach(window: QMainWindow) {
        attachedWindow = window
        window.setMenuWidget(this)
    }

    fun rebuildFor(window: QMainWindow, project: ProjectBase?, selection: Any?) {
        attachedWindow = window
        lastProject = project
        lastSelection = selection
        clearLayout()

        val allItems = BuiltinRegistries.MenuItem.all().toList()
        val projectType = resolveProjectType(project)
        val items = filterItemsForProjectType(allItems, projectType)
            .sortedWith(compareBy({ it.parentId ?: "" }, { it.order }, { it.title }))

        val children = HashMap<String, MutableList<MenuItem>>()
        val roots = mutableListOf<MenuItem>()

        for (it in items) {
            val pid = it.parentId
            if (pid == null) roots.add(it) else children.computeIfAbsent(pid) { mutableListOf() }.add(it)
        }

        val topSorted = roots.sortedWith(compareBy({ it.order }, { it.title }))
        val leftItems = topSorted.filter {
            val ctx = MenuActionContext(project, window, selection, it.meta)
            !isRightAligned(it) && it.isVisible(ctx)
        }

        /*
         Left-side overlay container – holds menu item widgets in its own
         layout so they get proper parenting + show(). Positioned manually
         outside the main layout to avoid shifting the center section.
        */
        if (leftItems.isNotEmpty()) {
            val container = QWidget(this)
            container.objectName = "menuBarLeftOverlay"
            val leftHBox = hBoxLayout(container) {
                widgetSpacing = 0
                contentsMargins = 0.m
            }
            leftItems.forEach { top ->
                addTopItemTo(leftHBox, window, top, children, project, selection)
            }
            container.show()
            leftOverlay = container
        }

        if (project != null) {
            layout.addStretch(1)
            addCenterSection(window, project, selection)
            layout.addStretch(1)
        } else {
            layout.addStretch(1)
        }

        createSettingsButton(window)

        positionAllChildren()
        update()
    }

    private fun loadMenuIcon(key: String, targetSize: Int): QIcon {
        val pix = TIcons.pixForKey(key, 24, 24)
        if (!pix.isNull) {
            val scaled = pix.scaled(targetSize, targetSize, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.FastTransformation)
            return scaled.icon
        }
        return pix.icon
    }

    private fun loadMenuIcon(icon: QIcon, targetSize: Int): QIcon {
        val pix = icon.pixmap(targetSize, targetSize)
        if (!pix.isNull) {
            return pix.scaled(targetSize, targetSize, Qt.AspectRatioMode.IgnoreAspectRatio, Qt.TransformationMode.FastTransformation).icon
        }
        return icon
    }

    private fun addCenterSection(window: QMainWindow, project: ProjectBase, selection: Any?) {
        centerSection?.let { cs ->
            layout.removeWidget(cs)
            cs.disposeLater()
        }
        centerSection = qWidget {
            objectName = "menuBarCenterSection"
            val hbox = hBoxLayout(this) {
                widgetSpacing = 12
                contentsMargins = 0.m
            }

            val iconPix = runCatching {
                val iconPath = project.getIconPath()
                QPixmap(iconPath)
            }.getOrNull()
            projectIconLabel = label {
                objectName = "menuBarProjectIcon"
                if (iconPix != null && !iconPix.isNull) {
                    pixmap = iconPix.scaled(18, 18, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                }
            }

            projectNameLabel = label(project.name) {
                objectName = "menuBarProjectName"
            }

            playBtn = LongPressButton().apply {
                val playItem = BuiltinRegistries.MenuItem.all().find { it.id == "play" }
                objectName = "menuBarPlayBtn"
                setProperty("menuIconOnly", true)
                isFlat = true
                iconSize = QSize(24, 24)

                fun refreshPlayState() {
                    val ctx = playItem?.let { MenuActionContext(project, window, selection, it.meta) }
                    isEnabled = playItem?.isEnabled(ctx) ?: true
                    icon = playItem?.resolveIcon(ctx)?.let { loadMenuIcon(it, 24) } ?: loadMenuIcon("menu/run", 24)
                    toolTip = playItem?.tooltip ?: "Play"
                }
                refreshPlayState()

                if (playItem != null) {
                    onNormalClick = {
                        val actionCtx = MenuActionContext(project, window, selection, playItem.meta)
                        playItem.action?.invoke(actionCtx)
                        refreshPlayState()
                    }
                    onLongPress = {
                        CoroutineScope(Dispatchers.Main).launch {
                            BuiltinMenuItems.launchOrPrepare(project)
                        }
                    }
                }

                val stateTimer = QTimer(this).apply {
                    interval = 200
                    timeout.connect { refreshPlayState() }
                    start()
                }
                destroyed.connect { stateTimer.stop() }
            }

            stopBtn = pushButton {
                val stopItem = BuiltinRegistries.MenuItem.all().find { it.id == "stop_game" }
                val useShiftHoverForceIcon = stopItem?.meta?.get("shiftHoverForceIcon") == "true"
                objectName = "menuBarStopBtn"
                setProperty("menuIconOnly", true)
                isFlat = true
                mouseTracking = true
                iconSize = QSize(24, 24)

                fun refreshStopState() {
                    val ctx = stopItem?.let { MenuActionContext(project, window, selection, it.meta) }
                    isEnabled = stopItem?.isEnabled(ctx) ?: false
                    val showForceIcon = useShiftHoverForceIcon &&
                        isEnabled &&
                        underMouse() &&
                        QGuiApplication.queryKeyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)
                    val resolvedIcon = if (showForceIcon) TIcons.ForceStop.icon else stopItem?.resolveIcon(ctx)
                    icon = resolvedIcon?.let { loadMenuIcon(it, 24) } ?: loadMenuIcon("menu/stop", 24)
                    toolTip = if (showForceIcon) "Force-stop game process" else (stopItem?.tooltip ?: "Stop")
                }
                refreshStopState()

                if (stopItem != null) {
                    clicked.connect {
                        val actionCtx = MenuActionContext(project, window, selection, stopItem.meta)
                        stopItem.action?.invoke(actionCtx)
                        refreshStopState()
                    }
                }

                val stateTimer = QTimer(this).apply {
                    interval = 50
                    timeout.connect { refreshStopState() }
                    start()
                }
                destroyed.connect { stateTimer.stop() }
            }

            buildBtn = LongPressButton().apply {
                objectName = "menuBarBuildBtn"
                setProperty("menuIconOnly", true)
                isFlat = true
                icon = loadMenuIcon("menu/build", 22)
                iconSize = QSize(24, 24)
                holdOnPress = true

                fun refreshBuildState() {
                    isEnabled = !RegistryRefreshService.isBuilding(project)
                    icon = loadMenuIcon("menu/build", 24)
                    toolTip = "Build Registry"
                }
                refreshBuildState()

                onNormalClick = {
                    RegistryRefreshService.triggerBuild(project)
                    refreshBuildState()
                }

                onLongPress = {
                    RegistryRefreshService.triggerRefresh(project)
                    refreshBuildState()
                }

                val stateTimer = QTimer(this).apply {
                    interval = 200
                    timeout.connect { refreshBuildState() }
                    start()
                }
                destroyed.connect { stateTimer.stop() }
            }

            hbox.addWidget(projectIconLabel!!)
            hbox.addWidget(projectNameLabel!!)
            hbox.addWidget(playBtn!!)
            hbox.addWidget(stopBtn!!)
            hbox.addWidget(buildBtn!!)
        }
        layout.addWidget(centerSection)
    }

    private fun createSettingsButton(window: QMainWindow) {
        settingsBtn = pushButton(this) {
            icon = loadMenuIcon("menu/settings", 24)
            iconSize = QSize(24, 24)
            objectName = "menuBarSettingsBtn"
            toolTip = "Settings"
            isFlat = true
            setProperty("menuIconOnly", true)
            clicked.connect {
                (window as? ProjectViewWindow)?.openSettings()
            }
            show()
        }
    }

    private fun positionAllChildren() {
        val h = height
        leftOverlay?.let { container ->
            container.adjustSize()
            container.move(0, (h - container.height()) / 2)
            container.raise()
        }

        settingsBtn?.let { btn ->
            btn.adjustSize()
            val margin = 4
            btn.move(width - btn.width() - margin, (h - btn.height()) / 2)
            btn.raise()
        }
    }

    override fun resizeEvent(event: io.qt.gui.QResizeEvent?) {
        super.resizeEvent(event)
        positionAllChildren()
    }

    private fun resolveProjectType(project: ProjectBase?): ProjectType? {
        val typeId = project?.typeId?.trim().orEmpty()
        if (typeId.isEmpty()) return null

        val projectTypes = BuiltinRegistries.ProjectType
        projectTypes.get(typeId)?.let { return it }
        val localId = typeId.substringAfterLast(':', missingDelimiterValue = typeId)
        if (localId != typeId) {
            projectTypes.get(localId)?.let { return it }
        }
        return null
    }

    private fun filterItemsForProjectType(items: List<MenuItem>, projectType: ProjectType?): List<MenuItem> {
        val scope = projectType?.menuScope ?: return items
        val includeIds = scope.includedIds()
        val excludeIds = scope.excludedIds()
        if (!scope.strict && includeIds.isEmpty() && excludeIds.isEmpty()) {
            return items
        }
        if (scope.strict && includeIds.isEmpty()) {
            return emptyList()
        }

        val parentById = items.associate { it.id to it.parentId }

        fun chainContains(startId: String, ids: Set<String>): Boolean {
            var current: String? = startId
            while (current != null) {
                if (current in ids) return true
                current = parentById[current]
            }
            return false
        }

        val seedIds = linkedSetOf<String>()
        items.forEach { item ->
            val includedByChain = chainContains(item.id, includeIds)
            val excludedByChain = chainContains(item.id, excludeIds)
            val keep = if (scope.strict) {
                includedByChain
            } else {
                !excludedByChain || includedByChain
            }
            if (keep) {
                seedIds += item.id
            }
        }
        if (seedIds.isEmpty()) return emptyList()

        val keepWithAncestors = linkedSetOf<String>()
        seedIds.forEach { id ->
            var current: String? = id
            while (current != null) {
                keepWithAncestors += current
                current = parentById[current]
            }
        }
        return items.filter { it.id in keepWithAncestors }
    }

    private fun addTopItem(
        window: QMainWindow,
        top: MenuItem,
        children: Map<String, List<MenuItem>>,
        project: ProjectBase?,
        selection: Any?
    ) {
        addTopItemTo(layout, window, top, children, project, selection)
    }

    private fun addTopItemTo(
        target: QLayout,
        window: QMainWindow,
        top: MenuItem,
        children: Map<String, List<MenuItem>>,
        project: ProjectBase?,
        selection: Any?
    ) {
        val ctx = MenuActionContext(project, window, selection, top.meta)
        if (!top.isVisible(ctx)) return
        when (top.kind) {
            MenuItemKind.WIDGET -> {
                val widget = top.widgetFactory?.invoke(ctx)
                if (widget != null) {
                    target.addWidget(widget)
                }
            }

            MenuItemKind.ACTION -> {
                target.addWidget(makeActionButton(window, top, project, selection))
            }

            MenuItemKind.MENU -> {
                target.addWidget(makeMenuButton(window, top, children, project, selection))
            }

            MenuItemKind.SEPARATOR -> {
                target.addWidget(makeSeparator())
            }
        }
    }



    private fun makeSeparator(): QWidget {
        val sep = QFrame()
        sep.frameShape = QFrame.Shape.VLine
        sep.frameShadow = QFrame.Shadow.Sunken
        return sep
    }

    private fun isRightAligned(item: MenuItem): Boolean =
        item.meta["align"]?.equals("right", ignoreCase = true) == true

    private fun makeActionButton(window: QMainWindow, item: MenuItem, project: ProjectBase?, selection: Any?): QPushButton {
        val baseCtx = MenuActionContext(project, window, selection, item.meta)
        registerActionHandler(item, window, project, selection)
        val iconOnly = item.meta["iconOnly"]?.equals("true", ignoreCase = true) == true
        val useShiftHoverForceIcon = item.meta["shiftHoverForceIcon"]?.equals("true", ignoreCase = true) == true
        val btn = QPushButton(if (iconOnly) "" else item.resolveTitle(baseCtx))
        btn.isFlat = true
        btn.mouseTracking = true
        fun refreshVisualState() {
            val ctx = MenuActionContext(project, window, selection, item.meta)
            btn.isEnabled = item.isEnabled(ctx)
            if (!iconOnly) {
                btn.text = item.resolveTitle(ctx)
            }

            val showForceIcon = useShiftHoverForceIcon &&
                btn.isEnabled &&
                btn.underMouse() &&
                QGuiApplication.queryKeyboardModifiers().testFlag(Qt.KeyboardModifier.ShiftModifier)

            val resolvedIcon = if (showForceIcon) TIcons.ForceStop.icon else item.resolveIcon(ctx)
            resolvedIcon?.let { btn.icon = it }
            btn.toolTip = if (showForceIcon) "Force-stop game process" else item.tooltip.orEmpty()
        }
        refreshVisualState()
        if (iconOnly) {
            btn.setProperty("menuIconOnly", true)
        }
        if (useShiftHoverForceIcon) {
            val stateTimer = QTimer(btn).apply {
                interval = 50
                timeout.connect { refreshVisualState() }
                start()
            }
            btn.destroyed.connect {
                stateTimer.stop()
            }
        }
        btn.clicked.connect {
            try {
                val ctx = MenuActionContext(project, window, selection, item.meta)
                item.action?.invoke(ctx)
            } catch (t: Throwable) {
                logger.warn("Menu action '{}' failed", item.id, t)
            }
            refreshVisualState()
        }
        return btn
    }

    private fun makeMenuButton(
        window: QMainWindow,
        item: MenuItem,
        children: Map<String, List<MenuItem>>,
        project: ProjectBase?,
        selection: Any?
    ): QToolButton {
        val baseCtx = MenuActionContext(project, window, selection, item.meta)
        val btn = QToolButton()
        btn.text = item.resolveTitle(baseCtx)
        btn.isEnabled = item.isEnabled(baseCtx)
        btn.toolTip = item.tooltip.orEmpty()
        item.resolveIcon(baseCtx)?.let { btn.icon = it }
        btn.popupMode = QToolButton.ToolButtonPopupMode.InstantPopup
        // Keep labels visible across platform styles (KDE can default to icon-only toolbuttons).
        btn.toolButtonStyle = Qt.ToolButtonStyle.ToolButtonTextOnly
        btn.autoRaise = true

        val menu = QMenu(btn)
        val kids = childItems(item, children, window, project, selection)
        if (kids.isNotEmpty()) {
            for (child in kids) {
                val childCtx = MenuActionContext(project, window, selection, child.meta)
                if (!child.isVisible(childCtx)) continue
                if (child.kind == MenuItemKind.SEPARATOR) {
                    menu.addSeparator()
                    continue
                }
                val submenuKids = childItems(child, children, window, project, selection)
                if (submenuKids.isNotEmpty() && child.kind != MenuItemKind.ACTION) {
                    val submenu = QMenu(child.resolveTitle(childCtx), menu)
                    submenuKids.forEach { grand ->
                        addActionToMenu(submenu, grand, window, project, selection, children)
                    }
                    menu.addMenu(submenu)
                } else {
                    addActionToMenu(menu, child, window, project, selection, children)
                }
            }
        }

        // Allow top-level action for menu button
        if (item.action != null) {
            registerActionHandler(item, window, project, selection)
            val act = QAction(item.resolveTitle(baseCtx), menu)
            item.resolveIcon(baseCtx)?.let { act.icon = it }
            act.isEnabled = item.isEnabled(baseCtx)
            val actionId = shortcutActionIdFor(item)
            val mappedShortcuts = KeymapMngr.sequencesFor(actionId)
            val hasExplicitOverride = KeymapMngr.activeKeymap.localOverrides().containsKey(actionId)
            val hasDeclaredShortcut = actionId in KeymapMngr.declaredActionIds()
            if (mappedShortcuts.isNotEmpty() || hasExplicitOverride || hasDeclaredShortcut) {
                act.setShortcuts(mappedShortcuts)
            } else {
                item.shortcut?.let { act.setShortcut(it) }
            }
            act.triggered.connect {
                try {
                    val ctx = MenuActionContext(project, window, selection, item.meta)
                    item.action!!.invoke(ctx)
                } catch (t: Throwable) {
                    logger.warn("Menu action '{}' failed", item.id, t)
                }
            }
            menu.insertAction(menu.actions().firstOrNull(), act)
            this.addAction(act)
        }

        btn.setMenu(menu)
        return btn
    }

    private fun addActionToMenu(
        menu: QMenu,
        item: MenuItem,
        window: QMainWindow,
        project: ProjectBase?,
        selection: Any?,
        children: Map<String, List<MenuItem>>
    ) {
        val ctx = MenuActionContext(project, window, selection, item.meta)
        if (!item.isVisible(ctx)) return
        if (item.kind == MenuItemKind.SEPARATOR) {
            menu.addSeparator()
            return
        }

        val subKids = childItems(item, children, window, project, selection)
        if (subKids.isNotEmpty() && item.kind != MenuItemKind.ACTION) {
            val submenu = QMenu(item.resolveTitle(ctx), menu)
            subKids.forEach { sub ->
                addActionToMenu(submenu, sub, window, project, selection, children)
            }
            menu.addMenu(submenu)
            return
        }

        val act = QAction(item.resolveTitle(ctx), menu)
        registerActionHandler(item, window, project, selection)
        item.resolveIcon(ctx)?.let { act.icon = it }
        act.isEnabled = item.isEnabled(ctx)
        val actionId = shortcutActionIdFor(item)
        val mappedShortcuts = KeymapMngr.sequencesFor(actionId)
        val hasExplicitOverride = KeymapMngr.activeKeymap.localOverrides().containsKey(actionId)
        val hasDeclaredShortcut = actionId in KeymapMngr.declaredActionIds()
        if (mappedShortcuts.isNotEmpty() || hasExplicitOverride || hasDeclaredShortcut) {
            act.setShortcuts(mappedShortcuts)
        } else {
            item.shortcut?.let { act.setShortcut(it) }
        }
        item.tooltip?.let { act.toolTip = it }
        act.triggered.connect {
            try {
                val actionCtx = MenuActionContext(project, window, selection, item.meta)
                item.action?.invoke(actionCtx)
            } catch (t: Throwable) {
                logger.warn("Menu action '{}' failed", item.id, t)
            }
        }
        menu.addAction(act)
        this.addAction(act)
    }

    private fun shortcutActionIdFor(item: MenuItem): String =
        item.shortcutActionId ?: "menu.${item.id}"

    private fun registerActionHandler(item: MenuItem, window: QMainWindow, project: ProjectBase?, selection: Any?) {
        if (item.action == null) return
        ActionRegistry.registerHandler(
            id = shortcutActionIdFor(item),
            allowKeyboardShortcuts = item.allowKeyboardShortcuts,
            allowMouseShortcuts = item.allowMouseShortcuts,
            focusGroups = item.shortcutFocusGroups
        ) {
            val actionCtx = MenuActionContext(project, window, selection, item.meta)
            item.action!!.invoke(actionCtx)
        }
    }

    private fun childItems(
        parent: MenuItem,
        children: Map<String, List<MenuItem>>,
        window: QMainWindow,
        project: ProjectBase?,
        selection: Any?
    ): List<MenuItem> {
        val staticChildren = children[parent.id].orEmpty()
        val ctx = MenuActionContext(project, window, selection, parent.meta)
        val dynamicChildren = runCatching { parent.childrenProvider?.invoke(ctx).orEmpty() }
            .onFailure { t -> logger.warn("Dynamic menu children provider failed for '{}'", parent.id, t) }
            .getOrDefault(emptyList())
        return (staticChildren + dynamicChildren)
            .sortedWith(compareBy({ it.order }, { it.title }))
    }

    private fun clearLayout() {
        // 1. Manually remove and dispose of all actions associated with this widget
        val currentActions = actions()
        for (action in currentActions) {
            removeAction(action)
            action?.disposeLater()
        }

        // 2. Clear the layout and dispose of all widgets (buttons)
        // Disposing the buttons will also dispose of their parented QMenu objects.
        val count = layout.count()
        for (i in 0 until count) {
            val item = layout.takeAt(0)
            item?.widget()?.let { w ->
                w.hide()
                // Explicitly unparent to stop any active shortcut participation immediately
                w.setParent(null)
                w.disposeLater()
            }
        }

        leftOverlay?.let { w ->
            w.hide()
            w.setParent(null)
            w.disposeLater()
        }

        settingsBtn?.let { btn ->
            btn.hide()
            btn.setParent(null)
            btn.disposeLater()
        }

        leftOverlay = null
        centerSection = null
        projectIconLabel = null
        projectNameLabel = null
        playBtn = null
        stopBtn = null
        buildBtn = null
        settingsBtn = null
    }
}
