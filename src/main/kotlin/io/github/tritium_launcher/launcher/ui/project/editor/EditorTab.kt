package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.onClicked
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.theme.qt.qtStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.toolButton
import io.qt.NonNull
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.QObject
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget

/**
 * An individual tab representing an open File or other widget provided by Extensions or otherwise.
 *
 * @see EditorTabBar
 */
class EditorTab(icon: QIcon?, text: String, private val parentBar: EditorTabBar): QWidget() {
    private val iconLabel = label()
    private val textLabel = label()
    private val closeBtn  = toolButton()
    private val layout    = hBoxLayout(this)

    var isSelected = false
        set(value) {
            field = value
            update()
        }

    var isHovered = false
        set(value) {
            field = value
            update()
        }

    var onClicked: (() -> Unit)? = null
    var onCloseClicked: (() -> Unit)? = null
    var onHoverChanged: (() -> Unit)? = null

    var showClose: Boolean = false
        set(value) {
            field = value
            updateCloseBtn()
        }

    init {
        objectName = "EditorTab"
        setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        setFixedHeight(parentBar.tabHeightPx())

        layout.apply {
            setContentsMargins(6,4,6,4)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        iconLabel.apply {
            setFixedSize(16,16)
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }

        textLabel.apply {
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
            setText(text)
        }

        closeBtn.apply {
            autoRaise = true
            setFixedSize(16,16)
            setIcon(TIcons.SmallCross.icon)
            iconSize = qs(16,16)
            styleSheet = qtStyle {
                selector("QToolButton") {
                    background("transparent")
                    border()
                }
            }.toStyleSheet()
        }

        layout.addWidget(iconLabel)
        layout.setAlignment(iconLabel, Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(textLabel)
        layout.setAlignment(textLabel, Qt.AlignmentFlag.AlignVCenter)
        layout.addWidget(closeBtn)
        layout.setAlignment(closeBtn, Qt.AlignmentFlag.AlignVCenter)

        if(icon != null) setIcon(icon)

        closeBtn.onClicked {
            onCloseClicked?.invoke()
        }

        mouseTracking = true
        installEventFilter(object : QObject(this@EditorTab) {
            override fun eventFilter(
                watched: @Nullable QObject?,
                event: @Nullable QEvent?
            ): Boolean {
                when(event?.type()) {
                    QEvent.Type.Enter -> {
                        isHovered = true
                        onHoverChanged?.invoke()
                    }
                    QEvent.Type.Leave -> {
                        isHovered = false
                        onHoverChanged?.invoke()
                    }
                    QEvent.Type.MouseButtonRelease -> {
                        val me = event as QMouseEvent
                        if(me.button() === Qt.MouseButton.LeftButton) {
                            onClicked?.invoke()
                            return true
                        }
                    }

                    else -> {}
                }
                return super.eventFilter(watched, event)
            }
        })

        updateCloseBtn()
    }

    fun setIcon(icon: QIcon?) {
        if(icon == null) {
            iconLabel.clear()
        } else {
            val pm = icon.pixmap(16,16)
            iconLabel.pixmap = pm
        }
    }

    fun setText(t: String) {
        textLabel.text = t
        updateGeometry()
    }

    fun getText(): String = textLabel.text

    fun getIcon(): QIcon {
        val pm = iconLabel.pixmap
        return if (pm != null && !pm.isNull) QIcon(pm) else QIcon()
    }

    internal fun updateColors() {
        update()
    }

    private fun updateCloseBtn() {
        closeBtn.isVisible = true
        closeBtn.isEnabled = showClose
        closeBtn.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, !showClose)
        closeBtn.icon = if(showClose) TIcons.SmallCross.icon else QIcon()
    }

    override fun paintEvent(event: @Nullable QPaintEvent?) {
        val painter = QPainter(this)

        val colorStr = when {
            isSelected -> parentBar.selectedHoveredTabColor
            else -> parentBar.normalTabColor
        }

        if(colorStr != "transparent") {
            val color = parseRgbaColor(colorStr)
            painter.fillRect(rect, color)
        }

        if(isSelected) {
            val indicatorHeight = 2
            painter.fillRect(0, height() - indicatorHeight, width().coerceAtLeast(1), indicatorHeight, QColor(255, 255, 255, 230))
        }

        painter.end()
        super.paintEvent(event)
    }

    private fun parseRgbaColor(colorStr: String): QColor {
        val regex = """rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)""".toRegex()
        val match = regex.find(colorStr)

        return if (match != null) {
            val (r, g, b, a) = match.destructured
            val alpha = (a.toFloatOrNull() ?: 1.0f) * 255
            QColor(r.toInt(), g.toInt(), b.toInt(), alpha.toInt())
        } else {
            QColor(0, 0, 0, 0)
        }
    }

    override fun sizeHint(): @NonNull QSize {
        val fm = fontMetrics()
        val textW = fm.horizontalAdvance(textLabel.text)
        val w = 6 + 16 + 6 + textW + 6 + 16 + 6
        val h = parentBar.tabHeightPx()
        return qs(w, h)
    }
}
