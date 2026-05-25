package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.NonNull
import io.qt.core.QUrl
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
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentHtml: String = ""

    init {
        destroyed.connect { scope.cancel() }
    }

    fun setHtmlContent(html: String) {
        currentHtml = html
        setHtml(html)
    }

    override fun loadResource(type: Int, url: @NonNull QUrl): Any? {
        if(type != 2) return super.loadResource(type, url)
        val urlStr = url.toString()
        if(!urlStr.startsWith("http")) return super.loadResource(type, url)

        val cached = imageCache[urlStr]
        if(cached != null) {
            val pixmap = QPixmap()
            pixmap.loadFromData(cached)
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