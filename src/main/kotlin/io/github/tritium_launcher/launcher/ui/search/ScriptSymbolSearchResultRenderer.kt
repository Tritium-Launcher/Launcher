/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultRenderer
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget

class ScriptSymbolSearchResultRenderer : SearchResultRenderer {
    override val id = "script_symbol"
    override val handledKinds = setOf("script_symbol")

    override fun buildDetailPane(result: SearchResult, context: SearchDetailContext): QWidget {
        return QWidget().apply {
            vBoxLayout(this) {
                contentsMargins = 12.m
                widgetSpacing = 8
                addWidget(QLabel("<b>${escape(result.name)}</b>").apply { wordWrap = true })
                addWidget(QLabel("Kind: script symbol"))
                addWidget(QLabel("Path: ${escape(result.path)}").apply { wordWrap = true })
                if (result.sourceLine > 0) {
                    addWidget(QLabel("Line: ${result.sourceLine}"))
                }
                addWidget(QLabel("Detail: ${escape(result.detail)}").apply { wordWrap = true })
                addStretch(1)
            }
        }
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
