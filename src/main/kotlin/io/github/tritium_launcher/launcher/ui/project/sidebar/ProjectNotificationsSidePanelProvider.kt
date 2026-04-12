package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.ui.notifications.ProjectNotificationListPanel
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.core.Qt
import io.qt.gui.QIcon

/**
 * Side panel showing project notification history.
 */
class ProjectNotificationsSidePanelProvider : SidePanelProvider {
    override val id: String = "notifications"
    override val displayName: String = "Notifications"
    override val icon: QIcon = TIcons.QuestionMark.icon
    override val order: Int = 20

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(ProjectNotificationListPanel(project))
        return dock
    }
}
