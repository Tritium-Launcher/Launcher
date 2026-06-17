package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectDirWatcher
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.Nullable
import io.qt.core.*
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.gui.QCursor
import io.qt.gui.QDropEvent
import io.qt.gui.QIcon
import io.qt.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * The standard project files view while a Project is open.
 *
 * @see SidePanelProvider
 * @see DockWidget
 */

private var _clipboardSource: List<String> = emptyList()
private var _clipboardIsCut = false

internal fun clipboardSource(): List<String> = _clipboardSource

internal fun setClipboard(paths: List<String>, isCut: Boolean) {
    _clipboardSource = paths
    _clipboardIsCut = isCut
}

internal fun clipboardIsCut(): Boolean = _clipboardIsCut

internal fun clearClipboard() {
    _clipboardSource = emptyList()
    _clipboardIsCut = false
}

internal fun showInlineInput(parent: QWidget, defaultText: String, globalPos: QPoint): String? {
    val dialog = QDialog(parent, Qt.WindowFlags(Qt.WindowType.Popup))
    val layout = vBoxLayout(dialog) {
        contentsMargins = 4.m
    }
    val edit = QLineEdit(dialog)
    edit.minimumWidth = 200
    edit.text = defaultText
    edit.selectAll()
    layout.addWidget(edit)
    dialog.adjustSize()
    dialog.move(globalPos)

    var result: String? = null
    edit.returnPressed.connect {
        val text = edit.text().trim()
        if (text.isNotEmpty()) result = text
        dialog.accept()
    }

    edit.setFocus()
    if (dialog.exec() == 1) return result
    return null
}

internal fun selectedPaths(tree: QTreeWidget): List<VPath> {
    val items = tree.selectedItems()
    if (items.isNotEmpty()) {
        return items.mapNotNull { it?.data(0, UserRole) as? VPath }
    }
    val current = tree.currentItem()
    val path = current?.data(0, UserRole) as? VPath
    return if (path != null) listOf(path) else emptyList()
}

