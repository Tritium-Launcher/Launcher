/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.Serializable

@Serializable
data class DonationUrl(
    val id: String,
    val platform: String,
    val url: String
)
