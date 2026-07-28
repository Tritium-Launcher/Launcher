/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.*
import io.github.tritium_launcher.api.editor.EditorArea
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.api.runOnGuiThread
import io.github.tritium_launcher.api.state.UIStateMngr
import io.github.tritium_launcher.launcher.core.project.ProjectDirWatcher
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.IconHueShifter
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * The standard project files view while a Project is open.
 *
 * @see io.github.tritium_launcher.api.docks.DockPanelProvider
 * @see io.github.tritium_launcher.api.docks.DockWidget
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

private const val RootIdRole = UserRole + 1

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

class ProjectFilesDockPanelProvider: DockPanelProvider, DockPanelTitleBarAccessoryProvider {
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
    override val displayName: String = "Project"
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
        val controller = Controller(project, tree)
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

        private fun applyMarkIcon(item: QTreeWidgetItem, path: VPath, primaryIcon: QIcon?, markMngr: ProjectDirectoryMarkMngr?) {
            if (markMngr == null || primaryIcon == null) return
            val mark = markMngr.highestPriorityMark(path)
            if (mark?.icon != null) {
                val markIcon = mark.icon
                if (markIcon != null) item.setIcon(0, markIcon)
            } else if (mark?.hueShiftDegrees != null) {
                item.setIcon(0, QIcon(IconHueShifter.modifyPixels(primaryIcon.pixmap(16, 16), hueShift = mark.hueShiftDegrees)))
            }
        }

        private fun buildNode(
            project: ProjectBase,
            parent: QTreeWidgetItem,
            spec: ProjectFilesNodeSpec,
            presentations: List<ProjectTreeDirectoryPresentation>,
            markMngr: ProjectDirectoryMarkMngr? = null
        ) {
            val path = spec.path
            val item = QTreeWidgetItem(parent)
            val primary = FileTypeDescriptor.primary(path, project)
            val currentName = spec.label ?: path.fileName()
            item.setText(0, applyDisplayName(project, path.parent(), path, primary, currentName, presentations))
            item.setData(0, UserRole, path)
            if (primary?.icon != null) item.setIcon(0, primary.icon ?: TIcons.File.icon)

            if (path.isDir()) {
                applyMarkIcon(item, path, primary?.icon, markMngr)
            }

            if (!runCatching { path.isDir() }.getOrDefault(false)) return

            if (runCatching { path.isDir() }.getOrDefault(false)) {
                QTreeWidgetItem(item).apply { setText(0, "") }
            }
        }

        private fun buildRootNode(
            project: ProjectBase,
            parent: QTreeWidgetItem,
            root: ProjectTreeRoot
        ) {
            val item = QTreeWidgetItem(parent)
            val path = root.rootPath(project)
            item.setText(0, root.displayName(project))
            item.setData(0, UserRole, path)
            item.setData(0, RootIdRole, root.id)
            val baseIcon = root.icon(project)
            val color = root.iconColor(project)
            if (color != null) {
                val tinted = IconHueShifter.tintPixmap(baseIcon.pixmap(16, 16), color)
                item.setIcon(0, QIcon(tinted))
            } else {
                item.setIcon(0, baseIcon)
            }
            if (runCatching { path.isDir() }.getOrDefault(false)) {
                QTreeWidgetItem(item).apply { setText(0, "") }
            }
        }

