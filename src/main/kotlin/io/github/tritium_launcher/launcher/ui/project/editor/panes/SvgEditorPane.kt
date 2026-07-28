/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.EditorPane
import io.github.tritium_launcher.api.editor.EditorPaneProvider
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.AnimatedScrollController
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.hBoxLayout
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.label
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.Nullable
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.gui.QResizeEvent
import io.qt.widgets.QLabel
import io.qt.widgets.QScrollArea
import io.qt.widgets.QSizePolicy

class SvgEditorPane(project: ProjectBase, file: VPath) : SplitEditorPane(project, file) {

    override val viewModes: List<String> = listOf("Preview", "Split", "Text")
    override var currentViewMode: String? = "Split"

    override fun viewModeIcon(mode: String): String? = when (mode) {
        "Preview" -> "ui/editor_image_preview"
        "Split" -> "ui/editor_text_other_left"
        "Text" -> "ui/editor_text"
        else -> null
    }

    override fun onViewModeChanged(mode: String) {
        when (mode) {
            "Preview" -> showRightOnly()
            "Text" -> showLeftOnly()
            "Split" -> showBoth()
        }
    }

    private val imageLabel = object : QLabel() {
        init {
            setAlignment(Qt.AlignmentFlag.AlignCenter)
            setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Ignored)
        }

        override fun resizeEvent(event: @Nullable QResizeEvent?) {
            super.resizeEvent(event)
            updatePixmap()
        }

        fun updatePixmap() {
            val pix = originalPixmap
            if (pix == null || pix.isNull) return
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val sourceW = pix.width()
            val sourceH = pix.height()
            val dpr = devicePixelRatio()
            val targetW = (w * dpr).toInt()
            val targetH = (h * dpr).toInt()
            val isUpscaling = targetW > sourceW || targetH > sourceH
            val isSmall = sourceW <= 128 || sourceH <= 128
            val transformMode = if (isUpscaling && isSmall) {
                Qt.TransformationMode.FastTransformation
            } else {
                Qt.TransformationMode.SmoothTransformation
            }
            val scaled = pix.scaled(targetW, targetH, Qt.AspectRatioMode.KeepAspectRatio, transformMode)
            try { scaled.setDevicePixelRatio(dpr) } catch (_: Throwable) { }
            scaledPixmap?.dispose()
            scaledPixmap = scaled
            super.setPixmap(scaled)
        }
    }

    private var originalPixmap: QPixmap? = null
    private var scaledPixmap: QPixmap? = null

    private val scroll = QScrollArea().apply {
        setWidget(imageLabel)
        widgetResizable = true
        setAlignment(Qt.AlignmentFlag.AlignCenter)
    }

    private val infoLabel = label { isVisible = false }

    private val infoRow = qWidget {
        hBoxLayout(this) {
            contentsMargins = 4.m
            widgetSpacing = 6
            addWidget(infoLabel)
            addStretch()
        }
    }

    private val textEditor: TextEditorPane = run {
        val lang = BuiltinRegistries.SyntaxLanguage.all().find { it.matches(file) }
        TextEditorPane(project, file, lang)
    }

    private val rightWidget = qWidget {
        vBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 4
            addWidget(infoRow)
            addWidget(scroll)
        }
    }

    init {
        AnimatedScrollController.attach(scroll)
        if (file.exists()) {
            val path = file.toAbsoluteString()
            originalPixmap = QPixmap(path)
            val pix = originalPixmap
            if (pix != null && !pix.isNull) {
                val bytes = file.sizeOrNull() ?: 0L
                infoLabel.text = "${pix.width()}x${pix.height()}, ${formatFileSize(bytes)}"
                infoLabel.isVisible = true
            }
            imageLabel.updatePixmap()
        }
        setLeftContent(textEditor.widget(), textEditor)
        setRightContent(rightWidget)
    }

    override fun onOpen() {
        super.onOpen()
    }

    override fun onClose() {
        textEditor.onClose()
        scaledPixmap?.dispose()
        scaledPixmap = null
        originalPixmap?.dispose()
        originalPixmap = null
        imageLabel.clear()
        super.onClose()
    }

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "%.1f %s".format(size, units[unitIndex])
    }

    object Provider : EditorPaneProvider {
        override val id = "svg_editor"
        override val displayName = "SVG Editor"
        override val order = 5

        override fun canOpen(file: VPath, project: ProjectBase): Boolean =
            file.extension().matches("svg", "svgz")

        override fun tabIcon(file: VPath, project: ProjectBase): QIcon = TIcons.Image.icon

        override fun create(project: ProjectBase, file: VPath): EditorPane =
            SvgEditorPane(project, file)
    }
}
