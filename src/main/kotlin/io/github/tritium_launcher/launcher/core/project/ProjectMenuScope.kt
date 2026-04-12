package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.ui.project.menu.MenuItem

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
    internal fun includedIds(): Set<String> = includedItems.asSequence().map { it.id }.toSet()
    internal fun excludedIds(): Set<String> = excludedItems.asSequence().map { it.id }.toSet()

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
