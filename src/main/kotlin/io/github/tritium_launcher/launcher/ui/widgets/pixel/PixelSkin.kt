package io.github.tritium_launcher.launcher.ui.widgets.pixel

import io.github.tritium_launcher.launcher.currentDpr
import io.qt.gui.*
import io.qt.widgets.QWidget
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** DSL marker for pixel skin builders. */
@DslMarker
annotation class PixelDsl

/** Controls how a skin raster is generated for HiDPI. */
enum class PixelRasterMode {
    /** Render in physical pixels for the target DPR and tag pixmap DPR metadata. */
    DeviceAware,
    /** Render directly in logical pixels (DIP-like sizing). */
    Logical
}

/** Defines named colors for a pixel skin. */
@PixelDsl
class PixelPaletteBuilder {
    private val colors = mutableMapOf<String, QColor>()

    /** Register a palette color by name. */
    fun color(name: String, color: QColor) {
        colors[name] = color
    }

    /** Register a palette color by hex string (e.g. "#ff00ff"). */
    fun color(name: String, hex: String) {
        colors[name] = QColor(hex)
    }

    internal fun build(): Map<String, QColor> = colors.toMap()
}

@PixelDsl
class PixelStateBuilder {
    private val ops = mutableListOf<(PixelDrawScope) -> Unit>()

    /** Add a draw step for this state. */
    fun draw(block: PixelDrawScope.() -> Unit) {
        ops += { scope -> scope.block() }
    }

    internal fun build(): PixelState = PixelState(ops.toList())
}

@PixelDsl
class PixelSkinBuilder {
    /** Base pixel size before DPR scaling. */
    var pixelSize: Int = 2
    /** Rasterization strategy for HiDPI displays. */
    var rasterMode: PixelRasterMode = PixelRasterMode.DeviceAware
    private val palette = PixelPaletteBuilder()
    private val states = mutableMapOf<String, PixelState>()

    /** Define the palette for this skin. */
    fun palette(block: PixelPaletteBuilder.() -> Unit) {
        palette.block()
    }

    /** Define a visual state by name (e.g. "normal", "pressed"). */
    fun state(name: String, block: PixelStateBuilder.() -> Unit) {
        val builder = PixelStateBuilder().apply(block)
        states[name] = builder.build()
    }

    internal fun build(): PixelSkin = PixelSkin(
        pixelSize = pixelSize.coerceAtLeast(1),
        rasterMode = rasterMode,
        palette = palette.build(),
        states = states.toMap()
    )
}

/** Build a pixel skin with a DSL. */
fun pixelSkin(block: PixelSkinBuilder.() -> Unit): PixelSkin =
    PixelSkinBuilder().apply(block).build()

/** Drawing scope for pixel skins. Units are in device pixels. */
class PixelDrawScope internal constructor(
    private val painter: QPainter,
    val width: Int,
    val height: Int,
    val px: Int,
    private val palette: Map<String, QColor>,
    private val paletteBrushes: Map<String, QBrush>
) {
    /** Convert logical units to device pixels. */
    fun u(units: Int): Int = units * px

    /** Get a palette color by name. */
    fun color(name: String): QColor =
        palette[name] ?: throw IllegalArgumentException("Unknown color '$name' in pixel palette")

    /** Fill a rect in device pixels using a color value. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: QColor) {
        painter.fillRect(x, y, w, h, QBrush(color))
    }

    /** Fill a rect in device pixels using a palette color name. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, colorName: String) {
        val brush = paletteBrushes[colorName]
            ?: throw IllegalArgumentException("Unknown color '$colorName' in pixel palette")
        painter.fillRect(x, y, w, h, brush)
    }

    /** Draw a horizontal line using a direct color. */
    fun hLine(x: Int, y: Int, length: Int, color: QColor, thickness: Int = 1) {
        fillRect(x, y, length, thickness, color)
    }

    /** Draw a horizontal line using a palette color name. */
    fun hLine(x: Int, y: Int, length: Int, colorName: String, thickness: Int = 1) {
        fillRect(x, y, length, thickness, colorName)
    }

    /** Draw a vertical line using a direct color. */
    fun vLine(x: Int, y: Int, height: Int, color: QColor, thickness: Int = 1) {
        fillRect(x, y, thickness, height, color)
    }

    /** Draw a vertical line using a palette color name. */
    fun vLine(x: Int, y: Int, height: Int, colorName: String, thickness: Int = 1) {
        fillRect(x, y, thickness, height, colorName)
    }
}

/**
 * Drawing scope that maps a logical grid into the current draw area while preserving aspect ratio.
 */
