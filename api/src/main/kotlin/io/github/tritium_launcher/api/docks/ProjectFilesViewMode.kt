/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable

interface ProjectFilesViewMode : Registrable {
    val displayName: String
    val order: Int get() = 0

    fun rootEntries(project: ProjectBase): List<ProjectFilesNodeSpec>

    fun childEntries(parent: VPath, project: ProjectBase): List<ProjectFilesNodeSpec> =
        runCatching { parent.list() }
            .getOrDefault(emptyList())
            .map { ProjectFilesNodeSpec(it) }
}
