/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.registry.Registrable
import io.qt.gui.QIcon

interface ProjectDirectoryMark : Registrable {
    val displayName: String
    val icon: QIcon?
    val incompatibleWith: List<String>
    val order: Int

    /**
     * If non-null, applies a hue rotation to the default
     * folder icon when this mark is the highest-priority mark on a directory
     * and [icon] is null.
     */
    val hueShiftDegrees: Float?

    /**
     * Return false to suppress this file-watch event for paths under a directory
     * marked with this mark. Called for every event whose path falls under the
     * marked directory.
     */
    fun filterWatchEvent(path: VPath, event: VWatchEvent): Boolean = true

    /**
     * Called after this mark is applied to a directory.
     */
    fun onMarkApplied(project: ProjectBase, path: VPath) {}

    /**
     * Called after this mark is removed from a directory.
     */
    fun onMarkRemoved(project: ProjectBase, path: VPath) {}

    companion object {
        /**
         * Create a simple mark with no behavior hooks.
         */
        fun create(
            id: String,
            displayName: String,
            icon: QIcon? = null,
            incompatibleWith: List<String> = emptyList(),
            order: Int = 0,
            hueShiftDegrees: Float? = null
        ): ProjectDirectoryMark = object : ProjectDirectoryMark {
            override val id: String = id
            override val displayName: String = displayName
            override val icon: QIcon? = icon
            override val incompatibleWith: List<String> = incompatibleWith
            override val order: Int = order
            override val hueShiftDegrees: Float? = hueShiftDegrees
        }
    }
}
