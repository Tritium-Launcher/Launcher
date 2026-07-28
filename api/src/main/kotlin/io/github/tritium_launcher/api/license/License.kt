/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.license

import io.github.tritium_launcher.api.io.ResourceLoader
import io.github.tritium_launcher.api.registry.Registrable

/**
 * Represents a License for a project
 */
interface License: Registrable {
    override val id: String
    val name: String
    val resourcePath: String
    val requiresAuthor: Boolean
    val requiresYear: Boolean
    val order: Int

    fun content(): String = ResourceLoader.loadText(resourcePath, caller = this::class.java)
}

