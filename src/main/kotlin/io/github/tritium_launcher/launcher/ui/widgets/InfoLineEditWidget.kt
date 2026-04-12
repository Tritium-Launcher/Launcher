package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.theme.qt.setStyle
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.qt.Nullable
import io.qt.core.QPoint
import io.qt.core.Qt
import io.qt.gui.QFocusEvent
import io.qt.gui.QMoveEvent
import io.qt.gui.QResizeEvent
import io.qt.widgets.QLabel
import io.qt.widgets.QLineEdit
import io.qt.widgets.QWidget

/**
 * Line Edit with a Tooltip.
 */
class InfoLineEditWidget(
    var tipText: String = "",
    parent: QWidget? = null
): QLineEdit(parent) {

    private var popup: QLabel? = null
    private val popupMaxWidth = 1000

    init {
        destroyed.connect {
            popup?.close()
            popup = null
        }
    }

    fun setFocusToolTip(text: String) {
        tipText = text
        if(hasFocus()) showPinnedToolTip()
    }

    private fun ensurePopup(): QLabel {
        if(popup == null) {
            popup = label {
                setWindowFlag(Qt.WindowType.ToolTip)
                setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, true)
                setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
                margin = 8
                wordWrap = true
                setStyle {
                    border(1, "rga(255,255,255,0.08)")
                    background("rgba(30,30,30,0.95)")
                    borderRadius(6)
                    padding(6)
                }
            }
        }

        return popup!!
    }


    private fun showPinnedToolTip() {
        if(tipText.isBlank() || !isVisible) return

        val p = ensurePopup()
        p.text = tipText

        p.maximumWidth = popupMaxWidth
        p.adjustSize()

        val globalTopCenter = mapToGlobal(QPoint(width / 2, 0))

        val x = globalTopCenter.x() - p.width / 2
        val y = globalTopCenter.y() - p.height - 6

        p.move(x, y)
        p.show()
        p.raise()
    }

    private fun hidePinnedTip() {
        popup?.hide()
    }

    override fun focusInEvent(arg__1: @Nullable QFocusEvent?) {
        super.focusInEvent(arg__1)
        if(tipText.isNotBlank()) showPinnedToolTip()
    }

    override fun focusOutEvent(arg__1: @Nullable QFocusEvent?) {
        super.focusOutEvent(arg__1)
        hidePinnedTip()
    }

    override fun resizeEvent(event: @Nullable QResizeEvent?) {
        super.resizeEvent(event)
        if(hasFocus()) showPinnedToolTip()
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        if(hasFocus()) showPinnedToolTip()
    }
}