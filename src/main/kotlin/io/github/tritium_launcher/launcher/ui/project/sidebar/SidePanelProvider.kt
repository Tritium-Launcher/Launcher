package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.core.Qt
import io.qt.gui.QIcon

/**
 * Provides a dockable side panel for a project window.
 */
interface SidePanelProvider: Registrable {

    val displayName: String
    val icon: QIcon
    val order: Int

    val closeable: Boolean get() = true
    val floatable: Boolean get() = true
    val preferredArea: Qt.DockWidgetArea get() = Qt.DockWidgetArea.LeftDockWidgetArea

    fun create(project: ProjectBase): DockWidget
}