class PixelGridScope internal constructor(
    private val parent: PixelDrawScope,
    private val gridWidth: Int,
    private val gridHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    offsetX: Int,
    offsetY: Int
) {
    private val mappedX = buildMappedEdges(
        segments = gridWidth,
        targetSize = targetWidth,
        offset = offsetX
    )
    private val mappedY = buildMappedEdges(
        segments = gridHeight,
        targetSize = targetHeight,
        offset = offsetY
    )

    private fun mapX(gridX: Int): Int = mappedX[gridX.coerceIn(0, gridWidth)]
    private fun mapY(gridY: Int): Int = mappedY[gridY.coerceIn(0, gridHeight)]

    private fun buildMappedEdges(segments: Int, targetSize: Int, offset: Int): IntArray {
        if (segments <= 0) return IntArray(1) { offset }

        val widths = IntArray(segments)
        val base = targetSize / segments
        val rem = targetSize - base * segments
        for (i in 0 until segments) {
            widths[i] = base
        }

        if (rem > 0) {
            val favorInterior = base > 0 && segments > 2
            if (favorInterior) {
                val interior = segments - 2
                for (i in 0 until interior) {
                    val prev = (i * rem) / interior
                    val next = ((i + 1) * rem) / interior
                    widths[i + 1] += (next - prev)
                }
            } else {
                for (i in 0 until segments) {
                    val prev = (i * rem) / segments
                    val next = ((i + 1) * rem) / segments
                    widths[i] += (next - prev)
                }
            }
        }

        val mapped = IntArray(segments + 1)
        mapped[0] = offset
        var cursor = offset
        for (i in 0 until segments) {
            cursor += widths[i]
            mapped[i + 1] = cursor
        }
        return mapped
    }

    /** Fill a logical grid rect with a direct color. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: QColor) {
        val px0 = mapX(x)
        val px1 = mapX(x + w)
        val py0 = mapY(y)
        val py1 = mapY(y + h)
        parent.fillRect(
            x = px0,
            y = py0,
            w = (px1 - px0).coerceAtLeast(1),
            h = (py1 - py0).coerceAtLeast(1),
            color = color
        )
    }

    /** Fill a logical grid rect with a palette color name. */
    fun fillRect(x: Int, y: Int, w: Int, h: Int, colorName: String) {
        val px0 = mapX(x)
        val px1 = mapX(x + w)
        val py0 = mapY(y)
        val py1 = mapY(y + h)
        parent.fillRect(
            x = px0,
            y = py0,
            w = (px1 - px0).coerceAtLeast(1),
            h = (py1 - py0).coerceAtLeast(1),
            colorName = colorName
        )
    }

    /** Draw a horizontal line in logical grid units using a direct color. */
    fun hLine(x: Int, y: Int, length: Int, color: QColor, thickness: Int = 1) {
        fillRect(x, y, length, thickness, color)
    }

    /** Draw a horizontal line in logical grid units using a palette color name. */
    fun hLine(x: Int, y: Int, length: Int, colorName: String, thickness: Int = 1) {
        fillRect(x, y, length, thickness, colorName)
    }

    /** Draw a vertical line in logical grid units using a direct color. */
    fun vLine(x: Int, y: Int, height: Int, color: QColor, thickness: Int = 1) {
        fillRect(x, y, thickness, height, color)
    }

    /** Draw a vertical line in logical grid units using a palette color name. */
    fun vLine(x: Int, y: Int, height: Int, colorName: String, thickness: Int = 1) {
        fillRect(x, y, thickness, height, colorName)
    }
}

/**
 * Execute [block] inside a fitted logical grid mapped to this draw scope.
 *
 * The grid keeps aspect ratio and is centered within the current render area.
 */
fun PixelDrawScope.withFittedGrid(
    gridWidth: Int,
    gridHeight: Int,
    snapToIntegerScale: Boolean = false,
    block: PixelGridScope.() -> Unit
) {
    if (gridWidth <= 0 || gridHeight <= 0) return

    val targetWidth: Int
    val targetHeight: Int
    if (snapToIntegerScale) {
        val cell = min(width / gridWidth, height / gridHeight).coerceAtLeast(1)
        targetWidth = (gridWidth * cell).coerceAtLeast(1)
        targetHeight = (gridHeight * cell).coerceAtLeast(1)
    } else {
        if (width * gridHeight > height * gridWidth) {
            targetHeight = height
            targetWidth = ((targetHeight * gridWidth) / gridHeight).coerceAtLeast(1)
        } else {
            targetWidth = width
            targetHeight = ((targetWidth * gridHeight) / gridWidth).coerceAtLeast(1)
        }
    }

    val scope = PixelGridScope(
        parent = this,
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        offsetX = ((width - targetWidth) / 2).coerceAtLeast(0),
        offsetY = ((height - targetHeight) / 2).coerceAtLeast(0)
    )
    scope.block()
}

/**
 * Shared DPR/cache state helper for widgets that render [PixelSkin].
 */
class PixelSkinRenderState {
    private var lastDpr: Double = -1.0

