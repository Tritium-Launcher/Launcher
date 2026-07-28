/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.search

import io.github.tritium_launcher.api.registry.Registrable

interface SearchResultAction : Registrable {
    val label: String
    val icon: String
    val handledKinds: Set<String>

    fun canActOn(result: SearchResult): Boolean = result.kind in handledKinds
    suspend fun execute(result: SearchResult)
}
