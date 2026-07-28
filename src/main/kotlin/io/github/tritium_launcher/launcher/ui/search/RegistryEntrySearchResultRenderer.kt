/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.search

import io.github.tritium_launcher.api.search.SearchDetailContext
import io.github.tritium_launcher.api.search.SearchResult
import io.github.tritium_launcher.api.search.SearchResultRenderer
import io.github.tritium_launcher.launcher.core.project.ProjectMngr
import io.github.tritium_launcher.launcher.m
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.vBoxLayout
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget

class RegistryEntrySearchResultRenderer : SearchResultRenderer {
    override val id = "registry_entry"
    override val handledKinds = setOf("registry_entry")

    override fun buildDetailPane(result: SearchResult, context: SearchDetailContext): QWidget {
        return QWidget().apply {
            objectName = "registryDetailBody"
            vBoxLayout(this) {
                contentsMargins = 16.m
                widgetSpacing = 8

                val project = ProjectMngr.activeProject

                addWidget(SearchIconLoader.subtextHeader("REGISTRY ID"))
                val idLabel = QLabel(result.id.removePrefix("registry_entry:")).apply {
                    wordWrap = true
                    styleSheet = "color: ${TColors.Text}; font-size: 13px;"
                }
                addWidget(idLabel)

                if (project != null) {
                    val tags = runCatching {
                        result.outputId?.let { RegistryDatabase.tagsForItem(project, it) }
                    }.getOrNull() ?: emptyList()
                    if (tags.isNotEmpty()) {
                        addWidget(SearchIconLoader.subtextHeader("TAGS"))
                        addWidget(QLabel(tags.joinToString(", ")).apply {
                            wordWrap = true
                            styleSheet = "color: ${TColors.Text}; font-size: 13px;"
                        })
                    }
                }

                addStretch(1)
            }
        }
    }

}