        private fun expandNode(
            project: ProjectBase,
            item: QTreeWidgetItem,
            viewMode: ProjectFilesViewMode,
            presentations: List<ProjectTreeDirectoryPresentation>,
            markMngr: ProjectDirectoryMarkMngr? = null
        ) {
            val path = item.data(0, UserRole) as? VPath ?: return
            if (!runCatching { path.isDir() }.getOrDefault(false)) return

            // Clear dummy or existing children
            while (item.childCount() > 0) {
                item.removeChild(item.child(0))
            }

            val rootId = item.data(0, RootIdRole) as? String
            val rawChildren = if (rootId != null) {
                val treeRoot = BuiltinRegistries.ProjectTreeRoot.all().firstOrNull { it.id == rootId }
                treeRoot?.childEntries(project, path) ?: emptyList()
            } else {
                viewMode.childEntries(path, project)
            }
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
                buildNode(project, item, childSpec, presentations, markMngr)
            }
        }

        private fun populateTree(
            project: ProjectBase,
            tree: QTreeWidget,
            viewMode: ProjectFilesViewMode?,
            presentations: List<ProjectTreeDirectoryPresentation>,
            markMngr: ProjectDirectoryMarkMngr? = null
        ) {
            tree.clear()
            val root = tree.invisibleRootItem() ?: return
            val mode = viewMode ?: return

            val treeRoots = BuiltinRegistries.ProjectTreeRoot.all()
                .filter { it.isAvailable(project) }
                .sortedBy { it.order }
            if (treeRoots.isNotEmpty() && mode.id == "project") {
                treeRoots.forEach { treeRoot ->
                    buildRootNode(project, root, treeRoot)
                }
            } else {
                val rootEntries = mode.rootEntries(project)
                val specsByPath = rootEntries.associateBy { it.path }
                defaultSortChildren(rootEntries.map { it.path }).forEach { path ->
                    val spec = specsByPath[path] ?: ProjectFilesNodeSpec(path)
                    buildNode(project, root, spec, presentations, markMngr)
                }
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

        private fun captureTreeState(tree: QTreeWidget, project: ProjectBase): TreeState {
            val treeRoots = BuiltinRegistries.ProjectTreeRoot.all().associateBy { it.id }
            val expanded = linkedSetOf<String>()

            fun walk(item: QTreeWidgetItem, rootId: String?) {
                val path = (item.data(0, UserRole) as? VPath)?.toAbsolute() ?: return
                val root = rootId?.let { treeRoots[it] }
                val normalized = root?.normalizePath(project, path) ?: path.toString()
                if(item.isExpanded && normalized.isNotBlank()) {
                    expanded.add(normalized)
                }
                for(i in 0 until item.childCount()) {
                    walk(item.child(i) ?: continue, rootId)
                }
            }

            val invisibleRoot = tree.invisibleRootItem() ?: return TreeState(emptySet(), null)
            for(i in 0 until invisibleRoot.childCount()) {
                val child = invisibleRoot.child(i) ?: continue
                val rootId = child.data(0, RootIdRole) as? String
                walk(child, rootId)
            }

            val selectedPath = pathOf(visibleSelectionItem(tree.currentItem()))
            return TreeState(expandedPaths = expanded, selectedPath = selectedPath)
        }

        private fun restoreTreeState(
            tree: QTreeWidget,
            state: TreeState,
            project: ProjectBase,
            viewMode: ProjectFilesViewMode?,
            presentations: List<ProjectTreeDirectoryPresentation>,
            markMngr: ProjectDirectoryMarkMngr? = null,
            onBeforeExpand: (() -> Unit)? = null,
            onAfterExpand: (() -> Unit)? = null
        ) {
            val invisibleRoot = tree.invisibleRootItem() ?: return
            var selectedItem: QTreeWidgetItem? = null
            val itemsByPath = linkedMapOf<String, QTreeWidgetItem>()
            val treeRoots = BuiltinRegistries.ProjectTreeRoot.all().associateBy { it.id }

            fun resolveExpandedPaths(rootId: String?): Set<String> {
                val treeRoot = rootId?.let { treeRoots[it] } ?: return state.expandedPaths
                return state.expandedPaths.map { treeRoot.resolvePath(project, it) }.toSet()
            }

            fun walk(item: QTreeWidgetItem, resolvedPaths: Set<String>) {
                val path = pathOf(item)
                if (!path.isNullOrBlank()) {
                    itemsByPath[path] = item
                    if (resolvedPaths.contains(path)) {
                        onBeforeExpand?.invoke()
                        if (viewMode != null) expandNode(project, item, viewMode, presentations, markMngr)
                        item.isExpanded = true
                        onAfterExpand?.invoke()
                    }
                    if (selectedItem == null && path == state.selectedPath) {
                        selectedItem = item
                    }
                }
                for (i in 0 until item.childCount()) {
                    val child = item.child(i) ?: continue
                    if (pathOf(child) == null) continue
                    walk(child, resolvedPaths)
                }
            }

            for (i in 0 until invisibleRoot.childCount()) {
                val rootItem = invisibleRoot.child(i) ?: continue
                val rootId = rootItem.data(0, RootIdRole) as? String
                val resolvedPaths = resolveExpandedPaths(rootId)
                walk(rootItem, resolvedPaths)
            }

            if (selectedItem == null && !state.selectedPath.isNullOrBlank()) {
                var cursor = runCatching { VPath.parse(state.selectedPath) }.getOrNull()
                while (cursor != null && selectedItem == null) {
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
        private val tree: QTreeWidget
    ) {
        private val logger = logger()
        private var presentations = emptyList<ProjectTreeDirectoryPresentation>()
        private var viewModes = emptyList<ProjectFilesViewMode>()
        private var contextActions = emptyList<ProjectFilesContextAction>()
        private var activeViewId = "project"
        private val perViewState = linkedMapOf<String, TreeState>()
        private var selectorButton: QToolButton? = null
        private var titleBarStateChanged: (() -> Unit)? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private val watchers = mutableListOf<ProjectDirWatcher>()
        private val markMngr = ProjectDirectoryMarkMngr(project)
        private val perRootTreeState = mutableMapOf<String, TreeState>()
        private var collapsingRoots = false
        private var pendingState: DockState? = null
        private var suppressSync = false
        internal var restoringTreeState = false

        fun start() {
            UIStateMngr.register(markMngr)
            tree.contextMenuPolicy = Qt.ContextMenuPolicy.CustomContextMenu
            tree.customContextMenuRequested.connect { pos ->
                showContextMenu(pos)
            }

            tree.itemExpanded.connect { item ->
                if (item != null && !restoringTreeState) {
                    suppressSync = true
                    restoringTreeState = true
                    val rootId = item.data(0, RootIdRole) as? String
                    if (rootId != null) {
                        saveOtherRootsState(tree, rootId)
                    }
                    if (rootId != null && CoreSettingValues.projectFilesSingleRoot) {
                        collapsingRoots = true
                        collapseOtherRoots(tree, rootId)
                        collapsingRoots = false
                    }
                    expandNode(project, item, currentViewMode() ?: run { suppressSync = false; return@connect }, presentations, markMngr)

                    val path = pathOf(item)
                    if (!path.isNullOrBlank()) {
                        val current = perViewState[activeViewId] ?: TreeState(emptySet(), null)
                        perViewState[activeViewId] = current.copy(
                            expandedPaths = current.expandedPaths + path
                        )
                    }

                    if (rootId != null) {
                        perRootTreeState[rootId]?.let { cachedState ->
                            restoreTreeState(
                                tree,
                                cachedState,
                                project,
                                currentViewMode(),
                                presentations,
                                markMngr,
                                onBeforeExpand = { restoringTreeState = true },
                                onAfterExpand = { restoringTreeState = false }
                            )
                            val current = perViewState[activeViewId] ?: TreeState(emptySet(), null)
                            perViewState[activeViewId] = current.copy(
                                expandedPaths = current.expandedPaths + cachedState.expandedPaths
                            )
                        }
                        perRootTreeState.remove(rootId)
                    }
                    suppressSync = false
                    restoringTreeState = false
                }
            }


            tree.itemCollapsed.connect { item ->
                if (!collapsingRoots) {
                    val collapsed = item ?: return@connect
                    val current = tree.currentItem() ?: return@connect
                    suppressSync = true
                    if (current != collapsed && isDescendantOf(current, collapsed)) {
                        tree.setCurrentItem(collapsed)
                    }
                    suppressSync = false
                    val path = pathOf(collapsed)
                    if (!path.isNullOrBlank()) {
                        val currentState = perViewState[activeViewId] ?: TreeState(emptySet(), null)
                        perViewState[activeViewId] = currentState.copy(
                            expandedPaths = currentState.expandedPaths.filter { p ->
                                !p.startsWith(path)
                            }.toSet()
                        )
                    }
                }
            }


            tree.currentItemChanged.connect { _, _ -> if(!suppressSync) syncStateFromTree() }

            DeferredRegistryBuilder(BuiltinRegistries.FileType) { refresh() }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectTreeDirectoryPresentation) { snapshot ->
                presentations = snapshot.sortedBy { it.order }
                refresh()
            }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectFilesViewMode) { snapshot ->
                viewModes = snapshot.sortedBy { it.order }
                if (pendingState != null) {
                    applyPendingState()
                } else {
                    if(viewModes.none { it.id == activeViewId }) {
                        activeViewId = viewModes.firstOrNull()?.id ?: "project_files"
                    }
                    if(BuiltinRegistries.ProjectTreeRoot.all().any { it.isAvailable(project) } && activeViewId != "project") {
                        activeViewId = "project"
                    }
                    updateSelectorText()
                    rebuildSelectorMenu()
                    refresh()
                }
            }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectFilesAction) { snapshot ->
                contextActions = snapshot.sortedBy { it.order }
            }
            DeferredRegistryBuilder(BuiltinRegistries.ProjectTreeRoot) { snapshot ->
                if (snapshot.any { it.isAvailable(project) } && activeViewId != "project") {
                    activeViewId = "project"
                    updateSelectorText()
                    rebuildSelectorMenu()
                }
                runOnGuiThread {
                    if(pendingState != null) {
                        applyPendingState()
                    } else {
                        refresh()
                    }
                }
            }

            scope.onEvent<TritiumEvent.SettingChanged> { event ->
                val key = "${event.namespace}:${event.nodeKey}"
                if (key == CoreSettingKeys.ProjectFilesConfigSort.toString()) {
                    runOnGuiThread { refresh() }
                }
            }
            scope.onEvent<TritiumEvent.DirectoryMarksChanged> { event ->
                if (event.project.projectDir == project.projectDir) {
                    runOnGuiThread { refresh() }
                }
            }

            val watchedRoots = mutableSetOf<String>()
            val treeRoots = BuiltinRegistries.ProjectTreeRoot.all()
            for (treeRoot in treeRoots) {
                val rootPath = treeRoot.rootPath(project).toAbsoluteString()
                if (watchedRoots.add(rootPath)) {
                    val watcher = ProjectDirWatcher(VPath.get(rootPath))
                    watchers.add(watcher)
                    watcher.start({ event -> onFsEvent(event) }, filter = { e ->
                        markMngr.filterWatchEvent(e.path, e)
                    })
                }
            }
        }

        private fun saveOtherRootsState(tree: QTreeWidget, activeRootId: String) {
            val invisibleRoot = tree.invisibleRootItem() ?: return
            for (i in 0 until invisibleRoot.childCount()) {
                val child = invisibleRoot.child(i) ?: continue
                val rid = child.data(0, RootIdRole) as? String ?: continue
                if (rid == activeRootId) continue
                perRootTreeState[rid] = captureRootState(child)
            }
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

            if (isDir) {
                val allMarks = BuiltinRegistries.ProjectDirectoryMark.all().sortedBy { it.order }
                if (allMarks.isNotEmpty() && lastSection != null) menu.addSeparator()
                for (mark in allMarks) {
                    val isMarked = markMngr.hasMark(path, mark.id)
                    val action = if (isMarked) {
                        menu.addAction("Unmark as ${mark.displayName}")
                    } else {
                        menu.addAction("Mark as ${mark.displayName}")
                    }
                    action?.triggered?.connect {
                        markMngr.toggleMark(path, mark.id)
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
            UIStateMngr.unregister(markMngr)
            scope.cancel()
            watchers.forEach { it.stop() }
            watchers.clear()
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
            val currentPath = pathOf(tree.currentItem())
            if(currentPath != null) {
                val current = perViewState[activeViewId] ?: TreeState(emptySet(), null)
                if(current.selectedPath != currentPath) perViewState[activeViewId] = current.copy(selectedPath = currentPath)
            }
            return DockState(
                activeViewId = activeViewId,
                viewStates = perViewState.map { (viewId, treeState) -> ViewState(viewId, treeState) }
            )
        }

        fun restoreState(state: DockState) {
            pendingState = state
            perViewState.clear()
            state.viewStates.forEach { perViewState[it.viewId] = it.treeState }
            activeViewId = state.activeViewId.takeIf { it.isNotBlank() } ?: activeViewId
            if (viewModes.isNotEmpty()) {
                applyPendingState()
            }
        }

        private fun applyPendingState() {
            val state = pendingState ?: return
            pendingState = null

            perViewState.clear()
            state.viewStates.forEach { perViewState[it.viewId] = it.treeState }

            activeViewId = state.activeViewId.takeIf { it.isNotBlank() } ?: activeViewId
            if(viewModes.none { it.id == activeViewId }) {
                activeViewId = viewModes.firstOrNull()?.id ?: "project_files"
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
            perViewState[activeViewId] = captureTreeState(tree, project)
        }

        private fun onFsEvent(event: VWatchEvent) {
            val absPath = runCatching { event.path.toAbsoluteString() }.getOrNull()
            if (absPath != null && absPath.contains("registryObjs/latest.json")) {
                refresh()
                return
            }
            when(event.kind) {
                VWatchEvent.Kind.Create -> handleCreate(event.path)
                VWatchEvent.Kind.Delete -> handleDelete(event.path)
                VWatchEvent.Kind.Overflow -> refresh()
                else -> {}
            }
        }

        private fun handleCreate(path: VPath) {
            val parentPath = runCatching { path.parent() }.getOrNull() ?: return
            val parentItem = findItemForPath(parentPath) ?: run {
                refresh()
                return
            }
            buildNode(project, parentItem, ProjectFilesNodeSpec(path), presentations, markMngr)
            syncStateFromTree()
        }

        private fun handleDelete(path: VPath) {
            val item = findItemForPath(path) ?: return
            val parent = item.parent() ?: tree.invisibleRootItem() ?: return
            parent.removeChild(item)
            syncStateFromTree()
        }

        private fun findItemForPath(path: VPath): QTreeWidgetItem? {
            val target = path.toAbsoluteString()
            fun walk(item: QTreeWidgetItem): QTreeWidgetItem? {
                if(pathOf(item) == target) return item
                for(i in 0 until item.childCount()) {
                    walk(item.child(i) ?: continue)?.let { return it }
                }
                return null
            }
            val root = tree.invisibleRootItem() ?: return null
            for(i in 0 until root.childCount()) {
                walk(root.child(i) ?: continue)?.let { return it }
            }
            return null
        }

        fun refresh() {
            val stateBeforeRefresh = perViewState[activeViewId] ?: TreeState(emptySet(), null)
            tree.blockSignals(true)
            populateTree(project, tree, currentViewMode(), presentations, markMngr)
            restoreTreeState(tree, stateBeforeRefresh, project, currentViewMode(), presentations, markMngr)
            tree.blockSignals(false)
        }

        private fun captureRootState(rootItem: QTreeWidgetItem): TreeState {
            val expanded = linkedSetOf<String>()
            fun walk(item: QTreeWidgetItem) {
                val path = (item.data(0, UserRole) as? VPath)?.toAbsolute()?.toString()
                if (item.isExpanded && !path.isNullOrBlank()) expanded.add(path)
                for (i in 0 until item.childCount()) {
                    walk(item.child(i) ?: continue)
                }
            }
            for (i in 0 until rootItem.childCount()) {
                walk(rootItem.child(i) ?: continue)
            }
            val selectedPath = pathOf(visibleSelectionItem(tree.currentItem()))
            return TreeState(expandedPaths = expanded, selectedPath = selectedPath)
        }

        private fun collapseOtherRoots(tree: QTreeWidget, activeRootId: String) {
            saveOtherRootsState(tree,activeRootId)
            val root = tree.invisibleRootItem() ?: return
            for (i in 0 until root.childCount()) {
                val child = root.child(i) ?: continue
                val rid = child.data(0, RootIdRole) as? String ?: continue
                if (rid == activeRootId || !child.isExpanded) continue
                child.isExpanded = false
            }
        }
    }
}
