package io.github.tritium_launcher.launcher

import io.github.tritium_launcher.launcher.io.VPath

/**
 * General constant values
 */
object TConstants {
    const val TR = "Tritium"
    const val TR_SERVICE = "TritiumLauncher"
    val VERSION: String by lazy { resolveVersion() }
    val TR_DIR: VPath = fromTR()

    /**
     * Directories in `~/tritium`
     */
    object Dirs {
        const val PROJECTS = "projects"
        const val EXTENSIONS = "extensions"
        const val LOADERS = "loaders"
        const val CACHE = "cache"
        const val PROFILES = ".profiles"
        const val MSAL = ".msal"
        const val ASSETS = "assets"
        const val SETTINGS = "settings"
        const val LSPS = "lsps"
    }

    val EXT_DIR = fromTR(Dirs.EXTENSIONS)
    val LSPS_DIR = fromTR(Dirs.LSPS)
    val classLoader: ClassLoader = javaClass.classLoader

    object Lists {
        val ImageExtensions = listOf(
            "png", "jpg", "jpeg", "jpe", "gif", "bmp", "tiff", "tif", "webp", "avif", "heic", "heif", "jp2",
            "jxl", "ico", "cur", "dds", "exr", "svg", "svgz", "eps", "pdf", "ai", "cdr", "raw", "dng", "nef", "cr2",
            "cr3", "arw", "orf", "rw2", "pef", "aseprite"
        )
    }

    /**
     * Get current Tritium version
     */
    private fun resolveVersion(): String {
        val fromManifest = TConstants::class.java.`package`?.implementationVersion?.trim()
        if (!fromManifest.isNullOrBlank()) return fromManifest

        val fromResource = runCatching {
            TConstants::class.java.classLoader
                ?.getResourceAsStream("version.txt")
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText().trim() }
        }.getOrNull()
        if (!fromResource.isNullOrBlank() && !fromResource.contains("\${")) return fromResource

        return "0.0.0"
    }
}
