/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.DockPanelProvider
import io.github.tritium_launcher.api.docks.DockWidget
import io.github.tritium_launcher.launcher.ui.notifications.ProjectNotificationListPanel
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.core.Qt
import io.qt.gui.QIcon

/**
 * Side panel showing project notification history.
 */
class NotificationsDockPanel : DockPanelProvider {
    override val id: String = "notifications"
    override val displayName: String = "Notifications"
    override var icon: QIcon? = TIcons.QuestionMark.icon
    override val order: Int = 20

    override val closeable: Boolean = false
    override val floatable: Boolean = false
    override val preferredArea: Qt.DockWidgetArea = Qt.DockWidgetArea.RightDockWidgetArea
    override val allowSplit: Boolean = false

    override fun create(project: ProjectBase): DockWidget {
        val dock = DockWidget(displayName, null)
        dock.setWidget(ProjectNotificationListPanel(project))
        return dock
    }
}
