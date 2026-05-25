package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.widgets.QWidget

/**
 * Provides a dockable side panel for a project window.
 */
interface SidePanelProvider: Registrable {

    /**
     * Which dock areas this side panel is allowed to be placed in. Defaults to left/right/bottom for
     * backwards compatibility.
     */
    val allowedDockAreas: Set<Qt.DockWidgetArea>
        get() = setOf(
            Qt.DockWidgetArea.LeftDockWidgetArea,
            Qt.DockWidgetArea.RightDockWidgetArea,
            Qt.DockWidgetArea.BottomDockWidgetArea
        )

    val displayName: String
    val icon: QIcon
    val order: Int

    val closeable: Boolean get() = true
    val floatable: Boolean get() = true
    val preferredArea: Qt.DockWidgetArea get() = Qt.DockWidgetArea.LeftDockWidgetArea

    /**
     * Create [DockWidget] from provided [ProjectBase]
     */
    fun create(project: ProjectBase): DockWidget
}

interface SidePanelTitleBarAccessoryProvider {
    fun createTitleBarAccessory(project: ProjectBase, dock: DockWidget, onStateChanged: () -> Unit): QWidget?
}
