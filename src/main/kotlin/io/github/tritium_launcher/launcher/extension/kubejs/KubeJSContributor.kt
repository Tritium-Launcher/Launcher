/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.extension.kubejs

import io.github.tritium_launcher.api.search.IndexableDocument
import io.github.tritium_launcher.api.search.SearchIndexContributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class KubeJSContributor : SearchIndexContributor {
    override val id = "kubejs"
    override val producedKinds = setOf("recipe")
    override val kindDisplayNames = mapOf("recipe" to "Recipes")

    override suspend fun contributeDocuments(projectPath: String): Flow<IndexableDocument> =
        callbackFlow {
            val scripts = withContext(Dispatchers.IO) {
                findKubeScripts(projectPath)
            }
            for (script in scripts) {
                val docs = withContext(Dispatchers.IO) {
                    KubeJSIntelligenceService.extractIndexableRecipes(projectPath, script)
                }
                docs.forEach { trySend(it) }
            }
            close()
            awaitClose { }
        }.buffer(Channel.UNLIMITED)

    override suspend fun contributeFile(projectPath: String, filePath: String): Flow<IndexableDocument>? {
        if (!isKubeScript(filePath)) return null
        val docs = withContext(Dispatchers.IO) {
            KubeJSIntelligenceService.extractIndexableRecipes(projectPath, filePath)
        }
        return callbackFlow {
            docs.forEach { trySend(it) }
            close()
            awaitClose { }
        }.buffer(Channel.UNLIMITED)
    }

    private fun findKubeScripts(projectPath: String): List<String> {
        val kubejsDir = File(projectPath, "kubejs")
        if (!kubejsDir.exists()) return emptyList()

        val scripts = mutableListOf<String>()
        for (subDir in listOf("server_scripts", "startup_scripts", "client_scripts")) {
            val dir = File(kubejsDir, subDir)
            if (dir.isDirectory) {
                dir.walkTopDown().forEach { file ->
                    if (file.isFile && file.extension == "js") {
                        scripts.add(file.absolutePath)
                    }
                }
            }
        }
        return scripts
    }

    private fun isKubeScript(filePath: String): Boolean {
        val file = File(filePath)
        if (file.extension != "js") return false
        val parent = file.parentFile?.name ?: return false
        return parent in setOf("server_scripts", "startup_scripts", "client_scripts")
    }
}
