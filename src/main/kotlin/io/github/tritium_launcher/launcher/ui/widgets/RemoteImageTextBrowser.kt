package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.NonNull
import io.qt.core.QUrl
import io.qt.core.Qt
import io.qt.gui.QPixmap
import io.qt.widgets.QTextBrowser
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Used to render Images in rich text environments
 */
class RemoteImageTextBrowser(
    private val fetchBytes: suspend (String) -> ByteArray
): QTextBrowser() {

    private val imageCache = ConcurrentHashMap<String, ByteArray>()
    private val pixmapCache = ConcurrentHashMap<String, QPixmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentHtml: String = ""

    private val imgTagRegex = Regex("""(?i)<img\b[^>]*>""")
    private val dimAttrRegex = Regex("""\s+(?:width|height)\s*=\s*["'][^"']*["']""")

    init {
        destroyed.connect { scope.cancel() }
    }

    fun setHtmlContent(html: String) {
        val cleaned = html.replace(imgTagRegex) { match ->
            match.value.replace(dimAttrRegex, "")
        }
        currentHtml = cleaned
        setHtml(cleaned)
    }

    override fun loadResource(type: Int, url: @NonNull QUrl): Any? {
        if(type != 2) return super.loadResource(type, url)
        val urlStr = url.toString()
        if(!urlStr.startsWith("http")) return super.loadResource(type, url)

        val cachedPixmap = pixmapCache[urlStr]
        if(cachedPixmap != null) return cachedPixmap

        val cached = imageCache[urlStr]
        if(cached != null) {
            val pixmap = QPixmap()
            if (pixmap.loadFromData(cached)) {
                val vp = viewport()
                val maxW = (vp?.width()?.coerceAtLeast(100) ?: 600) - 10
                val maxH = ((vp?.height()?.coerceAtLeast(100) ?: 600) * 0.6).toInt().coerceAtMost(400).coerceAtLeast(200)
                val scaled = if (pixmap.width() > maxW || pixmap.height() > maxH) {
                    pixmap.scaled(maxW, maxH, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                } else pixmap
                pixmapCache[urlStr] = scaled
                return scaled
            }
            return pixmap
        }

        if(pending.add(urlStr)) {
            scope.launch {
                val bytes = runCatching { fetchBytes(urlStr) }.getOrNull()
                pending.remove(urlStr)
                if(bytes != null && bytes.isNotEmpty()) {
                    imageCache[urlStr] = bytes
                    runOnGuiThread {
                        val bar = verticalScrollBar()
                        val pos = bar?.value ?: 0
                        html = currentHtml
                        bar?.value = pos
                    }
                }
            }
        }

        return null
    }
}
