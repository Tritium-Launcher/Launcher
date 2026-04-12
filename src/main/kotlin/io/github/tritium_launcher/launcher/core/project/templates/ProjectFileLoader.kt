package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.project.TrProjectFile
import io.github.tritium_launcher.launcher.io.VPath

/**
 * Optional hook for loading projects using the unified project definition file.
 */
interface ProjectFileLoader {
    fun loadFromProjectFile(projectFile: TrProjectFile, projectDir: VPath): ProjectBase
}
