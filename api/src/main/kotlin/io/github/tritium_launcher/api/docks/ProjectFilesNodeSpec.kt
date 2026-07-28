/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.docks

import io.github.tritium_launcher.api.io.VPath
import io.qt.gui.QIcon

data class ProjectFilesNodeSpec(
    val path: VPath,
    val label: String? = null,
    val icon: QIcon? = null,
    val isProjRoot: Boolean = false
)
