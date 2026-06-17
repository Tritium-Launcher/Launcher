package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.io.VPath
import io.qt.core.Qt
import io.qt.widgets.QTreeWidget
import io.qt.widgets.QTreeWidgetItem

/**
 * A single node in the file tree displayed in the import dialog.
 *
 * @param path Absolute path to the file or directory.
 * @param isDirectory Whether this entry is a directory.
 * @param parent Absolute path of the parent directory, or `null` for the root entry.
 */
data class FileTreeEntry(val path: VPath, val isDirectory: Boolean, val parent: VPath?)

/**
 * Recursively collects all files and directories under [dir] into a flat list of [FileTreeEntry].
 *
 * Entries are sorted with directories first, then alphabetically by filename.
 *
 * @param dir The root directory to scan.
 * @return A flat list of [FileTreeEntry] representing all descendants.
 */
fun collectFileTreeEntries(dir: VPath): List<FileTreeEntry> {
    val result = mutableListOf<FileTreeEntry>()
    fun walk(current: VPath, parent: VPath?) {
        if (!current.isDir()) return
        val children = current.list()
            .sortedWith(compareBy<VPath> { !it.isDir() }.thenBy { it.fileName().lowercase() })
        for (child in children) {
            val childIsDir = child.isDir()
            result.add(FileTreeEntry(child, childIsDir, parent))
            if (childIsDir) {
                walk(child, child)
            }
        }
    }
    walk(dir, null)
    return result
}

/**
 * Persists the expanded state of the file tree for a given instance.
 *
 * @param fileTree The tree widget to snapshot.
 * @param instance The instance associated with this tree.
 * @param expandedState Map to write the expanded paths into, keyed by instance path.
 */
fun saveExpandedState(fileTree: QTreeWidget, instance: DetectedInstance, expandedState: MutableMap<String, Set<String>>) {
    val path = instance.minecraftDir.toAbsolute().toString()
    val expanded = mutableSetOf<String>()
    fun walk(item: QTreeWidgetItem) {
        val data = item.data(0, Qt.ItemDataRole.UserRole) as? String
        if (data != null && item.isExpanded) expanded.add(data)
        for (i in 0 until item.childCount()) {
            item.child(i)?.let { walk(it) }
        }
    }
    val root = fileTree.invisibleRootItem() ?: return
    for (i in 0 until root.childCount()) {
        root.child(i)?.let { walk(it) }
    }
    expandedState[path] = expanded
}

/**
 * Restores a previously saved expanded state onto the file tree.
 *
 * @param fileTree The tree widget to restore.
 * @param instancePath The instance path that was used as the save key.
 * @param expandedState The persisted expanded state map.
 */
fun restoreExpandedState(fileTree: QTreeWidget, instancePath: String, expandedState: Map<String, Set<String>>) {
    val saved = expandedState[instancePath] ?: return
    fun walk(item: QTreeWidgetItem) {
        val data = item.data(0, Qt.ItemDataRole.UserRole) as? String
        if (data != null && data in saved) item.isExpanded = true
        for (i in 0 until item.childCount()) {
            item.child(i)?.let { walk(it) }
        }
    }
    val root = fileTree.invisibleRootItem() ?: return
    for (i in 0 until root.childCount()) {
        root.child(i)?.let { walk(it) }
    }
}

/**
 * Collects all file paths that are currently checked in the tree widget.
 *
 * Directories are skipped; only leaf nodes are returned. A partially checked parent is
 * considered checked and its leaf children are collected recursively.
 *
 * @param fileTree The tree widget to read check states from.
 * @return List of [VPath] for checked files.
 */
fun collectCheckedFiles(fileTree: QTreeWidget): List<VPath> {
    val result = mutableListOf<VPath>()
    fun walk(item: QTreeWidgetItem) {
        if (item.checkState(0) == Qt.CheckState.Checked || item.checkState(0) == Qt.CheckState.PartiallyChecked) {
            for (i in 0 until item.childCount()) {
                item.child(i)?.let { walk(it) }
            }
            if (item.childCount() == 0) {
                val pathStr = item.data(0, Qt.ItemDataRole.UserRole) as? String
                if (pathStr != null) {
                    val vpath = VPath.get(pathStr)
                    if (!vpath.isDir()) result.add(vpath)
                }
            }
        }
    }
    val root = fileTree.invisibleRootItem() ?: return result
    for (i in 0 until root.childCount()) {
        root.child(i)?.let { walk(it) }
    }
    return result
}
