package io.github.tritium_launcher.api.search

data class SearchResult(
    val id: String,
    val kind: String,
    val name: String,
    val detail: String,
    val path: String,
    val modId: String,
    val sourceLine: Long,
    val outputId: String?,
    val inputIds: String?,
    val recipeType: String?,
    val sourceKind: String?,
    val score: Float
)
