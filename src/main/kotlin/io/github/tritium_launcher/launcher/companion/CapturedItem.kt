/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.companion

import kotlinx.serialization.Serializable

@Serializable
data class CapturedItem(
    val id: String,
    val count: Int,
    val displayName: String,
    val nbtFormatted: String
)
