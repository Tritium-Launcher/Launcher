/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.inspection

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.io.VPath

data class InspectionContext(
    val project: ProjectBase,
    val file: VPath,
    val tree: SyntaxTree,
    val fullText: String,
    val data: Map<String, Any?>
)

interface ParamTypeResolver {
    fun resolve(receiver: String?, method: String, paramIndex: Int): String?
}

object InspectionDataProviders {
    private val providers = mutableMapOf<String, suspend (ProjectBase) -> Any?>()

    fun register(key: String, provider: suspend (ProjectBase) -> Any?) {
        providers[key] = provider
    }

    suspend fun build(project: ProjectBase, file: VPath, tree: SyntaxTree, text: String): InspectionContext {
        val data = mutableMapOf<String, Any?>()
        for ((key, provider) in providers) {
            data[key] = provider(project)
        }
        return InspectionContext(project, file, tree, text, data)
    }
}
