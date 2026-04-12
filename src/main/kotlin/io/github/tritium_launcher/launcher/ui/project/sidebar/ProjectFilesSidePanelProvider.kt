package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.ProjectDirWatcher
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.DeferredRegistryBuilder
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.core.QSize
import io.qt.core.Qt.ItemDataRole.UserRole
import io.qt.gui.QIcon
import io.qt.widgets.*

/**
 * The standard project files view while a Project is open.
 *
 * @see SidePanelProvider
 * @see DockWidget
 */
class ProjectFilesSidePanelProvider: SidePanelProvider {
    data class TreeState(
        val expandedPaths: Set<String>,
        val selectedPath: String?
    )

    override val id: String = "project_files"
    override val displayName: String = "Project Files"
    override val icon: QIcon = TIcons.Folder.icon
    override val order: Int = 0

    override val closeable: Boolean = false
    override val floatable: Boolean = false

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        val tree = QTreeWidget().apply {
            headerHidden = true
            selectionMode = QAbstractItemView.SelectionMode.SingleSelection
            alternatingRowColors = false
            isAnimated = false
            uniformRowHeights = true
            iconSize = QSize(16, 16)
            frameShape = QFrame.Shape.NoFrame
            styleSheet = "QTreeWidget { border: none; } QTreeView::item { margin: 0px; padding: 0px; }"
        }
        dock.setWidget(tree)

        fun refresh() {
            val stateBeforeRefresh = captureTreeState(tree)
            populateTree(project, tree)
            restoreTreeState(tree, stateBeforeRefresh)
        }

        DeferredRegistryBuilder(BuiltinRegistries.FileType) { _ ->
            refresh()
        }

        val watcher = ProjectDirWatcher(project.projectDir)
        watcher.start({
            refresh()
        })

        dock.destroyed.connect { watcher.stop() }

        return dock
    }

    private fun populateTree(project: ProjectBase, tree: QTreeWidget) {
        tree.clear()
        val root = tree.invisibleRootItem() ?: return
        buildNode(project.projectDir, root, project)
    }

    private fun buildNode(path: VPath, parent: QTreeWidgetItem, project: ProjectBase) {
        val files = path.list().takeIf { it.isNotEmpty() } ?: return
        val sorted = files.sortedWith(compareBy({ !it.isDir() }, { it.fileName() }))
        val ftr = BuiltinRegistries.FileType
        for(c in sorted) {
            val item = QTreeWidgetItem(parent)
            item.setText(0, c.fileName())
            item.setData(0, UserRole, c)
            val matches = ftr.all().filter { desc -> desc.matches(c, project) }.sortedBy { it.order }
            val primary = matches.firstOrNull()
            if(primary != null && primary.icon != null) item.setIcon(0, primary.icon ?: TIcons.File.icon)
            if(c.isDir()) buildNode(c, item, project)
        }
    }

    companion object {
        fun captureDockTreeState(dock: QDockWidget?): TreeState {
            val tree = dock?.widget() as? QTreeWidget ?: return TreeState(emptySet(), null)
            return captureTreeState(tree)
        }

        fun restoreDockTreeState(dock: QDockWidget?, state: TreeState) {
            val tree = dock?.widget() as? QTreeWidget ?: return
            restoreTreeState(tree, state)
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

            val selectedPath = (tree.currentItem()?.data(0, UserRole) as? VPath)?.toAbsolute()?.toString()
            return TreeState(expandedPaths = expanded, selectedPath = selectedPath)
        }

        private fun restoreTreeState(tree: QTreeWidget, state: TreeState) {
            val root = tree.invisibleRootItem() ?: return
            var selectedItem: QTreeWidgetItem? = null

            fun walk(item: QTreeWidgetItem) {
                val path = (item.data(0, UserRole) as? VPath)?.toAbsolute()?.toString()
                if(!path.isNullOrBlank() && state.expandedPaths.contains(path)) {
                    item.isExpanded = true
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

            selectedItem?.let { item ->
                tree.setCurrentItem(item)
                tree.scrollToItem(item)
            }
        }
    }
}
