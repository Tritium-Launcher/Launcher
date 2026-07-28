/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.project.TrProjectFile

/**
 * Optional hook for loading projects using the unified project definition file.
 */
interface ProjectFileLoader {
    fun loadFromProjectFile(projectFile: TrProjectFile, projectDir: VPath): ProjectBase
}
