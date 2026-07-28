/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModrinthTagCategory(
    val icon: String? = null,
    val name: String,
    @SerialName("project_type") val projectType: String,
    val header: String
)
