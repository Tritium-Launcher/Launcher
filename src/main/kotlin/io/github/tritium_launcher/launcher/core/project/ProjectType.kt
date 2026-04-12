package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.core.project.templates.TemplateExecutionResult
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QIcon
import io.qt.widgets.QWidget
import java.nio.file.Path

/**
 * Defines a project type that can be created via the UI.
 */
interface ProjectType: Registrable {
    override val id: String
    val displayName: String
    val description: String
    val icon: QIcon
    val order: Int
    /**
     * Controls which menu items appear for this project type in [io.github.tritium_launcher.launcher.ui.project.menu.ProjectMenuBar].
     */
    val menuScope: ProjectMenuScope
        get() = ProjectMenuScope.all()

    /**
     * Build a setup widget for collecting project variables.
     *
     * @param projectRootHint Suggested root directory.
     * @param initialVars Mutable map that will be filled with user selections.
     */
    fun createSetupWidget(projectRootHint: Path?, initialVars: MutableMap<String, String>): QWidget

    /**
     * Create the project on disk.
     *
     * @param vars Variables collected from [createSetupWidget].
     */
    suspend fun createProject(vars: Map<String, String>): TemplateExecutionResult
}
