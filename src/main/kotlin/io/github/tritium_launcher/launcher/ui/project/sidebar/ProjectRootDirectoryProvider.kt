package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable

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
