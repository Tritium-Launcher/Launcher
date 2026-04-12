package io.github.tritium_launcher.launcher.core.modpack.modrinth.api

import kotlinx.serialization.Serializable

/*
* Contains responses for Modrinth's API specifically for mods, such as searching for, viewing, and downloading.
*/

@Serializable
data class Search(
    val query: String,
    val facets: String,
    val index: Sorting,
    val offset: Int,
    val limit: Int
) {
    enum class Sorting {
        RELEVANCE,
        DOWNLOADS,
        FOLLOWS,
        NEWEST,
        UPDATED;
    }

    @Serializable
    data class Hit(
        val slug: String,
        val title: String,
        val description: String,
        val categories: List<String>,
        val client_side: String,
        val server_side: String,
        val project_type: String,
        val downloads: Int,
        val icon_url: String?,
        val color: Int?,
        val project_id: String,
        val author: String,
        val display_categories: List<String>,
        val versions: List<String>,
        val follows: Int,
        val date_created: String,
        val date_modified: String,
        val license: String,
        val featured_gallery: String?
    )

    // Responses

    @Serializable
    data class Ok(
        val hits: List<Hit>,
        val offset: Int,
        val limit: Int,
        val total_hits: Int
    )

    @Serializable
    data class BadRequest(
        val error: String,
        val description: String
    )
}