package io.github.tritium_launcher.api.search

data class IndexableDocument(
    val id: String,
    val kind: String,
    val name: String,
    val nameExact: String = name,
    val detail: String = "",
    val path: String = "",
    val modId: String = "",
    val tags: String = "",
    val mtime: Long = 0L,
    val outputId: String? = null,
    val inputIds: String? = null,
    val recipeType: String? = null,
    val sourceKind: String? = null,
    val sourceLine: Long? = null
)
