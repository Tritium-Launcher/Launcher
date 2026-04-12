package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.referenceWidget
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QWidget
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Default icons used throughout Tritium, including get methods.
 */
object TIcons {
    private val cached = ConcurrentHashMap<String, QIcon>()

    init {
        ThemeMngr.addListener {
            cached.clear()
        }
    }

    val Tritium get() = pix("ui/tritium", 16, 16)

    /* File Icons */
    val File       get() = pix("file/file", 16, 16)
    val Folder     get() = pix("file/folder", 16, 16)
    val CSV        get() = pix("file/csv", 16, 16)
    val HTML       get() = pix("file/html", 16, 16)
    val JavaScript get() = pix("file/javascript", 16, 16)
    val TypeScript get() = pix("file/typescript", 16, 16)
    val Image      get() = pix("file/image", 16, 16)
    val JSON       get() = pix("file/json", 16, 16)
    val TOML       get() = pix("file/toml", 16, 16)
    val Archive    get() = pix("file/archive", 16, 16)
    val Jar        get() = pix("file/jar", 16, 16)
    val Markdown   get() = pix("file/markdown", 16, 16)
    val CSS        get() = pix("file/css", 16, 16)
    val Python     get() = pix("file/py", 16, 16)
    val YAML       get() = pix("file/yaml", 16, 16)
    val NPM        get() = pix("file/npm", 16, 16)
    val Shell      get() = pix("file/shell", 16, 16)
    val Powershell get() = pix("file/powershell", 16, 16)
    val Log        get() = pix("file/log", 16, 16)

    val ModConfig   get() = pix("file/config", 16, 16)
    val TrMeta      get() = pix("file/tr_config", 16, 16)
    val WorldBackup get() = pix("file/world_backup", 16, 16)
    val PlayerData  get() = pix("file/player_data", 16, 16)
    val KubeScript  get() = pix("file/kube", 16, 16)
    val ZenScript   get() = pix("file/zenscript", 16, 16)
    val SessionLock get() = pix("file/lock_file", 16, 16)
    val AnvilRegion get() = pix("file/region_file", 16, 16)
    val McFunction  get() = pix("file/mcfunction", 16, 16)
    val Schematic   get() = pix("file/schematic", 16, 16)
    val NBT         get() = pix("file/nbt", 16, 16)

    // Menu Icons
    val CurseForge get() = pix("ui/curseforge")
    val Modrinth   get() = pix("ui/modrinth")

    val Fabric get() = pix("ui/fabric")
    val NeoForge get() = pix("ui/neoforge")

    val QuestionMark get() = pix("ui/question")

    val NewProject  get() = pix("dashboard/new_project", 32, 32)
    val Import      get() = pix("dashboard/folder_import", 32, 32)
    val Git         get() = pix("dashboard/git", 32, 32)
    val Search      get() = pix("dashboard/search", 32, 32)
    val ListView    get() = pix("dashboard/list_view")
    val GridView    get() = pix("dashboard/grid_view")
    val CompactView get() = pix("dashboard/compact_view")
    val Microsoft   get() = pix("dashboard/microsoft", 32, 32)
    val SmallGrass  get() = pix("dashboard/tiny_grass", 32, 32)

    val Build      get() = pix("menu/build", 16, 16)
    val Run        get() = pix("menu/run", 16, 16)
    val Rerun      get() = pix("menu/rerun", 16, 16)
    val Stop       get() = pix("menu/stop", 16, 16)
    val ForceStop  get() = pix("menu/force_stop", 16, 16)
    val Download   get() = pix("menu/download", 16, 16)

    val Cross          get() = pix("ui/cross", 16, 16)
    val SmallCross     get() = pix("ui/small_cross", 16, 16)
    val SmallArrowDown get() = pix("ui/small_arrow_down", 16, 16)
    val SmallPause     get() = pix("ui/small_pause", 16, 16)
    val SmallPlay      get() = pix("ui/small_play", 16, 16)
    val SmallMenu      get() = pix("ui/small_menu", 16, 16)

