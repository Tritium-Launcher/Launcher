/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.api.currentDpr
import io.github.tritium_launcher.launcher.ui.theme.TCol
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.widgets.pixel.PixelSkin
import io.github.tritium_launcher.launcher.ui.widgets.pixel.pixelSkin
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.gui.*
import io.qt.widgets.QPushButton
import io.qt.widgets.QStyle
import io.qt.widgets.QStyleOptionButton
import io.qt.widgets.QWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Minecraft-styled push button.
 *
 * @param parent Parent widget.
 * @param tint Optional hex color string to tint the button (e.g. [TColors.Green]).
 *   Preserves the original button's lightness gradients while applying the tint's
 *   hue and saturation.
 */
class TPushButton(
    parent: QWidget? = null,
    tint: String? = null
) : QPushButton(parent) {
    private var lastDpr: Double = -1.0

    /**
     * Optional hex color string to tint the button.
     * Preserves lightness from the theme button colors while applying this color's
     * hue and saturation.
     */
    var tint: String? = tint
        set(value) {
            if (field != value) {
                field = value
                update()
            }
        }

    fun setTint(col: TCol?) {
        tint = col?.value
        update()
    }

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

        val dpr = currentDpr(this)
        handleDprChange(dpr)

        val s = if (tint != null) getTintedSkin(tint!!) else skin
        val bg = s.render(state.key, w, h, dpr)

        if(!bg.isNull) {
            painter.drawPixmap(0, 0, bg)
        }

        drawLabel(painter, dpr)

        painter.end()
    }

    /**
     * Draw standard Qt button label on top of sprite
     */
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
                tintedSkins.values.forEach { it.clearCache(disposePixmaps = true) }
                tintedSkins.clear()
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

    /**
     * When DPR changes, update button to new values
     */
    private fun handleDprChange(dpr: Double) {
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            tintedSkins.values.forEach { it.clearCache(disposePixmaps = true) }
            lastDpr = dpr
            update()
        }
    }

    /**
     * Button states
     */
    enum class State(val key: String) { Normal("normal"), Pressed("pressed"), Disabled("disabled") }

    companion object {
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var skin = buildSkin()
        private val tintedSkins = mutableMapOf<String, PixelSkin>()

        init {
            scope.launch {
                ThemeMngr.currentColorThemeId.collect {
                    val prev = skin
                    skin = buildSkin()
                    prev.clearCache(disposePixmaps = true)
                    tintedSkins.values.forEach { it.clearCache(disposePixmaps = true) }
                    tintedSkins.clear()
                }
            }
        }

        /**
         * Return the tinted skin for [tintHex], building it on first use.
         */
        private fun getTintedSkin(tintHex: String): PixelSkin =
            tintedSkins.getOrPut(tintHex) { buildTintedSkin(tintHex) }

        /**
         * Apply [tintHex] hue and saturation to each button color while preserving
         * the original lightness.
         */
        private fun buildTintedSkin(tintHex: String) = pixelSkin {
            pixelSize = 2
            palette {
                color("border", tintColor(TColors.Button0, tintHex))
                color("shadow", tintColor(TColors.Button1, tintHex))
                color("primary", tintColor(TColors.Button2, tintHex))
                color("bright", tintColor(TColors.Button3, tintHex))
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

        /**
         * Replace the lightness of [originalHex] with the hue+saturation of [tintHex].
         */
        private fun tintColor(originalHex: String, tintHex: String): String {
            val src = QColor(originalHex)
            val t = QColor(tintHex)
            val l = src.lightness()
            val h = t.hslHue()
            val s = t.hslSaturation()
            val effectiveHue = if (h < 0) src.hslHue() else h
            return QColor.fromHsl(effectiveHue, s, l).name()
        }

        private fun tintColor(originalHex: String, tintCol: TCol): String = tintColor(originalHex, tintCol.value)
        private fun tintColor(originalCol: TCol, tintCol: TCol): String = tintColor(originalCol.value, tintCol.value)
        private fun tintColor(originalCol: TCol, tintHex: String): String = tintColor(originalCol.value, tintHex)

        /**
         * Build Sprite
         */
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

        operator fun invoke(parent: QWidget? = null, tint: String? = null, block: TPushButton.() -> Unit = {}): TPushButton =
            TPushButton(parent, tint).apply(block)
        @JvmName("invokeTCol")
        operator fun invoke(parent: QWidget? = null, tint: TCol, block: TPushButton.() -> Unit = {}): TPushButton = invoke(parent, tint.value, block)
    }
}
