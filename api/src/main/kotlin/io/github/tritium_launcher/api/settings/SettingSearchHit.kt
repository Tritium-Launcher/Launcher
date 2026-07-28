/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.settings

/**
 * Ranked settings search hit returned by [io.github.tritium_launcher.launcher.settings.SettingsMngr.search].
 *
 * @property node Matching setting.
 * @property category Category that contains [node], when still registered.
 * @property score Relevance score (higher is better).
 * @see io.github.tritium_launcher.launcher.settings.SettingsMngr.search
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
