package io.github.tritium_launcher.launcher.ui.notifications

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.TPushButton
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.widget
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.widgets.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Project side panel list showing notification history for the current project.
 */
class ProjectNotificationListPanel(
    private val project: ProjectBase
) : QWidget() {
    private val controlsRow = widget(this) {
        objectName = "notificationListControls"
    }
    private val clearButton = TPushButton {
        text = "Clear"
        minimumWidth = 72
        minimumHeight = 24
        toolTip = "Clear notification log"
    }
    private val emptyLabel = label("No notifications yet.", this) {
        objectName = "notificationListEmpty"
        setAlignment(Qt.AlignmentFlag.AlignCenter)
        wordWrap = true
    }
    private val list = QListWidget(this).apply {
        objectName = "notificationList"
        wordWrap = true
        alternatingRowColors = false
        selectionMode = QAbstractItemView.SelectionMode.NoSelection
        editTriggers = QAbstractItemView.EditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        frameShape = QFrame.Shape.NoFrame
    }
    private val viewportResizeFilter = object : QObject(list.viewport()) {
        override fun eventFilter(watched: QObject?, event: QEvent?): Boolean {
            if (event?.type() == QEvent.Type.Resize) {
                updateRowLayouts()
            }
            return super.eventFilter(watched, event)
        }
    }

    private val unsubscribe: () -> Unit

    init {
        objectName = "notificationListPanel"
        list.viewport()?.installEventFilter(viewportResizeFilter)

        hBoxLayout(controlsRow) {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 6
            addStretch()
            addWidget(clearButton)
        }
        controlsRow.sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        val controlsHeight = clearButton.sizeHint().height().coerceAtLeast(clearButton.minimumHeight)
        controlsRow.minimumHeight = controlsHeight
        controlsRow.maximumHeight = controlsHeight

        vBoxLayout(this) {
            setContentsMargins(6, 0, 6, 6)
            widgetSpacing = 6
            addWidget(controlsRow)
            addWidget(emptyLabel)
            addWidget(list, 1)
        }

        clearButton.onClicked {
            NotificationMngr.clearForProject(project, includeGlobal = true)
        }

        unsubscribe = NotificationMngr.addListener {
            runOnGuiThread { refresh() }
        }

        destroyed.connect {
            list.viewport()?.removeEventFilter(viewportResizeFilter)
            unsubscribe()
        }

        setThemedStyle {
            selector("#notificationListPanel") {
                backgroundColor(TColors.Surface1)
            }
            selector("#notificationListControls") {
                backgroundColor("transparent")
            }
            selector("#notificationListEmpty") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#notificationList") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border()
                any("outline", "none")
            }
            selector("#notificationList::item") {
                padding(0)
                border(0, "transparent")
            }
            selector("#notificationListRow") {
                backgroundColor(TColors.Surface1)
                borderRadius(4)
            }
            selector("#notificationListHeader") {
                color(TColors.Text)
                fontWeight(600)
                fontSize(12)
            }
            selector("#notificationListStatus") {
                color(TColors.Subtext)
                fontSize(10)
            }
            selector("#notificationListDescription") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#notificationListTime") {
                color(TColors.Subtext)
                fontSize(10)
            }
            selector("#notificationListLink") {
                color(TColors.Accent)
                any("text-decoration", "underline")
                fontSize(11)
            }
        }

        refresh()
    }

    private fun refresh() {
        val entries = NotificationMngr.entriesForProject(
            project = project,
            includeDismissed = true,
            includeGlobal = true
        )

        list.clear()
        if (entries.isEmpty()) {
            list.hide()
            emptyLabel.show()
            return
        }

        entries.forEach { entry ->
            val rowWidget = createRowWidget(entry)
            val item = QListWidgetItem()
            item.setSizeHint(rowWidget.sizeHint())
            list.addItem(item)
            list.setItemWidget(item, rowWidget)
        }

        emptyLabel.hide()
        list.show()
        QTimer.singleShot(0) { updateRowLayouts() }
    }

    private fun updateRowLayouts() {
        val viewport = list.viewport() ?: return
        val width = viewport.width().coerceAtLeast(0)
        if (width <= 0) return

        for (index in 0 until list.count()) {
            val item = list.item(index) ?: continue
            val row = list.itemWidget(item) ?: continue
            row.setFixedWidth(width)
            row.adjustSize()
            item.setSizeHint(row.sizeHint())
        }
        list.doItemsLayout()
    }

    private fun createRowWidget(entry: NotificationEntry): QWidget {
        val globallyDisabled = NotificationMngr.isDisabledGlobally(entry.definitionId)
        val projectDisabled = NotificationMngr.isDisabledForProject(project, entry.definitionId)
        val disabled = globallyDisabled || projectDisabled
        val status = when {
            globallyDisabled -> "Disabled (Global)"
            projectDisabled -> "Disabled (Project)"
            entry.dismissed -> "Dismissed"
            else -> "Active"
        }

        val row = widget {
            objectName = "notificationListRow"
        }

        vBoxLayout(row) {
            setContentsMargins(8, 7, 8, 7)
            widgetSpacing = 4

            val headerRow = widget(row)
            hBoxLayout(headerRow) {
                setContentsMargins(0, 0, 0, 0)
                widgetSpacing = 6
                addWidget(label(headerRow) {
                    pixmap = entry.icon.pixmap(16, 16)
                })
                addWidget(label(entry.header, headerRow) {
                    objectName = "notificationListHeader"
                    wordWrap = false
                    minimumWidth = 0
                    toolTip = entry.header
                    sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
                }, 1)
                addWidget(label(status, headerRow) {
                    objectName = "notificationListStatus"
                })
            }
            addWidget(headerRow)

            if (entry.description.isNotBlank()) {
                addWidget(label(entry.description.trim(), row) {
                    objectName = "notificationListDescription"
                    wordWrap = true
                })
            }

            entry.links.forEach { link ->
                addWidget(createLinkLabel(link, row))
            }

            if (disabled) {
                val actionsRow = widget(row)
                hBoxLayout(actionsRow) {
                    setContentsMargins(0, 0, 0, 0)
                    widgetSpacing = 6

                    if (projectDisabled) {
                        val btn = TPushButton(actionsRow).apply {
                            text = "Enable Project"
                            minimumHeight = 20
                            minimumWidth = 96
                            toolTip = "Re-enable this notification for the current project"
                        }
                        btn.onClicked {
                            NotificationMngr.enableForProject(project, entry.definitionId)
                        }
                        addWidget(btn)
                    }

                    if (globallyDisabled) {
                        val btn = TPushButton(actionsRow).apply {
                            text = "Enable Global"
                            minimumHeight = 20
                            minimumWidth = 96
                            toolTip = "Re-enable this notification globally"
                        }
                        btn.onClicked {
                            NotificationMngr.enableGlobally(entry.definitionId)
                        }
                        addWidget(btn)
                    }

                    addStretch()
                }
                addWidget(actionsRow)
            }

            addWidget(label(formatTimestamp(entry.createdAtEpochMs), row) {
                objectName = "notificationListTime"
            })
        }

        return row
    }

    private fun createLinkLabel(link: NotificationLink, parent: QWidget): QLabel {
        val text = escapeHtml(link.text.ifBlank { link.url })
        val url = escapeHtml(link.url)
        return label("<a href=\"$url\">$text</a>", parent) {
            objectName = "notificationListLink"
            wordWrap = true
            setTextInteractionFlags(Qt.TextInteractionFlag.TextBrowserInteraction)
            openExternalLinks = false
            linkActivated.connect { target ->
                Platform.openBrowser(target)
            }
        }
    }

    private fun formatTimestamp(epochMs: Long): String {
        val instant = Instant.ofEpochMilli(epochMs)
        return instant.atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)
    }

    private fun escapeHtml(raw: String): String =
        raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
