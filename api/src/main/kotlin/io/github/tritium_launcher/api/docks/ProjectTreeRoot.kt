/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable
import io.qt.gui.QColor
import io.qt.gui.QIcon

interface ProjectTreeRoot : Registrable {
    val order: Int get() = 0

    fun isAvailable(project: ProjectBase): Boolean = true
    fun displayName(project: ProjectBase): String
    fun rootPath(project: ProjectBase): VPath
    fun icon(project: ProjectBase): QIcon
    fun iconColor(project: ProjectBase): QColor? = null
    fun childEntries(project: ProjectBase, root: VPath): List<ProjectFilesNodeSpec> =
        runCatching { root.list() }
            .getOrDefault(emptyList())
            .map { ProjectFilesNodeSpec(it) }

    fun normalizePath(project: ProjectBase, path: VPath): String = path.toAbsoluteString()

    fun resolvePath(project: ProjectBase, normalized: String): String = normalized
}
