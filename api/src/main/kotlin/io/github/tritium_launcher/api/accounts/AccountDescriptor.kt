/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.accounts

import kotlinx.serialization.Serializable

@Serializable
data class AccountDescriptor(
    val id: String,
    val username: String? = null,
    val subtitle: String? = null,
    val avatarUrl: String? = null,
    val label: String? = null
)

enum class AccountCapability {
    UPLOAD,
    VIEW_PROJECTS
}
