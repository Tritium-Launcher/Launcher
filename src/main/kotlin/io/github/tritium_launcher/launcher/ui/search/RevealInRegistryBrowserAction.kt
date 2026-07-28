/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultAction
import io.github.tritium_launcher.launcher.ui.project.ProjectWindows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RevealInRegistryBrowserAction : SearchResultAction {
    override val id = "reveal_in_registry_browser"
    override val label = "Reveal in Registry Browser"
    override val icon = "registry"
    override val handledKinds = setOf("registry_entry")

    override suspend fun execute(result: SearchResult) {
        withContext(Dispatchers.Main) {
            val window = ProjectWindows.anyOpenWindow() ?: return@withContext
            val dock = window.dockPanelMngr.getDock("registry_browser")
            if (dock != null) {
                dock.show()
                dock.raise()
            }
        }
    }
}
