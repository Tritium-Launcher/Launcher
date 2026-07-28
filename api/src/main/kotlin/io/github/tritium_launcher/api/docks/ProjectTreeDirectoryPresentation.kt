/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable

/**
 * Extension point for customizing how a specific directory is presented in the project files tree.
 *
 * Implementations can target one or more directories and modify ordering or display names for the
 * immediate children shown within that directory.
 */
interface ProjectTreeDirectoryPresentation : Registrable {
    val order: Int get() = 0

    fun matches(directory: VPath, project: ProjectBase): Boolean

    fun sortChildren(directory: VPath, children: List<VPath>, project: ProjectBase): List<VPath> = children

    fun displayName(
        directory: VPath,
        child: VPath,
        project: ProjectBase,
        primaryType: FileTypeDescriptor?,
        currentDisplayName: String
    ): String = currentDisplayName
}
