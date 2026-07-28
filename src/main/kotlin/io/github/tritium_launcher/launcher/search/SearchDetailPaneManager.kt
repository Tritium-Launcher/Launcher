/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.search

import io.github.tritium_launcher.api.registry.Registry
import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultRenderer
import io.qt.widgets.QWidget

class SearchDetailPaneManager(
    private val renderers: Registry<SearchResultRenderer>
) {
    fun rendererFor(result: SearchResult): SearchResultRenderer? =
        renderers.all()
            .filter { it.canRender(result) }
            .maxByOrNull { it.priority }

    fun buildDetailPane(
        result: SearchResult,
        context: SearchDetailContext
    ): QWidget? = rendererFor(result)?.buildDetailPane(result, context)
}
