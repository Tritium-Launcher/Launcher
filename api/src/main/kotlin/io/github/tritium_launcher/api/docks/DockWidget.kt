/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.qt.core.QSize
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QDockWidget
import io.qt.widgets.QMainWindow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Used in [io.github.tritium_launcher.launcher.ui.project.ProjectViewWindow] to display content in pop-out panes.
 * @see io.github.tritium_launcher.launcher.ui.project.sidebar.DockPanelMngr
 * @see DockPanelProvider
 * @see io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectFilesDockPanelProvider
 */
open class DockWidget(title: String, parent: QMainWindow?): QDockWidget(title, parent) {

    init {
        minimumSize = QSize(20, 20)
    }

    var index: Int
        get() = property("dockIndex") as? Int ?: 0
        set(value) {
            setProperty("dockIndex", value)
            _indexChanges.tryEmit(value)
        }

    private val _indexChanges = MutableSharedFlow<Int>(replay = 0)
    val indexChanges: SharedFlow<Int> = _indexChanges.asSharedFlow()

    /**
     * Set by [io.github.tritium_launcher.launcher.ui.project.sidebar.DockPanelMngr] to allow the provider to update the sidebar button
     * and title bar icon at runtime after creation.
     */
    var iconUpdater: ((QIcon) -> Unit)? = null

    fun applyIcon(icon: QIcon?) {
        if (icon != null) {
            iconUpdater?.invoke(icon) ?: run { windowIcon = icon }
        }
    }
    fun applyIcon(icon: QPixmap?) { if(icon != null) applyIcon(QIcon(icon)) }
}
