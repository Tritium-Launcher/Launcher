/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.editor.intelligence

import io.qt.gui.QPixmap

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind = CompletionItemKind.Text,
    val detail: String? = null,
    val documentation: String? = null,
    val insertText: String? = null,
    val pixmap: QPixmap? = null
)
