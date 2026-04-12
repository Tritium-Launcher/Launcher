package io.github.tritium_launcher.launcher.core.project

/**
 * Listener for project lifecycle events.
 */
interface ProjectMngrListener {
    fun onProjectCreated(project: ProjectBase)
    fun onProjectOpened(project: ProjectBase)
    fun onProjectDeleted(project: ProjectBase)
    fun onProjectUpdated(project: ProjectBase)
    fun onProjectsFinishedLoading(projects: List<ProjectBase>)
    fun onProjectFailedToGenerate(project: ProjectBase, errorMsg: String, exception: Exception?)
}
