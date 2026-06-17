package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.launcher.io.VPath
import io.qt.core.*
import io.qt.gui.*
import io.qt.widgets.QApplication
import io.qt.widgets.QWidget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.reflect.KClass
/**
 * Shortens the [System.getProperty] call
 */
fun getProperty(key: String): String {
    return System.getProperty(key)
}

/**
 * Shortens the [System.getenv] call
 */
fun getEnv(key: String): String? {
    return System.getenv(key)
}

/**
 * Returns the user home directory
 */
val userHome: VPath get() = VPath.get(System.getProperty("user.home"))

/**
 * Returns [File] from ~/
 */
fun fromHome(vararg child: String): VPath {
    return VPath.get(getProperty("user.home"), *child)
}

/**
 * Returns [File] from ~/tritium
 */
fun fromTRFile(vararg child: String = arrayOf("")): File {
    return VPath.get(userHome, "tritium", *child).toJFile()
}

/**
 * Returns [VPath] from ~/tritium using VPath
 */
fun fromTR(first: VPath, vararg rest: VPath): VPath {
    return VPath.get(userHome, VPath.parse("tritium"), first, *rest)
}

/**
 * Returns [VPath] from ~/tritium using String
 */
fun fromTR(first: String, vararg rest: String): VPath {
    return VPath.get(userHome, "tritium", first, *rest)
}

/**
 * Returns [VPath] to ~/tritium
 */
fun fromTR(): VPath {
    return VPath.get(userHome, "tritium")
}

/**
 * Returns the current screen's DPR value
 */
fun currentDpr(widget: QWidget?): Double {
    val app = QApplication.instance()
    val guiThread = app?.thread()
    if (app != null && guiThread != null && guiThread != QThread.currentThread()) {
        var result = 1.0
        try {
            QMetaObject.invokeMethod(
                app,
                { result = currentDprOnGui(widget) },
                Qt.ConnectionType.BlockingQueuedConnection
            )
            return result
        } catch (_: Throwable) {
            return 1.0
        }
    }

    return currentDprOnGui(widget)
}

/**
 * Get DPR Value from several sources
 */
private fun currentDprOnGui(widget: QWidget?): Double {
    if (widget != null) {
        widget.window()?.windowHandle()?.devicePixelRatio()?.let { dpr ->
            if (dpr > 0.0) return dpr
        }

        try {
            val widgetDpr = widget.devicePixelRatioF()
            if (widgetDpr > 0.0) return widgetDpr
        } catch (_: NoSuchMethodError) {
        }

        if (widget.isVisible && widget.width() > 0 && widget.height() > 0) {
            val center = widget.mapToGlobal(QPoint(widget.width() / 2, widget.height() / 2))
            QGuiApplication.screenAt(center)?.devicePixelRatio?.let { sDpr ->
                if (sDpr > 0.0) return sDpr
            }
        } else {
            try {
                val probe = widget.mapToGlobal(QPoint(0, 0))
                QGuiApplication.screenAt(probe)?.devicePixelRatio?.let { sDpr ->
                    if (sDpr > 0.0) return sDpr
                }
            } catch (_: RuntimeException) {
            }
        }
    }

    // 4) Primary screen fallback
    QGuiApplication.primaryScreen()?.devicePixelRatio?.takeIf { it > 0.0 }?.let { return it }

    // Last resort
    return 1.0
}

/**
 * Get a [QIcon] resource from classLoader
 */
fun resourceIcon(resource: String, classLoader: ClassLoader): QIcon? {
    val stream = classLoader.getResourceAsStream(resource) ?: run {
        mainLogger.warn("Icon resource not found: $resource")
        return null
    }

    stream.use {
        val data = it.readBytes()
        val pixmap = QPixmap()
        if (!pixmap.loadFromData(data)) {
            mainLogger.warn("Failed to load icon pixmap from data: $resource (bytes=${data.size})")
            return null
        }
        return QIcon(pixmap)
    }
}

/**
 * Makes a quick [Logger] with the name of the class it is created in.
 */
fun Any.logger(): Logger {
    return LoggerFactory.getLogger(this::class.java)
}

/**
 * Makes a [Logger] using string for name
 */
fun logger(name: String): Logger = LoggerFactory.getLogger(name)

/**
 * Makes a [Logger] using [KClass] for full qualifier name
 */
fun logger(any: KClass<*>): Logger = LoggerFactory.getLogger(any.java)

/**
 * Format a duration in milliseconds as "m s ms", omitting larger units when zero.
 */
fun formatDurationMs(totalMs: Long): String {
    if (totalMs < 1000) return "$totalMs ms"
    val minutes = totalMs / 60000
    val seconds = (totalMs % 60000) / 1000
    val ms = totalMs % 1000
    return if (minutes > 0) {
        "$minutes m $seconds s $ms ms"
    } else {
        "$seconds s $ms ms"
    }
}

/**
 * Redacts the local user-home prefix to "~/".
 */
fun String.redactUserPath(): String {
    val home = System.getProperty("user.home")?.trim().orEmpty().trimEnd('/', '\\')
    if (home.isEmpty()) return this

    val variants = linkedSetOf(
        home,
        home.replace('\\', '/'),
        home.replace('/', '\\')
    )

    var out = this
    variants.forEach { candidate ->
        if (candidate.isNotEmpty()) {
            out = out.replace(candidate, "~")
        }
    }
    return out.replace("~\\", "~/")
}

/**
 * Applies built-in log sanitization (user-home path only).
 */
