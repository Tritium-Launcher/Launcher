package io.github.tritium_launcher.api.search

interface SearchDetailContext {
    val textureCache: Any?
    val recipeParser: Any?
    val registryBrowser: Any?
    val recipeBuilder: Any?

    companion object {
        fun empty() = object : SearchDetailContext {
            override val textureCache: Any? = null
            override val recipeParser: Any? = null
            override val registryBrowser: Any? = null
            override val recipeBuilder: Any? = null
        }
    }
}
