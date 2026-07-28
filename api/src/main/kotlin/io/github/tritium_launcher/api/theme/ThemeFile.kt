/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A parsed theme file. Themes use flat key-value maps and can optionally
 * inherit from a [base][ThemeMeta.base] theme.
 *
 * A single file may declare colors, icons, or both.
 *
 * @property meta  Metadata including id, display name, and base parent.
 * @property colors  Flat map of color key → hex/rgba string (e.g. `"Surface0" → "#242424"`).
 * @property icons  Flat map of logical icon key → file path (e.g. `"ui/tritium" → "icons/ui/tritium.svg"`).
 * @property stylesheets  Named QSS template blocks with `${key}` interpolation support.
 */
@Serializable
data class ThemeFile(
    val meta: ThemeMeta,
    val colors: Map<String, String> = emptyMap(),
    val icons: Map<String, String> = emptyMap(),
    val stylesheets: Map<String, String> = emptyMap(),
)

/**
 * Metadata for a [ThemeFile].
 *
 * @property id  Unique identifier used as the key in [io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.themes].
 * @property name  Display name.
 * @property type  Dark or Light — determines the type-appropriate fallback chain.
 * @property base  Optional parent theme ID to inherit colors/icons/stylesheet entries from.
 * @property authors  Optional list of author names.
 */
@Serializable
data class ThemeMeta(
    val id: String,
    val name: String,
    val type: ThemeType,
    val base: String? = null,
    val authors: List<String> = emptyList()
)

/** Whether a theme targets a dark or light background — used for fallback selection. */
@Serializable
enum class ThemeType { @SerialName("dark") Dark, @SerialName("light") Light }
