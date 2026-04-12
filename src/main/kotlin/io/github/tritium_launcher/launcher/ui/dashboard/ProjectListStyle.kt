package io.github.tritium_launcher.launcher.ui.dashboard

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QIcon
import io.qt.widgets.QWidget

/** Describes a sorting option that a list style can expose. */
data class ProjectSortOption(
    val id: String,
    val label: String,
    val description: String = "",
    val kind: SortKind = SortKind.Comparator,
    val comparatorProvider: ((ProjectSortContext) -> Comparator<ProjectBase>)? = null
) {
    fun comparator(ctx: ProjectSortContext): Comparator<ProjectBase>? = comparatorProvider?.invoke(ctx)
}

/** Indicates how a sort option should be applied. */
enum class SortKind { Comparator, Manual }

/** Context shared with list styles for access to core services and stores. */
data class ProjectStyleContext(
    val host: QWidget,
    val openProject: (ProjectBase) -> Unit,
    val layoutStore: LayoutStore,
    val groupStore: GroupStore,
    val requestRefresh: () -> Unit,
    val controlsVisible: () -> Boolean = { false },
    val hideInvalidProject: (ProjectBase) -> Unit = {}
)

/** Context passed to sort options for accessing style-related data. */
data class ProjectSortContext(
    val style: ProjectStyleContext
)

/** Contract for pluggable project list styles. */
interface ProjectListStyle {
    val id: String
    val title: String
    val icon: QIcon?
    val sortOptions: List<ProjectSortOption>

    /** Returns the root widget that renders projects for this style. */
    fun widget(): QWidget

    /** Applies projects using the provided sort option. */
    fun applyProjects(projects: List<ProjectBase>, sort: ProjectSortOption)

    /** Clean up listeners or resources. */
    fun dispose()
}

/** Registrable provider for project list styles. */
interface ProjectListStyleProvider : Registrable {
    val title: String
    val icon: QIcon?
    val hidden: Boolean get() = false
    fun create(ctx: ProjectStyleContext): ProjectListStyle
}
