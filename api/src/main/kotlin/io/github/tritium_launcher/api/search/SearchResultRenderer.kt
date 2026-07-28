/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.search

import io.github.tritium_launcher.api.registry.Registrable
import io.qt.widgets.QWidget

interface SearchResultRenderer : Registrable {
    val handledKinds: Set<String>
    val priority: Int get() = 0
    val detailMinimumWidth: Int get() = 0

    fun canRender(result: SearchResult): Boolean = result.kind in handledKinds

    fun buildDetailPane(result: SearchResult, context: SearchDetailContext): QWidget
}
