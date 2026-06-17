package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.launcher.currentDpr
import io.github.tritium_launcher.launcher.referenceWidget
import io.github.tritium_launcher.launcher.ui.theme.TIcons.pix
import io.qt.gui.QPixmap
import java.io.File
import java.nio.file.Files


/**
 * Default icons used throughout Tritium, including get methods.
 */
object TIcons {

    val Tritium get() = pix("ui/tritium", 16, 16)
    val TritiumGrayscale get() = pix("ui/tritium_grayscale", 16, 16)

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

    /* Menu Icons */

    val CurseForge get() = pix("ui/curseforge", 16, 16)
    val Modrinth   get() = pix("ui/modrinth", 64, 64)

    /** Key constants for account service icons — pass to [pix] with a target size. */
    const val CURSEFORGE = "ui/curseforge"
    const val MODRINTH = "ui/modrinth"
    const val MICROSOFT = "dashboard/microsoft"

    val Fabric   get() = pix("ui/fabric", 16, 16)
    val NeoForge get() = pix("ui/neoforge", 16, 16)

    val Prism    get() = pix("ui/prism_launcher", 16, 16)
    val GDL      get() = pix("ui/gd_launcher", 16, 16)
    val ATL      get() = pix("ui/at_launcher", 16, 16)
    val CFPack   get() = pix("ui/curseforge_pack", 16, 16)
    val MRPack   get() = pix("ui/modrinth_pack", 16, 16)

    val QuestionMark get() = pix("ui/question", 16, 16)
    val Unknown      get() = pix("ui/unknown_question", 16, 16)

    internal val Companion get() = pix("ui/companion_mod", 16, 16)

    val NewProject  get() = pix("dashboard/new_project", 32, 32)
    val Import      get() = pix("dashboard/folder_import", 32, 32)
    val Git         get() = pix("dashboard/git", 32, 32)
    val Search      get() = pix("dashboard/search", 32, 32)
    val ListView    get() = pix("dashboard/list_view", 16, 16)
    val GridView    get() = pix("dashboard/grid_view", 16, 16)
    val CompactView get() = pix("dashboard/compact_view", 16, 16)
    val Microsoft   get() = pix("dashboard/microsoft", 64, 64)
    val SmallGrass  get() = pix("dashboard/tiny_grass", 32, 32)

    val Build      get() = pix("menu/build", 12, 12)
    val Run        get() = pix("menu/run", 12, 12)
    val Rerun      get() = pix("menu/rerun", 12, 12)
    val Stop       get() = pix("menu/stop", 12, 12)
    val ForceStop  get() = pix("menu/force_stop", 12, 12)
    val Download   get() = pix("menu/download", 12, 12)
    val Settings   get() = pix("menu/settings", 12, 12)

    val Cross          get() = pix("ui/cross", 16, 16)
    val SmallCross     get() = pix("ui/small_cross", 16, 16)
    val SmallArrowDown get() = pix("ui/small_arrow_down", 16, 16)
    val SmallPause     get() = pix("ui/small_pause", 16, 16)
    val SmallPlay      get() = pix("ui/small_play", 16, 16)
    val SmallMenu      get() = pix("ui/small_menu", 16, 16)
    val ExternalArrow  get() = pix("ui/external_arrow", 12, 12)

    val ItemBrowser    get() = pix("ui/item_browser", 16, 16)

    /**
     * Creates a [QPixmap] from specified Icon paths
     */
    private fun pix(keyOrPath: String, width: Int, height: Int, useDpr: Boolean = true): QPixmap {
        val dpr = if (useDpr) {
            try { currentDpr(referenceWidget) } catch (_: Throwable) { 1.0 }
        } else 1.0

        return ThemeMngr.getPixmap(keyOrPath, width, height, dpr) ?: QPixmap()
    }

    fun pixForKey(key: String, width: Int, height: Int) = pix(key, width, height)

    /** Render a themed icon at the exact target [size] (square). */
    fun pix(key: String, size: Int): QPixmap {
        val dpr = try { currentDpr(referenceWidget) } catch (_: Throwable) { 1.0 }
        return ThemeMngr.getPixmap(key, size, size, dpr) ?: QPixmap()
    }

    /**
     * When generating a Project without specifying an Icon, use a generic icon
     * TODO: Get rid of this for a better system
     */
    internal val defaultProjectIcon: String by lazy {
        resolveFileResource("/icons/folder.png")
            ?: renderFolderIconToTempPng()
            ?: ""
    }

    /**
     * Gets a file from bundled resources
     */
    private fun resolveFileResource(path: String): String? {
        val url = javaClass.getResource(path) ?: return null
        return try {
            if(url.protocol == "file") File(url.toURI()).absolutePath else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Temporary icon for rendering
     */
    private fun renderFolderIconToTempPng(): String? {
        return try {
            val pix = ThemeMngr.getPixmap("file/folder", 16, 16, 1.0)
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

typealias IconKey = String

fun IconKey.icon(size: Int): QPixmap = TIcons.pix(this, size)
