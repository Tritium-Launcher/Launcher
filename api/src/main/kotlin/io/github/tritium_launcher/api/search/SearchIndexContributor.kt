/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.search

import io.github.tritium_launcher.api.registry.Registrable
import kotlinx.coroutines.flow.Flow

interface SearchIndexContributor : Registrable {
    val producedKinds: Set<String>

    val kindDisplayNames: Map<String, String>
        get() = producedKinds.associateWith { kind ->
            kind.replaceFirstChar { it.uppercase() }
        }

    suspend fun contributeDocuments(projectPath: String): Flow<IndexableDocument>

    suspend fun contributeFile(
        projectPath: String,
        filePath: String
    ): Flow<IndexableDocument>? = null
}
