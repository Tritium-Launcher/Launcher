/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.file

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable
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

    /**
     * Whether this file type supports creating new files via the Project Files context menu.
     * Override and return true when [createDefaultFile] is implemented.
     */
    val supportsCreation: Boolean get() = false

    /**
     * Whether this file type can be created in [directory].
     * Used to filter the "New > FileType" menu entries.
     * Defaults to [supportsCreation].
     */
    fun canCreateIn(directory: VPath, project: ProjectBase): Boolean = supportsCreation

    /**
     * Default file name used as the starting value in the "New File" dialog.
     */
    fun defaultFileName(): String = "untitled"

    /**
     * Creates a new file of this type in [directory] with the given base [name].
     * Called from the "New > FileType" context menu entry in the Project Files tree.
     * Only invoked when [supportsCreation] is true.
     * @param directory The directory to create the file in
     * @param name The file name entered by the user
     * @param project The current project
     * @return The path to the created file, or null if creation failed
     */
    fun createDefaultFile(directory: VPath, name: String, project: ProjectBase): VPath? = null

    companion object {
        private var sortedTypes: List<FileTypeDescriptor>? = null

        fun matching(file: VPath, project: ProjectBase): List<FileTypeDescriptor> {
            val sorted = sortedTypes ?: BuiltinRegistries.FileType.all().sortedBy { it.order }.also { sortedTypes = it }
            return sorted.filter { it.matches(file, project) }
        }

        fun primary(file: VPath, project: ProjectBase): FileTypeDescriptor? =
            matching(file, project).firstOrNull()

        fun create(
            id: String,
            displayName: String,
            icon: QIcon? = null,
            matches: (VPath, ProjectBase) -> Boolean,
            languageId: ((VPath, ProjectBase) -> String?)? = null,
            order: Int = 0,
            canCreateIn: ((VPath, ProjectBase) -> Boolean)? = null,
            defaultFileName: (() -> String)? = null,
            createDefaultFile: ((VPath, String, ProjectBase) -> VPath?)? = null,
        ): FileTypeDescriptor {
            val supports = createDefaultFile != null
            return object : FileTypeDescriptor {
                override val id = id
                override val displayName: String = displayName
                override val order = order
                override val icon = icon
                override val supportsCreation: Boolean get() = supports

                override fun matches(
                    file: VPath,
                    project: ProjectBase
                ): Boolean = matches(file, project)

                override fun languageId(
                    file: VPath,
                    project: ProjectBase
                ): String? = languageId?.invoke(file, project)

                override fun canCreateIn(
                    directory: VPath,
                    project: ProjectBase
                ): Boolean = canCreateIn?.invoke(directory, project) ?: supports

                override fun defaultFileName(): String = defaultFileName?.invoke() ?: "untitled"

                override fun createDefaultFile(
                    directory: VPath,
                    name: String,
                    project: ProjectBase
                ): VPath? = createDefaultFile?.invoke(directory, name, project)
            }
        }
    }
}