    private fun icon(keyOrPath: String, width: Int? = null, height: Int? = null): QIcon {
        val dpr = try {
            currentDpr(referenceWidget)
        } catch (_: Throwable) { 1.0 }

        val baseW = width ?: 16
        val baseH = height ?: baseW

        val physW = ceil(baseW * dpr).toInt().coerceAtLeast(1)
        val physH = ceil(baseH * dpr).toInt().coerceAtLeast(1)

        val dprKey = String.format("%.3f", dpr)
        val cacheKey = "$keyOrPath|${baseW}x${baseH}|${physW}x${physH}@$dprKey"

        return cached.computeIfAbsent(cacheKey) {
            ThemeMngr.getIcon(keyOrPath, baseW, baseH, dpr)
                ?: run {
                    val normalized = if(keyOrPath.startsWith("/")) keyOrPath else "/$keyOrPath"
                    val url = this::class.java.getResource(normalized) ?: this::class.java.classLoader.getResource(keyOrPath)
                    if(url != null) {
                        val pix = QPixmap(url.toString())
                        if(!pix.isNull) {
                            val scaled = pix.scaled(qs(physW, physH), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                            try { scaled.setDevicePixelRatio(dpr) } catch (_: Throwable) {}
                            QIcon(scaled)
                        } else QIcon()
                    } else QIcon()
                }
        }
    }

    private fun pix(keyOrPath: String, width: Int = 16, height: Int = 16, useDpr: Boolean = true): QPixmap {
        if(!useDpr) {
            val icon = ThemeMngr.getIcon(keyOrPath, width, height, 1.0) ?: return QPixmap()
            return icon.pixmap(width, height)
        }

        val dpr = try {
            currentDpr(referenceWidget)
        } catch (_: Throwable) { 1.0 }

        val icon = ThemeMngr.getIcon(keyOrPath, width, height, dpr) ?: return QPixmap()

        return icon.pixmap(width, height)
    }

    fun debugIcon(keyOrPath: String, baseW: Int, baseH: Int, widget: QWidget?) {
        val dpr = try { currentDpr(widget) } catch (_: Throwable) { 1.0 }
        val physW = ceil(baseW * dpr).toInt().coerceAtLeast(1)
        val physH = ceil(baseH * dpr).toInt().coerceAtLeast(1)
        println("DEBUG ICON: key=$keyOrPath dpr=$dpr base=${baseW}x$baseH phys=${physW}x${physH}")

        val ic = ThemeMngr.getIcon(keyOrPath, baseW, baseH, dpr)
        println("  ThemeMngr.getIcon -> ${if (ic == null) "null" else "icon (isNull=${ic.isNull})"}")
        ic?.let {
            val pm = it.pixmap(baseW, baseH)
            println("  icon.pixmap(logical) -> isNull=${pm.isNull} size=${pm.width()}x${pm.height()} dpr=${try { pm.devicePixelRatio() } catch(_: Throwable) { "?" }}")
        }
    }

    internal val defaultProjectIcon: String by lazy {
        resolveFileResource("/icons/folder.png")
            ?: renderFolderIconToTempPng()
            ?: ""
    }

    private fun resolveFileResource(path: String): String? {
        val url = javaClass.getResource(path) ?: return null
        return try {
            if(url.protocol == "file") File(url.toURI()).absolutePath else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun renderFolderIconToTempPng(): String? {
        return try {
            val pix = ThemeMngr.getIcon("file/folder", 16, 16, 1.0)?.pixmap(16, 16)
            if(pix == null || pix.isNull) {
                null
            } else {
                val temp = Files.createTempFile("tritium-default-folder-", ".png").toFile()
                temp.deleteOnExit()
                if(pix.save(temp.absolutePath, "PNG")) temp.absolutePath else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