    /**
     * Resolves current DPR for [widget] and clears [skin] cache when DPR changes.
     */
    fun prepare(widget: QWidget?, skin: PixelSkin): Double {
        val dpr = detectWidgetDpr(widget)
        if (lastDpr < 0.0 || abs(lastDpr - dpr) > 0.001) {
            skin.clearCache(disposePixmaps = true)
            lastDpr = dpr
        }
        return dpr
    }

    /**
     * Forces cache invalidation for [skin].
     */
    fun invalidate(skin: PixelSkin) {
        skin.clearCache(disposePixmaps = true)
        lastDpr = -1.0
    }
}

/**
 * Best-effort DPR detection for a widget render context.
 */
fun detectWidgetDpr(widget: QWidget?): Double {
    try {
        val dpr = currentDpr(widget)
        if (dpr > 0.0) return dpr
    } catch (_: Throwable) {
    }

    try {
        val screenDpr = widget?.window()?.devicePixelRatio()
        if (screenDpr != null && screenDpr > 0.0) return screenDpr
    } catch (_: Throwable) {
    }

    try {
        val own = widget?.devicePixelRatio()
        if (own != null && own > 0.0) return own
    } catch (_: Throwable) {
    }

    try {
        val primary = QGuiApplication.primaryScreen()?.devicePixelRatio
        if (primary != null && primary > 0.0) return primary
    } catch (_: Throwable) {
    }

    return 1.0
}

/** A named visual state for a pixel skin. */
class PixelState internal constructor(
    private val ops: List<(PixelDrawScope) -> Unit>
) {
    internal fun draw(scope: PixelDrawScope) {
        ops.forEach { it(scope) }
    }
}

class PixelSkin internal constructor(
    private val pixelSize: Int,
    private val rasterMode: PixelRasterMode,
    private val palette: Map<String, QColor>,
    private val states: Map<String, PixelState>
) {
    private data class CacheKey(
        val state: String,
        val width: Int,
        val height: Int,
        val dprBucket: Int
    )

    private val cache = ConcurrentHashMap<CacheKey, QPixmap>()
    private val paletteBrushes = palette.mapValues { (_, color) -> QBrush(color) }
    private val defaultState = states["normal"] ?: states.values.firstOrNull()

    /**
     * Render the skin for a specific state and size.
     * Results are cached by state, size, and DPR.
     */
    fun render(state: String, width: Int, height: Int, dpr: Double): QPixmap {
        val dprBucket = if (rasterMode == PixelRasterMode.DeviceAware) {
            quantizeDprBucket(dpr)
        } else {
            1
        }
        val key = CacheKey(
            state = state,
            width = width,
            height = height,
            dprBucket = dprBucket
        )
        return cache.computeIfAbsent(key) {
            renderInternal(state, width, height, dprBucket)
        }
    }

    /** Clear the render cache. */
    fun clearCache(disposePixmaps: Boolean = false) {
        if (disposePixmaps) {
            cache.values.forEach { it.dispose() }
        }
        cache.clear()
    }

    /**
     * Render the pixmap as an image to keep it from mutating when changing screens.
     */
    private fun renderInternal(state: String, width: Int, height: Int, dprBucket: Int): QPixmap {
        val quantDpr = dprBucket.toDouble() / DPR_BUCKETS_PER_UNIT
        val renderW: Int
        val renderH: Int
        val px: Int
        when (rasterMode) {
            PixelRasterMode.DeviceAware -> {
                renderW = (width * quantDpr).roundToInt().coerceAtLeast(1)
                renderH = (height * quantDpr).roundToInt().coerceAtLeast(1)
                // Round (not ceil) so fractional DPR does not systematically thicken borders.
                px = max(1, (pixelSize * quantDpr).roundToInt())
            }

            PixelRasterMode.Logical -> {
                renderW = width.coerceAtLeast(1)
                renderH = height.coerceAtLeast(1)
                px = pixelSize.coerceAtLeast(1)
            }
        }

        val img = QImage(renderW, renderH, QImage.Format.Format_ARGB32)
        img.fill(0)

        val painter = QPainter(img)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, false)

        val chosen = states[state] ?: defaultState
        if(chosen != null) {
            val scope = PixelDrawScope(
                painter = painter,
                width = renderW,
                height = renderH,
                px = px,
                palette = palette,
                paletteBrushes = paletteBrushes
            )
            chosen.draw(scope)
        }

        painter.end()
        val pixmap = QPixmap.fromImage(img)
        if (rasterMode == PixelRasterMode.DeviceAware) {
            pixmap.setDevicePixelRatio(quantDpr)
        }
        return pixmap
    }

    private fun quantizeDprBucket(dpr: Double): Int {
        return (dpr * DPR_BUCKETS_PER_UNIT).roundToInt().coerceAtLeast(1)
    }

    private companion object {
        private const val DPR_BUCKETS_PER_UNIT = 1000
    }
}