internal fun pasteTo(targetDir: VPath, onRefresh: () -> Unit = {}) {
    if (_clipboardSource.isEmpty()) return
    var anySuccess = false
    for (src in _clipboardSource) {
        val srcPath = runCatching { VPath.parse(src) }.getOrNull() ?: continue
        val dest = targetDir.resolve(srcPath.fileName())
        if (_clipboardIsCut) {
            runCatching {
                Files.move(srcPath.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
                anySuccess = true
            }
        } else {
            runCatching {
                Files.copy(srcPath.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
                anySuccess = true
            }
        }
    }
    if (_clipboardIsCut) {
        _clipboardSource = emptyList()
        _clipboardIsCut = false
    }
    if (anySuccess) onRefresh()
}

internal fun promptNewFolder(targetDir: VPath, globalPos: QPoint, onRefresh: () -> Unit = {}) {
    val name = showInlineInput(QApplication.activeWindow() ?: return, "", globalPos)
    if (name != null) {
        val newDir = targetDir.resolve(name)
        if (runCatching { newDir.mkdirs() }.getOrDefault(false)) {
            onRefresh()
        }
    }
}

internal fun promptRename(path: VPath, tree: QTreeWidget, onRefresh: () -> Unit = {}) {
    val oldName = path.fileName()
    val globalPos = QCursor.pos()
    val newName = showInlineInput(tree, oldName, globalPos)
    if (newName == null || newName == oldName) return
    val parent = runCatching { path.parent() }.getOrNull() ?: return
    val dest = parent.resolve(newName)
    runCatching {
        Files.move(path.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
        onRefresh()
    }
}

internal fun promptDelete(path: VPath, tree: QWidget, onRefresh: () -> Unit = {}) {
    val name = path.fileName()
    val isDir = runCatching { path.isDir() }.getOrDefault(false)
    val kind = if (isDir) "folder" else "file"
    val result = QMessageBox.question(
        tree, "Delete $kind",
        "Are you sure you want to delete \"$name\"?",
        QMessageBox.StandardButtons(
            QMessageBox.StandardButton.Yes,
            QMessageBox.StandardButton.No
        ),
        QMessageBox.StandardButton.Yes
    )
    if (result != QMessageBox.StandardButton.Yes) return
    runCatching {
        val jPath = path.toJPath()
        if (isDir) {
            Files.walk(jPath).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } else {
            Files.deleteIfExists(jPath)
        }
        onRefresh()
    }
}

class ProjectFilesSidePanelProvider: SidePanelProvider, SidePanelTitleBarAccessoryProvider {
    data class TreeState(
        val expandedPaths: Set<String>,
        val selectedPath: String?
    )

    data class ViewState(
        val viewId: String,
        val treeState: TreeState
    )

    data class DockState(
        val activeViewId: String,
        val viewStates: List<ViewState>
    )

    override val id: String = "project_files"
    override val displayName: String = "Project Files"
    override var icon: QIcon? = TIcons.Folder.icon
    override val order: Int = 0

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val defaultVisible: Boolean = true

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        val tree = FileTreeWidget({ project }, { refreshDock(dock) }).apply {
            headerHidden = true
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
            alternatingRowColors = false
            isAnimated = false
            uniformRowHeights = true
            iconSize = QSize(16, 16)
            frameShape = QFrame.Shape.NoFrame
            styleSheet = "QTreeWidget { border: none; } QTreeView::item { margin: 0px; padding: 0px; }"
            verticalScrollMode = QAbstractItemView.ScrollMode.ScrollPerPixel
            dragEnabled = true
            acceptDrops = true
            setDropIndicatorShown(true)
        }
        AnimatedScrollController.attach(tree)
        dock.setWidget(tree)
        val controller = Controller(project, dock, tree)
        controllers[dock] = controller
        controller.start()
        dock.destroyed.connect { controllers.remove(dock)?.dispose() }

        return dock
    }

    override fun createTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget? {
        val controller = controllers[dock] ?: return null
        return controller.createViewSelector(onStateChanged)
    }

    override fun onDockCreated(project: ProjectBase, editorArea: EditorArea, dock: DockWidget, onStateChanged: () -> Unit) {
        val tree = dock.widget() as? QTreeWidget ?: dock.findChild(QTreeWidget::class.java)
        tree?.itemDoubleClicked?.connect { item, _ ->
            val path = item?.data(0, UserRole) as? VPath
            if (path != null && !path.isDir()) {
                editorArea.openFile(path)
            }
        }
        tree?.itemExpanded?.connect { onStateChanged() }
        tree?.itemCollapsed?.connect { onStateChanged() }
        tree?.currentItemChanged?.connect { _, _ -> onStateChanged() }

        val pendingState = pendingInitialDockState
        if (pendingState != null) {
            pendingInitialDockState = null
            restoreDockTreeState(dock, pendingState)
        }
    }

    companion object {
        private val controllers = WeakHashMap<DockWidget, Controller>()
        private var pendingInitialDockState: DockState? = null

        fun setPendingInitialDockState(state: DockState) {
            pendingInitialDockState = state
        }

        fun captureDockTreeState(dock: QDockWidget?): DockState {
            val typedDock = dock as? DockWidget ?: return DockState("project_files", emptyList())
            return controllers[typedDock]?.captureState() ?: DockState("project_files", emptyList())
        }

        fun restoreDockTreeState(dock: QDockWidget?, state: DockState) {
            val typedDock = dock as? DockWidget ?: return
            controllers[typedDock]?.restoreState(state)
        }

        internal fun refreshDock(dock: DockWidget) {
            controllers[dock]?.refresh()
        }

        private fun defaultSortChildren(children: List<VPath>): List<VPath> {
            val (dirs, files) = children.partition { runCatching { it.isDir() }.getOrDefault(false) }
            return dirs.sortedBy { it.fileName().lowercase() } + files.sortedBy { it.fileName().lowercase() }
        }

        private fun buildNode(
            project: ProjectBase,
            parent: QTreeWidgetItem,
            spec: ProjectFilesNodeSpec,
            viewMode: ProjectFilesViewMode,
            presentations: List<ProjectTreeDirectoryPresentation>
        ) {
            val path = spec.path
            val item = QTreeWidgetItem(parent)
            val primary = FileTypeDescriptor.primary(path, project)
            val currentName = spec.label ?: path.fileName()
            item.setText(0, applyDisplayName(project, path.parent(), path, primary, currentName, presentations))
            item.setData(0, UserRole, path)
            if (primary?.icon != null) item.setIcon(0, primary.icon ?: TIcons.File.icon)

            if (!runCatching { path.isDir() }.getOrDefault(false)) return

            // Add dummy child if directory has content, to show the expander
            if (runCatching { path.list().isNotEmpty() }.getOrDefault(false)) {
                QTreeWidgetItem(item).apply { setText(0, "Loading...") }
            }
        }

        private fun expandNode(
            project: ProjectBase,
            item: QTreeWidgetItem,
            viewMode: ProjectFilesViewMode,
            presentations: List<ProjectTreeDirectoryPresentation>
        ) {
            val path = item.data(0, UserRole) as? VPath ?: return
            if (!runCatching { path.isDir() }.getOrDefault(false)) return

            // Clear dummy or existing children
            while (item.childCount() > 0) {
                item.removeChild(item.child(0))
            }

            val rawChildren = viewMode.childEntries(path, project)
            if (rawChildren.isEmpty()) return

            val activePresentations = presentations
                .filter { it.matches(path, project) }
                .sortedBy { it.order }

            var sortedPaths = defaultSortChildren(rawChildren.map { it.path })
            activePresentations.forEach { presentation ->
                sortedPaths = presentation.sortChildren(path, sortedPaths, project)
            }
            val childSpecsByPath = rawChildren.associateBy { it.path }
            sortedPaths.forEach { child ->
                val childSpec = childSpecsByPath[child] ?: ProjectFilesNodeSpec(child)
                buildNode(project, item, childSpec, viewMode, presentations)
            }
        }

        private fun populateTree(
            project: ProjectBase,
            tree: QTreeWidget,
            viewMode: ProjectFilesViewMode?,
            presentations: List<ProjectTreeDirectoryPresentation>
        ) {
            tree.clear()
            val root = tree.invisibleRootItem() ?: return
            val mode = viewMode ?: return
            val rootEntries = mode.rootEntries(project)
            val specsByPath = rootEntries.associateBy { it.path }
            defaultSortChildren(rootEntries.map { it.path }).forEach { path ->
                val spec = specsByPath[path] ?: ProjectFilesNodeSpec(path)
                buildNode(project, root, spec, mode, presentations)
            }
        }

        private fun applyDisplayName(
            project: ProjectBase,
            directory: VPath,
            child: VPath,
            primary: FileTypeDescriptor?,
            initialDisplayName: String,
            presentations: List<ProjectTreeDirectoryPresentation>
        ): String {
            val activePresentations = presentations
                .filter { it.matches(directory, project) }
                .sortedBy { it.order }
            var displayName = initialDisplayName
            activePresentations.forEach { presentation ->
                displayName = presentation.displayName(directory, child, project, primary, displayName)
            }
            return displayName
        }

        private fun captureTreeState(tree: QTreeWidget): TreeState {
            val expanded = linkedSetOf<String>()
            val root = tree.invisibleRootItem() ?: return TreeState(emptySet(), null)

            fun walk(item: QTreeWidgetItem) {
                val path = (item.data(0, UserRole) as? VPath)?.toAbsolute()?.toString()
                if(item.isExpanded && !path.isNullOrBlank()) {
                    expanded.add(path)
                }
                for(i in 0 until item.childCount()) {
                    val child = item.child(i) ?: continue
                    walk(child)
                }
            }

            for(i in 0 until root.childCount()) {
                val child = root.child(i) ?: continue
                walk(child)
            }

            val selectedPath = pathOf(visibleSelectionItem(tree.currentItem()))
            return TreeState(expandedPaths = expanded, selectedPath = selectedPath)
        }

        private fun restoreTreeState(tree: QTreeWidget, state: TreeState, project: ProjectBase, viewMode: ProjectFilesViewMode?, presentations: List<ProjectTreeDirectoryPresentation>) {
            val root = tree.invisibleRootItem() ?: return
            var selectedItem: QTreeWidgetItem? = null
            val itemsByPath = linkedMapOf<String, QTreeWidgetItem>()

            fun walk(item: QTreeWidgetItem) {
                val path = pathOf(item)
                if(!path.isNullOrBlank() && state.expandedPaths.contains(path)) {
                    if (viewMode != null) {
                        expandNode(project, item, viewMode, presentations)
                    }
                    item.isExpanded = true
                }
                if(!path.isNullOrBlank()) {
                    itemsByPath[path] = item
                }
                if(selectedItem == null && !path.isNullOrBlank() && path == state.selectedPath) {
                    selectedItem = item
                }
                for(i in 0 until item.childCount()) {
                    val child = item.child(i) ?: continue
                    walk(child)
                }
            }

            for(i in 0 until root.childCount()) {
                val child = root.child(i) ?: continue
                walk(child)
            }

            if(selectedItem == null && !state.selectedPath.isNullOrBlank()) {
                var cursor = runCatching { VPath.parse(state.selectedPath) }.getOrNull()
                while(cursor != null && selectedItem == null) {
                    selectedItem = itemsByPath[cursor.toAbsolute().toString()]
                    cursor = runCatching { cursor.parent() }.getOrNull()
                }
            }

            selectedItem?.let { item ->
                tree.setCurrentItem(item)
                tree.scrollToItem(item)
            }
        }

        private fun visibleSelectionItem(item: QTreeWidgetItem?): QTreeWidgetItem? {
            var current = item ?: return null
            while(true) {
                val parent = current.parent() ?: return current
                if(!parent.isExpanded) {
                    return parent
                }
                current = parent
            }
        }

        private fun pathOf(item: QTreeWidgetItem?): String? =
            (item?.data(0, UserRole) as? VPath)?.toAbsolute()?.toString()

        private fun isDescendantOf(item: QTreeWidgetItem, ancestor: QTreeWidgetItem): Boolean {
            var current = item.parent()
            while(current != null) {
                if(current == ancestor) return true
                current = current.parent()
            }
            return false
        }
    }

    private class FileTreeWidget(
        private val projectProvider: () -> ProjectBase,
        private val onDropCompleted: () -> Unit
    ) : QTreeWidget() {
        override fun mimeData(items: MutableCollection<out QTreeWidgetItem?>): QMimeData {
            val mimeData = QMimeData()
            val urls = mutableListOf<QUrl>()
            for (item in items) {
                val path = item?.data(0, UserRole) as? VPath ?: continue
                val file = path.toJFile()
                if (file.exists()) {
                    urls.add(QUrl.fromLocalFile(file.absolutePath))
                }
            }
            if (urls.isNotEmpty()) {
                mimeData.setUrls(urls)
            }
            return mimeData
        }

        override fun dropEvent(event: @Nullable QDropEvent?) {
            val ev = event ?: return
            val mimeData = ev.mimeData() ?: return

            val pos = ev.position().toPoint()
            val targetItem = itemAt(pos)
            val project = projectProvider()
            val targetDir = resolveDropTarget(targetItem, project)

            if (ev.source() === this) {
                val items = selectedItems()
                var moved = false
                for (item in items) {
                    val path = item?.data(0, UserRole) as? VPath ?: continue
                    if (path == targetDir) continue
                    if (runCatching { path.isDir() }.getOrDefault(false) && targetDir.startsWith(path)) continue
                    val dest = targetDir.resolve(path.fileName())
                    runCatching {
                        Files.move(path.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
                        moved = true
                    }
                }
                if (moved) {
                    ev.acceptProposedAction()
                    onDropCompleted()
                }
            } else if (mimeData.hasUrls()) {
                var copied = false
                for (url in mimeData.urls()) {
                    val filePath = url.toLocalFile() ?: continue
                    val source = VPath.parse(filePath)
                    val dest = targetDir.resolve(source.fileName())
                    runCatching {
                        Files.copy(source.toJPath(), dest.toJPath(), StandardCopyOption.REPLACE_EXISTING)
                        copied = true
                    }
                }
                if (copied) {
                    ev.acceptProposedAction()
                    onDropCompleted()
                }
            }
        }

        override fun dropMimeData(parent: QTreeWidgetItem?, index: Int, data: QMimeData?, action: Qt.DropAction): Boolean = false

        private fun resolveDropTarget(item: QTreeWidgetItem?, project: ProjectBase): VPath {
            if (item != null) {
                val path = item.data(0, UserRole) as? VPath
                if (path != null && runCatching { path.isDir() }.getOrDefault(false)) {
                    return path
                }
                val parent = path?.let { runCatching { it.parent() }.getOrNull() }
                if (parent != null) return parent
            }
            return project.projectDir
        }
    }

    private class Controller(
        private val project: ProjectBase,
        private val dock: DockWidget,
        private val tree: QTreeWidget
    ) {
        private val logger = logger()
        private var presentations = emptyList<ProjectTreeDirectoryPresentation>()
        private var viewModes = emptyList<ProjectFilesViewMode>()
        private var contextActions = emptyList<ProjectFilesContextAction>()
        private var activeViewId = "project_files"
        private val perViewState = linkedMapOf<String, TreeState>()
        private var selectorButton: QToolButton? = null
        private var titleBarStateChanged: (() -> Unit)? = null
        private val scope = CoroutineScope(Dispatchers.Main)

        private val watcher = ProjectDirWatcher(project.projectDir)

        fun start() {
            tree.contextMenuPolicy = Qt.ContextMenuPolicy.CustomContextMenu
            tree.customContextMenuRequested.connect { pos ->
                showContextMenu(pos)
            }

            tree.itemExpanded.connect { item ->
                if (item != null) {
                    expandNode(project, item, currentViewMode() ?: return@connect, presentations)
                }
                syncStateFromTree()
            }
            tree.itemCollapsed.connect { item ->
                val collapsed = item ?: return@connect
                val current = tree.currentItem() ?: return@connect
                if (current != collapsed && isDescendantOf(current, collapsed)) {
                    tree.setCurrentItem(collapsed)
                }
                syncStateFromTree()
            }
            tree.currentItemChanged.connect { _, _ -> syncStateFromTree() }

            DeferredRegistryBuilder(BuiltinRegistries.FileType) { refresh() }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectTreeDirectoryPresentation) { snapshot ->
                presentations = snapshot.sortedBy { it.order }
                refresh()
            }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectFilesViewMode) { snapshot ->
                viewModes = snapshot.sortedBy { it.order }
                if (viewModes.none { it.id == activeViewId }) {
                    activeViewId = viewModes.firstOrNull()?.id ?: "project_files"
                }
                updateSelectorText()
                rebuildSelectorMenu()
                refresh()
            }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectFilesAction) { snapshot ->
                contextActions = snapshot.sortedBy { it.order }
            }

            scope.onEvent<TritiumEvent.SettingChanged> { event ->
                val key = "${event.namespace}:${event.nodeKey}"
                if (key == CoreSettingKeys.ProjectFilesConfigSort.toString()) {
                    runOnGuiThread { refresh() }
                }
            }
            watcher.start(::refresh)
        }

        private fun showContextMenu(pos: QPoint) {
            val item = tree.itemAt(pos) ?: return
            val path = item.data(0, UserRole) as? VPath ?: return
            val isDir = runCatching { path.isDir() }.getOrDefault(false)
            val fileType = FileTypeDescriptor.primary(path, project)
            val targetDir: VPath = if (isDir) {
                path
            } else {
                runCatching { path.parent() }.getOrNull() ?: path
            }

            val globalPos = (tree.viewport() ?: tree).mapToGlobal(pos)
            val menu = QMenu(tree)

            val newMenu = menu.addMenu("New") ?: return

            val creatableTypes = BuiltinRegistries.FileType.all()
                .filter { it.canCreateIn(targetDir, project) }
                .sortedBy { it.order }

            val kubejsType = creatableTypes.firstOrNull { it.id == "kubescript" }
            val plainFileType = creatableTypes.firstOrNull { it.id == "file" }
            val primaryType = kubejsType ?: plainFileType

            if (primaryType != null) {
                val primaryAction = newMenu.addAction(primaryType.icon ?: QIcon(), primaryType.displayName)
                primaryAction?.triggered?.connect {
                    val name = showInlineInput(tree, "", globalPos)
                    if (name != null) {
                        primaryType.createDefaultFile(targetDir, name, project)?.let { refresh() }
                    }
                }
            }

            val folderAction = newMenu.addAction(TIcons.Folder.icon, "Folder")
            folderAction?.triggered?.connect {
                promptNewFolder(targetDir, globalPos, ::refresh)
            }

            newMenu.addSeparator()

            val remaining = if (primaryType != null) {
                creatableTypes.filter { it != primaryType }
            } else {
                creatableTypes
            }
            val sortedRemaining = if (plainFileType != null && primaryType != plainFileType) {
                listOf(plainFileType) + remaining.filter { it != plainFileType }
            } else {
                remaining
            }
            sortedRemaining.forEach { type -> addNewFileTypeAction(type, targetDir, newMenu, globalPos) }

            var lastSection: ProjectFilesContextAction.Section? = null
            val matching = contextActions
                .filter { it.section != ProjectFilesContextAction.Section.NEW && it.matches(path, isDir, fileType, project) }
                .sortedBy { it.section }
            for (action in matching) {
                if (lastSection != null && action.section != lastSection) {
                    menu.addSeparator()
                }
                lastSection = action.section
                val qAction = menu.addAction(action.icon ?: QIcon(), action.displayName)
                qAction?.triggered?.connect {
                    try {
                        action.execute(path, project, tree)
                        if (action.needsRefresh) refresh()
                    } catch (t: Throwable) {
                        logger.warn("Failed to execute context action '{}'", action.id, t)
                    }
                }
            }

            if (menu.isEmpty) return
            menu.exec((tree.viewport() ?: tree).mapToGlobal(pos))
        }

        private fun addNewFileTypeAction(type: FileTypeDescriptor, targetDir: VPath, parentMenu: QMenu, globalPos: QPoint) {
            val action = parentMenu.addAction(type.icon ?: QIcon(), type.displayName)
            action?.triggered?.connect {
                try {
                    val name = showInlineInput(tree, "", globalPos)
                    if (name != null) {
                        type.createDefaultFile(targetDir, name, project)?.let { refresh() }
                    }
                } catch (t: Throwable) {
                    logger.warn("Failed to create new '{}' file", type.id, t)
                }
            }
        }

        fun dispose() {
            scope.cancel()
            watcher.stop()
        }

        fun createViewSelector(onStateChanged: () -> Unit): QWidget {
            titleBarStateChanged = onStateChanged
            val button = QToolButton().apply {
                autoRaise = true
                popupMode = QToolButton.ToolButtonPopupMode.InstantPopup
            }
            selectorButton = button
            updateSelectorText()
            rebuildSelectorMenu()
            return button
        }

        fun captureState(): DockState {
            syncStateFromTree()
            return DockState(
                activeViewId = activeViewId,
                viewStates = perViewState.map { (viewId, treeState) -> ViewState(viewId, treeState) }
            )
        }

        fun restoreState(state: DockState) {
            perViewState.clear()
            state.viewStates.forEach { perViewState[it.viewId] = it.treeState }
            activeViewId = state.activeViewId.takeIf { it.isNotBlank() } ?: activeViewId
            if (viewModes.isNotEmpty() && viewModes.none { it.id == activeViewId }) {
                activeViewId = viewModes.first().id
            }
            updateSelectorText()
            rebuildSelectorMenu()
            refresh()
        }

        private fun rebuildSelectorMenu() {
            val button = selectorButton ?: return
            val menu = QMenu(button)
            viewModes.forEach { mode ->
                val action = menu.addAction(mode.displayName)
                action?.isCheckable = true
                action?.isChecked = mode.id == activeViewId
                action?.triggered?.connect {
                    if (activeViewId == mode.id) return@connect
                    syncStateFromTree()
                    activeViewId = mode.id
                    updateSelectorText()
                    refresh()
                    rebuildSelectorMenu()
                    titleBarStateChanged?.invoke()
                }
            }
            button.setMenu(menu)
        }

        private fun updateSelectorText() {
            selectorButton?.text = currentViewMode()?.displayName ?: "View"
        }

        private fun currentViewMode(): ProjectFilesViewMode? =
            viewModes.firstOrNull { it.id == activeViewId } ?: viewModes.firstOrNull()

        private fun syncStateFromTree() {
            perViewState[activeViewId] = captureTreeState(tree)
        }

        internal fun refresh() {
            val stateBeforeRefresh = perViewState[activeViewId] ?: TreeState(emptySet(), null)
            populateTree(project, tree, currentViewMode(), presentations)
            restoreTreeState(tree, stateBeforeRefresh, project, currentViewMode(), presentations)
            syncStateFromTree()
        }
    }
}
