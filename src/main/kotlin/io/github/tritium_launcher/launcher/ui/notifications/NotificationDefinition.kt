package io.github.tritium_launcher.launcher.ui.notifications

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QIcon
import io.qt.widgets.QWidget

/**
 * Hyperlink
 */
data class NotificationLink(
    val text: String,
    val url: String
)

/**
 * Render context
 */
data class NotificationRenderContext(
    val project: ProjectBase?,
    val entry: NotificationEntry
)

/**
 * Factory for rendering content
 */
typealias NotificationWidgetFactory = (NotificationRenderContext) -> QWidget

/**
 * Extension-registered notification definition.
 *
 * Definitions are gathered from the registry and are later
 * referenced by [NotificationRequest.id] when notifications are posted.
 */
data class NotificationDefinition(
    override val id: String,
    val header: String,
    val description: String = "",
    val icon: QIcon = TIcons.QuestionMark.icon,
    val links: List<NotificationLink> = emptyList(),
    val customWidgetFactory: NotificationWidgetFactory? = null,
    val sendToOsByDefault: Boolean = false,
    val order: Int = 0,
) : Registrable

/**
 * Runtime request used to emit an in-program notification.
 *
 * Any nullable override falls back to values from the registered [NotificationDefinition].
 */
data class NotificationRequest(
    val id: String,
    val project: ProjectBase? = null,
    val header: String? = null,
    val description: String? = null,
    val icon: QIcon? = null,
    val links: List<NotificationLink>? = null,
    val customWidgetFactory: NotificationWidgetFactory? = null,
    val sendToOs: Boolean? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Notification entry stored by [NotificationMngr].
 */
data class NotificationEntry(
    val instanceId: String,
    val definitionId: String,
    val projectPath: String?,
    val projectName: String?,
    val header: String,
    val description: String,
    val icon: QIcon,
    val links: List<NotificationLink>,
    val customWidgetFactory: NotificationWidgetFactory?,
    val sendToOs: Boolean,
    val createdAtEpochMs: Long,
    val metadata: Map<String, String>,
    val dismissed: Boolean = false,
)

/**
 * Change events emitted by [NotificationMngr].
 */
sealed interface NotificationEvent {
    /** Emitted when a new notification entry is posted. */
    data class Posted(val entry: NotificationEntry) : NotificationEvent
    /** Emitted when an existing notification entry changes state. */
    data class Updated(val entry: NotificationEntry) : NotificationEvent
    /** Emitted when enable/disable preferences change for a definition id. */
    data class PreferencesChanged(val definitionId: String) : NotificationEvent
    /** Emitted when one or more entries are removed from history. */
    data class Cleared(val removedCount: Int) : NotificationEvent
}
