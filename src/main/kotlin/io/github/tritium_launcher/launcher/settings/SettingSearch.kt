package io.github.tritium_launcher.launcher.settings

/**
 * Ranked settings search hit returned by [SettingsMngr.search].
 *
 * @property node Matching setting.
 * @property category Category that contains [node], when still registered.
 * @property score Relevance score (higher is better).
 * @see SettingsMngr.search
 */
data class SettingSearchHit(
    val node: SettingNode<*>,
    val category: SettingsRegistry.CategoryNode?,
    val score: Int
) {
    /**
     * Fully-qualified key of the matching setting.
     */
    val key: NamespacedId get() = node.key
}
