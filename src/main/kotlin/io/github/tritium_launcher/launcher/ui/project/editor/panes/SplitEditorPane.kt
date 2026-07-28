/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.EditorPane
import io.github.tritium_launcher.api.io.VPath
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.widgets.QSplitter
import io.qt.widgets.QVBoxLayout
import io.qt.widgets.QWidget

/**
 * Container pane that splits a tab into two independent areas.
 *
 * Subclasses call [setLeftContent] and [setRightContent] during [init]
 * to set up the two sides. The base class provides show/hide helpers
 * ([showLeftOnly], [showRightOnly], [showBoth]) and default view mode
 * support via ["Left", "Split", "Right"].
 */
open class SplitEditorPane(
    project: ProjectBase,
    file: VPath?,
    val tabTitle: String = "",
    val tabIcon: QIcon? = null
) : EditorPane(project, file) {

    override val viewModes: List<String> = listOf("Left", "Split", "Right")
    override var currentViewMode: String? = "Split"

    override fun viewModeIcon(mode: String): String? = null

    override fun onViewModeChanged(mode: String) {
        when (mode) {
            "Left" -> showLeftOnly()
            "Right" -> showRightOnly()
            "Split" -> showBoth()
        }
    }

    protected var leftPane: EditorPane? = null
        private set
    protected var rightPane: EditorPane? = null
        private set

    private var _leftWidget: QWidget? = null
    private var _rightWidget: QWidget? = null

    protected fun setLeftContent(widget: QWidget, pane: EditorPane? = null) {
        _leftWidget = widget
        leftPane = pane
        if (pane != null) pane.onModifiedChanged = { recalcModified() }
        rebuildSplitter()
        recalcModified()
    }

    protected fun setRightContent(widget: QWidget, pane: EditorPane? = null) {
        _rightWidget = widget
        rightPane = pane
        if (pane != null) pane.onModifiedChanged = { recalcModified() }
        rebuildSplitter()
        recalcModified()
    }

    private fun rebuildSplitter() {
        _leftWidget?.setParent(null)
        _rightWidget?.setParent(null)
        if (_leftWidget != null) splitter.addWidget(_leftWidget!!)
        if (_rightWidget != null) splitter.addWidget(_rightWidget!!)
    }

    protected val splitter = QSplitter(Qt.Orientation.Horizontal)
    private val rootWidget = QWidget()

    init {
        val rootLayout = QVBoxLayout(rootWidget)
        rootLayout.setContentsMargins(0, 0, 0, 0)
        rootLayout.addWidget(splitter, 1)
    }

    override fun widget(): QWidget = rootWidget

    protected fun showLeftOnly() {
        _leftWidget?.show()
        _rightWidget?.hide()
        splitter.setSizes(listOf(splitter.width().coerceAtLeast(200), 0))
    }

    protected fun showRightOnly() {
        _leftWidget?.hide()
        _rightWidget?.show()
        splitter.setSizes(listOf(0, splitter.width().coerceAtLeast(200)))
    }

    protected fun showBoth() {
        _leftWidget?.show()
        _rightWidget?.show()
        val total = splitter.width().coerceAtLeast(200)
        splitter.setSizes(listOf(total / 2, total / 2))
    }

    protected open fun recalcModified() {
        modified = (leftPane?.modified == true) || (rightPane?.modified == true)
    }

    override fun onOpen() {
        leftPane?.onOpen()
        rightPane?.onOpen()
    }

    override fun onClose() {
        leftPane?.onClose()
        rightPane?.onClose()
    }

    override suspend fun save(): Boolean {
        val l = leftPane?.save() ?: true
        val r = rightPane?.save() ?: true
        return l && r
    }
}
