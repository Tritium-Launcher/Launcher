package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.qt.gui.QIcon
import io.qt.widgets.QTreeWidget

/**
 * A context menu action for the Project Files tree.
 *
 * Extensions implement this to add custom right-click actions on files and directories.
 * Register instances via [io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries.ProjectFilesAction].
 */
interface ProjectFilesContextAction : Registrable {
    val displayName: String
    val order: Int
    val icon: QIcon?

    /**
     * Which section of the context menu this action belongs to.
     * Sections are rendered in order with separators between them.
     */
    val section: Section

    /**
     * Whether the tree should be refreshed after execution.
     */
    val needsRefresh: Boolean

    /**
     * Whether this action should appear in the context menu for the given path.
     */
    fun matches(path: VPath, isDirectory: Boolean, fileType: FileTypeDescriptor?, project: ProjectBase): Boolean

    /**
     * Execute this action. [tree] is provided for post-execution operations like refresh or selection changes.
     */
    fun execute(path: VPath, project: ProjectBase, tree: QTreeWidget)

    enum class Section { NEW, CLIPBOARD, RENAME, DELETE, RELOAD, EXTENSIONS }

    companion object {
        fun create(
            id: String,
            displayName: String,
            order: Int = 0,
            icon: QIcon? = null,
            section: Section = Section.EXTENSIONS,
            needsRefresh: Boolean = false,
            matches: (VPath, Boolean, FileTypeDescriptor?, ProjectBase) -> Boolean = { _, _, _, _ -> true },
            execute: (VPath, ProjectBase, QTreeWidget) -> Unit
        ): ProjectFilesContextAction = object : ProjectFilesContextAction {
            override val id = id
            override val displayName = displayName
            override val order = order
            override val icon = icon
            override val section = section
            override val needsRefresh = needsRefresh
            override fun matches(path: VPath, isDirectory: Boolean, fileType: FileTypeDescriptor?, project: ProjectBase): Boolean =
                matches(path, isDirectory, fileType, project)
            override fun execute(path: VPath, project: ProjectBase, tree: QTreeWidget) =
                execute(path, project, tree)
        }
    }
}
