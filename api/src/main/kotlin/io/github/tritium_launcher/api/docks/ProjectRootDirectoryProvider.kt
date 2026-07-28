/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.registry.Registrable

/**
 * Provides root directories for the "Project" view mode.
 */
interface ProjectRootDirectoryProvider : Registrable {
    fun getRootDirectories(project: ProjectBase): List<ProjectFilesNodeSpec>
}

/**
 * Helper to create a simple provider for a single directory.
 */
fun projectRootDirectory(id: String, relativePath: String, label: String): ProjectRootDirectoryProvider {
    return object : ProjectRootDirectoryProvider {
        override val id: String = id
        override fun getRootDirectories(project: ProjectBase): List<ProjectFilesNodeSpec> {
            val path = project.projectDir.resolve(relativePath)
            return if (path.isDir()) listOf(ProjectFilesNodeSpec(path, label)) else emptyList()
        }
    }
}
