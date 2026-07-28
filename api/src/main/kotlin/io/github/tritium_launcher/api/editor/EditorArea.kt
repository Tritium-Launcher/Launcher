/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.qt.gui.QIcon

interface EditorArea {
    fun openFile(file: VPath): EditorPane?
    fun openEditorPane(
        provider: EditorPaneProvider,
        title: String,
        icon: QIcon? = null,
        paneFactory: (ProjectBase) -> EditorPane
    ): EditorPane
}
