package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QIcon

interface EditorPaneProvider: Registrable {

    val displayName: String
    val order: Int

    fun canOpen(file: VPath, project: ProjectBase): Boolean

    fun tabTitle(file: VPath, project: ProjectBase): String = file.fileName()

    fun tabIcon(file: VPath, project: ProjectBase): QIcon? = null

    fun create(project: ProjectBase, file: VPath): EditorPane
}
