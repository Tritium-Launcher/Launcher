package io.github.tritium_launcher.launcher.ui.project.menu

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.qt.widgets.QMainWindow

data class MenuActionContext(
    val project: ProjectBase?,
    val window: QMainWindow?,
    val selection: Any?,
    val meta: Map<String, String> = emptyMap()
)
