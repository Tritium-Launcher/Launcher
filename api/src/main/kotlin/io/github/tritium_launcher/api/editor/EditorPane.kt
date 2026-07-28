/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.qt.gui.QIcon
import io.qt.widgets.QStackedWidget
import io.qt.widgets.QWidget

/**
 * Extendable impl for Editor Panes, which could be for editing text, menus, or other kinds of widgets.
 * A secondary class implementing [EditorPaneProvider] is necessary for registration.
 * @see io.github.tritium_launcher.launcher.ui.project.editor.panes.TextEditorPane
 * @see io.github.tritium_launcher.launcher.ui.project.editor.panes.ImageViewerPane
 * @see io.github.tritium_launcher.launcher.ui.project.editor.EditorArea
 */
abstract class EditorPane(
    val project: ProjectBase,
    val file: VPath? = null
) {
    open val allowAutoSave: Boolean = true
    open val isReadOnly: Boolean = false

    /** View modes for panes that support multiple views (e.g. ["Text", "Split", "Preview"]). */
    open val viewModes: List<String> = emptyList()

    /** The currently active view mode. Pane updates its internal widget on change. */
    open var currentViewMode: String? = null

    /** Return an icon key for the given view mode, or null to use text label. */
    open fun viewModeIcon(mode: String): String? = null

    /** Called by EditorArea when the user clicks a view toggle button. */
    open fun onViewModeChanged(mode: String) { }

    /**
     * Cache for view-mode-specific widgets.
     */
    protected val viewModeWidgetCache = mutableMapOf<String, QWidget>()

    /**
     * Create a [QStackedWidget] with one page per [viewModes] entry,
     * using [factory].
     */
    protected fun createViewModeStack(factory: (String) -> QWidget): QStackedWidget {
        val stack = QStackedWidget()
        for (mode in viewModes) {
            val w = factory(mode)
            viewModeWidgetCache[mode] = w
            stack.addWidget(w)
        }
        val idx = viewModes.indexOf(currentViewMode ?: viewModes.firstOrNull())
        if (idx >= 0) stack.currentIndex = idx
        return stack
    }

    /**
     * Switch [this] stack to the page for [mode].
     */
    protected fun QStackedWidget.selectViewMode(mode: String) {
        val idx = viewModes.indexOf(mode)
        if (idx >= 0) currentIndex = idx
    }

    var modified: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                onModifiedChanged?.invoke(value)
            }
        }

    var onModifiedChanged: ((Boolean) -> Unit)? = null
    var onTitleChanged: ((String) -> Unit)? = null
    var onIconChanged: ((QIcon?) -> Unit)? = null

    abstract fun widget(): QWidget

    open fun onOpen() {}

    open fun onClose() {}

    open suspend fun save(): Boolean = true
}
