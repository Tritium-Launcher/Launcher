/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.search

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.search.IndexableDocument
import io.github.tritium_launcher.api.search.SearchIndexContributor
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class RegistryEntryContributor : SearchIndexContributor {
    override val id = "registry_entry"
    override val producedKinds = setOf("registry_entry")
    override val kindDisplayNames = mapOf("registry_entry" to "Registry")

    override suspend fun contributeDocuments(projectPath: String): Flow<IndexableDocument> =
        flow {
            val project = projectFromPath(projectPath) ?: return@flow
            val items = withContext(Dispatchers.IO) {
                RegistryDatabase.searchItems(project, "", offset = 0, limit = Int.MAX_VALUE)
            }
            items.forEach { item ->
                emit(
                    IndexableDocument(
                        id = "registry_entry:${item.id}",
                        kind = "registry_entry",
                        name = item.displayName ?: item.id,
                        nameExact = item.id,
                        detail = "${item.namespace} · ${item.path}",
                        path = item.id,
                        modId = item.namespace,
                        tags = item.tags.joinToString(" ")
                    )
                )
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun contributeFile(projectPath: String, filePath: String): Flow<IndexableDocument>? =
        null

    private fun projectFromPath(path: String): ProjectBase? =
        io.github.tritium_launcher.launcher.core.project.ProjectMngr.projects.find {
            it.projectDir.toAbsolute().toString() == VPath.get(path).toAbsolute().toString()
        }
}
