package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.launcher.io.VPath
import io.qt.core.*
import io.qt.gui.QGuiApplication
import io.qt.gui.QIcon
import io.qt.gui.QImage
import io.qt.gui.QPixmap
import io.qt.widgets.QApplication
import io.qt.widgets.QWidget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.reflect.KClass

private val logger = LoggerFactory.getLogger("GlobalFunctions") //TODO: Make a central io.github.tritium_launcher.launcher.logger for things like this and others
val koinLogger: Logger = LoggerFactory.getLogger("Koin")

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

fun logger(name: String): Logger = LoggerFactory.getLogger(name)
fun logger(any: KClass<*>): Logger = LoggerFactory.getLogger(any.java)

fun compareMCVersions(ver1: String, ver2: String): Boolean {
    fun parse(v: String): Triple<Int, Int, Int> {
        val parts = v.split('.')
        val major = parts[0].toInt()
        val minor = parts.getOrNull(1)?.toInt() ?: 0
        val patch = parts.getOrNull(2)?.toInt() ?: 0
        return Triple(major, minor, patch)
    }

    val (_, min1, pat1) = parse(ver1)
    val (_, min2, pat2) = parse(ver2)

    return when {
        min1 > min2 -> true
        min1 < min2 -> false
        pat1 >= pat2 -> true
        else -> false
    }
}

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

fun qs(w: Int, h: Int = -1): QSize = if(h == -1) QSize(w,w) else QSize(w, h)

val activeWindow: QWidget?
    get() {
        if(QApplication.instance() != null) return QApplication.activeWindow()
        return null
    }

fun loadScaledPixmap(path: String, target: QSize, dprWidgetRef: QWidget? = null): QPixmap = try {
    val img = QImage(path)
    if (img.isNull) {
        QPixmap()
    } else {
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
        pix
    }
} catch (_: Throwable) {
    QPixmap()
}
