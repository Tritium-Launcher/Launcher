package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.widgets.pixel.PixelDrawScope
import io.github.tritium_launcher.launcher.ui.widgets.pixel.PixelSkinRenderState
import io.github.tritium_launcher.launcher.ui.widgets.pixel.pixelSkin
import io.github.tritium_launcher.launcher.ui.widgets.pixel.withFittedGrid
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.widgets.QSizePolicy
import io.qt.widgets.QWidget

/**
 * Minecraft-styled on/off switch widget.
 * Emits [toggled] when the checked state changes.
 */
class TToggleSwitch(parent: QWidget? = null) : QWidget(parent) {
    var isChecked: Boolean = false
        private set
    private val renderState = PixelSkinRenderState()

    val toggled = Signal1<Boolean>()

    init {
        focusPolicy = Qt.FocusPolicy.StrongFocus
        minimumSize = qs(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
    }

    /**
     * Update checked state and repaint if it changed.
     */
    fun setChecked(checked: Boolean) {
        if (isChecked == checked) return
        isChecked = checked
        toggled.emit(isChecked)
        update()
    }

    /**
     * Toggle checked state.
     */
    fun toggle() {
        setChecked(!isChecked)
    }

    override fun mousePressEvent(event: @Nullable QMouseEvent?) {
        if (event?.button() == Qt.MouseButton.LeftButton) {
            toggle()
        }
        super.mousePressEvent(event)
    }

    override fun keyPressEvent(event: @Nullable QKeyEvent?) {
        if (event?.key() == Qt.Key.Key_Space.value() || event?.key() == Qt.Key.Key_Return.value()) {
            toggle()
            return
        }
        super.keyPressEvent(event)
    }

    override fun paintEvent(event: QPaintEvent?) {
        val painter = QPainter(this)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, false)

        val state = when {
            !isEnabled && isChecked -> State.DisabledOn
            !isEnabled -> State.DisabledOff
            isChecked -> State.On
            else -> State.Off
        }

        val dpr = renderState.prepare(this, skin)

        val bg = skin.render(state.key, width(), height(), dpr)
        if (!bg.isNull) {
            painter.drawPixmap(0, 0, bg)
        }

        painter.end()
    }

    override fun changeEvent(e: @Nullable QEvent?) {
        super.changeEvent(e)
        when (e?.type()) {
            QEvent.Type.StyleChange,
            QEvent.Type.PaletteChange,
            QEvent.Type.DevicePixelRatioChange,
            QEvent.Type.ScreenChangeInternal -> {
                renderState.invalidate(skin)
                update()
            }

            else -> {}
        }
    }

    override fun event(e: @Nullable QEvent?): Boolean {
        if (e?.type() == QEvent.Type.ScreenChangeInternal || e?.type() == QEvent.Type.DevicePixelRatioChange) {
            renderState.prepare(this, skin)
        }
        return super.event(e)
    }

    override fun moveEvent(event: @Nullable QMoveEvent?) {
        super.moveEvent(event)
        renderState.prepare(this, skin)
    }

    override fun showEvent(event: @Nullable QShowEvent?) {
        super.showEvent(event)
        renderState.prepare(this, skin)
    }

    override fun sizeHint(): QSize = qs(DEFAULT_WIDTH, DEFAULT_HEIGHT)

    private enum class State(val key: String) {
        Off("off"),
        On("on"),
        DisabledOff("disabled_off"),
        DisabledOn("disabled_on")
    }

