package io.github.tritium_launcher.launcher.ui.notifications

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.widgets.*

/**
 * Notification Toast stack.
 */
class Toaster(
    private val project: ProjectBase,
    private val window: QMainWindow,
) {
    private val overlayParent = window.centralWidget() ?: window
    private val parentResizeFilter = object : QObject(overlayParent) {
        override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
            if (event?.type() == QEvent.Type.Resize) {
                reposition()
            }
            return super.eventFilter(watched, event)
        }
    }
    private val container = widget(overlayParent) {
        objectName = "notificationOverlay"
    }

    private val layout = vBoxLayout(container) {
        setContentsMargins(0, 0, 0, 0)
        widgetSpacing = 8
    }
    private val cardsById = LinkedHashMap<String, NotificationToastCard>()
    private var refreshQueued = false

    private val unsubscribe: () -> Unit

    init {
        overlayParent.installEventFilter(parentResizeFilter)
        container.hide()
        container.raise()

        unsubscribe = NotificationMngr.addListener {
            scheduleRefresh()
        }

        window.destroyed.connect {
            overlayParent.removeEventFilter(parentResizeFilter)
            unsubscribe()
            container.disposeLater()
        }

        scheduleRefresh()
    }

    /**
     * Repositions overlay to the corner of the project window.
     */
    fun reposition() {
        if (!container.isVisible) return

        val area = overlayParent.contentsRect()
        if (area.width() <= 0 || area.height() <= 0) return

        val margin = 12
        val maxWidth = 320
        val minWidth = 180
        val availableWidth = (area.width() - margin * 2).coerceAtLeast(0)
        if (availableWidth <= 0) {
            return
        }
        val width = availableWidth
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minWidth.coerceAtMost(availableWidth))
        container.setFixedWidth(width)

        layout.activate()
        container.adjustSize()
        val maxHeight = (area.height() - margin * 2).coerceAtLeast(0)
        if (maxHeight <= 0) {
            return
        }
        var desiredHeight = container.sizeHint().height()
        if (desiredHeight <= 0) desiredHeight = layout.sizeHint().height()
        if (desiredHeight <= 0) desiredHeight = container.height().coerceAtLeast(1)
        val height = desiredHeight.coerceAtMost(maxHeight)
        container.resize(width, height)

        val x = (area.x() + area.width() - container.width() - margin).coerceAtLeast(area.x() + margin)
        val y = (area.y() + area.height() - container.height() - margin).coerceAtLeast(area.y() + margin)
        container.move(x, y)
        container.raise()
    }

    private fun refresh() {
        clearLayout()

        val active = NotificationMngr.entriesForProject(
            project = project,
            includeDismissed = false,
            includeGlobal = true,
            includeDisabled = false
        ).sortedBy { it.createdAtEpochMs }.takeLast(4)

        if (active.isEmpty()) {
            container.hide()
            return
        }

        active.forEach { entry ->
            val card = NotificationToastCard(project, entry)
            cardsById[entry.instanceId] = card
            layout.addWidget(card)
        }

        layout.activate()
        container.show()
        QTimer.singleShot(0) { reposition() }
    }

    private fun scheduleRefresh() {
        runOnGuiThread {
            if (refreshQueued) return@runOnGuiThread
            refreshQueued = true
            QTimer.singleShot(0) {
                refreshQueued = false
                refresh()
            }
        }
    }

    private fun clearLayout() {
        cardsById.clear()
        while (layout.count() > 0) {
            val item = layout.takeAt(0)
            item?.widget()?.let { w ->
                w.hide()
                w.disposeLater()
            }
        }
    }
}

private class NotificationToastCard(
    private val project: ProjectBase,
    private val entry: NotificationEntry
) : QFrame() {
    companion object {
        private val logger = logger()
    }

    init {
        objectName = "notificationCard"
        frameShape = Shape.StyledPanel
        setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)

        val root = vBoxLayout(this) {
            setContentsMargins(10, 8, 10, 8)
            widgetSpacing = 6
        }

        val topRow = widget(this)
        val topLayout = hBoxLayout(topRow) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 8
        }

        val iconLabel = label(topRow) {
            pixmap = entry.icon.pixmap(16, 16)
            setAlignment(Qt.AlignmentFlag.AlignTop)
        }

        val headerLabel = label(entry.header, topRow) {
            objectName = "notificationHeader"
            wordWrap = true
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        val disableButton = QToolButton(topRow).apply { //TODO: Make this its own button, not a tool button
            objectName = "notificationDisableButton"
            text = "Disable"
            popupMode = QToolButton.ToolButtonPopupMode.InstantPopup
            icon = TIcons.SmallMenu.icon
        }
        val disableMenu = QMenu(disableButton)
        disableMenu.addAction("Disable for This Project")?.triggered?.connect {
            NotificationMngr.disableForProject(project, entry.definitionId)
        }
        disableMenu.addAction("Disable Globally")?.triggered?.connect {
            NotificationMngr.disableGlobally(entry.definitionId)
        }
        disableButton.setMenu(disableMenu)

        val dismissButton = QToolButton(topRow).apply {
            objectName = "notificationDismissButton"
            icon = TIcons.SmallCross.icon
            toolTip = "Dismiss"
            autoRaise = true
        }
        dismissButton.clicked.connect {
            NotificationMngr.dismiss(entry.instanceId)
        }

        topLayout.addWidget(iconLabel)
        topLayout.addWidget(headerLabel, 1)
        topLayout.addWidget(disableButton)
        topLayout.addWidget(dismissButton)
        root.addWidget(topRow)

        if (entry.description.isNotBlank()) {
            root.addWidget(label(entry.description, this) {
                objectName = "notificationDescription"
                wordWrap = true
                setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
            })
        }

        entry.links.forEach { link ->
            root.addWidget(createLinkLabel(link, this))
        }

        entry.customWidgetFactory?.let { factory ->
            try {
                val custom = factory(NotificationRenderContext(project, entry))
                root.addWidget(custom)
            } catch (t: Throwable) {
                logger.warn("Custom notification widget failed for {}", entry.definitionId, t)
            }
        }

        setThemedStyle {
            selector("#notificationCard") {
                backgroundColor(TColors.Surface1)
                border(1, TColors.Surface2)
                borderRadius(6)
            }
            selector("#notificationHeader") {
                color(TColors.Text)
                fontWeight(600)
                fontSize(12)
            }
            selector("#notificationDescription") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#notificationDisableButton") {
                backgroundColor(TColors.Button1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
                padding(2, 6, 2, 6)
            }
            selector("#notificationDismissButton") {
                border()
                padding(1)
            }
            selector("#notificationLink") {
                color(TColors.Accent)
                any("text-decoration", "underline")
                fontSize(11)
            }
        }
    }

    private fun createLinkLabel(link: NotificationLink, parent: QWidget): QLabel {
        val text = escapeHtml(link.text.ifBlank { link.url })
        val url = escapeHtml(link.url)
        return label("<a href=\"$url\">$text</a>", parent) {
            objectName = "notificationLink"
            wordWrap = true
            setTextInteractionFlags(Qt.TextInteractionFlag.TextBrowserInteraction)
            openExternalLinks = false
            linkActivated.connect { target ->
                Platform.openBrowser(target)
            }
        }
    }

    private fun escapeHtml(raw: String): String =
        raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
