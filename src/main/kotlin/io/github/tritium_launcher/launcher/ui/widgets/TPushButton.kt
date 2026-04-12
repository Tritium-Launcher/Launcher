package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.gui.QMoveEvent
import io.qt.gui.QPaintEvent
import io.qt.gui.QPainter
import io.qt.gui.QShowEvent
import io.qt.widgets.QPushButton
import io.qt.widgets.QStyle
import io.qt.widgets.QStyleOptionButton
import io.qt.widgets.QWidget
import kotlin.math.abs

/**
 * Minecraft-styled push button.
 */
class TPushButton(
    parent: QWidget? = null
) : QPushButton(parent) {
    private var lastDpr: Double = -1.0
    /**
     * Additional Y offset applied to the button label.
     *
     * Positive values move up, Negative values move down.
     */
    var textVerticalOffset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    init {
        toggled.connect { checked ->
            if (isCheckable) {
                isDown = checked
            }
        }
        minimumHeight = 20
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, false)

        val w = width()
        val h = height()

        val state = when {
            !isEnabled -> State.Disabled
            isDown || isChecked -> State.Pressed
            else -> State.Normal
        }

        val dpr = detectDpr(this)
        handleDprChange(dpr)

        val bg = skin.render(state.key, w, h, dpr)

        if(!bg.isNull) {
            painter.drawPixmap(0, 0, bg)
        }

        drawLabel(painter, dpr)

        painter.end()
    }

    private fun drawLabel(painter: QPainter, dpr: Double) {
        val opt = QStyleOptionButton()
        initStyleOption(opt)

        val r = opt.rect
        val stateOffset = if (isDown || isChecked) 1 else -1
        r.translate(0, stateOffset + textVerticalOffset)
        opt.rect = r

        painter.save()
        style()?.drawControl(QStyle.ControlElement.CE_PushButtonLabel, opt, painter, this)
        painter.restore()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> {
                skin.clearCache(disposePixmaps = true)
                lastDpr = -1.0
                update()
            }
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

    private fun detectDpr(widget: QWidget?): Double {
        return try {
            currentDpr(widget)
        } catch (_: Throwable) {
            1.0
        }
    }

    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
            update()
        }
    }

    enum class State(val key: String) { Normal("normal"), Pressed("pressed"), Disabled("disabled") }

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

        operator fun invoke(parent: QWidget? = null, block: TPushButton.() -> Unit = {}): TPushButton =
            TPushButton(parent).apply(block)
    }
}
