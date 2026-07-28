/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.lsp

import io.qt.widgets.QWidget

abstract class HoverContentWidget(parent: QWidget? = null) : QWidget(parent) {
    abstract fun clear()
}
