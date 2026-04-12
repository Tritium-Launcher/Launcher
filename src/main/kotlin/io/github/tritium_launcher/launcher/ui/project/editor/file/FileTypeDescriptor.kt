package io.github.tritium_launcher.launcher.ui.project.editor.file

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QIcon

/**
 * Defines a File Type, including setting match-cases to detect when files can be this file type.
 */
interface FileTypeDescriptor : Registrable {
    val displayName: String
    val icon: QIcon?
    val order: Int
    fun matches(file: VPath, project: ProjectBase): Boolean
    fun languageId(file: VPath, project: ProjectBase): String? = null

    companion object {
        fun create(
            id: String,
            displayName: String,
            icon: QIcon? = null,
            matches: (VPath, ProjectBase) -> Boolean,
            languageId: ((VPath, ProjectBase) -> String?)? = null,
            order: Int = 0,
        ): FileTypeDescriptor = object : FileTypeDescriptor {
            override val id = id
            override val displayName: String = displayName
            override val order = order
            override val icon = icon

            override fun matches(
                file: VPath,
                project: ProjectBase
            ): Boolean = matches(file, project)

            override fun languageId(
                file: VPath,
                project: ProjectBase
            ): String? = languageId?.invoke(file, project)
        }
    }
}