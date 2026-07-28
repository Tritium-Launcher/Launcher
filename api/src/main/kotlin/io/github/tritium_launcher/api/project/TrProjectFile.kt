/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project

import kotlinx.serialization.Serializable

/**
 * Full contents of the Tritium project definition file (`.trproj`) on disk.
 */
@Serializable
data class TrProjectFile(
    val type: String = "",
    val name: String = "",
    val icon: String = "",
    val schemaVersion: Int = 1,
    val metaPath: String = ""
)