    companion object {
        private const val SPRITE_WIDTH = 20
        private const val SPRITE_HEIGHT = 12
        private const val DEFAULT_WIDTH = 40
        private const val DEFAULT_HEIGHT = 24

        private var skin = buildSkin()

        init {
            ThemeMngr.addListener {
                val prev = skin
                skin = buildSkin()
                prev.clearCache(disposePixmaps = true)
            }
        }

        private fun buildSkin() = pixelSkin {
            pixelSize = 1

            val green = ThemeMngr.getQColor("Green") ?: QColor("#3A8325")
            val greenLight = QColor(green).lighter(122)
            val greenDisabled = QColor(green).darker(145)
            val greenLightDisabled = QColor(greenDisabled).lighter(112)

            palette {
                color("transparent", QColor(0, 0, 0, 0))
                color("border", TColors.Button0)
                color("surfaceLight", TColors.Button3)
                color("surfaceMid", TColors.Button2)
                color("surfaceLift", QColor(TColors.Button2).lighter(112))
                color("surfaceShadow", TColors.Button1)
                color("surfaceShadowDark", QColor(TColors.Button1).darker(140))
                color("shine", TColors.SelectedText)

                color("disabledBorder", TColors.ButtonDisabled1)
                color("disabledLight", TColors.ButtonDisabled1)
                color("disabledMid", TColors.ButtonDisabled0)
                color("disabledShadow", QColor(TColors.ButtonDisabled0).darker(130))

                color("green", green)
                color("greenLight", greenLight)
                color("greenDisabled", greenDisabled)
                color("greenLightDisabled", greenLightDisabled)
            }

            state("off") {
                draw { drawSpriteRuns(OFF_RUNS, OFF_COLORS) }
            }

            state("on") {
                draw { drawSpriteRuns(ON_RUNS, ON_COLORS) }
            }

            state("disabled_off") {
                draw { drawSpriteRuns(OFF_RUNS, OFF_DISABLED_COLORS) }
            }

            state("disabled_on") {
                draw { drawSpriteRuns(ON_RUNS, ON_DISABLED_COLORS) }
            }
        }

        // Exact ON sprite shape from provided reference.
        private val ON_ROWS = arrayOf(
            "00000000011111111111",
            "00000000012222222221",
            "11111111112333333321",
            "14444444412333333321",
            "14555555412333333321",
            "14556555412333333321",
            "14556555412333333321",
            "14556555412333333321",
            "14556555412222222221",
            "14555555417777777771",
            "14444444417777777771",
            "11111111111111111111"
        )

        // Exact OFF sprite shape from provided reference.
        private val OFF_ROWS = arrayOf(
            "00000000000111111111",
            "02222222220333333333",
            "02444444420000000000",
            "02444444420555555550",
            "02444444420566666650",
            "02444444420566776650",
            "02444444420567667650",
            "02444444420567667650",
            "02222222220566776650",
            "06666666660566666650",
            "06666666660555555550",
            "00000000000000000000"
        )

        private data class HorizontalRun(
            val y: Int,
            val xStart: Int,
            val xEndExclusive: Int,
            val symbol: Char
        )

        private val ON_RUNS = compileRuns(ON_ROWS)
        private val OFF_RUNS = compileRuns(OFF_ROWS)

        private val ON_COLORS = mapOf(
            '0' to "transparent",
            '1' to "border",
            '2' to "surfaceLight",
            '3' to "surfaceMid",
            '4' to "greenLight",
            '5' to "green",
            '6' to "shine",
            '7' to "surfaceShadow"
        )

        private val OFF_COLORS = mapOf(
            '0' to "border",
            '1' to "transparent",
            '2' to "surfaceLight",
            '3' to "transparent",
            '4' to "surfaceMid",
            '5' to "surfaceLift",
            '6' to "surfaceShadow",
            '7' to "surfaceShadowDark"
        )

        private val ON_DISABLED_COLORS = mapOf(
            '0' to "transparent",
            '1' to "disabledBorder",
            '2' to "disabledLight",
            '3' to "disabledMid",
            '4' to "greenLightDisabled",
            '5' to "greenDisabled",
            '6' to "disabledLight",
            '7' to "disabledShadow"
        )

        private val OFF_DISABLED_COLORS = mapOf(
            '0' to "disabledBorder",
            '1' to "transparent",
            '2' to "disabledLight",
            '3' to "transparent",
            '4' to "disabledMid",
            '5' to "disabledLight",
            '6' to "disabledShadow",
            '7' to "disabledShadow"
        )

        private fun compileRuns(rows: Array<String>): List<HorizontalRun> {
            val runs = ArrayList<HorizontalRun>(rows.size * 8)
            rows.forEachIndexed { y, row ->
                if (row.isEmpty()) return@forEachIndexed
                var x = 0
                while (x < row.length) {
                    val symbol = row[x]
                    var end = x + 1
                    while (end < row.length && row[end] == symbol) end++
                    runs += HorizontalRun(
                        y = y,
                        xStart = x,
                        xEndExclusive = end,
                        symbol = symbol
                    )
                    x = end
                }
            }
            return runs
        }
        
        private fun PixelDrawScope.drawSpriteRuns(
            runs: List<HorizontalRun>,
            symbolColors: Map<Char, String>
        ) {
            withFittedGrid(SPRITE_WIDTH, SPRITE_HEIGHT, snapToIntegerScale = true) {
                runs.forEach { run ->
                    val colorName = symbolColors[run.symbol] ?: return@forEach
                    if (colorName == "transparent") return@forEach
                    hLine(
                        x = run.xStart,
                        y = run.y,
                        length = (run.xEndExclusive - run.xStart).coerceAtLeast(1),
                        colorName = colorName
                    )
                }
            }
        }
    }
}
