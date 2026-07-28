/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.menu

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.qt.widgets.QMainWindow

data class MenuActionContext(
    val project: ProjectBase?,
    val window: QMainWindow?,
    val selection: Any?,
    val meta: Map<String, String> = emptyMap()
)
