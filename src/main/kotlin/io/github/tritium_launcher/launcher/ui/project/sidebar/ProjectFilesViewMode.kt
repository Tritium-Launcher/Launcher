package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable

data class ProjectFilesNodeSpec(
    val path: VPath,
    val label: String? = null
)

interface ProjectFilesViewMode : Registrable {
    val displayName: String
    val order: Int get() = 0

    fun rootEntries(project: ProjectBase): List<ProjectFilesNodeSpec>

    fun childEntries(parent: VPath, project: ProjectBase): List<ProjectFilesNodeSpec> =
        runCatching { parent.list() }
            .getOrDefault(emptyList())
            .map { ProjectFilesNodeSpec(it) }
}

object ProjectFilesViewModes {
    fun all(): List<ProjectFilesViewMode> = listOf(ProjectViewMode, ProjectFilesFlatViewMode)
}

private object ProjectViewMode : ProjectFilesViewMode {
    override val id: String = "project"
    override val displayName: String = "Project"
    override val order: Int = 0

    override fun rootEntries(project: ProjectBase): List<ProjectFilesNodeSpec> =
        BuiltinRegistries.ProjectRootDirectory.all()
            .flatMap { it.getRootDirectories(project) }

    override fun childEntries(parent: VPath, project: ProjectBase): List<ProjectFilesNodeSpec> =
        runCatching { parent.list() }
            .getOrDefault(emptyList())
            .filterNot { it.fileName().startsWith('.') }
            .map { ProjectFilesNodeSpec(it) }
}

private object ProjectFilesFlatViewMode : ProjectFilesViewMode {
    override val id: String = "project_files"
    override val displayName: String = "Project Files"
    override val order: Int = 100

    override fun rootEntries(project: ProjectBase): List<ProjectFilesNodeSpec> =
        runCatching { project.projectDir.list() }
            .getOrDefault(emptyList())
            .map { ProjectFilesNodeSpec(it) }
}
