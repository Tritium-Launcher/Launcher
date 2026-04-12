package io.github.tritium_launcher.launcher.ui.project.editor

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.qt.widgets.QWidget

/**
 * Extendable impl for Editor Panes, which could be for editing text, menus, or other kinds of widgets.
 * A secondary class implementing [EditorPaneProvider] is necessary for registration.
 * @see TextEditorPane
 * @see io.github.tritium_launcher.launcher.ui.project.editor.pane.ImageViewerPane
 * @see EditorArea
 */
abstract class EditorPane(
    val project: ProjectBase,
    val file: VPath
) {

    abstract fun widget(): QWidget

    open fun onOpen() {}

    open fun onClose() {}

    open suspend fun save(): Boolean = true
}