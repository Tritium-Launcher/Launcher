package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.launcher.io.VPath
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Loads Themes from filesystem
 */
object ThemeLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Load a [ThemeFile] from Stream
     */
    @Throws(Exception::class)
    fun loadFromStream(stream: InputStream): ThemeFile {
        val bytes = stream.readBytes()
        return json.decodeFromString(ThemeFile.serializer(), bytes.decodeToString())
    }

    /**
     * Load a [ThemeFile] from filesystem path
     */
    @Throws(Exception::class)
    fun loadFromFile(path: VPath): ThemeFile = path.inputStream().use { return loadFromStream(it) }

    /**
     * Override one [ThemeFile]'s values with another.
     *
     * This is helpful when working with themes which only declare Colors or Icons.
     */
    fun merge(base: ThemeFile?, override: ThemeFile): ThemeFile {
        if(base == null) return override
        return ThemeFile(
            meta = override.meta,
            colors = base.colors + override.colors,
            icons = base.icons + override.icons,
            stylesheets = base.stylesheets + override.stylesheets,
        )
    }
}