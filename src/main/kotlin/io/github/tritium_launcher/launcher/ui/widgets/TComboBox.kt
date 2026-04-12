package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.theme.qt.setThemedStyle
import io.github.tritium_launcher.launcher.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.Qt
import io.qt.gui.QMoveEvent
import io.qt.gui.QPaintEvent
import io.qt.gui.QPainter
import io.qt.gui.QShowEvent
import io.qt.widgets.*
import kotlin.math.abs
import kotlin.math.min

/**
 * Sprite-style combo box.
 */
class TComboBox(parent: QWidget? = null) : QComboBox(parent) {
    private var popupVisible = false
    private var lastDpr: Double = -1.0

    init {
        frame = false
        setContentsMargins(0, 0, 0, 0)
        minimumHeight = 34

        val listView = QListView()
        listView.frameShape = QFrame.Shape.NoFrame
        listView.alternatingRowColors = true
        setView(listView)
        applyPopupStyle()
    }

    override fun showPopup() {
        popupVisible = true
        super.showPopup()
        update()
    }

    override fun hidePopup() {
        popupVisible = false
        super.hidePopup()
        update()
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, false)

        val w = width()
        val h = height()

        val state = when {
            !isEnabled -> State.Disabled
            popupVisible -> State.Pressed
            else -> State.Normal
        }

        val dpr = try {
            currentDpr(this)
        } catch (_: Throwable) {
            1.0
        }
        handleDprChange(dpr)
        val bg = skin.render(state.key, w, h, dpr)
        if (!bg.isNull) {
            painter.drawPixmap(0, 0, bg)
        }

        val opt = QStyleOptionComboBox()
        initStyleOption(opt)

        val arrowSize = min(16, (h - 10).coerceAtLeast(10))
        opt.rect = opt.rect.adjusted(8, 0, -(arrowSize + 10), 0)
        painter.save()
        painter.translate(0.0, -2.0)
        style()?.drawControl(QStyle.ControlElement.CE_ComboBoxLabel, opt, painter, this)
        painter.restore()

        val arrow = TIcons.SmallArrowDown
        if (!arrow.isNull) {
            val scaled = arrow.scaled(
                qs(arrowSize, arrowSize),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.FastTransformation
            )
            val x = w - arrowSize - 8
            val y = (h - arrowSize) / 2
            painter.drawPixmap(x, y, scaled)
        }

        painter.end()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> skin.clearCache(disposePixmaps = true)
            else -> {}
        }
    }

    override fun event(e: @Nullable QEvent?): Boolean {
        if (e?.type() == QEvent.Type.ScreenChangeInternal || e?.type() == QEvent.Type.DevicePixelRatioChange) {
            handleDprChange(currentDpr(this))
        }
        return super.event(e)
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        handleDprChange(currentDpr(this))
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        handleDprChange(currentDpr(this))
    }

    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
            update()
        }
    }

    private fun applyPopupStyle() {
        view()?.setThemedStyle {
            selector("QListView") {
                border(2, TColors.Surface0)
                borderRadius(4)
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                padding(2)
                any("alternate-background-color", TColors.Surface0)
            }
            selector("QListView::item") {
                border()
                padding(4, 6, 4, 6)
            }
        }
    }

    private enum class State(val key: String) { Normal("normal"), Pressed("pressed"), Disabled("disabled") }

    companion object {
        private var skin = buildSkin()

        init {
            ThemeMngr.addListener {
                val prev = skin
                skin = buildSkin()
                prev.clearCache(disposePixmaps = true)
            }
        }

        private fun buildSkin() = pixelSkin {
            pixelSize = 2
            palette {
                color("border", TColors.Button0)
                color("shadow", TColors.Button1)
                color("primary", TColors.Button2)
                color("bright", TColors.Button3)
                color("disabled", TColors.ButtonDisabled0)
                color("disabledBorder", TColors.ButtonDisabled1)
            }

            state("normal") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "border")
                    fillRect(p, p, w - p * 2, h - p * 2, "primary")
                    fillRect(p, p, w - p * 2, p, "bright")
                    fillRect(p, p, p, h - p * 5, "bright")
                    fillRect(w - p * 2, p, p, h - p * 5, "bright")
                    fillRect(p, h - p * 4, w - p * 2, p, "bright")
                    fillRect(p, h - p * 3, w - p * 2, p * 2, "shadow")
                }
            }

            state("pressed") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, p, w, h - p, "border")
                    fillRect(p, p + 2, w - p * 2, h - p - 4, "primary")
                    fillRect(p, p + 2, w - p * 2, p, "bright")
                    fillRect(p, p + 2, p, h - p * 4, "bright")
                    fillRect(w - p * 2, p + 2, p, h - p * 4, "bright")
                    fillRect(p, h - p * 2, w - p * 2, p, "bright")
                }
            }

            state("disabled") {
                draw {
                    val p = px
                    val w = width
                    val h = height
                    fillRect(0, 0, w, h, "disabledBorder")
                    fillRect(p, p, w - p * 2, h - p * 2, "disabled")
                    fillRect(p, p, w - p * 2, p, "disabled")
                    fillRect(p, p, p, h - p * 3, "disabled")
                    fillRect(w - p * 2, p, p, h - p * 3, "disabled")
                    fillRect(p, h - p * 2, w - p * 2, p, "disabled")
                }
            }
        }

        operator fun invoke(parent: QWidget? = null, block: TComboBox.() -> Unit = {}): TComboBox =
            TComboBox(parent).apply(block)
    }
}
