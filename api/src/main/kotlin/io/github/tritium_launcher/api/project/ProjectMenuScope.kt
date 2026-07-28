/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project

import io.github.tritium_launcher.api.menu.MenuItem

/**
 * Per-project-type menu visibility rules applied by the project menu bar.
 *
 * - [strict] = `true`: only [includedItems] (and their descendants) are shown.
 * - [strict] = `false`: all items are shown except [excludedItems].
 */
data class ProjectMenuScope(
    val includedItems: Set<MenuItem> = emptySet(),
    val excludedItems: Set<MenuItem> = emptySet(),
    val strict: Boolean = false
) {
    fun includedIds(): Set<String> = includedItems.asSequence().map { it.id }.toSet()
    fun excludedIds(): Set<String> = excludedItems.asSequence().map { it.id }.toSet()

    companion object {
        /**
         * Show all menu items.
         */
        fun all(): ProjectMenuScope = ProjectMenuScope()

        /**
         * Show only [items] and their descendants.
         */
        fun only(vararg items: MenuItem): ProjectMenuScope =
            ProjectMenuScope(
                includedItems = items.toSet(),
                strict = true
            )

        /**
         * Show all items except [items] and their descendants.
         */
        fun allExcept(vararg items: MenuItem): ProjectMenuScope =
            ProjectMenuScope(
                excludedItems = items.toSet(),
                strict = false
            )
    }
}
