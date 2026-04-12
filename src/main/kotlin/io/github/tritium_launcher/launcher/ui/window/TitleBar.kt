package io.github.tritium_launcher.launcher.ui.window

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.qt.Nullable
import io.qt.core.QPoint
import io.qt.core.Qt
import io.qt.gui.QMouseEvent
import io.qt.widgets.QMainWindow
import io.qt.widgets.QStyle
import io.qt.widgets.QWidget

/**
 * Unused attempt at an IntelliJ-like titlebar.
 */
class TitleBar(private val mainWindow: QMainWindow): QWidget(mainWindow) {
    val titleLabel = label(mainWindow.windowTitle, this)
    val minBtn     = toolButton(this)
    val maxBtn     = toolButton(this)
    val restoreBtn = toolButton(this)
    val closeBtn   = toolButton(this)

    private var dragging  = false
    private var dragStart = QPoint()

    init {
        val layout = hBoxLayout(this) {
            setContentsMargins(6,4,6,4)
            widgetSpacing = 8
        }

        titleLabel.setAlignment(Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(titleLabel, 1)

        minBtn.icon     = style()?.standardIcon(QStyle.StandardPixmap.SP_TitleBarMinButton) ?: TIcons.QuestionMark.icon
        maxBtn.icon     = style()?.standardIcon(QStyle.StandardPixmap.SP_TitleBarMaxButton) ?: TIcons.QuestionMark.icon
        restoreBtn.icon = style()?.standardIcon(QStyle.StandardPixmap.SP_TitleBarNormalButton) ?: TIcons.QuestionMark.icon
        closeBtn.icon   = style()?.standardIcon(QStyle.StandardPixmap.SP_TitleBarCloseButton) ?: TIcons.QuestionMark.icon

        restoreBtn.isVisible = false

        layout.addWidget(minBtn)
        layout.addWidget(maxBtn)
        layout.addWidget(restoreBtn)
        layout.addWidget(closeBtn)

        val toolButtonStyle = qtStyle {
            selector("QToolButton") {
                backgroundColor("transparent")
                padding(4)
            }
        }.toStyleSheet()
        listOf(minBtn, maxBtn, restoreBtn, closeBtn).forEach { btn ->
            btn.setCursor(Qt.CursorShape.PointingHandCursor)
            btn.toolButtonStyle = Qt.ToolButtonStyle.ToolButtonIconOnly
            btn.styleSheet = toolButtonStyle
        }

        minBtn.onClicked     { mainWindow.showMinimized() }
        maxBtn.onClicked     { mainWindow.showMaximized() }
        restoreBtn.onClicked { mainWindow.showNormal() }
        closeBtn.onClicked   { mainWindow.close() }

        mainWindow.windowTitleChanged.connect { newTitle -> titleLabel.text = newTitle }

        setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
    }

    override fun mousePressEvent(event: @Nullable QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            val handle = mainWindow.windowHandle()
            if (handle != null) {
                try {
                    handle.startSystemMove()
                } catch (_: Throwable) {
                    dragging = true
                    dragStart = event.globalPosition().toPoint()
                }
            } else {
                dragging = true
                dragStart = event.globalPosition().toPoint()
            }
            event.accept()
        } else {
            super.mousePressEvent(event)
        }
    }

    override fun mouseReleaseEvent(event: @Nullable QMouseEvent?) {
        dragging = false
        super.mouseReleaseEvent(event)
    }

    override fun mouseDoubleClickEvent(event: @Nullable QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            if (mainWindow.isMaximized) mainWindow.showNormal() else mainWindow.showMaximized()
        }
        super.mouseDoubleClickEvent(event)
    }

    override fun mouseMoveEvent(event: @Nullable QMouseEvent?) {
        super.mouseMoveEvent(event)
        if (dragging && event != null) {
            val g = event.globalPosition().toPoint()
            val dx = g.x() - dragStart.x()
            val dy = g.y() - dragStart.y()
            mainWindow.move(mainWindow.x() + dx, mainWindow.y() + dy)
            dragStart = g
            event.accept()
        }
    }
}