fun String.sanitizeForLogs(): String = redactUserPath()

/**
 * TODO: Probably needs to be removed, it's unnecessary
 */
fun qs(w: Int, h: Int = -1): QSize = if(h == -1) QSize(w,w) else QSize(w, h)

/**
 * Get active window
 */
val activeWindow: QWidget?
    get() {
        if(QApplication.instance() != null) return QApplication.activeWindow()
        return null
    }

/**
 * Load [QPixmap] from filesystem with quality scaling
 */
fun loadScaledPixmap(path: String, target: QSize, dprWidgetRef: QWidget? = null): QPixmap = try {
    loadScaledPixmap(QImage(path), target, dprWidgetRef)
} catch (_: Throwable) {
    QPixmap()
}

/**
 * Scale [QImage] to [target] size with quality scaling (DPR-aware, Fast on upscale / Smooth on downscale)
 */
fun loadScaledPixmap(img: QImage, target: QSize, dprWidgetRef: QWidget? = null): QPixmap {
    if (img.isNull) return QPixmap()

    val dpr = dprWidgetRef?.window()?.windowHandle()?.screen()?.devicePixelRatio
        ?: QGuiApplication.primaryScreen()?.devicePixelRatio()
        ?: 1.0

    val scaleUp = target.width() * dpr > img.width() || target.height() * dpr > img.height()
    val mode = if (scaleUp) Qt.TransformationMode.FastTransformation else Qt.TransformationMode.SmoothTransformation

    val scaledImg = img.scaled(
        kotlin.math.ceil(target.width() * dpr).toInt(),
        kotlin.math.ceil(target.height() * dpr).toInt(),
        Qt.AspectRatioMode.KeepAspectRatio,
        mode
    )
    var pix = QPixmap.fromImage(scaledImg)
    pix.setDevicePixelRatio(dpr)

    val logicalWidth = pix.width() / dpr
    val logicalHeight = pix.height() / dpr
    if (logicalWidth < target.width() || logicalHeight < target.height()) {
        pix = pix.scaled(target, Qt.AspectRatioMode.KeepAspectRatio, mode)
        pix.setDevicePixelRatio(1.0)
    }
    return pix
}

fun QPixmap.applyRainbowOverlay(targetSize: QSize = QSize(256, 256), opacity: Float = 1f): QPixmap {
    val scaled = scaled(targetSize, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation)
    val base = scaled.toImage().convertToFormat(QImage.Format.Format_ARGB32)
    val size = base.size()

    var minX = size.width();  var maxX = 0
    var minY = size.height(); var maxY = 0

    for (y in 0 until size.height()) {
        for (x in 0 until size.width()) {
            val a = (base.pixel(x, y) ushr 24) and 0xFF
            if (a > 0) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }

    val gradientImg = QImage(size, QImage.Format.Format_ARGB32)
    gradientImg.fill(0x00000000)

    val gx1 = minX.toDouble()
    val gy1 = minY.toDouble()
    val gx2 = maxX.toDouble()
    val gy2 = minY + (maxY - minY) * 0.67

    val axisX = gx2 - gx1
    val axisY = gy2 - gy1
    val axisLen2 = axisX * axisX + axisY * axisY

    data class Stop(val t: Float, val hue: Int, val lightness: Int)
    val stops = listOf(
        Stop(0.000f,   0, 128),
        Stop(0.166f,  30, 128),
        Stop(0.333f,  56, 128),
        Stop(0.500f, 130,  64),
        Stop(0.666f, 240, 128),
        Stop(1.000f, 300, 128),
    )

    fun interpolateStop(t: Float): Pair<Int, Int> {
        val clamped = t.coerceIn(0f, 1f)
        val i = stops.indexOfLast { it.t <= clamped }.coerceAtLeast(0)
        val s0 = stops[i]
        val s1 = stops.getOrNull(i + 1) ?: return s0.hue to s0.lightness
        val f = (clamped - s0.t) / (s1.t - s0.t)
        var dh = s1.hue - s0.hue
        if (dh > 180) dh -= 360
        if (dh < -180) dh += 360
        val hue = ((s0.hue + dh * f).toInt() + 360) % 360
        val lightness = (s0.lightness + (s1.lightness - s0.lightness) * f).toInt()
        return hue to lightness
    }

    val result = QImage(size, QImage.Format.Format_ARGB32)
    result.fill(0x00000000)

    for(y in 0 until size.height()) {
        for(x in 0 until size.width()) {
            val raw = base.pixel(x, y)
            val a = (raw ushr 24) and 0xFF
            if (a == 0) continue
            val baseColor = QColor(
                (raw ushr 16) and 0xFF,
                (raw ushr 8)  and 0xFF,
                raw          and 0xFF,
                a
            )
            val px = x - gx1
            val py = y - gy1
            val t = ((px * axisX + py * axisY) / axisLen2).toFloat()
            val (hue, _) = interpolateStop(t)
            val rainbowColor = QColor.fromHsl(hue, 255, baseColor.lightness(), a)
            val blended = if (opacity >= 1f) rainbowColor else QColor(
                (baseColor.red()   + (rainbowColor.red()   - baseColor.red())   * opacity).toInt(),
                (baseColor.green() + (rainbowColor.green() - baseColor.green()) * opacity).toInt(),
                (baseColor.blue()  + (rainbowColor.blue()  - baseColor.blue())  * opacity).toInt(),
                a,
            )
            result.setPixel(x, y, blended.rgba())
        }
    }

    return QPixmap.fromImage(result)
}