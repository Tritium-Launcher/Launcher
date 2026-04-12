package io.github.tritium_launcher.launcher.ui.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThemeFile(
    val meta: ThemeMeta,
    val colors: Map<String, String> = emptyMap(),
    val icons: Map<String, String> = emptyMap(),
    val stylesheets: Map<String, String> = emptyMap(),
)

@Serializable
data class ThemeMeta(
    val id: String,
    val name: String,
    val type: ThemeType,
    val base: String? = null,
    val authors: List<String> = emptyList()
)

@Serializable
enum class ThemeType { @SerialName("dark") Dark, @SerialName("light") Light }