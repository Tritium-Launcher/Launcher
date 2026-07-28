/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultAction
import io.qt.widgets.QApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CopyRegistryNameAction : SearchResultAction {
    override val id = "copy_registry_name"
    override val label = "Copy Name"
    override val icon = "copy"
    override val handledKinds = setOf("registry_entry", "recipe")

    override suspend fun execute(result: SearchResult) {
        withContext(Dispatchers.Main) {
            val text = result.outputId?.takeIf { it.isNotBlank() } ?: result.modId.takeIf { it.isNotBlank() }?.let { "$it:${result.name}" } ?: result.name
            QApplication.clipboard()?.setText(text)
        }
    }
}
