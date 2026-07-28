/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.theme.ThemeFile
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Deserializes [io.github.tritium_launcher.api.theme.ThemeFile]s from JSON streams/files and merges inheritance chains.
 */
object ThemeLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse a [io.github.tritium_launcher.api.theme.ThemeFile] from a stream.
     * @throws Exception if the JSON is invalid or [io.github.tritium_launcher.api.theme.ThemeFile.validate] fails.
     */
    @Throws(Exception::class)
    fun loadFromStream(stream: InputStream): ThemeFile {
        val bytes = stream.readBytes()
        return json.decodeFromString(ThemeFile.serializer(), bytes.decodeToString())
    }

    /**
     * Parse a [ThemeFile] from a filesystem path.
     * @throws Exception if the file cannot be read or parsed.
     */
    @Throws(Exception::class)
    fun loadFromFile(path: VPath): ThemeFile = path.inputStream().use { return loadFromStream(it) }

    /**
     * Merge an override theme into a base theme. The override's [meta] replaces the base's;
     * colors, icons, and stylesheets are merged with the override's entries.
     *
     * Used to apply theme inheritance: a child theme's declared values override its base's.
     *
     * @param base  The parent theme, or null if there is no base.
     * @param override  The child theme whose values take precedence.
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
