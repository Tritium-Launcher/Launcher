/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.registry.Registrable
import io.qt.gui.QIcon

interface EditorPaneProvider: Registrable {

    val displayName: String
    val order: Int

    val singletonGroup: String? get() = null

    fun canOpen(file: VPath, project: ProjectBase): Boolean

    fun tabTitle(file: VPath, project: ProjectBase): String = file.fileName()

    fun tabIcon(file: VPath, project: ProjectBase): QIcon? = null

    fun create(project: ProjectBase, file: VPath): EditorPane
}
