package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    @SerialName("description") val summary: String,
    @SerialName("body") val description: String = "",
    val categories: List<String> = emptyList(),
    @SerialName("additional_categories") val additionalCategories: List<String> = emptyList(),
    val downloads: Int = 0,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("issues_url") val issuesUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null
)
