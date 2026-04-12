package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QDockWidget
import io.qt.widgets.QMainWindow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Used in [io.github.tritium_launcher.launcher.ui.project.ProjectViewWindow] to display content in pop-out panes.
 * @see SidePanelMngr
 * @see SidePanelProvider
 * @see ProjectFilesSidePanelProvider
 */
open class DockWidget(title: String, parent: QMainWindow?): QDockWidget(title, parent) {

    var index: Int
        get() = property("dockIndex") as? Int ?: 0
        set(value) {
            setProperty("dockIndex", value)
            onIndexChangedListeners.forEach { it(value) }
        }

    private val onIndexChangedListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    fun addOnIndexChanged(listener: (Int) -> Unit) { onIndexChangedListeners.add(listener) }
    fun removeOnIndexChanged(listener: (Int) -> Unit) { onIndexChangedListeners.remove(listener) }

    fun applyIcon(icon: QIcon?) { if(icon != null) windowIcon = icon }
    fun applyIcon(icon: QPixmap?) { if(icon != null) windowIcon = QIcon(icon) }
}