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

class GameRecipeContributor : SearchIndexContributor {
    override val id = "game_recipe"
    override val producedKinds = setOf("recipe")
    override val kindDisplayNames = mapOf("recipe" to "Recipes")

    override suspend fun contributeDocuments(projectPath: String): Flow<IndexableDocument> =
        flow {
            val project = projectFromPath(projectPath) ?: return@flow
            val details = withContext(Dispatchers.IO) {
                val recipes = RegistryDatabase.browseRecipes(project, "", limit = Int.MAX_VALUE, offset = 0)
                if (recipes.isEmpty()) return@withContext emptyList()
                RegistryDatabase.recipeDetails(project, recipes.map { it.id })
            }
            details.forEach { detail ->
                emit(
                    IndexableDocument(
                        id = "recipe:${detail.id}",
                        kind = "recipe",
                        name = detail.id.substringAfterLast('/').substringAfterLast(':')
                            .replace('_', ' ').replaceFirstChar { it.uppercase() },
                        nameExact = detail.id,
                        detail = "${detail.namespace} · ${detail.recipeType ?: "unknown"}",
                        path = detail.path,
                        modId = detail.namespace,
                        tags = "",
                        recipeType = detail.recipeType,
                        sourceKind = "game_data"
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
