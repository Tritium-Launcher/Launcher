package io.github.tritium_launcher.launcher.ui.project.menu

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectType
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.keymap.ActionRegistry
import io.github.tritium_launcher.launcher.keymap.KeymapMngr
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QAction
import io.qt.gui.QGuiApplication
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Command bar that replaces the native menu bar and supports:
 * - Action buttons
 * - Drop-down menus
 * - Separators
 * - Arbitrary embedded widgets via [MenuItem.widgetFactory]
 *
 * Extensions contribute items via the `ui.menu` registry.
 * Visible items are filtered by the active project's [io.github.tritium_launcher.launcher.core.project.ProjectType.menuScope].
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
            }

            selector("#projectMenuBar QPushButton, #projectMenuBar QToolButton") {
                backgroundColor("transparent")
                color(TColors.Text)
                border()
                minHeight(22)
            }

            selector("#projectMenuBar QPushButton:hover, #projectMenuBar QToolButton:hover") {
                backgroundColor(TColors.Surface1)
            }

            selector("#projectMenuBar QPushButton:pressed, #projectMenuBar QToolButton:pressed") {
                backgroundColor(TColors.Surface2)
            }

            selector("#projectMenuBar QPushButton:disabled, #projectMenuBar QToolButton:disabled") {
                color(TColors.Subtext)
                backgroundColor("transparent")
            }

            selector("#projectMenuBar QPushButton[menuIconOnly=\"true\"]") {
                minWidth(26)
                maxWidth(26)
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
        val rightItems = topSorted.filter {
            val ctx = MenuActionContext(project, window, selection, it.meta)
            isRightAligned(it) && it.isVisible(ctx)
        }

        leftItems.forEach { top ->
            addTopItem(window, top, children, project, selection)
        }
        layout.addStretch(1)
        rightItems.forEach { top ->
            addTopItem(window, top, children, project, selection)
        }
        update()
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
        val ctx = MenuActionContext(project, window, selection, top.meta)
        if (!top.isVisible(ctx)) return
        when (top.kind) {
            MenuItemKind.WIDGET -> {
                val widget = top.widgetFactory?.invoke(ctx)
                if (widget != null) {
                    layout.addWidget(widget)
                }
            }

            MenuItemKind.ACTION -> {
                layout.addWidget(makeActionButton(window, top, project, selection))
            }

            MenuItemKind.MENU -> {
                layout.addWidget(makeMenuButton(window, top, children, project, selection))
            }

            MenuItemKind.SEPARATOR -> {
                layout.addWidget(makeSeparator())
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
                    item.action.invoke(ctx)
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
            item.action.invoke(actionCtx)
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
    }
}
