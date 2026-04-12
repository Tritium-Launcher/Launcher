package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable

interface EditorPaneProvider: Registrable {

    val displayName: String
    val order: Int

    fun canOpen(file: VPath, project: ProjectBase): Boolean

    fun create(project: ProjectBase, file: VPath): EditorPane
}