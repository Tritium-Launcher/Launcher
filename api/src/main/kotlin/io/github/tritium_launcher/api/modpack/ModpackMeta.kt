/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.modpack

import kotlinx.serialization.Serializable

/**
 * Modpack-specific metadata stored in `.tr/manifest.json`.
 */
@Serializable
data class ModpackMeta(
    val id: String,
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String,
    val source: String,
    val license: String? = null,
    val icon: String? = null
)
