package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor

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

object ProjectTreeDirectoryPresentations {
    fun all(): List<ProjectTreeDirectoryPresentation> = listOf(ConfigDirectoryPresentation)
}

private object ConfigDirectoryPresentation : ProjectTreeDirectoryPresentation {
    override val id: String = "config_directory"
    override val order: Int = 0

    override fun matches(directory: VPath, project: ProjectBase): Boolean =
        directory.toAbsolute() == project.projectDir.resolve("config").toAbsolute()

    override fun sortChildren(directory: VPath, children: List<VPath>, project: ProjectBase): List<VPath> {
        val mode = CoreSettingValues.projectFilesConfigSortMode
        val comparator = when (mode) {
            CoreSettingValues.ProjectFilesConfigSortMode.Alphabetical ->
                compareBy<VPath>({ !it.isDir() }, { displayStem(it).lowercase() }, { it.fileName().lowercase() })
            CoreSettingValues.ProjectFilesConfigSortMode.FileType ->
                compareBy<VPath>({ !it.isDir() }, { fileTypeSortKey(it, project) }, { displayStem(it).lowercase() }, { it.fileName().lowercase() })
        }
        return children.sortedWith(comparator)
    }

    override fun displayName(
        directory: VPath,
        child: VPath,
        project: ProjectBase,
        primaryType: FileTypeDescriptor?,
        currentDisplayName: String
    ): String {
        if (child.isDir()) return currentDisplayName
        return displayStem(child)
    }

    private fun fileTypeSortKey(path: VPath, project: ProjectBase): String {
        val specific = FileTypeDescriptor.matching(path, project).firstOrNull { it.id != "modcfg" }
        val resolved = specific ?: FileTypeDescriptor.primary(path, project)
        return resolved?.displayName?.lowercase() ?: path.extension().lowercase()
    }

    private fun displayStem(path: VPath): String {
        val name = path.fileName()
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
