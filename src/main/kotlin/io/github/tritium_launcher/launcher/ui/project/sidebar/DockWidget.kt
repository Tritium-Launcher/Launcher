package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QDockWidget
import io.qt.widgets.QMainWindow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
            _indexChanges.tryEmit(value)
        }

    private val _indexChanges = MutableSharedFlow<Int>(replay = 0)
    val indexChanges: SharedFlow<Int> = _indexChanges.asSharedFlow()

    fun applyIcon(icon: QIcon?) { if(icon != null) windowIcon = icon }
    fun applyIcon(icon: QPixmap?) { if(icon != null) windowIcon = QIcon(icon) }
}