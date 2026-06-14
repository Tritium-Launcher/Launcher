package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.io.VPath
import io.qt.core.Qt
import io.qt.widgets.QTreeWidget
import io.qt.widgets.QTreeWidgetItem

data class FileTreeEntry(val path: VPath, val isDirectory: Boolean, val parent: VPath?)

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
